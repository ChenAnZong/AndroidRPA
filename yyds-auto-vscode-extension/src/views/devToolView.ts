/**
 * 开发助手面板 — 移植自 ImageToolForm.java + ScreenPanel.java + HierarchyParser.kt
 * 使用 VS Code WebView 实现截图交互、控件树解析、区域选择等功能
 * 核心交互逻辑全部在 WebView HTML/JS 中实现（Canvas 绘图）
 */

import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import { ProjectServer } from '../engine/projectServer';
import { log, logError, showDebugChannel } from '../engine/logger';

export class DevToolPanel {
    public static readonly viewType = 'yyds.devTool';
    public static currentPanel: DevToolPanel | undefined;

    private readonly panel: vscode.WebviewPanel;
    private readonly extensionUri: vscode.Uri;
    private server: ProjectServer | undefined;
    private disposables: vscode.Disposable[] = [];
    private statusListener: (() => void) | undefined;

    public static createOrShow(extensionUri: vscode.Uri, server?: ProjectServer): DevToolPanel {
        const column = vscode.ViewColumn.Beside;

        if (DevToolPanel.currentPanel) {
            DevToolPanel.currentPanel.panel.reveal(column);
            if (server) {
                DevToolPanel.currentPanel.setServer(server);
            }
            return DevToolPanel.currentPanel;
        }

        const panel = vscode.window.createWebviewPanel(
            DevToolPanel.viewType,
            '开发助手 - Yyds.Auto',
            column,
            {
                enableScripts: true,
                retainContextWhenHidden: true,
                localResourceRoots: [extensionUri],
            }
        );

        DevToolPanel.currentPanel = new DevToolPanel(panel, extensionUri, server);
        return DevToolPanel.currentPanel;
    }

    private constructor(panel: vscode.WebviewPanel, extensionUri: vscode.Uri, server?: ProjectServer) {
        this.panel = panel;
        this.extensionUri = extensionUri;
        this.server = server;

        this.panel.webview.html = this.getHtmlContent();

        // 处理来自 WebView 的消息
        this.panel.webview.onDidReceiveMessage(
            (message) => this.handleMessage(message),
            null,
            this.disposables
        );

        this.panel.onDidDispose(() => this.dispose(), null, this.disposables);

        // 推送初始连接状态
        if (server) {
            this.setServer(server);
        } else {
            this.pushConnectionStatus();
        }
    }

    public setServer(server: ProjectServer): void {
        // 移除旧监听器
        if (this.statusListener && this.server) {
            this.server.getConnector().removeListener('statusChanged', this.statusListener);
        }
        this.server = server;
        // 注册新监听器
        this.statusListener = () => this.pushConnectionStatus();
        server.getConnector().on('statusChanged', this.statusListener);
        this.pushConnectionStatus();
    }

    private pushConnectionStatus(): void {
        if (!this.server) {
            this.panel.webview.postMessage({ command: 'connectionStatus', connected: false, ip: '' });
            return;
        }
        const c = this.server.getConnector();
        const connected = c.isApiConnected || c.isLogConnected;
        this.panel.webview.postMessage({
            command: 'connectionStatus',
            connected,
            ip: c.getDeviceIp(),
        });
    }

    private async handleMessage(message: any): Promise<void> {
        switch (message.command) {
            case 'loadScreenshot': {
                if (!this.server) {
                    this.postError('未连接设备，请先在左侧栏连接设备');
                    return;
                }
                if (!this.server.getConnector().isApiConnected) {
                    this.postError('设备 API 通道未连接，请检查设备状态');
                    return;
                }

                log('DevTool', '用户点击截图载入');
                this.panel.webview.postMessage({ command: 'setLoading', target: 'screenshot', loading: true, text: '正在获取截图...' });

                try {
                    const buf = await this.server.getScreenShot();
                    this.panel.webview.postMessage({ command: 'setLoading', target: 'screenshot', loading: false });

                    if (buf) {
                        log('DevTool', `截图成功, ${buf.length} 字节`);
                        this.panel.webview.postMessage({
                            command: 'screenshotLoaded',
                            imageBase64: buf.toString('base64'),
                        });
                    } else {
                        this.postError('截图失败 — 请打开「输出」面板查看 Yyds.Auto Debug 通道排查问题');
                    }
                } catch (e) {
                    this.panel.webview.postMessage({ command: 'setLoading', target: 'screenshot', loading: false });
                    logError('DevTool', '截图异常', e);
                    this.postError(`截图异常: ${e}`);
                }
                break;
            }
            case 'loadUiDump': {
                if (!this.server) {
                    this.postError('未连接设备，请先在左侧栏连接设备');
                    return;
                }
                if (!this.server.getConnector().isApiConnected) {
                    this.postError('设备 API 通道未连接，请检查设备状态');
                    return;
                }

                log('DevTool', '用户点击控件载入');
                this.panel.webview.postMessage({ command: 'setLoading', target: 'uidump', loading: true, text: '正在获取截图和控件树...' });

                try {
                    // 同时获取截图和控件树
                    const [screenshot, uiXml, foreground] = await Promise.all([
                        this.server.getScreenShot(),
                        this.server.getUiDump(),
                        this.server.getForeground(),
                    ]);

                    this.panel.webview.postMessage({ command: 'setLoading', target: 'uidump', loading: false });

                    if (screenshot) {
                        this.panel.webview.postMessage({
                            command: 'screenshotLoaded',
                            imageBase64: screenshot.toString('base64'),
                        });
                    }

                    if (uiXml) {
                        this.panel.webview.postMessage({
                            command: 'uiDumpLoaded',
                            xmlContent: uiXml,
                            foreground: foreground || '',
                        });
                    } else {
                        this.postError('获取控件信息失败 — 请打开「输出」面板查看 Yyds.Auto Debug 日志');
                    }
                } catch (e) {
                    this.panel.webview.postMessage({ command: 'setLoading', target: 'uidump', loading: false });
                    logError('DevTool', '控件载入异常', e);
                    this.postError(`控件载入异常: ${e}`);
                }
                break;
            }
            case 'click': {
                if (!this.server) { return; }
                if (!this.server.getConnector().isApiConnected) { return; }
                log('DevTool', `点击 (${message.x}, ${message.y})`);
                this.panel.webview.postMessage({ command: 'setLoading', target: 'screenshot', loading: true, text: '正在执行点击...' });
                try {
                    const success = await this.server.click(message.x, message.y);
                    if (success) {
                        // 点击后同时刷新截图和控件树
                        const [screenshot, uiXml, foreground] = await Promise.all([
                            this.server.getScreenShot(),
                            this.server.getUiDump(),
                            this.server.getForeground(),
                        ]);
                        this.panel.webview.postMessage({ command: 'setLoading', target: 'screenshot', loading: false });
                        if (screenshot) {
                            this.panel.webview.postMessage({
                                command: 'screenshotLoaded',
                                imageBase64: screenshot.toString('base64'),
                            });
                        }
                        if (uiXml) {
                            this.panel.webview.postMessage({
                                command: 'uiDumpLoaded',
                                xmlContent: uiXml,
                                foreground: foreground || '',
                            });
                        }
                    } else {
                        this.panel.webview.postMessage({ command: 'setLoading', target: 'screenshot', loading: false });
                    }
                } catch (e) {
                    this.panel.webview.postMessage({ command: 'setLoading', target: 'screenshot', loading: false });
                    logError('DevTool', '点击操作异常', e);
                }
                break;
            }
            case 'showDebugLog': {
                showDebugChannel();
                break;
            }
            case 'saveArea': {
                if (!message.imageBase64) {
                    this.postError('没有裁剪数据，请先在截图上拖拽框选区域');
                    return;
                }

                // 确定保存根目录: 优先项目目录, 回退到工作区
                let basePath: string | undefined;
                if (this.server) {
                    basePath = this.server.getProjectPath();
                } else {
                    const folders = vscode.workspace.workspaceFolders;
                    if (folders && folders.length > 0) {
                        basePath = folders[0].uri.fsPath;
                    }
                }
                if (!basePath) {
                    this.postError('无法确定保存目录 — 请先打开一个项目文件夹');
                    return;
                }

                let fileName = message.fileName || '1';
                let outFilePath: string;
                if (fileName.includes('/') || fileName.includes('\\')) {
                    outFilePath = path.join(basePath, `${fileName}.jpg`);
                } else {
                    outFilePath = path.join(basePath, 'img', `${fileName}.jpg`);
                }

                try {
                    const dir = path.dirname(outFilePath);
                    if (!fs.existsSync(dir)) {
                        fs.mkdirSync(dir, { recursive: true });
                    }
                    const buf = Buffer.from(message.imageBase64, 'base64');
                    fs.writeFileSync(outFilePath, buf);
                    log('DevTool', `裁剪保存: ${outFilePath} (${buf.length} bytes)`);

                    this.panel.webview.postMessage({
                        command: 'info',
                        text: `已保存: ${path.relative(basePath, outFilePath)} (${Math.round(buf.length / 1024)}KB)`,
                    });
                } catch (e) {
                    logError('DevTool', '保存裁剪失败', e);
                    this.postError(`保存失败: ${e}`);
                }
                break;
            }
        }
    }

    private postError(text: string): void {
        this.panel.webview.postMessage({ command: 'error', text });
    }

    dispose(): void {
        DevToolPanel.currentPanel = undefined;
        this.panel.dispose();
        while (this.disposables.length) {
            const d = this.disposables.pop();
            if (d) { d.dispose(); }
        }
    }

