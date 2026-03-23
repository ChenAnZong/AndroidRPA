/**
 * Pip package management tools
 */

import { z } from "zod";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { httpGetJson, httpPostJson } from "../client.js";

export function registerPipTools(server: McpServer) {
  server.tool(
    "pip_list",
    "List installed pip packages in the device Python environment.",
    {},
    async () => {
      const raw = await httpGetJson<Array<{ name: string; version: string }> | { error?: string }>("/pip/list");
      if (!Array.isArray(raw)) {
        throw new Error((raw as { error?: string }).error ?? "failed to get pip list");
      }
      const lines = raw.map((p) => `${p.name}==${p.version}`);
      return {
        content: [{ type: "text" as const, text: `Installed packages (${raw.length}):\n${lines.join("\n")}` }],
      };
    }
  );

  server.tool(
    "pip_install",
    "Install a pip package in the device Python environment.",
    {
      name: z.string().describe("Package name, supports version constraints e.g. requests>=2.28"),
      mirror: z.string().optional().describe("PyPI mirror URL (optional). e.g. https://pypi.tuna.tsinghua.edu.cn/simple"),
    },
    async ({ name, mirror }) => {
      const body: Record<string, string> = { name };
      if (mirror) body.mirror = mirror;
      const res = await httpPostJson<{ success: boolean; output: string; error?: string }>("/pip/install", body);
      if (res.error) throw new Error(res.error);
      return {
        content: [{ type: "text" as const, text: res.success ? `installed: ${name}\n${res.output}` : `install failed: ${name}\n${res.output}` }],
      };
    }
  );

  server.tool(
    "pip_uninstall",
    "Uninstall a pip package from the device Python environment.",
    {
      name: z.string().describe("Package name"),
    },
    async ({ name }) => {
      const res = await httpPostJson<{ success: boolean; output: string; error?: string }>("/pip/uninstall", { name });
      if (res.error) throw new Error(res.error);
      return {
        content: [{ type: "text" as const, text: res.success ? `uninstalled: ${name}` : `uninstall failed: ${name}\n${res.output}` }],
      };
    }
  );

  server.tool(
    "pip_show",
    "Show detailed information about a pip package.",
    {
      name: z.string().describe("Package name"),
    },
    async ({ name }) => {
      const res = await httpGetJson<Record<string, string>>(`/pip/show?name=${encodeURIComponent(name)}`);
      if ((res as Record<string, string>).error) throw new Error((res as Record<string, string>).error);
      const lines = Object.entries(res).map(([k, v]) => `${k}: ${v}`).join("\n");
      return { content: [{ type: "text" as const, text: lines }] };
    }
  );
}
