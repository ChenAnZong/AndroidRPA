/**
 * File operation tools
 */

import { z } from "zod";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { httpGetJson, httpGetText, httpPostJson } from "../client.js";

export function registerFileTools(server: McpServer) {
  server.tool(
    "list_files",
    "List files and subdirectories in a specified directory on the device.",
    {
      path: z.string().describe("Absolute directory path, e.g. /sdcard/"),
    },
    async ({ path }) => {
      const res = await httpGetJson<{
        files?: Array<{ name: string; path: string; isDir: boolean; size: number; lastModified: number }>;
        parent?: string;
        error?: string;
      }>(`/file/list?path=${encodeURIComponent(path)}`);
      if (res.error) throw new Error(res.error);
      const lines = (res.files ?? []).map((f) => {
        const type = f.isDir ? "[DIR]" : "[FILE]";
        const size = f.isDir ? "" : ` (${formatSize(f.size)})`;
        return `${type} ${f.name}${size}`;
      });
      return {
        content: [{
          type: "text" as const,
          text: `Path: ${path}\nParent: ${res.parent ?? "none"}\n\n${lines.join("\n") || "(empty directory)"}`,
        }],
      };
    }
  );

  server.tool(
    "read_file",
    "Read text file content from the device.",
    {
      path: z.string().describe("Absolute file path"),
    },
    async ({ path }) => {
      const content = await httpGetText(`/file/read-text?path=${encodeURIComponent(path)}`);
      return { content: [{ type: "text" as const, text: content }] };
    }
  );

  server.tool(
    "write_file",
    "Write text content to a file on the device (auto-creates parent directories).",
    {
      path: z.string().describe("Absolute file path"),
      content: z.string().describe("Text content to write"),
    },
    async ({ path, content }) => {
      const res = await httpPostJson<{ success?: boolean; error?: string }>("/file/write-text", { path, content });
      if (res.error) throw new Error(res.error);
      return { content: [{ type: "text" as const, text: `written: ${path}` }] };
    }
  );

  server.tool(
    "file_exists",
    "Check if a file or directory exists on the device.",
    {
      path: z.string().describe("Absolute file or directory path"),
    },
    async ({ path }) => {
      const res = await httpGetJson<{ exists: boolean }>(`/file/exists?path=${encodeURIComponent(path)}`);
      return {
        content: [{ type: "text" as const, text: `${path}: ${res.exists ? "exists" : "not found"}` }],
      };
    }
  );

  server.tool(
    "delete_file",
    "Delete a file or directory on the device (recursive).",
    {
      path: z.string().describe("Absolute file or directory path"),
    },
    async ({ path }) => {
      const res = await httpGetJson<{ success: boolean }>(`/file/delete?path=${encodeURIComponent(path)}`);
      return {
        content: [{ type: "text" as const, text: res.success ? `deleted: ${path}` : `delete failed: ${path}` }],
      };
    }
  );

  server.tool(
    "create_directory",
    "Create a directory on the device (including all parent directories).",
    {
      path: z.string().describe("Absolute directory path"),
    },
    async ({ path }) => {
      const res = await httpGetJson<{ success: boolean }>(`/file/mkdir?path=${encodeURIComponent(path)}`);
      return {
        content: [{ type: "text" as const, text: res.success ? `created: ${path}` : `create failed: ${path}` }],
      };
    }
  );

  server.tool(
    "rename_file",
    "Rename a file or directory on the device.",
    {
      path: z.string().describe("Current absolute file path"),
      newName: z.string().describe("New file name (name only, not full path)"),
    },
    async ({ path, newName }) => {
      const res = await httpPostJson<{ success: boolean; newPath?: string; error?: string }>(
        "/file/rename", { oldPath: path, newName }
      );
      if (res.error) throw new Error(res.error);
      return {
        content: [{ type: "text" as const, text: res.success ? `renamed: ${path} → ${res.newPath}` : "rename failed" }],
      };
    }
  );
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
}