    private getHtmlContent(): string {
        return /* html */ `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    :root {
        --radius-sm: 4px;
        --radius-md: 6px;
        --radius-lg: 8px;
        --shadow-sm: 0 1px 3px rgba(0,0,0,0.18);
        --shadow-md: 0 3px 10px rgba(0,0,0,0.22);
        --accent: #3d9cf5;
        --accent-hover: #5bb0ff;
        --accent-muted: rgba(61,156,245,0.12);
        --success: #3ddc84;
        --danger: #f55;
        --warning: #ffb74d;
    }
    body {
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif;
        font-size: 13px;
        background: var(--vscode-editor-background);
        color: var(--vscode-foreground);
        overflow: hidden;
        height: 100vh;
        display: flex;
        flex-direction: column;
    }

    /* ===== 顶部工具栏 ===== */
    .toolbar {
        display: flex;
        align-items: center;
        gap: 4px;
        padding: 4px 8px;
        background: var(--vscode-titleBar-activeBackground);
        border-bottom: 1px solid var(--vscode-panel-border);
        flex-shrink: 0;
        min-height: 36px;
        overflow: hidden;
    }
    .toolbar-group {
        display: flex;
        align-items: center;
        gap: 4px;
    }
    .toolbar-sep {
        width: 1px;
        height: 20px;
        background: var(--vscode-panel-border);
        margin: 0 8px;
        opacity: 0.6;
    }
    .tb-btn {
        display: inline-flex;
        align-items: center;
        gap: 5px;
        background: transparent;
        color: var(--vscode-foreground);
        border: 1px solid transparent;
        padding: 4px 10px;
        cursor: pointer;
        border-radius: var(--radius-md);
        font-size: 12px;
        font-weight: 500;
        white-space: nowrap;
        transition: all 0.18s ease;
        letter-spacing: 0.2px;
        flex-shrink: 0;
    }
    .tb-btn svg { width: 14px; height: 14px; flex-shrink: 0; }
    .tb-btn:hover {
        background: var(--vscode-toolbar-hoverBackground, rgba(255,255,255,0.06));
        border-color: rgba(255,255,255,0.06);
    }
    .tb-btn:active { transform: scale(0.97); }
    .tb-btn.primary {
        background: var(--accent);
        color: #fff;
        border-color: transparent;
        box-shadow: 0 1px 4px rgba(61,156,245,0.3);
    }
    .tb-btn.primary:hover { background: var(--accent-hover); box-shadow: 0 2px 8px rgba(61,156,245,0.35); }
    .tb-btn.primary svg { filter: brightness(10); }
    .conn-indicator {
        display: flex;
        align-items: center;
        gap: 5px;
        margin-left: auto;
        font-size: 11px;
        color: var(--vscode-descriptionForeground);
        padding: 3px 8px;
        background: rgba(255,255,255,0.03);
        border-radius: var(--radius-md);
        border: 1px solid rgba(255,255,255,0.04);
        white-space: nowrap;
        flex-shrink: 1;
        min-width: 0;
        overflow: hidden;
    }
    .conn-dot {
        width: 8px; height: 8px;
        border-radius: 50%;
        background: var(--danger);
        flex-shrink: 0;
        transition: background 0.3s, box-shadow 0.3s;
    }
    .conn-dot.connected {
        background: var(--success);
        box-shadow: 0 0 6px rgba(61,220,132,0.45);
    }
    .conn-link {
        color: var(--vscode-textLink-foreground);
        cursor: pointer;
        font-size: 11px;
        margin-left: 2px;
        text-decoration: none;
        opacity: 0.75;
        transition: opacity 0.15s;
    }
    .conn-link:hover { opacity: 1; text-decoration: underline; }

    /* ===== 主布局 ===== */
    .main-layout {
        display: flex;
        flex: 1;
        overflow: hidden;
    }
    .main-layout.vertical {
        flex-direction: column;
    }

    /* ===== 左侧：截图面板 ===== */
    .left-panel {
        display: flex;
        flex-direction: column;
        min-width: 200px;
        flex: 0 0 auto;
        border-right: 1px solid var(--vscode-panel-border);
        max-width: 100%;
        transition: width 0.05s ease;
    }
    .main-layout.vertical .left-panel {
        flex: 0 0 auto;
        border-right: none;
        border-bottom: 1px solid var(--vscode-panel-border);
        width: 100% !important;
    }
    .canvas-container {
        flex: 1;
        overflow: hidden;
        position: relative;
        background: #0d0d14;
        background-image:
            linear-gradient(rgba(255,255,255,0.02) 1px, transparent 1px),
            linear-gradient(90deg, rgba(255,255,255,0.02) 1px, transparent 1px);
        background-size: 20px 20px;
        cursor: crosshair;
    }
    #screenshotCanvas { display: block; }

    .placeholder-box {
        position: absolute;
        top: 50%; left: 50%;
        transform: translate(-50%, -50%);
        text-align: center;
        color: var(--vscode-descriptionForeground);
        pointer-events: none;
        padding: 32px;
    }
    .placeholder-box .ph-icon {
        width: 56px; height: 56px;
        margin: 0 auto 16px;
        opacity: 0.12;
    }
    .placeholder-box .ph-title {
        font-size: 14px;
        font-weight: 500;
        margin-bottom: 8px;
        opacity: 0.55;
    }
    .placeholder-box .ph-hint {
        font-size: 12px;
        opacity: 0.3;
        line-height: 1.5;
    }

    /* 坐标信息条 */
    .coord-bar {
        display: flex;
        align-items: center;
        padding: 0 8px;
        font-size: 11px;
        font-family: 'JetBrains Mono', 'Cascadia Code', 'Consolas', 'Courier New', monospace;
        background: var(--vscode-titleBar-activeBackground);
        border-top: 1px solid var(--vscode-panel-border);
        color: var(--vscode-descriptionForeground);
        height: 22px;
        flex-shrink: 0;
        gap: 12px;
        overflow: hidden;
        white-space: nowrap;
    }
    .coord-bar .coord-item {
        display: inline-flex;
        align-items: center;
        gap: 5px;
    }
    .coord-bar .coord-label {
        opacity: 0.4;
        font-size: 10px;
        text-transform: uppercase;
        letter-spacing: 0.5px;
    }
    .coord-bar .coord-item span:last-child {
        color: var(--vscode-foreground);
        opacity: 0.85;
    }

    /* 裁剪预览底栏 */
    .crop-bar {
        display: flex;
        align-items: center;
        gap: 6px;
        padding: 4px 8px;
        background: var(--vscode-sideBar-background);
        border-top: 1px solid var(--vscode-panel-border);
        height: 38px;
        flex-shrink: 0;
        overflow: hidden;
    }
    .crop-preview {
        width: 28px; height: 28px;
        border: 1px solid rgba(255,255,255,0.08);
        border-radius: var(--radius-sm);
        background: #0d0d14;
        flex-shrink: 0;
    }
    .crop-info {
        font-size: 10px;
        color: var(--vscode-descriptionForeground);
        font-family: 'JetBrains Mono', 'Consolas', monospace;
        white-space: nowrap;
        opacity: 0.7;
        overflow: hidden;
        text-overflow: ellipsis;
        min-width: 0;
        flex: 1;
    }
    .crop-bar .save-input {
        background: var(--vscode-input-background);
        color: var(--vscode-input-foreground);
        border: 1px solid var(--vscode-input-border);
        padding: 3px 6px;
        font-size: 12px;
        border-radius: var(--radius-sm);
        width: 60px;
        min-width: 40px;
        flex-shrink: 1;
        transition: border-color 0.15s;
    }
    .crop-bar .save-input:focus {
        outline: none;
        border-color: var(--accent);
    }
    .crop-bar .save-btn {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        background: var(--vscode-button-secondaryBackground);
        color: var(--vscode-button-secondaryForeground);
        border: none;
        padding: 4px 10px;
        border-radius: var(--radius-sm);
        font-size: 11px;
        font-weight: 500;
        cursor: pointer;
        transition: all 0.15s;
        white-space: nowrap;
        flex-shrink: 0;
    }
    .crop-bar .save-btn:hover {
        background: var(--vscode-button-secondaryHoverBackground);
        box-shadow: var(--shadow-sm);
    }
    .crop-bar .save-btn svg { width: 12px; height: 12px; flex-shrink: 0; }

    /* ===== 右侧面板 ===== */
    .right-panel {
        flex: 1;
        display: flex;
        flex-direction: column;
        min-width: 0;
        overflow: hidden;
    }
    .main-layout.vertical .right-panel {
        flex: 1;
        min-height: 120px;
    }

    /* Tab 头部 */
    .tab-header {
        display: flex;
        align-items: center;
        gap: 0;
        background: var(--vscode-titleBar-activeBackground);
        border-bottom: 1px solid var(--vscode-panel-border);
        flex-shrink: 0;
        padding: 0 8px;
    }
    .tab-btn {
        padding: 8px 16px;
        font-size: 12px;
        font-weight: 500;
        background: none;
        color: var(--vscode-descriptionForeground);
        border: none;
        border-bottom: 2px solid transparent;
        cursor: pointer;
        transition: all 0.18s;
        letter-spacing: 0.2px;
        display: inline-flex;
        align-items: center;
        gap: 6px;
    }
    .tab-btn svg { width: 14px; height: 14px; opacity: 0.6; }
    .tab-btn:hover {
        color: var(--vscode-foreground);
        background: rgba(255,255,255,0.03);
    }
    .tab-btn.active {
        color: var(--vscode-foreground);
        border-bottom-color: var(--accent);
    }
    .tab-btn.active svg { opacity: 1; }
    .tab-content { display: none; flex: 1; overflow: hidden; flex-direction: column; }
    .tab-content.active { display: flex; }

    /* 搜索栏 */
    .search-bar {
        padding: 8px 10px;
        border-bottom: 1px solid var(--vscode-panel-border);
        flex-shrink: 0;
    }
    .search-input {
        width: 100%;
        background: var(--vscode-input-background);
        color: var(--vscode-input-foreground);
        border: 1px solid var(--vscode-input-border);
        padding: 6px 10px 6px 30px;
        font-size: 12px;
        border-radius: var(--radius-md);
        transition: border-color 0.15s, box-shadow 0.15s;
        background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='%23888' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Ccircle cx='11' cy='11' r='8'/%3E%3Cline x1='21' y1='21' x2='16.65' y2='16.65'/%3E%3C/svg%3E");
        background-repeat: no-repeat;
        background-position: 8px center;
    }
    .search-input:focus {
        outline: none;
        border-color: var(--accent);
        box-shadow: 0 0 0 1px var(--accent-muted);
    }
    .search-input::placeholder { opacity: 0.4; }

    /* 前台应用信息栏 */
    .fg-bar {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 6px 10px;
        background: rgba(255,255,255,0.03);
        border-bottom: 1px solid rgba(255,255,255,0.05);
        flex-shrink: 0;
        font-size: 11px;
        min-height: 28px;
    }
    .fg-bar svg { width: 14px; height: 14px; opacity: 0.45; flex-shrink: 0; }
    .fg-bar .fg-pkg {
        color: var(--accent);
        font-weight: 600;
        font-family: 'JetBrains Mono', 'Consolas', monospace;
        font-size: 11px;
    }
    .fg-bar .fg-act {
        color: var(--vscode-descriptionForeground);
        font-family: 'JetBrains Mono', 'Consolas', monospace;
        font-size: 11px;
        opacity: 0.7;
    }
    .fg-bar .fg-label {
        color: var(--vscode-descriptionForeground);
        opacity: 0.5;
        font-size: 10px;
        margin-right: 3px;
    }
    .fg-bar .fg-pid {
        color: #e2c87a;
        font-family: 'JetBrains Mono', 'Consolas', monospace;
        font-size: 11px;
    }
    .fg-bar .fg-copy {
        cursor: pointer;
        border-radius: 2px;
        padding: 0 2px;
        transition: background 0.15s;
    }
    .fg-bar .fg-copy:hover {
        background: rgba(255,255,255,0.08);
        text-decoration: underline;
    }
    .fg-bar .fg-sep {
        opacity: 0.25;
        margin: 0 4px;
    }

    /* 控件树 */
    .tree-container {
        flex: 1;
        overflow: auto;
        padding: 4px 0;
    }
    .tree-container::-webkit-scrollbar { width: 6px; }
    .tree-container::-webkit-scrollbar-thumb {
        background: rgba(255,255,255,0.1);
        border-radius: 3px;
    }
    .tree-container::-webkit-scrollbar-thumb:hover { background: rgba(255,255,255,0.18); }
    .tree-node {
        padding: 3px 6px 3px 16px;
        cursor: pointer;
        user-select: none;
        white-space: nowrap;
        line-height: 22px;
        border-radius: 3px;
        margin: 0 4px;
        transition: background 0.1s;
    }
    .tree-node:hover { background: var(--vscode-list-hoverBackground); }
    .tree-node.selected {
        background: var(--vscode-list-activeSelectionBackground);
        color: var(--vscode-list-activeSelectionForeground);
    }
    .tree-node .toggle {
        display: inline-block;
        width: 16px;
        text-align: center;
        cursor: pointer;
        opacity: 0.5;
        font-size: 10px;
        transition: opacity 0.15s;
    }
    .tree-node:hover .toggle { opacity: 0.8; }
    .tree-node .label { font-size: 12px; }
    .tree-node .clickable { color: #56d6a0; }
    .tree-node .scrollable { color: #e2c87a; }
    .tree-node .text-node { color: #d4956a; }
    .tree-node .layout-node { color: #7ec8e3; }
    .tree-node .badge {
        display: inline-block;
        font-size: 9px;
        font-weight: 600;
        padding: 1px 5px;
        border-radius: 10px;
        margin-left: 6px;
        vertical-align: middle;
        letter-spacing: 0.3px;
    }
    .tree-node .badge-click { background: rgba(86,214,160,0.12); color: #56d6a0; }
    .tree-node .badge-scroll { background: rgba(226,200,122,0.12); color: #e2c87a; }
    .tree-node .badge-text { background: rgba(212,149,106,0.12); color: #d4956a; }
    .tree-node .node-icon {
        display: inline-block;
        font-size: 9px;
        font-weight: 600;
        font-family: 'JetBrains Mono', 'Consolas', monospace;
        background: rgba(255,255,255,0.06);
        border: 1px solid rgba(255,255,255,0.06);
        color: var(--vscode-descriptionForeground);
        padding: 0 3px;
        border-radius: 3px;
        margin-right: 4px;
        vertical-align: middle;
        opacity: 0.6;
        letter-spacing: 0.3px;
    }
    .tree-node[title] { position: relative; }

    /* 节点数量徽章 */
    .tab-btn .node-count {
        font-size: 9px;
        background: rgba(61,156,245,0.15);
        color: var(--accent);
        padding: 0 5px;
        border-radius: 8px;
        margin-left: 4px;
        font-weight: 600;
    }

    /* 操作提示条 */
    .hint-bar {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 12px;
        padding: 3px 8px;
        background: rgba(61,156,245,0.04);
        border-top: 1px solid rgba(61,156,245,0.08);
        flex-shrink: 0;
        height: 22px;
        overflow: hidden;
        white-space: nowrap;
    }
    .hint-bar .hint-item {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        font-size: 10px;
        color: var(--vscode-descriptionForeground);
        opacity: 0.6;
    }
    .hint-bar .hint-key {
        background: rgba(255,255,255,0.08);
        border: 1px solid rgba(255,255,255,0.06);
        border-radius: 3px;
        padding: 0 4px;
        font-size: 9px;
        font-family: 'JetBrains Mono', 'Consolas', monospace;
        color: var(--vscode-foreground);
        opacity: 0.8;
    }

    /* 快捷操作栏 */
    .quick-actions {
        display: flex;
        align-items: center;
        gap: 4px;
        padding: 6px 10px;
        background: rgba(61,156,245,0.04);
        border-bottom: 1px solid rgba(61,156,245,0.08);
        flex-shrink: 0;
        flex-wrap: nowrap;
        overflow: hidden;
    }
    .quick-actions .qa-label {
        font-size: 10px;
        color: var(--vscode-descriptionForeground);
        opacity: 0.5;
        margin-right: 4px;
        white-space: nowrap;
    }
    .qa-btn {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        background: rgba(255,255,255,0.04);
        border: 1px solid rgba(255,255,255,0.06);
        color: var(--vscode-foreground);
        font-size: 10px;
        padding: 3px 8px;
        border-radius: var(--radius-sm);
        cursor: pointer;
        white-space: nowrap;
        transition: all 0.15s;
        flex-shrink: 0;
    }
    .qa-btn:hover { background: rgba(255,255,255,0.1); border-color: rgba(255,255,255,0.12); }
    .qa-btn:active { transform: scale(0.96); }
    .qa-btn svg { width: 11px; height: 11px; flex-shrink: 0; }
    .qa-btn.primary { background: rgba(61,156,245,0.12); border-color: rgba(61,156,245,0.2); color: var(--accent); }
    .qa-btn.primary:hover { background: rgba(61,156,245,0.2); }

    /* ===== 属性面板 ===== */
    .props-container {
        flex: 1;
        overflow: auto;
        padding: 10px 12px;
    }
    .props-container::-webkit-scrollbar { width: 6px; }
    .props-container::-webkit-scrollbar-thumb {
        background: rgba(255,255,255,0.1);
        border-radius: 3px;
    }
    .props-empty {
        text-align: center;
        padding: 48px 20px;
        color: var(--vscode-descriptionForeground);
        opacity: 0.45;
        font-size: 13px;
        line-height: 1.6;
    }
    .prop-section {
        margin-bottom: 16px;
    }
    .prop-section-title {
        font-size: 10px;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.8px;
        color: var(--accent);
        opacity: 0.7;
        margin-bottom: 6px;
        padding-bottom: 4px;
        border-bottom: 1px solid rgba(255,255,255,0.05);
    }
    .prop-row {
        display: flex;
        align-items: flex-start;
        padding: 4px 6px;
        font-size: 12px;
        gap: 10px;
        border-radius: var(--radius-sm);
        transition: background 0.1s;
    }
    .prop-row:hover { background: rgba(255,255,255,0.03); }
    .prop-key {
        min-width: 80px;
        color: var(--vscode-descriptionForeground);
        flex-shrink: 0;
        font-size: 11px;
        opacity: 0.65;
        padding-top: 1px;
    }
    .prop-val {
        flex: 1;
        word-break: break-all;
        font-family: 'JetBrains Mono', 'Cascadia Code', 'Consolas', 'Courier New', monospace;
        font-size: 11.5px;
        line-height: 1.4;
    }
    .prop-val.empty { opacity: 0.25; font-style: italic; }
    .prop-copy-btn {
        background: none;
        border: none;
        color: var(--vscode-descriptionForeground);
        cursor: pointer;
        padding: 2px 4px;
        opacity: 0;
        transition: opacity 0.15s;
        font-size: 12px;
        flex-shrink: 0;
        border-radius: 3px;
    }
    .prop-row:hover .prop-copy-btn { opacity: 0.5; }
    .prop-copy-btn:hover { opacity: 1 !important; color: var(--vscode-foreground); background: rgba(255,255,255,0.06); }

    /* 代码片段框 */
    .code-snippet-box {
        margin-top: 10px;
        padding: 10px 12px;
        background: rgba(0,0,0,0.2);
        border-radius: var(--radius-md);
        font-family: 'JetBrains Mono', 'Cascadia Code', 'Consolas', 'Courier New', monospace;
        font-size: 12px;
        position: relative;
        border: 1px solid rgba(255,255,255,0.06);
        line-height: 1.5;
    }
    .code-snippet-box pre {
        white-space: pre-wrap;
        word-break: break-all;
        margin: 0;
    }
    .code-snippet-box .copy-code-btn {
        position: absolute;
        top: 6px; right: 6px;
        background: var(--vscode-button-secondaryBackground);
        color: var(--vscode-button-secondaryForeground);
        border: none;
        padding: 3px 10px;
        border-radius: var(--radius-sm);
        font-size: 11px;
        cursor: pointer;
        opacity: 0;
        transition: opacity 0.15s;
    }
    .code-snippet-box:hover .copy-code-btn { opacity: 1; }

    /* ===== 坐标过滤面板 (嵌入树Tab) ===== */
    .filter-panel {
        display: none;
        flex-direction: column;
        position: absolute;
        inset: 0;
        z-index: 5;
        background: var(--vscode-editor-background);
    }
    .filter-panel.active { display: flex; }
    .filter-header {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 6px 10px;
        background: rgba(61,156,245,0.06);
        border-bottom: 1px solid rgba(61,156,245,0.12);
        flex-shrink: 0;
    }
    .filter-header svg { width: 14px; height: 14px; color: var(--accent); flex-shrink: 0; opacity: 0.7; }
    .filter-header-text {
        flex: 1;
        font-size: 11px;
        color: var(--vscode-foreground);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }
    .filter-header-text strong { color: var(--accent); font-weight: 600; }
    .filter-clear-btn {
        background: none;
        border: 1px solid rgba(255,255,255,0.1);
        color: var(--vscode-descriptionForeground);
        font-size: 10px;
        padding: 2px 8px;
        border-radius: var(--radius-sm);
        cursor: pointer;
        white-space: nowrap;
        flex-shrink: 0;
        transition: all 0.15s;
    }
    .filter-clear-btn:hover { background: rgba(255,255,255,0.06); color: var(--vscode-foreground); }
    .filter-list {
        flex: 1;
        overflow-y: auto;
        padding: 4px 0;
    }
    .filter-list::-webkit-scrollbar { width: 5px; }
    .filter-list::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.1); border-radius: 3px; }
    .filter-item {
        display: flex;
        align-items: stretch;
        gap: 0;
        margin: 2px 6px;
        border-radius: var(--radius-sm);
        cursor: pointer;
        transition: background 0.1s;
        overflow: hidden;
        border: 1px solid transparent;
    }
    .filter-item:hover {
        background: var(--vscode-list-hoverBackground);
        border-color: rgba(255,255,255,0.05);
    }
    .filter-item.selected {
        background: var(--vscode-list-activeSelectionBackground);
        border-color: rgba(61,156,245,0.2);
    }
    .filter-item-color {
        width: 4px;
        flex-shrink: 0;
        border-radius: 2px 0 0 2px;
    }
    .filter-item-body {
        flex: 1;
        min-width: 0;
        padding: 6px 8px;
        display: flex;
        flex-direction: column;
        gap: 3px;
    }
    .filter-item-row1 {
        display: flex;
        align-items: center;
        gap: 6px;
    }
    .filter-item-cls {
        font-family: 'JetBrains Mono', 'Consolas', monospace;
        font-size: 12px;
        font-weight: 600;
        color: var(--vscode-foreground);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }
    .filter-item-id {
        font-family: 'JetBrains Mono', 'Consolas', monospace;
        font-size: 11px;
        color: #56d6a0;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }
    .filter-item-row2 {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 10px;
        color: var(--vscode-descriptionForeground);
        opacity: 0.7;
    }
    .filter-item-text {
        font-size: 11px;
        color: #e2c87a;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        max-width: 200px;
    }
    .filter-item-meta {
        font-family: 'JetBrains Mono', 'Consolas', monospace;
        font-size: 10px;
        white-space: nowrap;
    }
    .filter-item-badges {
        display: flex;
        gap: 3px;
        flex-shrink: 0;
        margin-left: auto;
    }
    .filter-item-badges .badge { font-size: 9px; padding: 0 4px; }

    /* ===== Loading ===== */
    .loading-overlay {
        position: absolute;
        top: 0; left: 0; right: 0; bottom: 0;
        background: rgba(0,0,0,0.6);
        backdrop-filter: blur(4px);
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        color: #fff;
        font-size: 13px;
        z-index: 100;
        gap: 12px;
    }
    .loading-overlay.hidden { display: none; }
    .spinner {
        width: 28px; height: 28px;
        border: 2.5px solid rgba(255,255,255,0.1);
        border-top-color: var(--accent);
        border-radius: 50%;
        animation: spin 0.8s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }

    /* ===== Toast 通知 ===== */
    .toast-container {
        position: fixed;
        bottom: 16px;
        right: 16px;
        z-index: 200;
        display: flex;
        flex-direction: column-reverse;
        gap: 8px;
        pointer-events: none;
    }
    .toast {
        padding: 10px 16px;
        border-radius: var(--radius-md);
        font-size: 12px;
        background: var(--vscode-notifications-background, #252526);
        color: var(--vscode-notifications-foreground, #ccc);
        border: 1px solid var(--vscode-notifications-border, #333);
        box-shadow: var(--shadow-md);
        animation: toastIn 0.3s cubic-bezier(0.22, 1, 0.36, 1);
        pointer-events: auto;
        max-width: 340px;
        line-height: 1.4;
    }
    .toast.success { border-left: 3px solid var(--success); }
    .toast.error { border-left: 3px solid var(--danger); }
    .toast.info { border-left: 3px solid var(--accent); }
    @keyframes toastIn { from { opacity: 0; transform: translateY(10px) scale(0.97); } to { opacity: 1; transform: translateY(0) scale(1); } }
</style>
</head>
<body>
<!-- ===== 顶部工具栏 ===== -->
<div class="toolbar">
    <div class="toolbar-group">
        <button class="tb-btn primary" id="btnLoadUiDump" title="同时获取截图和控件树">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="2" width="14" height="20" rx="2" ry="2"/><line x1="12" y1="18" x2="12" y2="18"/></svg>
            控件抓取
        </button>
        <button class="tb-btn" id="btnLoadScreen" title="仅获取截图">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg>
            截图
        </button>
    </div>
    <div class="toolbar-sep"></div>
    <div class="toolbar-group">
        <button class="tb-btn" id="btnExpandAll" title="展开所有控件节点">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
            展开
        </button>
        <button class="tb-btn" id="btnCollapseAll" title="折叠所有控件节点">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="18 15 12 9 6 15"/></svg>
            折叠
        </button>
    </div>
    <div class="conn-indicator">
        <div class="conn-dot" id="connDot"></div>
        <span id="connText">未连接</span>
        <span class="conn-link" id="debugLink">日志</span>
    </div>
</div>

<!-- ===== 主区域 ===== -->
<div class="main-layout">
    <!-- 左侧: 截图 -->
    <div class="left-panel" id="leftPanel">
        <div class="canvas-container" id="canvasContainer">
            <canvas id="screenshotCanvas"></canvas>
            <div class="placeholder-box" id="placeholder">
                <svg class="ph-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="2" width="14" height="20" rx="2" ry="2"/><line x1="12" y1="18" x2="12" y2="18"/></svg>
                <div class="ph-title">点击「控件抓取」开始</div>
                <div class="ph-hint">同时获取截图和控件树信息</div>
            </div>
            <div class="loading-overlay hidden" id="loadingOverlay">
                <div class="spinner"></div>
                <span id="loadingText">加载中...</span>
            </div>
        </div>
        <div class="coord-bar" id="coordBar">
            <span class="coord-item"><span class="coord-label">坐标</span> <span id="coordXY">-</span></span>
            <span class="coord-item"><span class="coord-label">分辨率</span> <span id="coordRes">-</span></span>
            <span class="coord-item" id="coordArea" style="display:none"><span class="coord-label">选区</span> <span id="coordAreaVal">-</span></span>
        </div>
        <div class="crop-bar" id="cropBar">
            <canvas id="previewCanvas" class="crop-preview" width="28" height="28"></canvas>
            <span class="crop-info" id="cropInfo"></span>
            <input type="text" id="saveNameInput" class="save-input" placeholder="文件名" value="1">
            <button class="save-btn" id="btnSaveArea">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg>
                保存裁剪
            </button>
        </div>
        <div class="hint-bar">
            <span class="hint-item"><span class="hint-key">单击</span> 选中高亮</span>
            <span class="hint-item"><span class="hint-key">双击</span> 执行点击</span>
            <span class="hint-item"><span class="hint-key">拖拽</span> 框选裁剪</span>
            <span class="hint-item"><span class="hint-key">右键</span> 查看属性</span>
        </div>
    </div>

    <!-- 右侧: Tab面板 -->
    <div class="right-panel" id="rightPanel">
        <div class="tab-header">
            <button class="tab-btn active" data-tab="tree">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v6"/><circle cx="12" cy="12" r="3"/><path d="M6 21v-3a3 3 0 0 1 3-3h0"/><path d="M18 21v-3a3 3 0 0 0-3-3h0"/><circle cx="6" cy="21" r="1"/><circle cx="18" cy="21" r="1"/></svg>
                控件树 <span class="node-count" id="nodeCountBadge" style="display:none"></span>
            </button>
            <button class="tab-btn" data-tab="props">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
                属性
            </button>
        </div>

        <!-- Tab: 控件树 -->
        <div class="tab-content active" id="tabTree" data-tab="tree" style="position:relative">
            <div class="search-bar">
                <input type="text" id="searchInput" class="search-input" placeholder="搜索控件 (id / text / class)... 回车确认">
            </div>
            <div class="fg-bar" id="fgBar" style="display:none">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="2" width="14" height="20" rx="2" ry="2"/><line x1="12" y1="18" x2="12" y2="18"/></svg>
                <span id="fgContent"></span>
            </div>
            <div class="tree-container" id="treeContainer">
                <div class="props-empty">加载控件树后此处显示节点层级</div>
            </div>
            <!-- 坐标过滤面板 (覆盖在树上方) -->
            <div class="filter-panel" id="filterPanel">
                <div class="filter-header">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
                    <span class="filter-header-text" id="filterHeaderText"></span>
                    <button class="filter-clear-btn" id="filterClearBtn">✕ 返回树</button>
                </div>
                <div class="filter-list" id="filterList"></div>
            </div>
        </div>

        <!-- Tab: 属性 -->
        <div class="tab-content" id="tabProps" data-tab="props">
            <div class="quick-actions" id="quickActions" style="display:none">
                <span class="qa-label">快捷</span>
                <button class="qa-btn primary" id="qaClick" title="点击该控件中心坐标">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 15l-2 5L9 9l11 4-5 2z"/><path d="M18.5 18.5L22 22"/></svg>
                    点击
                </button>
                <button class="qa-btn" id="qaCopyId" title="复制 resource-id">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                    复制ID
                </button>
                <button class="qa-btn" id="qaCopyText" title="复制文本内容">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                    复制文本
                </button>
                <button class="qa-btn" id="qaCopyXPath" title="复制控件 XPath">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
                    XPath
                </button>
                <button class="qa-btn" id="qaLocate" title="在控件树中定位此节点">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
                    定位
                </button>
            </div>
            <div class="props-container" id="propsContainer">
                <div class="props-empty">选中控件后此处显示属性详情</div>
            </div>
        </div>
    </div>
</div>

<!-- Toast 通知 -->
<div class="toast-container" id="toastContainer"></div>

<script>
(function() {
    const vscode = acquireVsCodeApi();

    // ========================================
    // 状态
    // ========================================
    let currentImage = null;
    let imageWidth = 0, imageHeight = 0;
    let scaleRatio = 1;
    let canvasW = 0, canvasH = 0;

    let isDragging = false;
    let dragStartX = 0, dragStartY = 0;
    let dragEndX = 0, dragEndY = 0;
    let dragCount = 0;
    let lastClickTime = 0;

    let uiNodes = [];
    let treeRoots = [];
    let selectedNode = null;
    let selectedNodeColor = '#3de495';
    let currentForeground = '';

    // ========================================
    // DOM 引用
    // ========================================
    const canvas = document.getElementById('screenshotCanvas');
    const ctx = canvas.getContext('2d');
    const canvasContainer = document.getElementById('canvasContainer');
    const placeholder = document.getElementById('placeholder');
    const loadingOverlay = document.getElementById('loadingOverlay');
    const coordXY = document.getElementById('coordXY');
    const coordRes = document.getElementById('coordRes');
    const coordArea = document.getElementById('coordArea');
    const coordAreaVal = document.getElementById('coordAreaVal');
    const previewCanvas = document.getElementById('previewCanvas');
    const previewCtx = previewCanvas.getContext('2d');
    const cropInfo = document.getElementById('cropInfo');
    const treeContainer = document.getElementById('treeContainer');
    const propsContainer = document.getElementById('propsContainer');
    const searchInput = document.getElementById('searchInput');
    const toastContainer = document.getElementById('toastContainer');

    // ========================================
    // Toast 通知
    // ========================================
    function showToast(text, type) {
        type = type || 'info';
        const el = document.createElement('div');
        el.className = 'toast ' + type;
        el.textContent = text;
        toastContainer.appendChild(el);
        setTimeout(() => { el.style.opacity = '0'; el.style.transition = 'opacity 0.3s'; }, 2500);
        setTimeout(() => { el.remove(); }, 2800);
    }

    // ========================================
    // Tab 切换
    // ========================================
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const tab = btn.dataset.tab;
            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
            document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
            btn.classList.add('active');
            document.querySelector('.tab-content[data-tab="' + tab + '"]').classList.add('active');
        });
    });

    function switchToTab(tab) {
        document.querySelectorAll('.tab-btn').forEach(b => {
            b.classList.toggle('active', b.dataset.tab === tab);
        });
        document.querySelectorAll('.tab-content').forEach(c => {
            c.classList.toggle('active', c.dataset.tab === tab);
        });
    }

    // ========================================
    // 剪贴板
    // ========================================
    function copyText(text) {
        navigator.clipboard.writeText(text).then(() => {
            showToast('已复制: ' + (text.length > 40 ? text.substring(0, 40) + '...' : text), 'success');
        }).catch(() => {
            showToast('复制失败', 'error');
        });
    }

    // ========================================
    // 截图 Canvas 渲染
    // ========================================
    const mainLayout = document.querySelector('.main-layout');
    const leftPanel = document.getElementById('leftPanel');
    const rightPanel = document.getElementById('rightPanel');
    let layoutMode = 'horizontal'; // 'horizontal' | 'vertical'
    let saveCounter = 1;

    function updateLayoutMode() {
        const bodyW = document.body.clientWidth;
        const bodyH = document.body.clientHeight;
        const newMode = (bodyW < 500) ? 'vertical' : 'horizontal';
        if (newMode !== layoutMode) {
            layoutMode = newMode;
            if (newMode === 'vertical') {
                mainLayout.classList.add('vertical');
            } else {
                mainLayout.classList.remove('vertical');
            }
        }
    }

    let lastPanelW = 0;

    function resizeCanvas() {
        updateLayoutMode();

        const bodyW = document.body.clientWidth;
        const bodyH = document.body.clientHeight;

        if (!currentImage) {
            const rect = canvasContainer.getBoundingClientRect();
            const newW = Math.round(rect.width);
            const newH = Math.round(rect.height);
            if (canvas.width !== newW || canvas.height !== newH) {
                canvas.width = newW;
                canvas.height = newH;
            }
            if (layoutMode === 'horizontal') {
                const w = Math.min(Math.round(bodyW * 0.5), 400);
                if (w !== lastPanelW) { leftPanel.style.width = w + 'px'; lastPanelW = w; }
            }
            return;
        }

        let newCanvasW, newCanvasH, newScale;

        if (layoutMode === 'vertical') {
            leftPanel.style.width = '';
            lastPanelW = 0;
            const availW = bodyW;
            const maxH = Math.round(bodyH * 0.55);
            newScale = availW / imageWidth;
            newCanvasW = availW;
            newCanvasH = Math.round(imageHeight * newScale);
            if (newCanvasH > maxH) {
                newScale = maxH / imageHeight;
                newCanvasH = maxH;
                newCanvasW = Math.round(imageWidth * newScale);
            }
        } else {
            const minRightW = 220;
            const maxLeftW = bodyW - minRightW;
            const containerRect = canvasContainer.getBoundingClientRect();
            const containerH = containerRect.height || (bodyH - 140);

            newScale = containerH / imageHeight;
            newCanvasH = containerH;
            newCanvasW = Math.round(imageWidth * newScale);

            if (newCanvasW > maxLeftW) {
                newScale = maxLeftW / imageWidth;
                newCanvasW = maxLeftW;
                newCanvasH = Math.round(imageHeight * newScale);
            }

            if (newCanvasH > containerH && containerH > 0) {
                newScale = containerH / imageHeight;
                newCanvasH = containerH;
                newCanvasW = Math.round(imageWidth * newScale);
            }

            const panelW = Math.max(newCanvasW + 2, 200);
            if (panelW !== lastPanelW) {
                leftPanel.style.width = panelW + 'px';
                lastPanelW = panelW;
            }
        }

        if (newCanvasW <= 0 || newCanvasH <= 0 || !newScale || newScale <= 0) return;

        // 固定精度避免浮点比较问题
        newScale = Math.round(newScale * 1e6) / 1e6;

        // 尺寸没变就不碰画布 — 保留已有高亮
        if (canvas.width === newCanvasW && canvas.height === newCanvasH && scaleRatio === newScale) {
            return;
        }

        scaleRatio = newScale;
        canvasW = newCanvasW;
        canvasH = newCanvasH;
        canvas.width = canvasW;
        canvas.height = canvasH;
        drawScreen();
        if (filterActive && filterMatches.length > 0) {
            filterMatches.forEach((n, j) => drawNodeRect(n, filterColors[j], { fill: true, label: true }));
            drawCrosshair(filterClickX, filterClickY);
        } else {
            highlightSelectedNodeRects();
        }
    }

    function drawScreen() {
        if (!currentImage) { return; }
        ctx.globalAlpha = 1;
        ctx.clearRect(0, 0, canvasW, canvasH);
        ctx.drawImage(currentImage, 0, 0, canvasW, canvasH);
    }

    function drawSelectionRect(x1, y1, x2, y2) {
        const sx = Math.min(x1, x2), sy = Math.min(y1, y2);
        const w = Math.abs(x2 - x1), h = Math.abs(y2 - y1);
        ctx.fillStyle = 'rgba(0,120,215,0.15)';
        ctx.fillRect(sx, sy, w, h);
        ctx.strokeStyle = '#0078d7';
        ctx.lineWidth = 1;
        ctx.setLineDash([4, 2]);
        ctx.strokeRect(sx, sy, w, h);
        ctx.setLineDash([]);
    }

    // 丰富的颜色调色板
    const COLORS = [
        '#3de495','#3d9cf5','#f5a623','#e84393','#00cec9','#fd79a8',
        '#6c5ce7','#ffeaa7','#55efc4','#74b9ff','#ff7675','#a29bfe',
        '#00b894','#fdcb6e','#e17055','#0984e3','#d63031','#636e72'
    ];
    let colorIdx = 0;
    function nextColor() { const c = COLORS[colorIdx % COLORS.length]; colorIdx++; return c; }
    function randomColor() { return COLORS[Math.floor(Math.random() * COLORS.length)]; }

    /**
     * 解析颜色为 rgba 字符串
     */
    function hexToRgba(hex, alpha) {
        const r = parseInt(hex.slice(1,3), 16);
        const g = parseInt(hex.slice(3,5), 16);
        const b = parseInt(hex.slice(5,7), 16);
        return 'rgba(' + r + ',' + g + ',' + b + ',' + alpha + ')';
    }

    /**
     * 绘制增强版节点方框: 半透明填充 + 边框 + 可选标签
     * @param {object} node - 节点对象
     * @param {string} color - 颜色 hex
     * @param {object} opts - 可选 { label: bool, fill: bool, lineWidth: number }
     */
    function drawNodeRect(node, color, opts) {
        if (!node || !node.bounds || node.bounds.width <= 0 || node.bounds.height <= 0) {
            return;
        }
        if (!scaleRatio || scaleRatio <= 0) {
            return;
        }
        opts = opts || {};
        const showLabel = opts.label !== undefined ? opts.label : false;
        const showFill = opts.fill !== undefined ? opts.fill : true;
        const lw = opts.lineWidth || 2;

        const x = Math.round(node.bounds.p1x * scaleRatio);
        const y = Math.round(node.bounds.p1y * scaleRatio);
        const w = Math.round(node.bounds.width * scaleRatio);
        const h = Math.round(node.bounds.height * scaleRatio);
        color = color || randomColor();

        ctx.save();
        ctx.globalAlpha = 1;

        // 半透明填充
        if (showFill) {
            ctx.fillStyle = hexToRgba(color, 0.15);
            ctx.fillRect(x, y, w, h);
        }

        // 外发光 (3px 半透明宽边)
        ctx.strokeStyle = hexToRgba(color, 0.3);
        ctx.lineWidth = lw + 3;
        ctx.strokeRect(x, y, w, h);

        // 主边框
        ctx.strokeStyle = color;
        ctx.lineWidth = lw;
        ctx.strokeRect(x, y, w, h);

        // 标签 (类名 + id)
        if (showLabel && (w > 30 && h > 14)) {
            const clsShort = node.cls ? node.cls.split('.').pop() : '';
            const idShort = node.id ? '#' + node.id.split('/').pop() : '';
            const txt = (clsShort + (idShort ? ' ' + idShort : '')).substring(0, 30);
            if (txt) {
                ctx.font = 'bold 11px "JetBrains Mono", Consolas, monospace';
                const tm = ctx.measureText(txt);
                const lx = x;
                const ly = y - 18;
                const lw2 = tm.width + 10;
                const lh = 16;
                // 背景
                ctx.fillStyle = hexToRgba(color, 0.9);
                const rx = Math.max(lx, 0);
                const ry = ly < 0 ? y + h + 2 : ly;
                ctx.fillRect(rx, ry, lw2, lh);
                // 文字
                ctx.fillStyle = '#fff';
                ctx.fillText(txt, rx + 5, ry + 12);
            }
        }
        ctx.restore();
    }

    /**
     * 在 canvas 上绘制十字准星
     */
    function drawCrosshair(realX, realY) {
        const cx = Math.round(realX * scaleRatio);
        const cy = Math.round(realY * scaleRatio);
        const size = 12;
        ctx.save();
        ctx.strokeStyle = 'rgba(255,255,255,0.7)';
        ctx.lineWidth = 1;
        ctx.setLineDash([3, 2]);
        // 水平线
        ctx.beginPath(); ctx.moveTo(0, cy); ctx.lineTo(canvasW, cy); ctx.stroke();
        // 垂直线
        ctx.beginPath(); ctx.moveTo(cx, 0); ctx.lineTo(cx, canvasH); ctx.stroke();
        ctx.setLineDash([]);
        // 中心圆点
        ctx.fillStyle = '#fff';
        ctx.beginPath(); ctx.arc(cx, cy, 3, 0, Math.PI * 2); ctx.fill();
        ctx.strokeStyle = 'rgba(0,0,0,0.6)';
        ctx.lineWidth = 1;
        ctx.beginPath(); ctx.arc(cx, cy, 3, 0, Math.PI * 2); ctx.stroke();
        ctx.restore();
    }

    // ========================================
    // 鼠标交互
    // ========================================
    canvas.addEventListener('mousedown', (e) => {
        if (!currentImage) return;
        isDragging = true;
        dragCount = 0;
        const rect = canvas.getBoundingClientRect();
        dragStartX = e.clientX - rect.left;
        dragStartY = e.clientY - rect.top;
    });

    canvas.addEventListener('mousemove', (e) => {
        if (!currentImage) return;
        const rect = canvas.getBoundingClientRect();
        const mx = e.clientX - rect.left;
        const my = e.clientY - rect.top;
        const realX = Math.round(mx / scaleRatio);
        const realY = Math.round(my / scaleRatio);
        coordXY.textContent = realX + ', ' + realY;

        if (isDragging) {
            dragEndX = mx;
            dragEndY = my;
            dragCount++;
            if (dragCount > 3) {
                drawScreen();
                highlightSelectedNodeRects();
                drawSelectionRect(dragStartX, dragStartY, dragEndX, dragEndY);
                const sw = Math.abs(Math.round((dragEndX - dragStartX) / scaleRatio));
                const sh = Math.abs(Math.round((dragEndY - dragStartY) / scaleRatio));
                coordArea.style.display = '';
                coordAreaVal.textContent = sw + ' x ' + sh;
            }
        }
    });

    canvas.addEventListener('mouseup', (e) => {
        if (!currentImage) return;
        isDragging = false;

        const dist = Math.hypot(dragEndX - dragStartX, dragEndY - dragStartY);
        if (dragCount < 3 || dist < 6) {
            const rect = canvas.getBoundingClientRect();
            handleClick(e.clientX - rect.left, e.clientY - rect.top);
            dragCount = 0;
            return;
        }

        const sx = Math.min(dragStartX, dragEndX), sy = Math.min(dragStartY, dragEndY);
        const w = Math.abs(dragEndX - dragStartX), h = Math.abs(dragEndY - dragStartY);
        const realX = Math.round(sx / scaleRatio), realY = Math.round(sy / scaleRatio);
        const realW = Math.round(w / scaleRatio), realH = Math.round(h / scaleRatio);

        coordArea.style.display = '';
        coordAreaVal.textContent = realW + ' x ' + realH + '  (' + realX + ',' + realY + ')  比例(' +
            (realX / imageWidth).toFixed(2) + ',' + (realY / imageHeight).toFixed(2) + ',' +
            (realW / imageWidth).toFixed(2) + ',' + (realH / imageHeight).toFixed(2) + ')';

        drawCropPreview(realX, realY, realW, realH);
        dragCount = 0;
    });

    // ========================================
    // 坐标过滤面板
    // ========================================
    const filterPanel = document.getElementById('filterPanel');
    const filterList = document.getElementById('filterList');
    const filterHeaderText = document.getElementById('filterHeaderText');
    let filterActive = false;
    let filterMatches = [];
    let filterColors = [];

    let filterClickX = 0, filterClickY = 0;

    function redrawFilterNodes() {
        drawScreen();
        if (filterActive && filterMatches.length > 0) {
            filterMatches.forEach((n, j) => drawNodeRect(n, filterColors[j], { fill: true, label: true }));
            drawCrosshair(filterClickX, filterClickY);
        }
    }

    function showFilterPanel(matches, colors, realX, realY) {
        filterMatches = matches;
        filterColors = colors;
        filterClickX = realX;
        filterClickY = realY;
        filterActive = true;
        filterPanel.classList.add('active');
        switchToTab('tree');
        filterHeaderText.innerHTML = '<strong>' + matches.length + '</strong> 个节点 · 坐标 (' + realX + ', ' + realY + ')';
        filterList.innerHTML = '';

        matches.forEach((node, i) => {
            const item = document.createElement('div');
            item.className = 'filter-item';

            const clsShort = node.cls ? node.cls.split('.').pop() : '?';
            const idShort = node.id ? node.id.split('/').pop() : '';
            const sizeStr = node.bounds.width + '×' + node.bounds.height;
            const depthStr = 'D' + node.depth;

            let row1 = '<span class="filter-item-cls">' + escapeHtml(clsShort) + '</span>';
            if (idShort) row1 += '<span class="filter-item-id">#' + escapeHtml(idShort) + '</span>';

            let row2Parts = '<span class="filter-item-meta">' + sizeStr + ' · ' + depthStr + '</span>';
            if (node.text) {
                const t = node.text.length > 25 ? node.text.substring(0, 25) + '…' : node.text;
                row2Parts = '<span class="filter-item-text">"' + escapeHtml(t) + '"</span>' + row2Parts;
            }

            let badgesHtml = '';
            if (node.isClickAble) badgesHtml += '<span class="badge badge-click">点击</span>';
            if (node.isScrollable) badgesHtml += '<span class="badge badge-scroll">滚动</span>';
            if (node.text) badgesHtml += '<span class="badge badge-text">文本</span>';

            item.innerHTML =
                '<div class="filter-item-color" style="background:' + colors[i] + '"></div>' +
                '<div class="filter-item-body">' +
                    '<div class="filter-item-row1">' + row1 +
                        (badgesHtml ? '<span class="filter-item-badges">' + badgesHtml + '</span>' : '') +
                    '</div>' +
                    '<div class="filter-item-row2">' + row2Parts + '</div>' +
                '</div>';

            // hover: 聚焦高亮当前节点, 其他半透明
            item.addEventListener('mouseenter', () => {
                drawScreen();
                matches.forEach((n, j) => {
                    ctx.globalAlpha = (j === i) ? 1 : 0.12;
                    drawNodeRect(n, colors[j], { fill: true, label: (j === i), lineWidth: (j === i) ? 2 : 1 });
                });
                ctx.globalAlpha = 1;
            });

            // mouseleave: 恢复全部高亮
            item.addEventListener('mouseleave', () => {
                redrawFilterNodes();
            });

            // click: 选中节点, 高亮 + 显示属性
            item.addEventListener('click', () => {
                filterList.querySelectorAll('.filter-item.selected').forEach(el => el.classList.remove('selected'));
                item.classList.add('selected');
                selectedNode = node;
                selectedNodeColor = colors[i];
                drawScreen();
                drawNodeRect(node, colors[i], { fill: true, label: true, lineWidth: 3 });
                renderPropsPanel(node);
                expandToNode(node);
            });

            filterList.appendChild(item);
        });
    }

    function clearFilter() {
        filterActive = false;
        filterPanel.classList.remove('active');
        filterMatches = [];
        filterColors = [];
        drawScreen();
        highlightSelectedNodeRects();
    }

    document.getElementById('filterClearBtn').addEventListener('click', clearFilter);

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && filterActive) clearFilter();
    });

    function handleClick(mx, my) {
        const now = Date.now();
        if (now - lastClickTime < 350) {
            lastClickTime = 0;
            const realX = Math.round(mx / scaleRatio);
            const realY = Math.round(my / scaleRatio);
            vscode.postMessage({ command: 'click', x: realX, y: realY });
            showToast('点击 (' + realX + ', ' + realY + ')', 'info');
            return;
        }
        lastClickTime = now;

        if (uiNodes.length === 0) return;
        const realX = Math.round(mx / scaleRatio);
        const realY = Math.round(my / scaleRatio);

        // 查找所有包含该坐标的节点
        const allMatching = uiNodes.filter(n =>
            realX >= n.bounds.p1x && realX <= n.bounds.p2x &&
            realY >= n.bounds.p1y && realY <= n.bounds.p2y &&
            n.bounds.width > 0 && n.bounds.height > 0
        );
        if (allMatching.length === 0) {
            if (filterActive) clearFilter();
            return;
        }

        // 按面积从小到大排 (最具体的在前面)
        allMatching.sort((a, b) => (a.bounds.width * a.bounds.height) - (b.bounds.width * b.bounds.height));

        // 为每个节点分配颜色
        const nodeColors = allMatching.map((_, i) => COLORS[i % COLORS.length]);

        // 绘制所有匹配节点 (带填充+标签)
        drawScreen();
        allMatching.forEach((n, i) => drawNodeRect(n, nodeColors[i], { fill: true, label: true }));
        drawCrosshair(realX, realY);

        // 如果只有1个, 直接选中
        if (allMatching.length === 1) {
            // 先关闭过滤面板(不重绘), 再选中节点(会重绘+高亮)
            filterActive = false;
            filterPanel.classList.remove('active');
            filterMatches = [];
            filterColors = [];
            selectedNodeColor = nodeColors[0];
            selectNodeInTree(allMatching[0]);
            return;
        }

        // 显示过滤面板
        showFilterPanel(allMatching, nodeColors, realX, realY);
    }

    function drawCropPreview(realX, realY, realW, realH) {
        if (!currentImage || realW <= 0 || realH <= 0) return;
        const offCanvas = document.createElement('canvas');
        offCanvas.width = realW;
        offCanvas.height = realH;
        offCanvas.getContext('2d').drawImage(currentImage, realX, realY, realW, realH, 0, 0, realW, realH);
        const pvW = 28, pvH = 28;
        previewCtx.clearRect(0, 0, pvW, pvH);
        const scale = Math.min(pvW / realW, pvH / realH);
        const dw = Math.round(realW * scale), dh = Math.round(realH * scale);
        previewCtx.drawImage(offCanvas, (pvW - dw) / 2, (pvH - dh) / 2, dw, dh);
        window._cropData = offCanvas.toDataURL('image/jpeg', 0.92);
        cropInfo.textContent = realW + 'x' + realH + ' (' + realX + ',' + realY + ')';
    }

    function clearCropData() {
        window._cropData = null;
        previewCtx.clearRect(0, 0, 28, 28);
        cropInfo.textContent = '';
    }

    function highlightSelectedNodeRects() {
        if (selectedNode) drawNodeRect(selectedNode, selectedNodeColor, { fill: true, label: true });
    }

    // ========================================
    // 控件树解析 (从 HierarchyParser.kt 移植)
    // ========================================
    function parseUiDumpXml(xmlString, foreground) {
        const parser = new DOMParser();
        const doc = parser.parseFromString(xmlString, 'text/xml');
        const rootElement = doc.documentElement;

        // XML 解析错误检测
        const parseErr = doc.querySelector('parsererror');
        if (parseErr) {
            showToast('控件树 XML 解析失败: ' + (parseErr.textContent || '').substring(0, 80), 'error');
            return;
        }

        uiNodes = [];
        treeRoots = [];

        // 找到第一个 Element 子节点
        let firstChild = rootElement.firstChild;
        while (firstChild && firstChild.nodeType !== Node.ELEMENT_NODE) {
            firstChild = firstChild.nextSibling;
        }
        if (!firstChild) return;

        function parseBounds(boundsStr) {
            if (!boundsStr) return { p1x: 0, p1y: 0, p2x: 0, p2y: 0, width: 0, height: 0 };
            // 用 match 提取数字（含负数），避免 esbuild minify 破坏字符类正则
            var nums = boundsStr.match(/-?[0-9]+/g);
            if (!nums || nums.length < 4) return { p1x: 0, p1y: 0, p2x: 0, p2y: 0, width: 0, height: 0 };
            var p1x = parseInt(nums[0]) || 0;
            var p1y = parseInt(nums[1]) || 0;
            var p2x = parseInt(nums[2]) || 0;
            var p2y = parseInt(nums[3]) || 0;
            return { p1x: p1x, p1y: p1y, p2x: p2x, p2y: p2y, width: p2x - p1x, height: p2y - p1y };
        }

        function parseElement(el, depth) {
            const node = {
                index: parseInt(el.getAttribute('index')) || 0,
                text: el.getAttribute('text') || '',
                id: el.getAttribute('resource-id') || '',
                cls: el.getAttribute('class') || '',
                pkg: el.getAttribute('package') || '',
                desc: el.getAttribute('content-desc') || '',
                isCheckable: el.getAttribute('checkable') === 'true',
                isChecked: el.getAttribute('checked') === 'true',
                isClickAble: el.getAttribute('clickable') === 'true',
                isEnable: el.getAttribute('enabled') === 'true',
                isFocusable: el.getAttribute('focusable') === 'true',
                isFocused: el.getAttribute('focused') === 'true',
                isScrollable: el.getAttribute('scrollable') === 'true',
                isLongClickable: el.getAttribute('long-clickable') === 'true',
                isPassword: el.getAttribute('password') === 'true',
                isSelected: el.getAttribute('selected') === 'true',
                isVisible: el.getAttribute('visible-to-user') !== 'false',
                bounds: parseBounds(el.getAttribute('bounds')),
                depth: depth,
                nodeIdx: uiNodes.length,
                children: [],
                expanded: depth < 2,
            };

            uiNodes.push(node);

            for (let i = 0; i < el.children.length; i++) {
                const child = parseElement(el.children[i], depth + 1);
                node.children.push(child);
            }

            return node;
        }

        const rootNode = parseElement(firstChild, 0);
        treeRoots = [rootNode];

        renderTree(foreground);
    }

    // ========================================
    // 控件树渲染
    // ========================================
    function renderTree(foreground) {
        currentForeground = foreground || currentForeground;
        treeContainer.innerHTML = '';

        // 解析前台应用信息: 格式为 "packageName activityName pid"
        const fgBar = document.getElementById('fgBar');
        const fgContent = document.getElementById('fgContent');
        if (currentForeground) {
            // 清理: 去除 { } 等多余符号, 过滤 null 和空值
            const cleaned = currentForeground.replace(/[{}]/g, '');
            const parts = cleaned.split(/\s+/).filter(p => p && p !== 'null');
            if (parts.length > 0) {
                const pkg = parts[0] || '';
                let act = parts.length > 1 ? parts[1] : '';
                const pid = parts.length > 2 ? parts[2] : '';
                // 简化 activity 名
                if (act && act.startsWith(pkg)) {
                    act = act.substring(pkg.length);
                }
                let html = '<span class="fg-label">应用</span><span class="fg-pkg fg-copy" data-copy="' + escapeAttr(pkg) + '" title="点击复制">' + escapeHtml(pkg) + '</span>';
                if (act) html += '<span class="fg-sep">·</span><span class="fg-label">活动</span><span class="fg-act fg-copy" data-copy="' + escapeAttr(act) + '" title="点击复制">' + escapeHtml(act) + '</span>';
                if (pid) html += '<span class="fg-sep">·</span><span class="fg-label">PID</span><span class="fg-pid fg-copy" data-copy="' + escapeAttr(pid) + '" title="点击复制">' + escapeHtml(pid) + '</span>';
                fgContent.innerHTML = html;
                fgBar.style.display = '';
                // 点击复制
                fgContent.querySelectorAll('.fg-copy').forEach(el => {
                    el.addEventListener('click', () => {
                        copyText(el.dataset.copy || el.textContent);
                    });
                });
            } else {
                fgBar.style.display = 'none';
            }
        } else {
            fgBar.style.display = 'none';
        }

        treeRoots.forEach(root => renderTreeNode(root, treeContainer, 0));

        // 更新节点数量徽章
        const badge = document.getElementById('nodeCountBadge');
        if (uiNodes.length > 0) {
            badge.textContent = uiNodes.length;
            badge.style.display = '';
        } else {
            badge.style.display = 'none';
        }
    }

    function renderTreeNode(node, parent, indent) {
        const div = document.createElement('div');
        div.className = 'tree-node';
        div.style.paddingLeft = (indent * 16 + 4) + 'px';
        div.dataset.nodeIdx = String(node.nodeIdx);

        const hasChildren = node.children && node.children.length > 0;
        const toggle = hasChildren ? (node.expanded ? '▼' : '►') : '  ';

        let cssClass = 'label';
        if (node.isClickAble) cssClass += ' clickable';
        else if (node.isScrollable) cssClass += ' scrollable';
        else if (node.text) cssClass += ' text-node';
        else cssClass += ' layout-node';

        const clsShort = node.cls ? node.cls.split('.').pop() : '';
        const clsLower = clsShort.toLowerCase();
        const idShort = node.id ? node.id.split('/').pop() : '';
        let labelText = clsShort;
        if (idShort) labelText += ' #' + idShort;
        if (node.text) {
            const t = node.text.length > 20 ? node.text.substring(0, 20) + '…' : node.text;
            labelText += ' "' + t + '"';
        }

        // 控件类型标记
        let typeIcon = '';
        if (clsLower.includes('button')) typeIcon = 'btn';
        else if (clsLower.includes('edittext') || clsLower.includes('input')) typeIcon = 'inp';
        else if (clsLower.includes('image') || clsLower.includes('icon')) typeIcon = 'img';
        else if (clsLower.includes('recycler') || clsLower.includes('listview')) typeIcon = 'lst';
        else if (clsLower.includes('scroll')) typeIcon = 'scr';
        else if (clsLower.includes('webview')) typeIcon = 'web';
        else if (clsLower.includes('switch') || clsLower.includes('checkbox')) typeIcon = 'chk';
        else if (clsLower.includes('viewpager') || clsLower.includes('tab')) typeIcon = 'tab';

        // tooltip: bounds / size / center
        const b = node.bounds;
        const cx = Math.round(b.p1x + b.width / 2), cy = Math.round(b.p1y + b.height / 2);
        const tip = clsShort + (idShort ? ' #' + idShort : '') +
            '\\n' + '位置: [' + b.p1x + ',' + b.p1y + '][' + b.p2x + ',' + b.p2y + ']' +
            '\\n' + '大小: ' + b.width + '×' + b.height +
            '\\n' + '中心: (' + cx + ', ' + cy + ')';
        div.setAttribute('title', tip);

        let badges = '';
        if (node.isClickAble) badges += '<span class="badge badge-click">点击</span>';
        if (node.isScrollable) badges += '<span class="badge badge-scroll">滚动</span>';

        const iconHtml = typeIcon ? '<span class="node-icon">' + typeIcon + '</span>' : '';
        div.innerHTML = '<span class="toggle">' + toggle + '</span>' + iconHtml + '<span class="' + cssClass + '">' + escapeHtml(labelText) + '</span>' + badges;

        parent.appendChild(div);

        const childrenDiv = document.createElement('div');
        childrenDiv.style.display = node.expanded ? 'block' : 'none';
        parent.appendChild(childrenDiv);

        if (hasChildren) {
            node.children.forEach(child => renderTreeNode(child, childrenDiv, indent + 1));
        }

        div.querySelector('.toggle').addEventListener('click', (e) => {
            e.stopPropagation();
            if (!hasChildren) return;
            node.expanded = !node.expanded;
            childrenDiv.style.display = node.expanded ? 'block' : 'none';
            div.querySelector('.toggle').textContent = node.expanded ? '▼' : '►';
        });

        // hover: 预览高亮
        div.addEventListener('mouseenter', () => {
            if (!currentImage || selectedNode === node) return;
            drawScreen();
            highlightSelectedNodeRects();
            drawNodeRect(node, '#74b9ff', { fill: true, label: true, lineWidth: 1 });
        });
        div.addEventListener('mouseleave', () => {
            if (!currentImage || selectedNode === node) return;
            drawScreen();
            highlightSelectedNodeRects();
        });

        // 左键: 选中 + 截图高亮
        div.addEventListener('click', () => {
            document.querySelectorAll('.tree-node.selected').forEach(el => el.classList.remove('selected'));
            div.classList.add('selected');
            selectedNode = node;
            selectedNodeColor = nextColor();
            drawScreen();
            drawNodeRect(node, selectedNodeColor, { fill: true, label: true, lineWidth: 2 });
            renderPropsPanel(node);
        });

        // 右键: 选中 + 切换到属性面板
        div.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            document.querySelectorAll('.tree-node.selected').forEach(el => el.classList.remove('selected'));
            div.classList.add('selected');
            selectedNode = node;
            selectedNodeColor = nextColor();
            drawScreen();
            drawNodeRect(node, selectedNodeColor, { fill: true, label: true, lineWidth: 2 });
            renderPropsPanel(node);
            switchToTab('props');
        });
    }

    // ========================================
    // 属性面板渲染
    // ========================================
    function renderPropsPanel(node) {
        if (!node) {
            propsContainer.innerHTML = '<div class="props-empty">选中控件后此处显示属性详情</div>';
            return;
        }

        const b = node.bounds;
        const idShort = node.id ? node.id.split('/').pop() : '';
        const clsShort = node.cls ? node.cls.split('.').pop() : '';
        const centerX = Math.round(b.p1x + b.width / 2);
        const centerY = Math.round(b.p1y + b.height / 2);
        const childCount = node.children ? node.children.length : 0;
        const hasImg = imageWidth > 0 && imageHeight > 0;

        let html = '';

        // 基本信息
        html += '<div class="prop-section">';
        html += '<div class="prop-section-title">基本信息</div>';
        html += propRow('class', clsShort, node.cls);
        html += propRow('resource-id', idShort, node.id);
        html += propRow('text', node.text || '', node.text);
        html += propRow('content-desc', node.desc || '', node.desc);
        html += propRow('package', node.pkg, node.pkg);
        html += propRow('index', String(node.index));
        html += propRow('depth', String(node.depth));
        html += propRow('children', String(childCount));
        html += '</div>';

        // 位置与尺寸
        html += '<div class="prop-section">';
        html += '<div class="prop-section-title">位置与尺寸</div>';
        html += propRow('bounds', '[' + b.p1x + ',' + b.p1y + '][' + b.p2x + ',' + b.p2y + ']', b.p1x+','+b.p1y+','+b.p2x+','+b.p2y);
        html += propRow('size', b.width + ' × ' + b.height, b.width+'x'+b.height);
        html += propRow('center', centerX + ', ' + centerY, centerX+','+centerY);
        if (hasImg) {
            const rx = (b.p1x / imageWidth).toFixed(3);
            const ry = (b.p1y / imageHeight).toFixed(3);
            const rw = (b.width / imageWidth).toFixed(3);
            const rh = (b.height / imageHeight).toFixed(3);
            const rcx = (centerX / imageWidth).toFixed(3);
            const rcy = (centerY / imageHeight).toFixed(3);
            html += propRow('比例坐标', rx + ', ' + ry + ', ' + rw + ', ' + rh, rx+','+ry+','+rw+','+rh);
            html += propRow('比例中心', rcx + ', ' + rcy, rcx+','+rcy);
        }
        html += '</div>';

        // 状态
        html += '<div class="prop-section">';
        html += '<div class="prop-section-title">状态</div>';
        const states = [];
        if (node.isClickAble) states.push('<span class="badge badge-click">clickable</span>');
        if (node.isScrollable) states.push('<span class="badge badge-scroll">scrollable</span>');
        if (node.isLongClickable) states.push('<span class="badge badge-click">long-clickable</span>');
        if (node.isCheckable) states.push('<span class="badge badge-text">checkable</span>');
        if (node.isChecked) states.push('<span class="badge badge-text">checked ✓</span>');
        if (node.isFocusable) states.push('<span class="badge badge-text">focusable</span>');
        if (node.isFocused) states.push('<span class="badge badge-text">focused</span>');
        if (node.isSelected) states.push('<span class="badge badge-text">selected</span>');
        if (node.isPassword) states.push('<span class="badge badge-text">password</span>');
        if (!node.isEnable) states.push('<span class="badge" style="background:rgba(255,85,85,0.12);color:var(--danger)">disabled</span>');
        if (!node.isVisible) states.push('<span class="badge" style="background:rgba(255,85,85,0.12);color:var(--danger)">invisible</span>');
        if (states.length === 0) states.push('<span class="badge badge-text">enabled</span>');
        html += '<div class="prop-row"><div class="prop-val" style="display:flex;flex-wrap:wrap;gap:4px;">' + states.join('') + '</div></div>';
        html += '</div>';

        // Python 代码片段
        html += '<div class="prop-section">';
        html += '<div class="prop-section-title">Python 代码片段</div>';

        // 生成 ui_match 查找参数
        let matchParams = [];
        if (idShort) matchParams.push('id="' + idShort + '"');
        if (node.text) matchParams.push('text="' + node.text.replace(/"/g, '\\\\"') + '"');
        if (node.desc) matchParams.push('desc="' + node.desc.replace(/"/g, '\\\\"') + '"');
        if (matchParams.length === 0) matchParams.push('cls="' + clsShort + '"');

        const findExpr = 'ui_match(' + matchParams.join(', ') + ')';
        const clickCode = 'click(' + centerX + ', ' + centerY + ')';

        html += codeSnippet('# 查找控件 (返回匹配列表)\\n' + findExpr);
        html += codeSnippet('# 点击中心坐标\\n' + clickCode);

        if (hasImg) {
            const rx = (b.p1x / imageWidth).toFixed(3);
            const ry = (b.p1y / imageHeight).toFixed(3);
            const rw = (b.width / imageWidth).toFixed(3);
            const rh = (b.height / imageHeight).toFixed(3);
            html += codeSnippet('# 区域比例 (图色/OCR识别范围)\\nx=' + rx + ', y=' + ry + ', w=' + rw + ', h=' + rh);
        }

        // 查找并点击
        if (idShort || node.text) {
            const findKey = idShort ? 'id="' + idShort + '"' : 'text="' + node.text.replace(/"/g, '\\\\"') + '"';
            html += codeSnippet('# 查找并点击\\nresult = ui_match(' + findKey + ')\\nif result:\\n    click(result[0].center_x, result[0].center_y)');
        }

        // 滑动代码 (如果可滚动)
        if (node.isScrollable) {
            const sx = centerX, sy1 = Math.round(b.p1y + b.height * 0.7), sy2 = Math.round(b.p1y + b.height * 0.3);
            html += codeSnippet('# 在此控件内上滑\\nswipe(' + sx + ', ' + sy1 + ', ' + sx + ', ' + sy2 + ', 500)');
        }

        html += '</div>';

        propsContainer.innerHTML = html;

        // 绑定复制按钮
        propsContainer.querySelectorAll('.prop-copy-btn').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                copyText(btn.dataset.copy);
            });
        });
        propsContainer.querySelectorAll('.copy-code-btn').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                copyText(btn.dataset.copy);
            });
        });

        // 更新快捷操作栏
        const qa = document.getElementById('quickActions');
        qa.style.display = '';
        // 复制ID按钮: 有id才显示
        document.getElementById('qaCopyId').style.display = idShort ? '' : 'none';
        // 复制文本按钮: 有text才显示
        document.getElementById('qaCopyText').style.display = node.text ? '' : 'none';
    }

    function propRow(key, displayVal, copyVal) {
        copyVal = copyVal || displayVal;
        const isEmpty = !displayVal;
        const valClass = isEmpty ? 'prop-val empty' : 'prop-val';
        const display = isEmpty ? '(空)' : escapeHtml(displayVal);
        let copyBtn = '';
        if (!isEmpty) {
            copyBtn = '<button class="prop-copy-btn" data-copy="' + escapeAttr(copyVal) + '" title="复制">📋</button>';
        }
        return '<div class="prop-row"><span class="prop-key">' + escapeHtml(key) + '</span><span class="' + valClass + '">' + display + '</span>' + copyBtn + '</div>';
    }

    function codeSnippet(code) {
        const displayCode = code.replace(/\\\\n/g, '\\n');
        const copyCode = code.replace(/\\\\n/g, '\\n').replace(/^#[^\\n]*\\n/gm, '').trim();
        return '<div class="code-snippet-box"><pre>' + escapeHtml(displayCode) + '</pre><button class="copy-code-btn" data-copy="' + escapeAttr(copyCode) + '">复制</button></div>';
    }

    function selectNodeInTree(node) {
        selectedNode = node;
        if (!selectedNodeColor) selectedNodeColor = nextColor();
        expandToNode(node);

        document.querySelectorAll('.tree-node.selected').forEach(el => el.classList.remove('selected'));
        const nodeDiv = treeContainer.querySelector('[data-node-idx="' + node.nodeIdx + '"]');
        if (nodeDiv) {
            nodeDiv.classList.add('selected');
            nodeDiv.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }

        // 在 canvas 上绘制选中节点的高亮
        drawScreen();
        drawNodeRect(node, selectedNodeColor, { fill: true, label: true, lineWidth: 2 });

        renderPropsPanel(node);
        switchToTab('props');
    }

    function expandToNode(target) {
        function findPath(node, path) {
            path.push(node);
            if (node === target) return true;
            for (const child of node.children || []) {
                if (findPath(child, path)) return true;
            }
            path.pop();
            return false;
        }

        for (const root of treeRoots) {
            const path = [];
            if (findPath(root, path)) {
                let needsFullRebuild = false;
                for (const n of path) {
                    if (!n.expanded) {
                        n.expanded = true;
                        needsFullRebuild = true;
                    }
                }
                if (needsFullRebuild) {
                    // 尝试直接toggle DOM而不重建tree
                    path.forEach(n => {
                        const div = treeContainer.querySelector('[data-node-idx="' + n.nodeIdx + '"]');
                        if (div) {
                            const toggle = div.querySelector('.toggle');
                            if (toggle) toggle.textContent = '▼';
                            const next = div.nextElementSibling;
                            if (next && next.tagName === 'DIV' && !next.classList.contains('tree-node')) {
                                next.style.display = 'block';
                            }
                        }
                    });
                    // 如果目标节点不在DOM中(父节点未渲染), 回退到全量重建
                    const targetDiv = treeContainer.querySelector('[data-node-idx="' + target.nodeIdx + '"]');
                    if (!targetDiv) {
                        renderTree('');
                    }
                }
                break;
            }
        }
    }

    function setAllExpanded(expanded) {
        uiNodes.forEach(n => { n.expanded = expanded; });
        renderTree('');
    }

    // ========================================
    // 搜索
    // ========================================
    searchInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            const keyword = searchInput.value.trim().toLowerCase();
            if (!keyword || uiNodes.length === 0) return;

            const matches = uiNodes.filter(n => {
                const cls = (n.cls || '').toLowerCase();
                const id = (n.id || '').toLowerCase();
                const text = (n.text || '').toLowerCase();
                const desc = (n.desc || '').toLowerCase();
                return cls.includes(keyword) || id.includes(keyword) || text.includes(keyword) || desc.includes(keyword);
            });

            if (matches.length === 0) {
                showToast('未找到匹配 "' + searchInput.value.trim() + '" 的控件', 'error');
                return;
            }
            if (matches.length > 50) {
                showToast('匹配过多 (' + matches.length + ' 个)，请缩小搜索范围', 'error');
                return;
            }

            showToast('找到 ' + matches.length + ' 个匹配控件', 'success');
            drawScreen();
            const color = randomColor();
            matches.forEach(n => drawNodeRect(n, color, { fill: true, label: true }));
            selectNodeInTree(matches[0]);
        }
        if (e.key === 'Escape') {
            searchInput.value = '';
            searchInput.blur();
            drawScreen();
            highlightSelectedNodeRects();
        }
    });

    // ========================================
    // 按钮事件
    // ========================================
    document.getElementById('btnLoadScreen').addEventListener('click', () => {
        vscode.postMessage({ command: 'loadScreenshot' });
    });

    document.getElementById('btnLoadUiDump').addEventListener('click', () => {
        vscode.postMessage({ command: 'loadUiDump' });
    });

    document.getElementById('btnSaveArea').addEventListener('click', () => {
        if (!window._cropData) {
            showToast('请先在截图上拖拽选择区域', 'error');
            return;
        }
        const nameInput = document.getElementById('saveNameInput');
        const name = nameInput.value.trim() || String(saveCounter);
        const idx = window._cropData.indexOf(',');
        const base64 = idx >= 0 ? window._cropData.substring(idx + 1) : window._cropData;
        vscode.postMessage({ command: 'saveArea', fileName: name, imageBase64: base64 });
        saveCounter++;
        nameInput.value = String(saveCounter);
    });

    document.getElementById('btnExpandAll').addEventListener('click', () => { setAllExpanded(true); });
    document.getElementById('btnCollapseAll').addEventListener('click', () => { setAllExpanded(false); });

    document.getElementById('debugLink').addEventListener('click', () => {
        vscode.postMessage({ command: 'showDebugLog' });
    });

    // 快捷操作栏
    document.getElementById('qaClick').addEventListener('click', () => {
        if (!selectedNode) return;
        const b = selectedNode.bounds;
        const cx = Math.round(b.p1x + b.width / 2);
        const cy = Math.round(b.p1y + b.height / 2);
        vscode.postMessage({ command: 'click', x: cx, y: cy });
        showToast('点击 (' + cx + ', ' + cy + ')', 'info');
    });
    document.getElementById('qaCopyId').addEventListener('click', () => {
        if (!selectedNode || !selectedNode.id) return;
        copyText(selectedNode.id.split('/').pop());
    });
    document.getElementById('qaCopyText').addEventListener('click', () => {
        if (!selectedNode || !selectedNode.text) return;
        copyText(selectedNode.text);
    });
    document.getElementById('qaCopyXPath').addEventListener('click', () => {
        if (!selectedNode) return;
        copyText(buildXPath(selectedNode));
    });
    document.getElementById('qaLocate').addEventListener('click', () => {
        if (!selectedNode) return;
        switchToTab('tree');
        expandToNode(selectedNode);
        const nodeDiv = treeContainer.querySelector('[data-node-idx="' + selectedNode.nodeIdx + '"]');
        if (nodeDiv) {
            document.querySelectorAll('.tree-node.selected').forEach(el => el.classList.remove('selected'));
            nodeDiv.classList.add('selected');
            nodeDiv.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
    });

    function buildXPath(targetNode) {
        // 从根到目标构建路径
        function findPath(node, target, path) {
            path.push(node);
            if (node === target) return true;
            for (const child of node.children || []) {
                if (findPath(child, target, path)) return true;
            }
            path.pop();
            return false;
        }
        let pathNodes = [];
        for (const root of treeRoots) {
            const p = [];
            if (findPath(root, targetNode, p)) { pathNodes = p; break; }
        }
        if (pathNodes.length === 0) return '';

        let xpath = '';
        for (let i = 0; i < pathNodes.length; i++) {
            const n = pathNodes[i];
            const clsShort = n.cls ? n.cls.split('.').pop() : '*';
            const idShort = n.id ? n.id.split('/').pop() : '';
            if (idShort) {
                xpath += '//' + clsShort + '[@resource-id="' + n.id + '"]';
            } else if (n.text) {
                xpath += '//' + clsShort + '[@text="' + n.text + '"]';
            } else if (n.desc) {
                xpath += '//' + clsShort + '[@content-desc="' + n.desc + '"]';
            } else if (i === pathNodes.length - 1) {
                // 叶节点: 用 class + index
                const parent = i > 0 ? pathNodes[i - 1] : null;
                if (parent) {
                    const sameClass = parent.children.filter(c => c.cls === n.cls);
                    if (sameClass.length > 1) {
                        const idx = sameClass.indexOf(n) + 1;
                        xpath += '//' + clsShort + '[' + idx + ']';
                    } else {
                        xpath += '//' + clsShort;
                    }
                } else {
                    xpath += '//' + clsShort;
                }
            } else {
                continue; // 跳过无标识的中间容器
            }
        }
        return xpath || '//' + (targetNode.cls ? targetNode.cls.split('.').pop() : '*');
    }

    // ========================================
    // VS Code 消息处理
    // ========================================
    window.addEventListener('message', (event) => {
        const msg = event.data;

        switch (msg.command) {
            case 'screenshotLoaded': {
                const img = new Image();
                img.onload = () => {
                    currentImage = img;
                    imageWidth = img.naturalWidth;
                    imageHeight = img.naturalHeight;
                    placeholder.style.display = 'none';
                    resizeCanvas();
                    coordRes.textContent = imageWidth + ' x ' + imageHeight;
                    showToast('截图已加载 (' + imageWidth + 'x' + imageHeight + ')', 'success');
                };
                img.onerror = () => {
                    showToast('图片数据无法解析', 'error');
                };
                img.src = 'data:image/jpeg;base64,' + msg.imageBase64;
                clearCropData();
                break;
            }
            case 'uiDumpLoaded': {
                parseUiDumpXml(msg.xmlContent, msg.foreground);
                showToast('控件树已加载 (' + uiNodes.length + ' 个节点)', 'success');
                switchToTab('tree');
                break;
            }
            case 'setLoading': {
                if (msg.loading) {
                    loadingOverlay.classList.remove('hidden');
                    document.getElementById('loadingText').textContent = msg.text || '加载中...';
                } else {
                    loadingOverlay.classList.add('hidden');
                }
                break;
            }
            case 'connectionStatus': {
                const dot = document.getElementById('connDot');
                const text = document.getElementById('connText');
                if (msg.connected) {
                    dot.classList.add('connected');
                    text.textContent = '已连接 ' + msg.ip;
                } else {
                    dot.classList.remove('connected');
                    text.textContent = msg.ip ? '断开 ' + msg.ip : '未连接';
                }
                break;
            }
            case 'info': {
                showToast(msg.text, 'success');
                break;
            }
            case 'error': {
                showToast(msg.text, 'error');
                break;
            }
        }
    });

    // ========================================
    // 窗口大小变化 (防抖, 监听整体布局)
    // ========================================
    let resizeRafId = null;
    function debouncedResize() {
        if (resizeRafId) cancelAnimationFrame(resizeRafId);
        resizeRafId = requestAnimationFrame(() => {
            resizeRafId = null;
            resizeCanvas();
        });
    }
    new ResizeObserver(debouncedResize).observe(document.body);
    new ResizeObserver(debouncedResize).observe(canvasContainer);
    // 初始化布局
    updateLayoutMode();
    if (!currentImage) {
        const bodyW = document.body.clientWidth;
        if (layoutMode === 'horizontal') {
            leftPanel.style.width = Math.min(Math.round(bodyW * 0.5), 400) + 'px';
        }
    }

    // ========================================
    // 工具函数
    // ========================================
    function escapeHtml(str) {
        return str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    }

    function escapeAttr(str) {
        return str.replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    }
})();
</script>
</body>
</html>`;
    }
}
