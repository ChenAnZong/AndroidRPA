/**
 * HTTP 客户端 — 与安卓设备 yyds.py 引擎通信 (端口 61140)
 * 内置 ADB 连接断开自动重连机制
 */

import http from "node:http";

const DEFAULT_HOST = "127.0.0.1";
const DEFAULT_PORT = 61140;
const DEFAULT_TIMEOUT = 15_000;

export function getHost(): string {
  return process.env.YYDS_DEVICE_HOST || DEFAULT_HOST;
}

export function getPort(): number {
  const p = process.env.YYDS_DEVICE_PORT;
  return p ? parseInt(p, 10) : DEFAULT_PORT;
}

function baseUrl(): string {
  return `http://${getHost()}:${getPort()}`;
}

/** 判断是否为本地地址（需要 ADB 转发的场景） */
export function isLocalHost(host: string): boolean {
  const h = host.trim().toLowerCase();
  return h === "127.0.0.1" || h === "localhost" || h === "0.0.0.0" || h === "::1";
}

// ================================================================
// ADB 自动重连管理
// ================================================================

/** ADB 上下文，由 index.ts 初始化时注入 */
let _adbPath: string | null = null;
let _adbSerial: string | null = null;
let _reconnecting: Promise<boolean> | null = null;
let _lastReconnectTime = 0;
const RECONNECT_COOLDOWN_MS = 5_000; // 5秒内不重复重连

export function setAdbContext(adbPath: string | null, serial: string | null): void {
  _adbPath = adbPath;
  _adbSerial = serial;
}

function log(msg: string): void {
  console.error(`[yyds-mcp:client] ${msg}`);
}

/** 尝试重建 ADB 端口转发，带去重和冷却 */
async function tryReconnectAdb(): Promise<boolean> {
  if (!_adbPath || !_adbSerial) {
    log("ADB context not available, cannot reconnect. Check USB cable and run: adb forward tcp:61140 tcp:61140");
    return false;
  }

  // 冷却期内不重复重连
  if (Date.now() - _lastReconnectTime < RECONNECT_COOLDOWN_MS) {
    return false;
  }

  // 去重：多个并发请求同时失败时只执行一次重连
  if (_reconnecting) return _reconnecting;

  _reconnecting = (async () => {
    try {
      log("Connection lost, attempting ADB forward reconnect...");
      _lastReconnectTime = Date.now();

      // 动态导入避免循环依赖
      const { adbForward } = await import("./adb.js");
      const port = getPort();
      const ok = await adbForward(_adbPath!, _adbSerial!, port, port);

      if (ok) {
        log("ADB forward reconnected successfully");
      } else {
        log("ADB forward reconnect failed. Device may be disconnected — check USB cable");
      }
      return ok;
    } catch (e) {
      log(`ADB reconnect error: ${e}`);
      return false;
    } finally {
      _reconnecting = null;
    }
  })();

  return _reconnecting;
}

/** 判断错误是否为连接断开（需要重连） */
function isConnectionError(err: unknown): boolean {
  if (err instanceof Error) {
    const msg = err.message;
    return msg.includes("ECONNREFUSED") || msg.includes("ECONNRESET") || msg.includes("EPIPE") || msg.includes("socket hang up");
  }
  return false;
}

// ================================================================
// 底层 HTTP 请求（无重连）
// ================================================================

function _httpGetText(path: string, timeoutMs: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const url = `${baseUrl()}${path}`;
    const req = http.get(url, { timeout: timeoutMs }, (res) => {
      const chunks: Buffer[] = [];
      res.on("data", (c: Buffer) => chunks.push(c));
      res.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf-8");
        if (res.statusCode && res.statusCode >= 400) {
          reject(new Error(`HTTP ${res.statusCode}: ${text}`));
        } else {
          resolve(text);
        }
      });
      res.on("error", reject);
    });
    req.on("timeout", () => { req.destroy(); reject(new Error(`Timeout: GET ${path}`)); });
    req.on("error", reject);
  });
}

function _httpGetBuffer(path: string, timeoutMs: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const url = `${baseUrl()}${path}`;
    const req = http.get(url, { timeout: timeoutMs }, (res) => {
      if (res.statusCode && res.statusCode >= 400) {
        const chunks: Buffer[] = [];
        res.on("data", (c: Buffer) => chunks.push(c));
        res.on("end", () => reject(new Error(`HTTP ${res.statusCode}: ${Buffer.concat(chunks).toString()}`)));
        return;
      }
      const chunks: Buffer[] = [];
      res.on("data", (c: Buffer) => chunks.push(c));
      res.on("end", () => resolve(Buffer.concat(chunks)));
      res.on("error", reject);
    });
    req.on("timeout", () => { req.destroy(); reject(new Error(`Timeout: GET(bin) ${path}`)); });
    req.on("error", reject);
  });
}

