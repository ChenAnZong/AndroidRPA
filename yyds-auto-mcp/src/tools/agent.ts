/**
 * AI Agent tools — start/stop/status/config
 */

import { z } from "zod";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { httpGetText, httpPostJson } from "../client.js";

export function registerAgentTools(server: McpServer) {
  server.tool(
    "agent_get_config",
    "Get AI Agent configuration (provider, model, advanced options).",
    {},
    async () => {
      const res = await httpGetText("/agent/config");
      return { content: [{ type: "text" as const, text: res }] };
    }
  );

  server.tool(
    "agent_set_config",
    "Set AI Agent configuration (provider, API Key, model, etc.).",
    {
      provider: z.enum(["autoglm", "doubao", "qwen", "zhipu", "deepseek", "moonshot", "baichuan", "minimax", "siliconflow", "openai", "anthropic", "gemini", "mistral", "groq", "xai", "openrouter", "custom"]).describe("AI provider"),
      api_key: z.string().describe("API Key"),
      base_url: z.string().optional().describe("Custom Base URL (for 'custom' provider only)"),
      model: z.string().optional().describe("Model name (optional, overrides preset)"),
      max_steps: z.number().optional().describe("Max execution steps"),
      use_ui_dump: z.boolean().optional().describe("Whether to use UI hierarchy enhancement"),
    },
    async (params) => {
      const res = await httpPostJson("/agent/config", params);
      return { content: [{ type: "text" as const, text: JSON.stringify(res) }] };
    }
  );

  server.tool(
    "agent_get_providers",
    "List all available AI providers with their default models and supported model lists.",
    {},
    async () => {
      const res = await httpGetText("/agent/providers");
      return { content: [{ type: "text" as const, text: res }] };
    }
  );

  server.tool(
    "agent_get_models",
    "Get available models for a specific AI provider.",
    {
      provider: z.string().describe("Provider ID (e.g. doubao, qwen, deepseek, openai)"),
    },
    async ({ provider }) => {
      const res = await httpGetText(`/agent/models?provider=${encodeURIComponent(provider)}`);
      return { content: [{ type: "text" as const, text: res }] };
    }
  );

  server.tool(
    "agent_run",
    "Start AI Agent to execute a task with natural language instruction (e.g. 'open WeChat and send a message to John').",
    {
      instruction: z.string().describe("Natural language task instruction"),
    },
    async ({ instruction }) => {
      const res = await httpPostJson("/agent/run", { instruction });
      return { content: [{ type: "text" as const, text: JSON.stringify(res) }] };
    }
  );

  server.tool(
    "agent_stop",
    "Stop the currently running AI Agent.",
    {},
    async () => {
      const res = await httpGetText("/agent/stop");
      return { content: [{ type: "text" as const, text: res }] };
    }
  );

  server.tool(
    "agent_status",
    "Query AI Agent current running status (running state, current step, logs).",
    {},
    async () => {
      const res = await httpGetText("/agent/status");
      return { content: [{ type: "text" as const, text: res }] };
    }
  );

  server.tool(
    "agent_test_connection",
    "Test if the AI model connection is working properly.",
    {
      provider: z.enum(["autoglm", "doubao", "qwen", "zhipu", "deepseek", "moonshot", "baichuan", "minimax", "siliconflow", "openai", "anthropic", "gemini", "mistral", "groq", "xai", "openrouter", "custom"]).describe("AI provider"),
      api_key: z.string().describe("API Key"),
      base_url: z.string().optional().describe("Custom Base URL"),
      model: z.string().optional().describe("Model name"),
    },
    async (params) => {
      const res = await httpPostJson("/agent/test-connection", params);
      return { content: [{ type: "text" as const, text: JSON.stringify(res) }] };
    }
  );
}
