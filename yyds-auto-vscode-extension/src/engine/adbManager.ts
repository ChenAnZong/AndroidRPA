/**
 * ADB 管理器 — 自动探测、按需下载、命令执行
 *
 * 三级策略:
 *   1. 用户手动配置 yyds.adbPath
 *   2. 自动探测 PATH、ANDROID_HOME、Android Studio 常见路径
 *   3. 按需下载 Google 官方 platform-tools
 */

import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import * as os from 'os';
import * as https from 'https';
import * as http from 'http';
import { execFile, spawn } from 'child_process';
import { log, logError } from './logger';

const TAG = 'AdbManager';

/** ADB 命令执行结果 */
export interface AdbResult {
    exitCode: number;
    stdout: string;
    stderr: string;
}

/** ADB 设备信息 */
export interface AdbDevice {
    serial: string;
    state: string; // device | offline | unauthorized | no permissions
}

export class AdbManager {
    private context: vscode.ExtensionContext;
    private cachedAdbPath: string | null = null;

    constructor(context: vscode.ExtensionContext) {
        this.context = context;
    }

    // ================================================================
    // ADB 路径查找
    // ================================================================

    /**
     * 查找可用的 ADB 路径（带缓存）
     * 优先级: 用户设置 > PATH > 环境变量 > 常见路径 > 已下载缓存
     */
    async findAdb(): Promise<string | null> {
        if (this.cachedAdbPath && fs.existsSync(this.cachedAdbPath)) {
            return this.cachedAdbPath;
        }

        const found = this.searchAdb();
        if (found) {
            this.cachedAdbPath = found;
            log(TAG, `找到ADB: ${found}`);
            return found;
        }

        log(TAG, '未找到ADB');
        return null;
    }

    /**
     * 同步搜索 ADB 可执行文件
     */
    private searchAdb(): string | null {
        const isWin = process.platform === 'win32';
        const adbName = isWin ? 'adb.exe' : 'adb';

        // 1. 用户手动配置
        const userPath = vscode.workspace.getConfiguration('yyds').get<string>('adbPath');
        if (userPath && userPath.trim().length > 0) {
            const resolved = userPath.trim();
            if (fs.existsSync(resolved)) {
                return resolved;
            }
            log(TAG, `用户配置的ADB路径不存在: ${resolved}`);
        }

        // 2. 搜索 PATH 环境变量
        const pathDirs = (process.env.PATH || '').split(path.delimiter);
        for (const dir of pathDirs) {
            const candidate = path.join(dir, adbName);
            if (fs.existsSync(candidate)) {
                return candidate;
            }
        }

        // 3. 搜索 ANDROID_HOME / ANDROID_SDK_ROOT
        const sdkEnvVars = ['ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_SDK'];
        for (const envVar of sdkEnvVars) {
            const sdkPath = process.env[envVar];
            if (sdkPath) {
                const candidate = path.join(sdkPath, 'platform-tools', adbName);
                if (fs.existsSync(candidate)) {
                    return candidate;
                }
            }
        }

        // 4. 搜索常见安装路径
        const commonPaths = this.getCommonAdbPaths(adbName);
        for (const candidate of commonPaths) {
            if (fs.existsSync(candidate)) {
                return candidate;
            }
        }

        // 5. 搜索插件 globalStorage 中已下载的 ADB
        const downloadedPath = this.getDownloadedAdbPath();
        if (downloadedPath && fs.existsSync(downloadedPath)) {
            return downloadedPath;
        }

        return null;
    }

    /**
     * 返回各平台常见的 ADB 安装路径
     */
    private getCommonAdbPaths(adbName: string): string[] {
        const home = os.homedir();
        const paths: string[] = [];

        if (process.platform === 'win32') {
            // Windows
            const localAppData = process.env.LOCALAPPDATA || path.join(home, 'AppData', 'Local');
            paths.push(
                path.join(localAppData, 'Android', 'Sdk', 'platform-tools', adbName),
                path.join(home, 'AppData', 'Local', 'Android', 'Sdk', 'platform-tools', adbName),
                'C:\\Android\\platform-tools\\' + adbName,
                'C:\\android-sdk\\platform-tools\\' + adbName,
                // Android Studio bundled
                path.join(localAppData, 'Google', 'AndroidStudio', 'platform-tools', adbName),
            );
            // 搜索 Program Files
            const programFiles = process.env['ProgramFiles'] || 'C:\\Program Files';
            const programFilesX86 = process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)';
            paths.push(
                path.join(programFiles, 'Android', 'platform-tools', adbName),
                path.join(programFilesX86, 'Android', 'platform-tools', adbName),
            );
        } else if (process.platform === 'darwin') {
            // macOS
            paths.push(
                path.join(home, 'Library', 'Android', 'sdk', 'platform-tools', adbName),
                '/usr/local/bin/' + adbName,
                '/opt/homebrew/bin/' + adbName,
            );
        } else {
            // Linux
            paths.push(
                path.join(home, 'Android', 'Sdk', 'platform-tools', adbName),
                path.join(home, 'android-sdk', 'platform-tools', adbName),
                '/usr/bin/' + adbName,
                '/usr/local/bin/' + adbName,
            );
        }

