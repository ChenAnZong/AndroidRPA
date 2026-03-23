/**
 * 截图工具
 */

import { z } from "zod";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { httpGetBuffer, autoApi } from "../client.js";

/** 截图并返回 buffer，失败自动重试+降级 */
async function captureScreen(quality: number, retries = 2): Promise<Buffer> {
  for (let i = 0; i <= retries; i++) {
    const q = i === retries ? Math.min(quality, 30) : quality; // 最后一次降级质量
    try {
      const data = await httpGetBuffer(`/screen/${q}?no-cache=${Date.now()}`);
      if (data && data.length > 0) return data;
    } catch {
      // ignore, retry
    }
    if (i < retries) await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error("Screenshot failed after retries — device may be off or engine not responding");
}

export function registerScreenTools(server: McpServer) {
  server.tool(
    "take_screenshot",
    "Capture the device screen and return the image for LLM to view and analyze. This is one of the most important tools for device interaction.",
    {
      quality: z
        .number()
        .optional()
        .default(80)
        .describe("JPEG quality 1-100, default 80. Lower = faster transfer"),
    },
    async ({ quality }) => {
      const data = await captureScreen(quality);
      const base64 = data.toString("base64");
      return {
        content: [
          {
            type: "image" as const,
            data: base64,
            mimeType: "image/jpeg",
          },
        ],
      };
    }
  );

  server.tool(
    "save_screenshot",
    "Take a screenshot and save it to a specified path on the device.",
    {
      path: z
        .string()
        .optional()
        .default("/sdcard/screenshot.png")
        .describe("Save path on device, default /sdcard/screenshot.png"),
    },
    async ({ path }) => {
      const res = await autoApi("/screenshot", { path });
      return {
        content: [{ type: "text" as const, text: `Screenshot saved: ${res}` }],
      };
    }
  );
}
