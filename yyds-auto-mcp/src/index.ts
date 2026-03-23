#!/usr/bin/env node
/**
 * yyds-auto-mcp — MCP Server for Yyds.Auto Android RPA
 *
 * 让 LLM 通过 MCP 协议直接操控安卓设备：截图、点击、UI分析、OCR、Shell、文件管理等
 * 通过 HTTP REST API 与设备端 yyds.py 引擎通信（端口 61140）
 */

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

import { httpGetText, httpGetBuffer, httpGetJson, getHost, getPort, isLocalHost, setAdbContext } from "./client.js";
import { checkAndForward } from "./adb.js";
import { ensureEngine } from "./engine-bootstrap.js";
import { registerDeviceTools } from "./tools/device.js";
import { registerTouchTools } from "./tools/touch.js";
import { registerScreenTools } from "./tools/screen.js";
import { registerUiTools } from "./tools/ui.js";
import { registerOcrTools } from "./tools/ocr.js";
import { registerShellTools } from "./tools/shell.js";
import { registerAppTools } from "./tools/app.js";
import { registerFileTools } from "./tools/file.js";
import { registerProjectTools } from "./tools/project.js";
import { registerPipTools } from "./tools/pip.js";
import { registerAgentTools } from "./tools/agent.js";

const server = new McpServer({
  name: "yyds-auto-mcp",
  version: "1.0.0",
});

// ============================================================
// 注册所有工具
// ============================================================

registerDeviceTools(server);
registerTouchTools(server);
registerScreenTools(server);
registerUiTools(server);
registerOcrTools(server);
registerShellTools(server);
registerAppTools(server);
registerFileTools(server);
registerProjectTools(server);
registerPipTools(server);
registerAgentTools(server);

// ============================================================
// 注册 MCP Resources
// ============================================================

server.resource(
  "screenshot",
  "device://screenshot",
  { description: "当前设备屏幕截图", mimeType: "image/jpeg" },
  async (uri) => {
    try {
      const data = await httpGetBuffer("/screen/80?no-cache=1");
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: "image/jpeg",
            blob: data.toString("base64"),
          },
        ],
      };
    } catch (e) {
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: "text/plain",
            text: `截图失败: ${e}`,
          },
        ],
      };
    }
  }
);

server.resource(
  "ui-hierarchy",
  "device://ui-hierarchy",
  { description: "当前屏幕 UI 控件树 (XML)", mimeType: "text/xml" },
  async (uri) => {
    try {
      const xml = await httpGetText("/uia_dump");
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: "text/xml",
            text: xml,
          },
        ],
      };
    } catch (e) {
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: "text/plain",
            text: `获取UI树失败: ${e}`,
          },
        ],
      };
    }
  }
);

server.resource(
  "device-info",
  "device://info",
  { description: "设备基本信息", mimeType: "application/json" },
  async (uri) => {
    try {
      const info = await httpGetText("/");
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: "application/json",
            text: JSON.stringify({ engine: info.trim(), host: getHost(), port: getPort() }),
          },
        ],
      };
    } catch (e) {
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: "text/plain",
            text: `获取设备信息失败: ${e}`,
          },
        ],
      };
    }
  }
);

// ============================================================
// 启动 MCP Server (stdio transport)
// ============================================================

async function main() {
  // 本地地址时自动 ADB 端口转发 + 引擎启动
  if (isLocalHost(getHost())) {
    await autoSetup(getPort());
  }

  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error(
    `[yyds-auto-mcp] MCP Server started, device=${getHost()}:${getPort()}`
  );
}

/** ADB 转发 + 引擎启动，best-effort 不阻塞 MCP 启动 */
async function autoSetup(port: number): Promise<void> {
  try {
    console.error("[yyds-auto-mcp] 检测到本地地址，自动执行 ADB 端口转发...");
    const { forwarded, adbPath, serial } = await checkAndForward(port);

    // 保存 ADB 上下文，供断线自动重连使用
    setAdbContext(adbPath ?? null, serial ?? null);

    if (forwarded && adbPath && serial) {
      await ensureEngine(adbPath, serial);
    } else if (!forwarded) {
      console.error("[yyds-auto-mcp] ADB 转发未成功，引擎启动检测跳过");
    }
  } catch (e) {
    console.error(`[yyds-auto-mcp] autoSetup 异常 (不影响启动): ${e}`);
  }
}

main().catch((err) => {
  console.error("[yyds-auto-mcp] Fatal error:", err);
  process.exit(1);
});
