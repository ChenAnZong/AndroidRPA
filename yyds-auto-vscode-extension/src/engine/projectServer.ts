/**
 * 项目服务 — 移植自 ProjectServerImpl.java
 * 管理项目配置读取、项目文件扫描、引擎调用的高层封装
 */

import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { EngineConnector } from './connector';
import { EngineImplement } from './implement';
import { PackageBuildConfig } from './protocol';
import { zipFiles } from '../utils/zipUtil';

// 项目配置常量键
const PROP_KEY_PROJECT_NAME = 'PROJECT_NAME';
const PROP_KEY_PROJECT_VERSION = 'PROJECT_VERSION';
const PROP_KEY_DEBUG_DEVICE_IP = 'DEBUG_DEVICE_IP';
const PROP_KEY_WITH_VERSION = 'PACK_KEY_WITH_VERSION';

export class ProjectServer {
    private connector: EngineConnector;
    private engine: EngineImplement;
    private projectPath: string;

    constructor(projectPath: string) {
        this.projectPath = projectPath;
        this.connector = new EngineConnector();
        this.engine = new EngineImplement(this.connector);

        // 读取项目配置中的设备IP
        try {
            const config = this.loadProjectProperties();
            const ip = config[PROP_KEY_DEBUG_DEVICE_IP];
            if (ip) {
                this.connector.setDeviceIp(ip);
            }
        } catch {
            // 配置读取失败，稍后使用默认IP或VS Code设置
        }

        // 检查 VS Code 配置是否覆盖
        const vsIp = vscode.workspace.getConfiguration('yyds').get<string>('deviceIp');
        if (vsIp && vsIp.trim().length > 0) {
            this.connector.setDeviceIp(vsIp.trim());
        }

        const vsPort = vscode.workspace.getConfiguration('yyds').get<number>('port');
        if (vsPort) {
            this.connector.setPort(vsPort);
        }
    }

    getConnector(): EngineConnector {
        return this.connector;
    }

    getEngine(): EngineImplement {
        return this.engine;
    }

    getProjectPath(): string {
        return this.projectPath;
    }

    // ================================================================
    // 项目配置
    // ================================================================

    /**
     * 加载 project.config 配置文件
     */
    loadProjectProperties(): Record<string, string> {
        const configPath = path.join(this.projectPath, 'project.config');
        if (!fs.existsSync(configPath)) {
            throw new Error('配置文件不存在: project.config');
        }

        const content = fs.readFileSync(configPath, 'utf-8');
        const props: Record<string, string> = {};

        for (const line of content.split('\n')) {
            const trimmed = line.trim();
            if (trimmed.length === 0 || trimmed.startsWith('#') || trimmed.startsWith('!')) {
                continue;
            }
            const eqIdx = trimmed.indexOf('=');
            if (eqIdx > 0) {
                const key = trimmed.substring(0, eqIdx).trim();
                const value = trimmed.substring(eqIdx + 1).trim();
                props[key] = value;
            }
        }

        return props;
    }

    getProjectProperty(key: string): string | undefined {
        try {
            return this.loadProjectProperties()[key];
        } catch {
            return undefined;
        }
    }

    // ================================================================
    // 项目操作
    // ================================================================

    /**
     * 连接设备
     */
    connect(): void {
        const autoConnect = vscode.workspace.getConfiguration('yyds').get<boolean>('autoConnect', true);
        if (autoConnect) {
            this.connector.ensureConnect();
        }
    }

    /**
     * 重新连接
     */
    reConnect(): void {
        try {
            const config = this.loadProjectProperties();
            const ip = config[PROP_KEY_DEBUG_DEVICE_IP];

            // 优先使用 VS Code 配置
            const vsIp = vscode.workspace.getConfiguration('yyds').get<string>('deviceIp');
            const finalIp = (vsIp && vsIp.trim().length > 0) ? vsIp.trim() : ip;

            if (finalIp) {
                this.connector.reConnect(finalIp);
            }
        } catch (e) {
            vscode.window.showErrorMessage(`重连失败: ${e}`);
        }
    }

    /**
     * 断开连接
     */
    disConnect(): void {
        this.connector.disconnect();
    }

    /**
     * 获取截图
     */
    async getScreenShot(): Promise<Buffer | null> {
        return this.engine.getScreenShot();
    }

    /**
     * 获取 UI Dump
     */
    async getUiDump(): Promise<string | null> {
        return this.engine.getUiDump();
    }

    /**
     * 获取前台应用
     */
    async getForeground(): Promise<string> {
        return this.engine.getForeground();
    }