function _httpPostJson<T>(path: string, data: Record<string, unknown>, timeoutMs: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const url = new URL(`${baseUrl()}${path}`);
    const jsonBody = JSON.stringify(data);
    const req = http.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname,
        method: "POST",
        timeout: timeoutMs,
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(jsonBody),
        },
      },
      (res) => {
        const chunks: Buffer[] = [];
        res.on("data", (c: Buffer) => chunks.push(c));
        res.on("end", () => {
          const body = Buffer.concat(chunks).toString("utf-8");
          if (res.statusCode && res.statusCode >= 400) {
            reject(new Error(`HTTP ${res.statusCode}: ${body}`));
          } else {
            try { resolve(JSON.parse(body) as T); }
            catch { resolve(body as unknown as T); }
          }
        });
        res.on("error", reject);
      }
    );
    req.on("timeout", () => { req.destroy(); reject(new Error(`Timeout: POST ${path}`)); });
    req.on("error", reject);
    req.write(jsonBody);
    req.end();
  });
}

// ================================================================
// 带自动重连的公开 HTTP 接口
// ================================================================

/** 通用重试包装：连接失败时自动重建 ADB forward 并重试一次 */
async function withReconnect<T>(fn: () => Promise<T>, label: string): Promise<T> {
  try {
    return await fn();
  } catch (err) {
    if (isConnectionError(err) && isLocalHost(getHost())) {
      log(`${label} failed (${(err as Error).message}), trying ADB reconnect...`);
      const reconnected = await tryReconnectAdb();
      if (reconnected) {
        // 重连成功，重试一次
        return await fn();
      }
      // 重连失败，抛出友好错误
      throw new Error(
        `Device connection lost. Please check:\n` +
        `1. USB cable is connected\n` +
        `2. ADB is authorized (check device screen for USB debugging prompt)\n` +
        `3. Run manually: adb forward tcp:${getPort()} tcp:${getPort()}\n` +
        `Original error: ${(err as Error).message}`
      );
    }
    throw err;
  }
}

export async function httpGetJson<T = Record<string, unknown>>(
  path: string,
  timeoutMs = DEFAULT_TIMEOUT
): Promise<T> {
  const body = await httpGetText(path, timeoutMs);
  return JSON.parse(body) as T;
}

export async function httpGetText(
  path: string,
  timeoutMs = DEFAULT_TIMEOUT
): Promise<string> {
  return withReconnect(() => _httpGetText(path, timeoutMs), `GET ${path}`);
}

export async function httpGetBuffer(
  path: string,
  timeoutMs = DEFAULT_TIMEOUT
): Promise<Buffer> {
  return withReconnect(() => _httpGetBuffer(path, timeoutMs), `GET(bin) ${path}`);
}

export async function httpPostJson<T = Record<string, unknown>>(
  path: string,
  data: Record<string, unknown> = {},
  timeoutMs = DEFAULT_TIMEOUT
): Promise<T> {
  return withReconnect(() => _httpPostJson<T>(path, data, timeoutMs), `POST ${path}`);
}

/** POST to /engine/auto — proxy to yyds.auto engine
 *  ExportApi 返回格式: {"ok":true,"data":"..."} 或 {"ok":false,"error":"..."}
 *  /engine/auto 包装为: {"success":true,"result":"<ExportApi原始JSON>"}
 *  本函数解析两层，返回 data 字段的字符串表示
 */
export async function autoApi(
  uri: string,
  params: Record<string, string> = {}
): Promise<string> {
  const res = await httpPostJson<{ success?: boolean; result?: string; error?: string }>(
    "/engine/auto",
    { uri, ...params }
  );
  if (res.error) throw new Error(res.error);
  const raw = res.result ?? "";
  // 尝试解析 ExportApi 的 JSON 响应
  try {
    const parsed = JSON.parse(raw);
    if (parsed.ok === false && parsed.error) {
      throw new Error(parsed.error);
    }
    if (parsed.data !== undefined) {
      // data 可能是对象/数组/字符串/数字，统一转为字符串
      return typeof parsed.data === "string"
        ? parsed.data
        : JSON.stringify(parsed.data);
    }
    return raw;
  } catch (e) {
    if (e instanceof SyntaxError) return raw; // 非JSON直接返回
    throw e;
  }
}

/** POST to /api/{api} — direct proxy to yyds.auto */
export async function directAutoApi(
  api: string,
  params: Record<string, string> = {}
): Promise<string> {
  return httpPostJson<string>(`/api/${api}`, params);
}
