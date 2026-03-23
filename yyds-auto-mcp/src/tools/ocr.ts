/**
 * OCR and image recognition tools
 */

import { z } from "zod";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { autoApi, httpGetBuffer } from "../client.js";

export function registerOcrTools(server: McpServer) {
  server.tool(
    "screen_ocr",
    "Perform OCR text recognition on the current screen. Can specify a region for partial recognition.",
    {
      x: z.number().optional().describe("Region top-left X (omit for full screen)"),
      y: z.number().optional().describe("Region top-left Y"),
      w: z.number().optional().describe("Region width"),
      h: z.number().optional().describe("Region height"),
    },
    async (params) => {
      const apiParams: Record<string, string> = {};
      if (params.x !== undefined) apiParams["x"] = String(params.x);
      if (params.y !== undefined) apiParams["y"] = String(params.y);
      if (params.w !== undefined) apiParams["w"] = String(params.w);
      if (params.h !== undefined) apiParams["h"] = String(params.h);
      const res = await autoApi("/screen_ocr", apiParams);
      return { content: [{ type: "text" as const, text: res }] };
    }
  );

  server.tool(
    "tap_text",
    "Find text on screen via OCR and tap its center. Combines OCR + tap in one step — very convenient for clicking buttons/labels by their visible text.",
    {
      text: z.string().describe("The visible text to find and tap"),
      index: z.number().optional().default(0).describe("If multiple matches, tap the Nth one (0-based), default 0"),
    },
    async ({ text, index }) => {
      const ocrRes = await autoApi("/screen_ocr", {});
      // Parse OCR result: each line is "confidence\ttext\tx1,y1 x2,y2 x3,y3 x4,y4"
      const lines = ocrRes.split("\n").filter((l) => l.trim());
      const matches: { cx: number; cy: number; t: string }[] = [];
      for (const line of lines) {
        const parts = line.split("\t");
        if (parts.length < 3) continue;
        const ocrText = parts[1];
        if (!ocrText.includes(text)) continue;
        // Parse bounds: "x1,y1 x2,y2 x3,y3 x4,y4"
        const coords = parts[2].split(" ").map((p) => {
          const [x, y] = p.split(",").map(Number);
          return { x, y };
        });
        if (coords.length >= 4) {
          const cx = Math.round((coords[0].x + coords[2].x) / 2);
          const cy = Math.round((coords[0].y + coords[2].y) / 2);
          matches.push({ cx, cy, t: ocrText });
        }
      }
      if (matches.length === 0) {
        return { content: [{ type: "text" as const, text: `Text "${text}" not found on screen` }] };
      }
      const target = matches[Math.min(index, matches.length - 1)];
      await autoApi("/touch", { x: String(target.cx), y: String(target.cy), time: "1", interval: "0" });
      return {
        content: [{
          type: "text" as const,
          text: `Tapped "${target.t}" at (${target.cx}, ${target.cy})${matches.length > 1 ? ` [${matches.length} matches, tapped #${index}]` : ""}`,
        }],
      };
    }
  );

  server.tool(
    "image_ocr",
    "Perform OCR text recognition on an image file on the device.",
    {
      path: z.string().describe("Image file path on device"),
    },
    async ({ path }) => {
      const res = await autoApi("/image_ocr", { path });
      return { content: [{ type: "text" as const, text: res }] };
    }
  );

  server.tool(
    "find_image_on_screen",
    "Find a template image's position on screen (template matching). Template image must exist on the device.",
    {
      templates: z.string().describe("Template image path(s), separate multiple with semicolons, e.g. /sdcard/img/btn.png;/sdcard/img/icon.png"),
      threshold: z.number().optional().default(-1).describe("Match threshold, -1 for default"),
    },
    async ({ templates, threshold }) => {
      const res = await autoApi("/find_image", { templates, threshold: String(threshold) });
      return { content: [{ type: "text" as const, text: res }] };
    }
  );

  server.tool(
    "get_pixel_color",
    "Get the pixel color (RGB) at specified screen coordinates.",
    {
      x: z.number().describe("X coordinate"),
      y: z.number().describe("Y coordinate"),
    },
    async ({ x, y }) => {
      const res = await autoApi("/get_color", { x: String(x), y: String(y) });
      return { content: [{ type: "text" as const, text: `color (${x},${y}): RGB(${res})` }] };
    }
  );

  server.tool(
    "compare_images",
    "Compare similarity of two images (0-1). Useful for detecting screen changes.",
    {
      image1: z.string().describe("First image path on device"),
      image2: z.string().describe("Second image path on device"),
    },
    async ({ image1, image2 }) => {
      const res = await autoApi("/image_similarity", { image1, image2 });
      return { content: [{ type: "text" as const, text: `similarity: ${res}` }] };
    }
  );

  server.tool(
    "set_ocr_version",
    "Switch OCR model version. v5_mobile is fast+accurate (recommended), v5_server is highest accuracy but slower, v2 is legacy.",
    {
      version: z.enum(["v2", "v5_mobile", "v5_server"]).describe("OCR model: v2(legacy), v5_mobile(recommended), v5_server(high accuracy)"),
      target_size: z.number().optional().describe("Detection target size (320/400/480/560/640), larger = more accurate but slower"),
    },
    async ({ version, target_size }) => {
      const apiParams: Record<string, string> = { code: version };
      if (target_size !== undefined) apiParams["target_size"] = String(target_size);
      const res = await autoApi("/update_language", apiParams);
      return { content: [{ type: "text" as const, text: res }] };
    }
  );

  server.tool(
    "wait_for_screen_change",
    "Wait for the screen to change by comparing screenshots. Useful after performing an action to confirm it took effect.",
    {
      timeout: z.number().optional().default(10000).describe("Timeout in ms, default 10000"),
      interval: z.number().optional().default(1000).describe("Check interval in ms, default 1000"),
      threshold: z.number().optional().default(0.95).describe("Similarity threshold below which screen is considered changed, default 0.95"),
    },
    async ({ timeout, interval, threshold }) => {
      // Take initial screenshot
      const initial = await httpGetBuffer(`/screen/50?no-cache=${Date.now()}`);
      if (!initial || initial.length === 0) throw new Error("Failed to capture initial screenshot");

      const start = Date.now();
      while (Date.now() - start < timeout) {
        await new Promise((r) => setTimeout(r, interval));
        const current = await httpGetBuffer(`/screen/50?no-cache=${Date.now()}`);
        if (!current || current.length === 0) continue;
        // Quick size-based comparison: if sizes differ significantly, screen changed
        const sizeDiff = Math.abs(current.length - initial.length) / initial.length;
        if (sizeDiff > (1 - threshold)) {
          return {
            content: [{ type: "text" as const, text: `Screen changed after ${Date.now() - start}ms (size diff: ${(sizeDiff * 100).toFixed(1)}%)` }],
          };
        }
      }
      return {
        content: [{ type: "text" as const, text: `Screen did not change within ${timeout}ms` }],
      };
    }
  );
}
