/**
 * App management tools
 */

import { z } from "zod";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { httpGetJson, httpPostJson, autoApi } from "../client.js";

export function registerAppTools(server: McpServer) {
  server.tool(
    "launch_app",
    "Launch a specified app by package name.",
    {
      packageName: z.string().describe("App package name, e.g. com.android.settings"),
    },
    async ({ packageName }) => {
      const res = await autoApi("/open_app", { pkg: packageName });
      return { content: [{ type: "text" as const, text: `launched: ${res}` }] };
    }
  );

  server.tool(
    "stop_app",
    "Force stop a specified app.",
    {
      packageName: z.string().describe("App package name"),
    },
    async ({ packageName }) => {
      const res = await httpPostJson<{ success?: boolean; result?: string }>(
        "/engine/shell",
        { command: `am force-stop ${packageName}` }
      );
      return {
        content: [{ type: "text" as const, text: `stopped ${packageName}: ${res.result ?? "ok"}` }],
      };
    }
  );

  server.tool(
    "list_installed_apps",
    "List non-system apps installed on the device (package name and app name).",
    {},
    async () => {
      const apps = await httpGetJson<Array<{ packageName: string; appName: string }>>(
        "/package/installed-apps"
      );
      const lines = apps.map((a) => `${a.appName} (${a.packageName})`).join("\n");
      return {
        content: [{ type: "text" as const, text: `Installed apps (${apps.length}):\n${lines}` }],
      };
    }
  );

  server.tool(
    "is_app_running",
    "Check if a specified app is running in the background.",
    {
      packageName: z.string().describe("App package name"),
    },
    async ({ packageName }) => {
      const res = await autoApi("/is_app_running", { pkg: packageName });
      return {
        content: [{ type: "text" as const, text: `${packageName}: ${res === "true" ? "running" : "not running"}` }],
      };
    }
  );

  server.tool(
    "open_url",
    "Open a URL in the system browser.",
    {
      url: z.string().describe("URL to open"),
    },
    async ({ url }) => {
      const res = await autoApi("/open_url", { url });
      return { content: [{ type: "text" as const, text: `opened: ${res}` }] };
    }
  );

  server.tool(
    "show_toast",
    "Display a Toast message on the device screen.",
    {
      message: z.string().describe("Toast text"),
    },
    async ({ message }) => {
      await autoApi("/toast", { content: message });
      return { content: [{ type: "text" as const, text: `Toast: ${message}` }] };
    }
  );

  server.tool(
    "install_apk",
    "Install an APK file on the device. The APK must already exist on the device filesystem.",
    {
      path: z.string().describe("APK file path on device, e.g. /sdcard/Download/app.apk"),
    },
    async ({ path }) => {
      const res = await httpPostJson<{ success?: boolean; result?: string; error?: string }>(
        "/engine/shell",
        { command: `pm install -r -d "${path}"` }
      );
      if (res.error) throw new Error(res.error);
      return { content: [{ type: "text" as const, text: `install: ${res.result ?? "ok"}` }] };
    }
  );

  server.tool(
    "uninstall_app",
    "Uninstall an app from the device by package name.",
    {
      packageName: z.string().describe("App package name to uninstall"),
    },
    async ({ packageName }) => {
      const res = await httpPostJson<{ success?: boolean; result?: string; error?: string }>(
        "/engine/shell",
        { command: `pm uninstall ${packageName}` }
      );
      if (res.error) throw new Error(res.error);
      return { content: [{ type: "text" as const, text: `uninstall ${packageName}: ${res.result ?? "ok"}` }] };
    }
  );
}
