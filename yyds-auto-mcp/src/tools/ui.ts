/**
 * UI automation tools — UI Dump, element finding, waiting, scrolling
 */

import { z } from "zod";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { httpGetText, autoApi } from "../client.js";

/** Parse XML and keep only interactive elements to reduce token usage */
function slimXml(xml: string): string {
  const lines: string[] = [];
  // Match each node element
  const nodeRe = /<node\s[^>]*>/g;
  let m: RegExpExecArray | null;
  while ((m = nodeRe.exec(xml)) !== null) {
    const tag = m[0];
    const get = (attr: string) => {
      const r = new RegExp(`${attr}="([^"]*)"`, "i");
      const v = r.exec(tag);
      return v ? v[1] : "";
    };
    const text = get("text");
    const rid = get("resource-id");
    const cls = get("class").replace("android.widget.", "").replace("android.view.", "");
    const desc = get("content-desc");
    const bounds = get("bounds");
    const clickable = get("clickable") === "true";
    const scrollable = get("scrollable") === "true";
    // Keep elements that have text, resource-id, content-desc, or are interactive
    if (text || rid || desc || clickable || scrollable) {
      const parts = [cls];
      if (text) parts.push(`text="${text}"`);
      if (rid) parts.push(`id="${rid}"`);
      if (desc) parts.push(`desc="${desc}"`);
      if (clickable) parts.push("clickable");
      if (scrollable) parts.push("scrollable");
      parts.push(bounds);
      lines.push(parts.join(" "));
    }
  }
  return lines.join("\n") || xml;
}

