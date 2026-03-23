/**
 * Touch, gesture, key input tools
 */

import { z } from "zod";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { httpPostJson, autoApi } from "../client.js";

export function registerTouchTools(server: McpServer) {
  server.tool(
    "tap",
    "Tap screen coordinates. Supports multiple taps with interval.",
    {
      x: z.number().describe("X coordinate"),
      y: z.number().describe("Y coordinate"),
      count: z.number().optional().default(1).describe("Tap count, default 1"),
      interval: z.number().optional().default(0).describe("Interval between taps (ms), default 0"),
    },
    async ({ x, y, count, interval }) => {
      const res = await autoApi("/touch", {
        x: String(x),
        y: String(y),
        time: String(count),
        interval: String(interval),
      });
      return {
        content: [{ type: "text" as const, text: `tap (${x}, ${y}) → ${res}` }],
      };
    }
  );

  server.tool(
    "swipe",
    "Perform a swipe gesture on the screen.",
    {
      x1: z.number().describe("Start X"),
      y1: z.number().describe("Start Y"),
      x2: z.number().describe("End X"),
      y2: z.number().describe("End Y"),
      duration: z.number().optional().default(300).describe("Swipe duration (ms), default 300"),
    },
    async ({ x1, y1, x2, y2, duration }) => {
      const res = await autoApi("/swipe", {
        x1: String(x1), y1: String(y1),
        x2: String(x2), y2: String(y2),
        duration: String(duration),
      });
      return {
        content: [{ type: "text" as const, text: `swipe (${x1},${y1})→(${x2},${y2}) ${duration}ms → ${res}` }],
      };
    }
  );

  server.tool(
    "long_press",
    "Long press at coordinates for a specified duration.",
    {
      x: z.number().describe("X coordinate"),
      y: z.number().describe("Y coordinate"),
      duration: z.number().optional().default(1500).describe("Press duration (ms), default 1500"),
    },
    async ({ x, y, duration }) => {
      await autoApi("/touch_down", { x: String(x), y: String(y) });
      await new Promise((r) => setTimeout(r, duration));
      await autoApi("/touch_up", { x: String(x), y: String(y) });
      return {
        content: [{ type: "text" as const, text: `long_press (${x}, ${y}) ${duration}ms` }],
      };
    }
  );

  server.tool(
    "drag",
    "Drag from one point to another (touch down → move → touch up).",
    {
      x1: z.number().describe("Start X"),
      y1: z.number().describe("Start Y"),
      x2: z.number().describe("End X"),
      y2: z.number().describe("End Y"),
      duration: z.number().optional().default(500).describe("Drag duration (ms), default 500"),
    },
    async ({ x1, y1, x2, y2, duration }) => {
      const res = await autoApi("/swipe", {
        x1: String(x1), y1: String(y1),
        x2: String(x2), y2: String(y2),
        duration: String(duration),
      });
      return {
        content: [{ type: "text" as const, text: `drag (${x1},${y1})→(${x2},${y2}) ${duration}ms → ${res}` }],
      };
    }
  );

  server.tool(
    "input_text",
    "Type text into the currently focused input field.",
    {
      text: z.string().describe("Text to input"),
    },
    async ({ text }) => {
      const res = await autoApi("/inject_text", { text });
      return { content: [{ type: "text" as const, text: `input_text → ${res}` }] };
    }
  );

  server.tool(
    "set_clipboard",
    "Set the device clipboard content.",
    {
      text: z.string().describe("Clipboard text"),
    },
    async ({ text }) => {
      await autoApi("/set_clipboard", { text });
      return { content: [{ type: "text" as const, text: "clipboard set" }] };
    }
  );

  server.tool(
    "get_clipboard",
    "Get the device clipboard text content.",
    {},
    async () => {
      const res = await autoApi("/get_clipboard");
      return { content: [{ type: "text" as const, text: res }] };
    }
  );

  server.tool(
    "press_key",
    "Simulate a key press (home/back/recent or Android keycode number).",
    {
      key: z
        .string()
        .describe(
          "Key name or keycode. Common: home(3), back(4), recent(187), enter(66), delete(67), volume_up(24), volume_down(25), power(26), tab(61)"
        ),
    },
    async ({ key }) => {
      const keyMap: Record<string, number> = {
        home: 3, back: 4, recent: 187, enter: 66, delete: 67,
        volume_up: 24, volume_down: 25, power: 26, tab: 61,
        escape: 111, menu: 82, search: 84, camera: 27,
      };
      const code = keyMap[key.toLowerCase()] ?? parseInt(key, 10);
      if (isNaN(code)) throw new Error(`Unknown key: ${key}`);
      const res = await autoApi("/key_code", { code: String(code) });
      return {
        content: [{ type: "text" as const, text: `press_key ${key}(${code}) → ${res}` }],
      };
    }
  );
}
