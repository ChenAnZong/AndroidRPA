/**
 * 引擎启动检测 — 检查 yyds.py 引擎是否运行，未运行则尝试通过 ADB 启动
 */

import http from "node:http";
import { adbShell } from "./adb.js";
import { getHost, getPort } from "./client.js";

function log(msg: string): void {
  console.error(`[yyds-mcp:bootstrap] ${msg}`);
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** HTTP GET /ping，快速检测引擎是否可达 */
export function pingEngine(timeoutMs = 3000): Promise<boolean> {
  return new Promise((resolve) => {
    const url = `http://${getHost()}:${getPort()}/ping`;
    const req = http.get(url, { timeout: timeoutMs }, (res) => {
      res.resume();
      resolve(res.statusCode !== undefined && res.statusCode < 500);
    });
    req.on("timeout", () => {
      req.destroy();
      resolve(false);
    });
    req.on("error", () => resolve(false));
  });
}

/** 轮询 /ping 直到成功或超时 */
async function waitForEngine(maxWaitMs: number, intervalMs = 3000): Promise<boolean> {
  const deadline = Date.now() + maxWaitMs;
  while (Date.now() < deadline) {
    if (await pingEngine()) return true;
    const remaining = deadline - Date.now();
    if (remaining <= 0) break;
    await sleep(Math.min(intervalMs, remaining));
  }
  return false;
}

/**
 * 通过 am start 打开 App，触发 App 内自动启动引擎
 * 这是最简单可靠的方式 — App 的 ensureEngineRunning 会处理一切
 */
async function launchApp(adbPath: string, serial: string): Promise<boolean> {
  log("尝试通过 am start 启动 Yyds.Auto App...");
  const result = await adbShell(
    adbPath,
    serial,
    "am start -n com.yyds.auto/com.tencent.yyds.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
  );
  if (result.stdout.includes("Error") || result.stderr.includes("Error")) {
    log(`am start 失败: ${result.stdout} ${result.stderr}`);
    return false;
  }
  log("App 启动命令已发送");
  return true;
}

/**
 * Fallback: 通过 ADB 直接启动 yyds.keep 守护进程
 * 参考 engineActivator.ts 的完整流程
 */
async function launchKeeper(adbPath: string, serial: string): Promise<boolean> {
  log("尝试通过 keeper 方式启动引擎...");

  // 检查 App 是否已安装
  const pmResult = await adbShell(adbPath, serial, "pm path com.yyds.auto");
  if (!pmResult.stdout.includes("package:")) {
    log("设备上未安装 Yyds.Auto (com.yyds.auto)");
    return false;
  }

  // 检查 keeper 是否已在运行
  const pidCheck = await adbShell(adbPath, serial, "pidof yyds.keep");
  if (pidCheck.stdout.trim().length > 0) {
    log(`yyds.keep 已在运行 (PID=${pidCheck.stdout.trim()})`);
    return true;
  }

  // 获取设备 ABI
  const abiResult = await adbShell(adbPath, serial, "getprop ro.product.cpu.abi");
  const abi = abiResult.stdout.trim() || "arm64-v8a";

  // 获取 APK 路径
  const apkPath = pmResult.stdout.trim().replace("package:", "");

  // 提取 SO 文件
  const extractCmds = [
    `mkdir -p /data/local/tmp/cache/lib/${abi}`,
    `unzip -o ${apkPath} "lib/${abi}/*.so" -d /data/local/tmp/cache 2>/dev/null`,
    `chmod +x /data/local/tmp/cache/lib/${abi}/libyyds_keep.so`,
  ].join(" && ");

  const extractResult = await adbShell(adbPath, serial, extractCmds);
  log(`SO 提取: exit=${extractResult.exitCode}`);

  // 验证 keeper 存在
  const checkKeeper = await adbShell(
    adbPath,
    serial,
    `ls /data/local/tmp/cache/lib/${abi}/libyyds_keep.so`
  );
  if (!checkKeeper.stdout.includes("libyyds_keep")) {
    log("SO 文件提取失败");
    return false;
  }

  // 启动 yyds.keep
  const keeperPath = `/data/local/tmp/cache/lib/${abi}/libyyds_keep.so`;
  const ldPath = `/data/local/tmp/cache/lib/${abi}`;
  const launchCmd = `LD_LIBRARY_PATH=${ldPath} nohup ${keeperPath} ${apkPath} LD_LIBRARY_PATH=${ldPath} >/dev/null 2>&1 &`;
  await adbShell(adbPath, serial, launchCmd);

  // 验证启动
  await sleep(2000);
  const verify = await adbShell(adbPath, serial, "pidof yyds.keep");
  if (!verify.stdout.trim()) {
    await sleep(3000);
    const retry = await adbShell(adbPath, serial, "pidof yyds.keep");
    if (!retry.stdout.trim()) {
      log("yyds.keep 启动失败");
      return false;
    }
  }

  log(`yyds.keep 已启动 (PID=${verify.stdout.trim() || "checking..."})`);
  return true;
}

/**
 * 确保引擎运行 — best-effort，失败不抛异常
 *
 * 策略:
 *   1. ping 检测，已通则直接返回
 *   2. am start 打开 App（App 内自动启动引擎），等待 30s
 *   3. fallback: 直接启动 keeper，等待 20s
 */
export async function ensureEngine(
  adbPath: string,
  serial: string
): Promise<boolean> {
  // 已经可达
  if (await pingEngine()) {
    log("引擎已在运行");
    return true;
  }

  log("引擎未响应，尝试启动...");

  // 策略 1: am start
  const appLaunched = await launchApp(adbPath, serial);
  if (appLaunched) {
    log("等待引擎启动 (最多 30s)...");
    if (await waitForEngine(30_000)) {
      log("引擎启动成功 (via App)");
      return true;
    }
  }

  // 策略 2: keeper fallback
  const keeperOk = await launchKeeper(adbPath, serial);
  if (keeperOk) {
    log("等待引擎启动 (最多 20s)...");
    if (await waitForEngine(20_000)) {
      log("引擎启动成功 (via keeper)");
      return true;
    }
  }

  log("引擎启动超时，MCP 将继续启动但设备可能不可用");
  return false;
}
