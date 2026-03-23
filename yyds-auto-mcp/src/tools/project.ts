/**
 * Script project management tools
 */

import { z } from "zod";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { httpGetJson, httpPostJson } from "../client.js";

export function registerProjectTools(server: McpServer) {
  server.tool(
    "list_projects",
    "List all Python script projects on the device.",
    {},
    async () => {
      const projects = await httpGetJson<Array<Record<string, unknown>>>("/project/list");
      if (!Array.isArray(projects) || projects.length === 0) {
        return { content: [{ type: "text" as const, text: "No script projects found" }] };
      }
      const lines = projects.map((p) => `- ${p.name ?? p.projectName ?? "unknown"}`);
      return {
        content: [{ type: "text" as const, text: `Projects (${projects.length}):\n${lines.join("\n")}` }],
      };
    }
  );

  server.tool(
    "project_status",
    "Get the current script project's running status.",
    {},
    async () => {
      const res = await httpGetJson<{ running: boolean; project: string | null; error?: string }>("/project/status");
      if (res.error) throw new Error(res.error);
      return {
        content: [{ type: "text" as const, text: res.running ? `Running: ${res.project}` : "No project running" }],
      };
    }
  );

  server.tool(
    "start_project",
    "Start a script project by name.",
    {
      name: z.string().describe("Project name (from list_projects)"),
    },
    async ({ name }) => {
      const res = await httpGetJson<{ success?: boolean; error?: string }>(
        `/project/start?name=${encodeURIComponent(name)}`
      );
      if (res.error) throw new Error(res.error);
      return { content: [{ type: "text" as const, text: `Started project: ${name}` }] };
    }
  );

  server.tool(
    "stop_project",
    "Stop the currently running script project.",
    {},
    async () => {
      const res = await httpGetJson<{ success?: boolean; error?: string }>("/project/stop");
      if (res.error) throw new Error(res.error);
      return { content: [{ type: "text" as const, text: "Project stopped" }] };
    }
  );

  server.tool(
    "run_python_code",
    "Execute a Python code snippet on the device Python engine (interactive mode, runs in main process). Returns stdout/stderr output.",
    {
      code: z.string().describe("Python code string to execute"),
    },
    async ({ code }) => {
      const res = await httpPostJson<{
        success?: boolean;
        stdout?: string;
        stderr?: string;
        error?: string;
      }>("/engine/run-code", { code });
      if (res.error) throw new Error(res.error);
      const parts: string[] = [];
      if (res.stdout) parts.push(res.stdout);
      if (res.stderr) parts.push(`[stderr]\n${res.stderr}`);
      if (!parts.length) parts.push(res.success ? "OK (no output)" : "Failed (no output)");
      return {
        content: [{ type: "text" as const, text: parts.join("\n") }],
      };
    }
  );
}