export function registerUiTools(server: McpServer) {
  server.tool(
    "dump_ui_hierarchy",
    "Get the current screen's full UI hierarchy (XML). Contains all elements' text, resource-id, class, bounds, clickable attributes. Foundation tool for locating and operating UI elements.",
    {},
    async () => {
      const xml = await httpGetText("/uia_dump");
      // Check if response is an error JSON instead of XML
      if (xml.includes('"error"')) {
        try {
          const err = JSON.parse(xml);
          if (err.error) {
            throw new Error(`UI dump failed: ${err.error}`);
          }
        } catch (e) {
          if (e instanceof SyntaxError) {
            // Not JSON, continue as XML
          } else {
            throw e;
          }
        }
      }
      // Auto-slim if XML is too large (>15KB) to save LLM tokens
      if (xml.length > 15000) {
        const slim = slimXml(xml);
        return {
          content: [
            { type: "text" as const, text: `[Simplified ${xml.length}→${slim.length} chars, showing interactive elements only]\n${slim}` },
          ],
        };
      }
      return { content: [{ type: "text" as const, text: xml }] };
    }
  );

  server.tool(
    "find_ui_elements",
    "Find UI elements by attributes. Returns matching elements' coordinates, text, class, etc. Supports text, resource-id, class-name, content-desc filtering.",
    {
      text: z.string().optional().describe("Element text (exact match)"),
      textContains: z.string().optional().describe("Element text (contains match)"),
      resourceId: z.string().optional().describe("resource-id attribute"),
      className: z.string().optional().describe("class name, e.g. android.widget.Button"),
      contentDesc: z.string().optional().describe("content-desc attribute"),
      clickable: z.string().optional().describe("Is clickable: true/false"),
      scrollable: z.string().optional().describe("Is scrollable: true/false"),
      limit: z.number().optional().default(10).describe("Max results, default 10"),
    },
    async (params) => {
      const apiParams: Record<string, string> = {};
      if (params.text) apiParams["text"] = params.text;
      if (params.textContains) apiParams["textContains"] = params.textContains;
      if (params.resourceId) apiParams["resource-id"] = params.resourceId;
      if (params.className) apiParams["class"] = params.className;
      if (params.contentDesc) apiParams["content-desc"] = params.contentDesc;
      if (params.clickable) apiParams["clickable"] = params.clickable;
      if (params.scrollable) apiParams["scrollable"] = params.scrollable;
      apiParams["limit"] = String(params.limit);

      const res = await autoApi("/uia_match", apiParams);
      return { content: [{ type: "text" as const, text: res }] };
    }
  );

  server.tool(
    "get_element_relation",
    "Get a UI element's parent/children/sibling relationships. Requires hashcode from find_ui_elements.",
    {
      hashcode: z.number().describe("Element hashcode (from find_ui_elements result)"),
      type: z.string().optional().default("parent").describe("Relation type: parent, children, sibling"),
    },
    async ({ hashcode, type }) => {
      const res = await autoApi("/uia_relation", {
        hashcode: String(hashcode),
        type,
      });
      return { content: [{ type: "text" as const, text: res }] };
    }
  );

  server.tool(
    "wait_for_element",
    "Wait for a UI element to appear on screen (polling with timeout). Useful after navigation or page transitions.",
    {
      text: z.string().optional().describe("Element text to wait for (exact match)"),
      textContains: z.string().optional().describe("Element text to wait for (contains)"),
      resourceId: z.string().optional().describe("resource-id to wait for"),
      timeout: z.number().optional().default(10000).describe("Timeout in ms, default 10000"),
      interval: z.number().optional().default(1000).describe("Poll interval in ms, default 1000"),
    },
    async ({ text, textContains, resourceId, timeout, interval }) => {
      if (!text && !textContains && !resourceId) {
        throw new Error("At least one of text, textContains, or resourceId is required");
      }
      const start = Date.now();
      while (Date.now() - start < timeout) {
        const apiParams: Record<string, string> = { limit: "1" };
        if (text) apiParams["text"] = text;
        if (textContains) apiParams["textContains"] = textContains;
        if (resourceId) apiParams["resource-id"] = resourceId;
        try {
          const res = await autoApi("/uia_match", apiParams);
          if (res && !res.includes("[]") && res.trim().length > 5) {
            return {
              content: [{ type: "text" as const, text: `Element found (${Date.now() - start}ms):\n${res}` }],
            };
          }
        } catch { /* retry */ }
        await new Promise((r) => setTimeout(r, interval));
      }
      return {
        content: [{ type: "text" as const, text: `Element not found after ${timeout}ms` }],
      };
    }
  );

  server.tool(
    "scroll_to_find",
    "Scroll the screen to find an element by text or resource-id. Automatically scrolls down and checks after each scroll.",
    {
      text: z.string().optional().describe("Text to find"),
      textContains: z.string().optional().describe("Text to find (contains)"),
      resourceId: z.string().optional().describe("resource-id to find"),
      maxScrolls: z.number().optional().default(5).describe("Max scroll attempts, default 5"),
      direction: z.enum(["down", "up"]).optional().default("down").describe("Scroll direction, default down"),
    },
    async ({ text, textContains, resourceId, maxScrolls, direction }) => {
      if (!text && !textContains && !resourceId) {
        throw new Error("At least one of text, textContains, or resourceId is required");
      }
      for (let i = 0; i <= maxScrolls; i++) {
        const apiParams: Record<string, string> = { limit: "1" };
        if (text) apiParams["text"] = text;
        if (textContains) apiParams["textContains"] = textContains;
        if (resourceId) apiParams["resource-id"] = resourceId;
        try {
          const res = await autoApi("/uia_match", apiParams);
          if (res && !res.includes("[]") && res.trim().length > 5) {
            return {
              content: [{ type: "text" as const, text: `Found after ${i} scrolls:\n${res}` }],
            };
          }
        } catch { /* continue */ }
        if (i < maxScrolls) {
          // Scroll: center of screen, 60% height
          const cx = 540, h = 2400;
          const [sy, ey] = direction === "down"
            ? [Math.round(h * 0.7), Math.round(h * 0.3)]
            : [Math.round(h * 0.3), Math.round(h * 0.7)];
          await autoApi("/swipe", {
            x1: String(cx), y1: String(sy),
            x2: String(cx), y2: String(ey),
            duration: "300",
          });
          await new Promise((r) => setTimeout(r, 800));
        }
      }
      return {
        content: [{ type: "text" as const, text: `Element not found after ${maxScrolls} scrolls` }],
      };
    }
  );
}
