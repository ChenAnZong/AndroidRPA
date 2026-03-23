/**
 * Shell command execution tool
 */

import { z } from "zod";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { httpPostJson } from "../client.js";

export function registerShellTools(server: McpServer) {
  server.tool(
    "run_shell",
    "Execute a command on the Android device with ROOT/SHELL privileges. Supports any Linux command: ls, cat, pm, am, dumpsys, getprop, etc.",
    {
      command: z.string().describe("Shell command to execute"),
    },
    async ({ command }) => {
      const res = await httpPostJson<{
        success?: boolean;
        result?: string;
        error?: string;
      }>("/engine/shell", { command });
      if (res.error) throw new Error(res.error);
      return { content: [{ type: "text" as const, text: res.result ?? "" }] };
    }
  );
}
