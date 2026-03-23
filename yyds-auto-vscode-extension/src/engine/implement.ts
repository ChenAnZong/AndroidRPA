/**
 * 引擎实现 — JSON + HTTP REST 架构
 *
 * 所有操作通过 HTTP REST 完成:
 *   - 截图: GET /screenshot
 *   - UI Dump: GET /ui-dump
 *   - 启动/停止: GET /project/start, /project/stop
 *   - 点击/代码/Shell: POST /engine/click, /engine/run-code, /engine/shell
 *   - 文件传输: POST /push-project (multipart)
 *
 * 不使用 WebSocket RPC，仅通过 HTTP REST 通信。
 */

import * as fs from 'fs';
import * as path from 'path';
import { EngineConnector } from './connector';
import { API, ApiResponse, InstalledAppInfo, PackageBuildConfig, PackageBuildResult, AgentConfig, AgentStatus } from './protocol';
import { zipFiles } from '../utils/zipUtil';
import { log, logError } from './logger';

const TAG = 'Engine';

export class EngineImplement {
    private connector: EngineConnector;

    constructor(connector: EngineConnector) {
        this.connector = connector;
    }

    // ================================================================
    // 项目控制
    // ================================================================

    async startProject(projectName: string): Promise<boolean> {
        log(TAG, `启动项目: ${projectName}`);
        const st = Date.now();
        const res = await this.connector.httpGetJson(`${API.PROJECT_START}?name=${encodeURIComponent(projectName)}`);
        if (res && res.success) {
            log(TAG, `项目启动指令已发送, 耗时 ${Date.now() - st}ms`);
            return true;
        }
        logError(TAG, `项目启动失败: ${res?.error || '无响应'}`);
        return false;
    }

    async stopProject(): Promise<void> {
        log(TAG, '停止项目');
        await this.connector.httpGetJson(API.PROJECT_STOP);
    }

    // ================================================================
    // 截图 — HTTP GET
    // ================================================================

    async getScreenShot(): Promise<Buffer | null> {
        log(TAG, '获取截图...');
        const st = Date.now();

        try {
            // 使用 /screen 端点（JPEG，直接从内存返回，不依赖项目目录）
            // ?no-cache 强制获取最新帧
            const buf = await this.connector.httpGetBuffer(`${API.SCREEN}?no-cache=true`);
            if (buf && buf.length > 100) {
                log(TAG, `截图成功, ${buf.length} 字节, 耗时 ${Date.now() - st}ms`);
                return buf;
            }
            logError(TAG, `截图返回数据异常: ${buf?.length || 0} 字节`);
        } catch (e) {
            logError(TAG, '截图失败', e);
        }

        logError(TAG, `截图失败, 耗时 ${Date.now() - st}ms`);
        return null;
    }

    // ================================================================
    // UI 控件树 — HTTP GET
    // ================================================================

    async getUiDump(): Promise<string | null> {
        log(TAG, '获取 UI Dump...');
        const st = Date.now();

        try {
            const xml = await this.connector.httpGetText(API.UI_DUMP);
            if (xml && xml.length > 10 && !xml.includes('"error"')) {
                log(TAG, `UI Dump 成功, ${xml.length} 字符, 耗时 ${Date.now() - st}ms`);
                return xml;
            }
            logError(TAG, `UI Dump 返回异常: ${xml?.substring(0, 100) || '空'}`);
        } catch (e) {
            logError(TAG, 'UI Dump 失败', e);
        }

        logError(TAG, `UI Dump 失败, 耗时 ${Date.now() - st}ms`);
        return null;
    }

    // ================================================================
    // 前台应用
    // ================================================================

    async getForeground(): Promise<string> {
        const res = await this.connector.httpGetJson(API.ENGINE_FOREGROUND);
        if (res && res.success) {
            return res.result || '';
        }
        return '';
    }

    // ================================================================
    // 代码执行
    // ================================================================

    async runCodeSnippet(code: string): Promise<boolean> {
        log(TAG, `运行代码片段, ${code.length} 字符`);
        const res = await this.connector.httpPostJson(API.ENGINE_RUN_CODE, {
            code: code.trimStart(),
        });
        const ok = res !== null && res.success === true;
        log(TAG, `代码执行结果: ${ok ? '成功' : '失败'}`);
        return ok;
    }

    // ================================================================
    // 点击
    // ================================================================

    async click(x: number, y: number): Promise<boolean> {
        log(TAG, `点击 (${x}, ${y})`);
        const res = await this.connector.httpPostJson(API.ENGINE_CLICK, { x, y });
        return res !== null && res.success === true;
    }

    // ================================================================
    // 文件传输 — HTTP POST multipart
    // ================================================================

