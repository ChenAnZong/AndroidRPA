/**
 * Device information tools
 */

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { httpGetText, autoApi, getHost, getPort } from "../client.js";

export function registerDeviceTools(server: McpServer) {
  server.tool(
    "device_info",
    "Get comprehensive Android device info: engine version, screen size, IMEI, foreground app, etc.",
    {},
    async () => {
      const results: Record<string, string> = {};
      try {
        results.engine = await httpGetText("/");
      } catch (e) {
        return {
          content: [
            {
              type: "text" as const,
              text: `Cannot connect to device ${getHost()}:${getPort()}\nPlease check:\n1. Device engine is running\n2. ADB port forward: adb forward tcp:${getPort()} tcp:${getPort()}\n3. Or device and PC are on the same LAN`,
            },
          ],
        };
      }
      try {
        results.screen_size = await autoApi("/screen_size");
      } catch { /* ignore */ }
      try {
        results.imei = await autoApi("/imei");
      } catch { /* ignore */ }
      try {
        results.foreground = await autoApi("/foreground");
      } catch { /* ignore */ }

      const lines = Object.entries(results)
        .map(([k, v]) => `${k}: ${v.trim()}`)
        .join("\n");
      return { content: [{ type: "text" as const, text: lines }] };
    }
  );

  server.tool(
    "get_foreground_app",
    "Get the current foreground app's package name and Activity info.",
    {},
    async () => {
      const res = await autoApi("/foreground");
      return { content: [{ type: "text" as const, text: res }] };
    }
  );

  server.tool(
    "get_screen_size",
    "Get device screen resolution (width, height).",
    {},
    async () => {
      const size = await autoApi("/screen_size");
      return { content: [{ type: "text" as const, text: size }] };
    }
  );

  server.tool(
    "is_network_online",
    "Check if the device has network connectivity.",
    {},
    async () => {
      const res = await autoApi("/is_net_online");
      return {
        content: [{ type: "text" as const, text: res === "true" ? "online" : "offline" }],
      };
    }
  );
}
