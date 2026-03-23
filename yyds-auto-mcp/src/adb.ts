/**
 * ADB 工具模块 — 纯 Node.js 实现，无 vscode 依赖
 *
 * 提供 ADB 路径搜索、设备列表、端口转发、shell 执行
 */

import { execFile } from "node:child_process";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join, delimiter } from "node:path";

const ADB_TIMEOUT = 15_000;

export interface AdbResult {
  exitCode: number;
  stdout: string;
  stderr: string;
}

export interface AdbDevice {
  serial: string;
  state: string;
}

/** 日志输出到 stderr（stdout 是 MCP 协议通道） */
function log(msg: string): void {
  console.error(`[yyds-mcp:adb] ${msg}`);
}

// ================================================================
// ADB 路径搜索
// ================================================================

let cachedAdbPath: string | null = null;

/**
 * 搜索 ADB 可执行文件
 * 优先级: YYDS_ADB_PATH 环境变量 > PATH > ANDROID_HOME > 常见路径
 */
export function findAdb(): string | null {
  if (cachedAdbPath && existsSync(cachedAdbPath)) {
    return cachedAdbPath;
  }

  const isWin = process.platform === "win32";
  const adbName = isWin ? "adb.exe" : "adb";

  // 1. 用户通过环境变量指定
  const envPath = process.env.YYDS_ADB_PATH;
  if (envPath && envPath.trim().length > 0 && existsSync(envPath.trim())) {
    cachedAdbPath = envPath.trim();
    return cachedAdbPath;
  }

  // 2. PATH 环境变量
  const pathDirs = (process.env.PATH || "").split(delimiter);
  for (const dir of pathDirs) {
    const candidate = join(dir, adbName);
    if (existsSync(candidate)) {
      cachedAdbPath = candidate;
      return cachedAdbPath;
    }
  }

  // 3. ANDROID_HOME / ANDROID_SDK_ROOT
  for (const envVar of ["ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_SDK"]) {
    const sdkPath = process.env[envVar];
    if (sdkPath) {
      const candidate = join(sdkPath, "platform-tools", adbName);
      if (existsSync(candidate)) {
        cachedAdbPath = candidate;
        return cachedAdbPath;
      }
    }
  }

  // 4. 平台常见路径
  const home = homedir();
  const commonPaths: string[] = [];

  if (isWin) {
    const localAppData =
      process.env.LOCALAPPDATA || join(home, "AppData", "Local");
    commonPaths.push(
      join(localAppData, "Android", "Sdk", "platform-tools", adbName),
      join(home, "AppData", "Local", "Android", "Sdk", "platform-tools", adbName),
      `C:\\Android\\platform-tools\\${adbName}`,
      `C:\\android-sdk\\platform-tools\\${adbName}`
    );
  } else if (process.platform === "darwin") {
    commonPaths.push(
      join(home, "Library", "Android", "sdk", "platform-tools", adbName),
      `/usr/local/bin/${adbName}`,
      `/opt/homebrew/bin/${adbName}`
    );
  } else {
    commonPaths.push(
      join(home, "Android", "Sdk", "platform-tools", adbName),
      `/usr/bin/${adbName}`,
      `/usr/local/bin/${adbName}`
    );
  }

  for (const candidate of commonPaths) {
    if (existsSync(candidate)) {
      cachedAdbPath = candidate;
      return cachedAdbPath;
    }
  }

  return null;
}

// ================================================================
// ADB 命令执行
// ================================================================

function runAdb(adbPath: string, args: string[], timeout = ADB_TIMEOUT): Promise<AdbResult> {
  return new Promise((resolve) => {
    execFile(adbPath, args, { timeout }, (err, stdout, stderr) => {
      const exitCode = err ? ((err as NodeJS.ErrnoException).code ? 1 : 1) : 0;
      resolve({ exitCode, stdout: stdout || "", stderr: stderr || "" });
    });
  });
}

/** 列出已连接的 ADB 设备 */
export async function getDevices(adbPath: string): Promise<AdbDevice[]> {
  const result = await runAdb(adbPath, ["devices"]);
  if (result.exitCode !== 0) return [];

  const devices: AdbDevice[] = [];
  for (const line of result.stdout.split("\n")) {
    const match = line
      .trim()
      .match(/^(\S+)\s+(device|offline|unauthorized|no\s+permissions.*)$/);
    if (match) {
      devices.push({
        serial: match[1],
        state: match[2].startsWith("no") ? "no permissions" : match[2],
      });
    }
  }
  return devices;
}

/** 执行 adb forward */
export async function adbForward(
  adbPath: string,
  serial: string,
  localPort: number,
  remotePort: number
): Promise<boolean> {
  const args = ["-s", serial, "forward", `tcp:${localPort}`, `tcp:${remotePort}`];
  log(`adb ${args.join(" ")}`);
  const result = await runAdb(adbPath, args);
  if (result.exitCode !== 0) {
    log(`forward 失败: ${result.stderr}`);
  }
  return result.exitCode === 0;
}

/** 执行 adb shell 命令 */
export async function adbShell(
  adbPath: string,
  serial: string,
  cmd: string
): Promise<AdbResult> {
  return runAdb(adbPath, ["-s", serial, "shell", cmd]);
}

// ================================================================
// 组合逻辑
// ================================================================

export interface ForwardResult {
  forwarded: boolean;
  adbPath: string | null;
  serial: string | null;
}

/**
 * 自动查找 ADB → 获取设备 → 执行端口转发
 * best-effort，失败不抛异常
 */
export async function checkAndForward(port: number): Promise<ForwardResult> {
  const adbPath = findAdb();
  if (!adbPath) {
    log("未找到 ADB，跳过端口转发。请安装 Android SDK 或设置 YYDS_ADB_PATH 环境变量");
    return { forwarded: false, adbPath: null, serial: null };
  }
  log(`找到 ADB: ${adbPath}`);

  const devices = await getDevices(adbPath);
  const available = devices.filter((d) => d.state === "device");
  if (available.length === 0) {
    log("未检测到可用 ADB 设备，跳过端口转发");
    return { forwarded: false, adbPath, serial: null };
  }

  // 优先使用环境变量指定的设备，否则取第一个
  const targetSerial =
    process.env.YYDS_DEVICE_SERIAL?.trim() ||
    available[0].serial;

  const device = available.find((d) => d.serial === targetSerial) || available[0];
  log(`目标设备: ${device.serial}` + (available.length > 1 ? ` (共 ${available.length} 台设备)` : ""));

  const ok = await adbForward(adbPath, device.serial, port, port);
  if (ok) {
    log(`端口转发成功: tcp:${port} → tcp:${port}`);
  }
  return { forwarded: ok, adbPath, serial: device.serial };
}