        return paths;
    }

    /**
     * 获取已下载的 ADB 路径
     */
    private getDownloadedAdbPath(): string | null {
        const storageUri = this.context.globalStorageUri;
        if (!storageUri) { return null; }
        const isWin = process.platform === 'win32';
        const adbName = isWin ? 'adb.exe' : 'adb';
        return path.join(storageUri.fsPath, 'platform-tools', adbName);
    }

    // ================================================================
    // ADB 下载
    // ================================================================

    /**
     * 获取当前平台的 platform-tools 下载 URL
     */
    private getPlatformToolsUrl(): string {
        const platformMap: Record<string, string> = {
            'win32': 'windows',
            'darwin': 'darwin',
            'linux': 'linux',
        };
        const platform = platformMap[process.platform] || 'linux';
        return `https://dl.google.com/android/repository/platform-tools-latest-${platform}.zip`;
    }

    /**
     * 下载 platform-tools 并解压到 globalStorage
     * 返回 ADB 可执行文件路径
     */
    async downloadPlatformTools(): Promise<string | null> {
        const confirm = await vscode.window.showInformationMessage(
            '未找到 ADB 工具，是否从 Google 官方下载 platform-tools？（约 5MB）',
            { modal: true },
            '下载',
            '手动选择ADB路径'
        );

        if (confirm === '手动选择ADB路径') {
            const selected = await vscode.window.showOpenDialog({
                canSelectFiles: true,
                canSelectFolders: false,
                canSelectMany: false,
                title: '选择 ADB 可执行文件',
                filters: process.platform === 'win32'
                    ? { 'ADB': ['exe'] }
                    : undefined,
            });
            if (selected && selected.length > 0) {
                const selectedPath = selected[0].fsPath;
                await vscode.workspace.getConfiguration('yyds').update('adbPath', selectedPath, true);
                this.cachedAdbPath = selectedPath;
                return selectedPath;
            }
            return null;
        }

        if (confirm !== '下载') {
            return null;
        }

        const url = this.getPlatformToolsUrl();
        const storageDir = this.context.globalStorageUri.fsPath;
        const zipPath = path.join(storageDir, 'platform-tools.zip');

        // 确保存储目录存在
        fs.mkdirSync(storageDir, { recursive: true });

        return vscode.window.withProgress({
            location: vscode.ProgressLocation.Notification,
            title: 'Yyds.Auto: 下载 ADB 工具',
            cancellable: true,
        }, async (progress, token) => {
            try {
                // 下载
                progress.report({ message: '正在下载 platform-tools...' });
                await this.downloadFile(url, zipPath, (percent) => {
                    progress.report({ message: `下载中 ${percent}%`, increment: 0 });
                }, token);

                if (token.isCancellationRequested) {
                    this.cleanupFile(zipPath);
                    return null;
                }

                // 解压
                progress.report({ message: '正在解压...' });
                await this.extractZip(zipPath, storageDir);

                // 清理 zip
                this.cleanupFile(zipPath);

                // 设置执行权限 (macOS/Linux)
                const adbPath = this.getDownloadedAdbPath();
                if (adbPath && process.platform !== 'win32') {
                    fs.chmodSync(adbPath, 0o755);
                }

                if (adbPath && fs.existsSync(adbPath)) {
                    this.cachedAdbPath = adbPath;
                    log(TAG, `ADB下载完成: ${adbPath}`);
                    vscode.window.showInformationMessage('ADB 工具下载完成！');
                    return adbPath;
                }

                logError(TAG, 'ADB下载后未找到可执行文件');
                return null;
            } catch (e: any) {
                logError(TAG, '下载ADB失败', e);
                this.cleanupFile(zipPath);
                vscode.window.showErrorMessage(`ADB 下载失败: ${e.message || e}`);
                return null;
            }
        });
    }

    /**
     * HTTP(S) 下载文件（支持重定向）
     */
    private downloadFile(
        url: string,
        destPath: string,
        onProgress: (percent: number) => void,
        token: vscode.CancellationToken
    ): Promise<void> {
        return new Promise((resolve, reject) => {
            const doRequest = (reqUrl: string, redirectCount: number) => {
                if (redirectCount > 5) {
                    reject(new Error('重定向次数过多'));
                    return;
                }

                const client = reqUrl.startsWith('https') ? https : http;
                const req = client.get(reqUrl, (res) => {
                    // 处理重定向
                    if (res.statusCode && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                        doRequest(res.headers.location, redirectCount + 1);
                        return;
                    }

                    if (res.statusCode !== 200) {
                        reject(new Error(`HTTP ${res.statusCode}`));
                        return;
                    }

                    const totalSize = parseInt(res.headers['content-length'] || '0', 10);
                    let downloaded = 0;
                    const file = fs.createWriteStream(destPath);

                    res.on('data', (chunk: Buffer) => {
                        if (token.isCancellationRequested) {
                            res.destroy();
                            file.close();
                            return;
                        }
                        downloaded += chunk.length;
                        file.write(chunk);
                        if (totalSize > 0) {
                            onProgress(Math.round(downloaded / totalSize * 100));
                        }
                    });

                    res.on('end', () => {
                        file.end(() => resolve());
                    });

                    res.on('error', (err) => {
                        file.close();
                        reject(err);
                    });
                });

                req.on('error', reject);

                token.onCancellationRequested(() => {
                    req.destroy();
                    reject(new Error('用户取消'));
                });
            };

            doRequest(url, 0);
        });
    }

    /**
     * 解压 zip 文件
     * Windows 使用 PowerShell Expand-Archive，其他平台使用 unzip
     */
    private extractZip(zipPath: string, destDir: string): Promise<void> {
        return new Promise((resolve, reject) => {
            if (process.platform === 'win32') {
                const psCmd = `Expand-Archive -Path '${zipPath}' -DestinationPath '${destDir}' -Force`;
                const proc = spawn('powershell', ['-NoProfile', '-Command', psCmd], {
                    stdio: ['ignore', 'pipe', 'pipe'],
                });
                let stderr = '';
                proc.stderr.on('data', (d) => { stderr += d.toString(); });
                proc.on('close', (code) => {
                    if (code === 0) { resolve(); }
                    else { reject(new Error(`解压失败 (code=${code}): ${stderr}`)); }
                });
                proc.on('error', reject);
            } else {
                execFile('unzip', ['-o', zipPath, '-d', destDir], (err) => {
                    if (err) { reject(err); } else { resolve(); }
                });
            }
        });
    }

    private cleanupFile(filePath: string): void {
        try { fs.unlinkSync(filePath); } catch {}
    }

    // ================================================================
    // ADB 命令执行
    // ================================================================

    /**
     * 执行 ADB 命令
     */
    async runAdb(...args: string[]): Promise<AdbResult> {
        const adbPath = await this.findAdb();
        if (!adbPath) {
            return { exitCode: -1, stdout: '', stderr: '未找到ADB' };
        }

        return new Promise((resolve) => {
            log(TAG, `执行: ${adbPath} ${args.join(' ')}`);
            execFile(adbPath, args, { timeout: 30_000 }, (err, stdout, stderr) => {
                const exitCode = err ? (err as any).code ?? 1 : 0;
                if (stdout) { log(TAG, `stdout: ${stdout.substring(0, 200)}`); }
                if (stderr) { log(TAG, `stderr: ${stderr.substring(0, 200)}`); }
                resolve({ exitCode, stdout: stdout || '', stderr: stderr || '' });
            });
        });
    }

    /**
     * 对指定设备执行 ADB shell 命令
     */
    async runAdbShell(serial: string | null, shellCmd: string): Promise<AdbResult> {
        const args: string[] = [];
        if (serial) {
            args.push('-s', serial);
        }
        args.push('shell', shellCmd);
        return this.runAdb(...args);
    }

    /**
     * 列出已连接的 ADB 设备
     */
    async getDevices(): Promise<AdbDevice[]> {
        const result = await this.runAdb('devices');
        if (result.exitCode !== 0) { return []; }

        const devices: AdbDevice[] = [];
        const lines = result.stdout.split('\n');
        for (const line of lines) {
            const match = line.trim().match(/^(\S+)\s+(device|offline|unauthorized|no\s+permissions.*)$/);
            if (match) {
                devices.push({
                    serial: match[1],
                    state: match[2].startsWith('no') ? 'no permissions' : match[2],
                });
            }
        }
        return devices;
    }

    // ================================================================
    // 无线 ADB 连接
    // ================================================================

    /**
     * 无线配对 (Android 11+)
     */
    async pairWireless(ip: string, port: number, code: string): Promise<AdbResult> {
        return this.runAdb('pair', `${ip}:${port}`, code);
    }

    /**
     * 无线连接
     */
    async connectWireless(ip: string, port: number): Promise<AdbResult> {
        return this.runAdb('connect', `${ip}:${port}`);
    }

    /**
     * 断开无线连接
     */
    async disconnectWireless(ip: string, port: number): Promise<AdbResult> {
        return this.runAdb('disconnect', `${ip}:${port}`);
    }

    // ================================================================
    // ADB 获取（查找或引导下载）
    // ================================================================

    /**
     * 确保 ADB 可用：找不到则引导用户下载
     * 返回 ADB 路径或 null（用户取消）
     */
    async ensureAdb(): Promise<string | null> {
        const adbPath = await this.findAdb();
        if (adbPath) { return adbPath; }
        return this.downloadPlatformTools();
    }

    /**
     * 清除缓存（用于调试或 ADB 路径变更后重新探测）
     */
    clearCache(): void {
        this.cachedAdbPath = null;
    }
}