    /**
     * 运行代码片段
     */
    async runCodeSnippet(code: string): Promise<void> {
        const success = await this.engine.runCodeSnippet(code);
        if (success) {
            vscode.window.showInformationMessage('代码运行完毕');
        } else {
            vscode.window.showErrorMessage('代码运行失败!');
        }
    }

    /**
     * 启动项目
     */
    async startProject(): Promise<void> {
        try {
            const config = this.loadProjectProperties();
            const projectName = config[PROP_KEY_PROJECT_NAME];
            if (!projectName) {
                vscode.window.showErrorMessage('project.config 中未设置 PROJECT_NAME');
                return;
            }
            const success = await this.engine.startProject(projectName);
            if (success) {
                vscode.window.showInformationMessage(`工程 ${projectName} 已发送启动指令`);
            }
        } catch (e) {
            vscode.window.showErrorMessage(`启动项目失败: ${e}`);
        }
    }

    /**
     * 停止项目
     */
    async stopProject(): Promise<void> {
        await this.engine.stopProject();
        vscode.window.showInformationMessage('已发送停止指令');
    }

    /**
     * 获取项目文件列表（排除隐藏文件和临时zip）
     */
    getProjectFiles(): string[] {
        const allFiles = fs.readdirSync(this.projectPath);
        return allFiles
            .filter(f => !f.startsWith('.') && !f.includes('yyp.zip'))
            .map(f => path.join(this.projectPath, f));
    }

    /**
     * 打包项目
     */
    async zipProject(): Promise<void> {
        try {
            const config = this.loadProjectProperties();
            const projectName = config[PROP_KEY_PROJECT_NAME];
            const projectVersion = config[PROP_KEY_PROJECT_VERSION] || '1.0';
            const isWithVersion = config[PROP_KEY_WITH_VERSION] === 'true';

            if (!projectName) {
                vscode.window.showErrorMessage('project.config 中未设置 PROJECT_NAME');
                return;
            }

            let zipFileName: string;
            if (isWithVersion) {
                zipFileName = `${projectName}_${projectVersion}.yyp.zip`;
            } else {
                zipFileName = `${projectName}.yyp.zip`;
            }

            const zipPath = path.join(this.projectPath, zipFileName);
            await zipFiles(this.getProjectFiles(), zipPath);
            vscode.window.showInformationMessage(`打包成功: ${zipFileName}`);
        } catch (e) {
            vscode.window.showErrorMessage(`打包失败: ${e}`);
        }
    }

    /**
     * 推送项目到设备
     */
    async sendProject(): Promise<boolean> {
        try {
            const config = this.loadProjectProperties();
            const projectName = config[PROP_KEY_PROJECT_NAME];
            if (!projectName) {
                vscode.window.showErrorMessage('project.config 中未设置 PROJECT_NAME');
                return false;
            }
            return await this.engine.sendEntireProject(
                this.projectPath,
                projectName,
                this.getProjectFiles()
            );
        } catch (e) {
            vscode.window.showErrorMessage(`推送项目失败: ${e}`);
            return false;
        }
    }

    /**
     * 点击设备屏幕
     */
    async click(x: number, y: number): Promise<boolean> {
        return this.engine.click(x, y);
    }

    /**
     * 获取连接状态描述
     */
    getConnectDescStatus(): string {
        return this.connector.getConnectDescStatus();
    }