    async sendEntireProject(projectPath: string, projectName: string, files: string[]): Promise<boolean> {
        log(TAG, `推送项目: ${projectName}, ${files.length} 个文件`);
        const tempZip = path.join(projectPath, '.local.zip');

        try {
            await zipFiles(files, tempZip);
            log(TAG, `压缩完成: ${tempZip}`);
        } catch (e) {
            logError(TAG, '压缩失败', e);
            return false;
        }

        const st = Date.now();
        const zipData = fs.readFileSync(tempZip);
        const sizeKb = Math.round(zipData.length / 1024);
        log(TAG, `发送中: ${sizeKb}KB...`);

        const res = await this.connector.httpPostMultipart(
            API.PUSH_PROJECT,
            { name: projectName },
            zipData,
            `${projectName}.zip`
        );
        const elapsed = Date.now() - st;

        try { fs.unlinkSync(tempZip); } catch {}

        if (res && res.success) {
            log(TAG, `推送完成: ${projectName} ${sizeKb}KB 耗时 ${elapsed}ms`);
            return true;
        }

        logError(TAG, `推送失败: ${projectName} ${res?.error || '无响应'}`);
        return false;
    }

    // ================================================================
    // APK打包
    // ================================================================

    async buildApk(config: PackageBuildConfig): Promise<PackageBuildResult | null> {
        log(TAG, `打包APK: ${config.appName} (${config.projectName})`);
        const st = Date.now();
        try {
            const res = await this.connector.httpPostJson(API.PACKAGE_BUILD, config);
            const elapsed = Date.now() - st;
            if (res) {
                if (res.success) {
                    log(TAG, `打包成功: ${res.outputPath} (${Math.round((res.fileSize || 0) / 1024)}KB, ${elapsed}ms)`);
                } else {
                    logError(TAG, `打包失败: ${res.error}`);
                }
                return res as PackageBuildResult;
            }
            logError(TAG, '打包无响应');
            return null;
        } catch (e) {
            logError(TAG, '打包异常', e);
            return null;
        }
    }

    async getBuiltApkList(): Promise<any[]> {
        try {
            const res = await this.connector.httpGetJson(API.PACKAGE_LIST);
            return Array.isArray(res) ? res : [];
        } catch (e) {
            logError(TAG, '获取已打包列表失败', e);
            return [];
        }
    }

    async getInstalledApps(): Promise<InstalledAppInfo[]> {
        try {
            const res = await this.connector.httpGetJson(API.PACKAGE_INSTALLED_APPS);
            return Array.isArray(res) ? res : [];
        } catch (e) {
            logError(TAG, '获取已安装应用列表失败', e);
            return [];
        }
    }

    async downloadApk(remotePath: string, localPath: string): Promise<boolean> {
        try {
            const buf = await this.connector.httpGetBuffer(
                `${API.PACKAGE_DOWNLOAD}?path=${encodeURIComponent(remotePath)}`
            );
            if (buf && buf.length > 0) {
                fs.writeFileSync(localPath, buf);
                log(TAG, `APK已下载: ${localPath} (${Math.round(buf.length / 1024)}KB)`);
                return true;
            }
            return false;
        } catch (e) {
            logError(TAG, 'APK下载失败', e);
            return false;
        }
    }

    // ================================================================
    // OCR 模型切换
    // ================================================================

    async setOcrVersion(version: 'v2' | 'v5_mobile' | 'v5_server', targetSize?: number): Promise<string> {
        let url = `${API.SET_OCR_VERSION}?version=${version}`;
        if (targetSize !== undefined) {
            url += `&target_size=${targetSize}`;
        }
        log(TAG, `切换OCR模型: ${version}`);
        const res = await this.connector.httpGetText(url);
        log(TAG, `OCR模型切换结果: ${res}`);
        return res || '';
    }

    // ================================================================
    // AI Agent
    // ================================================================

    async getAgentConfig(): Promise<AgentConfig | null> {
        try {
            return await this.connector.httpGetJson(API.AGENT_CONFIG) as AgentConfig;
        } catch (e) {
            logError(TAG, '获取Agent配置失败', e);
            return null;
        }
    }

    async setAgentConfig(config: Partial<AgentConfig>): Promise<boolean> {
        try {
            const res = await this.connector.httpPostJson(API.AGENT_CONFIG, config);
            return res?.success === true;
        } catch (e) {
            logError(TAG, '设置Agent配置失败', e);
            return false;
        }
    }

    async runAgent(instruction: string): Promise<{ success: boolean; error?: string }> {
        log(TAG, `启动Agent: ${instruction.substring(0, 50)}`);
        try {
            const res = await this.connector.httpPostJson(API.AGENT_RUN, { instruction });
            return (res as any) || { success: false, error: '无响应' };
        } catch (e) {
            logError(TAG, 'Agent启动失败', e);
            return { success: false, error: String(e) };
        }
    }

    async stopAgent(): Promise<boolean> {
        try {
            await this.connector.httpGetJson(API.AGENT_STOP);
            log(TAG, 'Agent已停止');
            return true;
        } catch (e) {
            logError(TAG, 'Agent停止失败', e);
            return false;
        }
    }

    async getAgentStatus(): Promise<AgentStatus | null> {
        try {
            return await this.connector.httpGetJson(API.AGENT_STATUS) as AgentStatus;
        } catch (e) {
            logError(TAG, '获取Agent状态失败', e);
            return null;
        }
    }

    async testAgentConnection(config: Partial<AgentConfig>): Promise<{ success: boolean; model?: string; latency_ms?: number; error?: string }> {
        try {
            const res = await this.connector.httpPostJson(API.AGENT_TEST, config);
            return (res as any) || { success: false, error: '无响应' };
        } catch (e) {
            logError(TAG, 'Agent连接测试失败', e);
            return { success: false, error: String(e) };
        }
    }
}