    /**
     * 打包项目为独立APK
     */
    async buildApk(): Promise<void> {
        try {
            const config = this.loadProjectProperties();
            const projectName = config[PROP_KEY_PROJECT_NAME];
            const projectVersion = config[PROP_KEY_PROJECT_VERSION] || '1.0';

            if (!projectName) {
                vscode.window.showErrorMessage('project.config 中未设置 PROJECT_NAME');
                return;
            }

            // 弹出输入框让用户自定义应用名
            const appName = await vscode.window.showInputBox({
                prompt: '输入打包后的应用名称',
                value: projectName,
                placeHolder: '例如: 我的脚本App'
            });
            if (!appName) { return; }

            const version = await vscode.window.showInputBox({
                prompt: '输入版本号',
                value: projectVersion,
                placeHolder: '例如: 1.0'
            });
            if (!version) { return; }

            // 自定义包名（可选）
            const packageName = await vscode.window.showInputBox({
                prompt: '自定义包名（留空则使用默认 com.yyds.auto）',
                value: '',
                placeHolder: '例如: com.example.myscript'
            });
            if (packageName === undefined) { return; } // 用户按了Esc

            // 克隆已安装应用图标（可选）
            const iconChoice = await vscode.window.showQuickPick(
                ['不更换图标（使用默认）', '从设备已安装应用克隆图标'],
                { placeHolder: '选择应用图标' }
            );
            if (iconChoice === undefined) { return; }

            let cloneIconPkg: string | undefined;
            if (iconChoice === '从设备已安装应用克隆图标') {
                const apps = await this.engine.getInstalledApps();
                if (apps.length === 0) {
                    vscode.window.showWarningMessage('未获取到设备上的已安装应用');
                } else {
                    const items = apps.map(a => ({
                        label: a.appName,
                        description: a.packageName,
                        pkg: a.packageName
                    }));
                    const picked = await vscode.window.showQuickPick(items, {
                        placeHolder: '选择要克隆图标的应用'
                    });
                    if (picked) {
                        cloneIconPkg = picked.pkg;
                    }
                }
            }

            // 运行行为选项（多选）
            const behaviorItems = [
                { label: '打开应用自动运行脚本', key: 'autoRunOnOpen', picked: false },
                { label: '保持屏幕常亮', key: 'keepScreenOn', picked: true },
                { label: '显示运行日志面板', key: 'showLog', picked: true },
                { label: '脚本停止后自动退出', key: 'exitOnScriptStop', picked: false },
                { label: '🔒 加密脚本代码（AES-256）', key: 'encryptScripts', picked: false },
            ];
            const selectedBehaviors = await vscode.window.showQuickPick(
                behaviorItems,
                { placeHolder: '选择运行行为（可多选，用户可在打包应用内修改）', canPickMany: true }
            );
            if (selectedBehaviors === undefined) { return; }

            const behaviorKeys = new Set(selectedBehaviors.map(b => b.key));

            const buildConfig: PackageBuildConfig = {
                appName,
                projectName,
                version,
                packageName: packageName || undefined,
                iconPath: cloneIconPkg ? `clone:${cloneIconPkg}` : undefined,
                autoStart: behaviorKeys.has('autoRunOnOpen'),
                autoRunOnOpen: behaviorKeys.has('autoRunOnOpen'),
                keepScreenOn: behaviorKeys.has('keepScreenOn'),
                showLog: behaviorKeys.has('showLog'),
                exitOnScriptStop: behaviorKeys.has('exitOnScriptStop'),
                encryptScripts: behaviorKeys.has('encryptScripts'),
            };

            // 先推送最新项目文件到设备，确保打包的是最新代码
            const pushed = await this.sendProject();
            if (!pushed) {
                const proceed = await vscode.window.showWarningMessage(
                    '项目推送失败，设备上可能不是最新代码。是否继续打包？',
                    '继续', '取消'
                );
                if (proceed !== '继续') { return; }
            }

            vscode.window.withProgress({
                location: vscode.ProgressLocation.Notification,
                title: `正在打包APK: ${appName}`,
                cancellable: false
            }, async (progress) => {
                progress.report({ increment: 10, message: '发送打包请求...' });

                const result = await this.engine.buildApk(buildConfig);

                if (result && result.success) {
                    const sizeMb = ((result.fileSize || 0) / 1024 / 1024).toFixed(1);
                    const durationSec = ((result.durationMs || 0) / 1000).toFixed(1);

                    const action = await vscode.window.showInformationMessage(
                        `打包成功! ${sizeMb}MB, ${durationSec}秒\n${result.outputPath}`,
                        '下载到本地', '确定'
                    );

                    if (action === '下载到本地' && result.outputPath) {
                        const saveUri = await vscode.window.showSaveDialog({
                            defaultUri: vscode.Uri.file(`${appName}_v${version}.apk`),
                            filters: { 'APK': ['apk'] }
                        });
                        if (saveUri) {
                            progress.report({ increment: 50, message: '下载APK...' });
                            const ok = await this.engine.downloadApk(result.outputPath, saveUri.fsPath);
                            if (ok) {
                                vscode.window.showInformationMessage(`APK已保存: ${saveUri.fsPath}`);
                            } else {
                                vscode.window.showErrorMessage('APK下载失败');
                            }
                        }
                    }
                } else {
                    vscode.window.showErrorMessage(`打包失败: ${result?.error || '未知错误'}`);
                }
            });
        } catch (e) {
            vscode.window.showErrorMessage(`打包异常: ${e}`);
        }
    }

    /**
     * 销毁
     */
    dispose(): void {
        this.connector.disconnect();
    }
}
