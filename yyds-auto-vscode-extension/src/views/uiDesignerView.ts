/**
 * UI 配置设计器 — 类似 Qt Designer 的可视化 ui.yml 编辑器
 * 支持拖拽添加组件、属性编辑、实时预览、YAML 源码切换
 */

import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { log } from '../engine/logger';

export class UiDesignerPanel {
    public static readonly viewType = 'yyds.uiDesigner';
    public static currentPanel: UiDesignerPanel | undefined;

    private readonly panel: vscode.WebviewPanel;
    private readonly extensionUri: vscode.Uri;
    private yamlFilePath: string | undefined;
    private disposables: vscode.Disposable[] = [];

    public static createOrShow(extensionUri: vscode.Uri, fileUri?: vscode.Uri): UiDesignerPanel {
        const column = vscode.ViewColumn.One;

        if (UiDesignerPanel.currentPanel) {
            UiDesignerPanel.currentPanel.panel.reveal(column);
            if (fileUri) {
                UiDesignerPanel.currentPanel.loadFile(fileUri.fsPath);
            }
            return UiDesignerPanel.currentPanel;
        }

        const panel = vscode.window.createWebviewPanel(
            UiDesignerPanel.viewType,
            'UI 设计器 - Yyds.Auto',
            column,
            {
                enableScripts: true,
                retainContextWhenHidden: true,
                localResourceRoots: [extensionUri],
            }
        );

        UiDesignerPanel.currentPanel = new UiDesignerPanel(panel, extensionUri, fileUri);
        return UiDesignerPanel.currentPanel;
    }

    private constructor(panel: vscode.WebviewPanel, extensionUri: vscode.Uri, fileUri?: vscode.Uri) {
        this.panel = panel;
        this.extensionUri = extensionUri;

        this.panel.webview.html = this.getHtmlContent();

        this.panel.webview.onDidReceiveMessage(
            (message) => this.handleMessage(message),
            null,
            this.disposables
        );

        this.panel.onDidDispose(() => this.dispose(), null, this.disposables);

        // Load file after webview is ready
        if (fileUri) {
            this.yamlFilePath = fileUri.fsPath;
        } else {
            // Auto-detect ui.yml in workspace
            this.yamlFilePath = this.findUiYaml();
        }

        if (this.yamlFilePath) {
            // Delay to ensure webview is ready
            setTimeout(() => this.loadFile(this.yamlFilePath!), 300);
        }
    }

    private findUiYaml(): string | undefined {
        const folders = vscode.workspace.workspaceFolders;
        if (!folders) { return undefined; }
        for (const folder of folders) {
            const uiPath = path.join(folder.uri.fsPath, 'ui.yml');
            if (fs.existsSync(uiPath)) { return uiPath; }
        }
        // Check subfolders
        for (const folder of folders) {
            try {
                const entries = fs.readdirSync(folder.uri.fsPath, { withFileTypes: true });
                for (const entry of entries) {
                    if (entry.isDirectory()) {
                        const uiPath = path.join(folder.uri.fsPath, entry.name, 'ui.yml');
                        if (fs.existsSync(uiPath)) { return uiPath; }
                    }
                }
            } catch { /* ignore */ }
        }
        return undefined;
    }

    private loadFile(filePath: string): void {
        this.yamlFilePath = filePath;
        try {
            if (fs.existsSync(filePath)) {
                const content = fs.readFileSync(filePath, 'utf-8');
                this.panel.webview.postMessage({ command: 'loadYaml', content, filePath });
                log('UiDesigner', `已加载: ${filePath}`);
            } else {
                this.panel.webview.postMessage({ command: 'loadYaml', content: '', filePath });
                log('UiDesigner', `文件不存在，创建空画布: ${filePath}`);
            }
        } catch (e) {
            vscode.window.showErrorMessage(`读取 ui.yml 失败: ${e}`);
        }
    }

    private async handleMessage(message: any): Promise<void> {
        switch (message.command) {
            case 'save': {
                if (!this.yamlFilePath) {
                    // Ask user to choose location
                    const folders = vscode.workspace.workspaceFolders;
                    if (folders) {
                        this.yamlFilePath = path.join(folders[0].uri.fsPath, 'ui.yml');
                    } else {
                        vscode.window.showErrorMessage('无法保存：未找到工作区');
                        return;
                    }
                }
                try {
                    const dir = path.dirname(this.yamlFilePath);
                    if (!fs.existsSync(dir)) {
                        fs.mkdirSync(dir, { recursive: true });
                    }
                    fs.writeFileSync(this.yamlFilePath, message.content, 'utf-8');
                    this.panel.webview.postMessage({ command: 'saved' });
                    log('UiDesigner', `已保存: ${this.yamlFilePath}`);
                } catch (e) {
                    vscode.window.showErrorMessage(`保存失败: ${e}`);
                }
                break;
            }
            case 'requestLoad': {
                if (this.yamlFilePath) {
                    this.loadFile(this.yamlFilePath);
                } else {
                    this.panel.webview.postMessage({ command: 'loadYaml', content: '', filePath: '' });
                }
                break;
            }
            case 'openFile': {
                if (this.yamlFilePath && fs.existsSync(this.yamlFilePath)) {
                    const doc = await vscode.workspace.openTextDocument(this.yamlFilePath);
                    await vscode.window.showTextDocument(doc, vscode.ViewColumn.Beside);
                }
                break;
            }
        }
    }

    dispose(): void {
        UiDesignerPanel.currentPanel = undefined;
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
${this.getCss()}
</style>
</head>
<body>
${this.getToolbarHtml()}
<div class="main-layout">
    ${this.getPaletteHtml()}
    ${this.getCanvasHtml()}
    ${this.getPropsHtml()}
</div>
<div class="status-bar" id="statusBar">
    <span id="statusFile">ui.yml</span>
    <span id="statusCount">0 个组件</span>
    <span id="statusModified" class="hidden">· 已修改</span>
</div>
<div class="toast-container" id="toastContainer"></div>
<script>
${this.getScript()}
</script>
</body>
</html>`;
    }

    private getCss(): string {
        return /* css */ `
* { margin: 0; padding: 0; box-sizing: border-box; }
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

/* ===== 工具栏 ===== */
.toolbar {
    display: flex;
    align-items: center;
    gap: 2px;
    padding: 4px 8px;
    background: var(--vscode-titleBar-activeBackground);
    border-bottom: 1px solid var(--vscode-panel-border);
    flex-shrink: 0;
    min-height: 34px;
}
.toolbar-group { display: flex; align-items: center; gap: 2px; }
.toolbar-sep { width: 1px; height: 18px; background: var(--vscode-panel-border); margin: 0 6px; }
.tb-btn {
    display: flex; align-items: center; gap: 4px;
    background: none; color: var(--vscode-foreground); border: none;
    padding: 4px 8px; cursor: pointer; border-radius: 4px;
    font-size: 12px; white-space: nowrap; transition: background 0.15s;
}
.tb-btn:hover { background: var(--vscode-toolbar-hoverBackground, rgba(90,93,94,0.31)); }
.tb-btn:active { background: var(--vscode-toolbar-activeBackground, rgba(99,102,103,0.5)); }
.tb-btn.primary { background: var(--vscode-button-background); color: var(--vscode-button-foreground); }
.tb-btn.primary:hover { background: var(--vscode-button-hoverBackground); }
.tb-btn.active-toggle { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); }
.tb-btn kbd {
    font-size: 10px; padding: 1px 4px; border-radius: 3px;
    background: rgba(128,128,128,0.2); color: var(--vscode-descriptionForeground); margin-left: 2px;
}
.toolbar-spacer { flex: 1; }

/* ===== 主布局 ===== */
.main-layout { display: flex; flex: 1; overflow: hidden; }

/* ===== 左侧组件面板 ===== */
.palette {
    width: 140px; flex-shrink: 0;
    border-right: 1px solid var(--vscode-panel-border);
    background: var(--vscode-sideBar-background);
    display: flex; flex-direction: column;
    overflow-y: auto;
}
.palette-title {
    font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px;
    color: var(--vscode-descriptionForeground); padding: 10px 10px 6px; font-weight: 600;
}
.palette-item {
    display: flex; align-items: center; gap: 8px;
    padding: 7px 10px; cursor: grab; border-radius: 4px;
    margin: 1px 4px; transition: background 0.15s; font-size: 12px;
    user-select: none;
}
.palette-item:hover { background: var(--vscode-list-hoverBackground); }
.palette-item:active { cursor: grabbing; }
.palette-item .pi-icon { font-size: 16px; width: 20px; text-align: center; flex-shrink: 0; }
.palette-item .pi-label { flex: 1; }

/* ===== 中央画布 ===== */
.canvas-area {
    flex: 1; display: flex; flex-direction: column; align-items: center;
    overflow-y: auto; padding: 24px 16px;
    background: var(--vscode-editor-background);
}
.phone-frame {
    width: 360px; min-height: 540px;
    border: 2px solid var(--vscode-panel-border);
    border-radius: 24px; background: #FBF7F1;
    display: flex; flex-direction: column;
    overflow: hidden; position: relative;
    box-shadow: 0 4px 24px rgba(0,0,0,0.15);
}
.phone-notch {
    height: 28px; background: #1a1a2e;
    display: flex; align-items: center; justify-content: center;
    border-radius: 22px 22px 0 0;
}
.phone-notch-dot { width: 8px; height: 8px; border-radius: 50%; background: #333; }
.phone-body {
    flex: 1; padding: 16px 20px; display: flex; flex-direction: column; gap: 0;
    min-height: 100px; position: relative;
    background: #fff; margin: 8px; border-radius: 12px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}
.phone-body.drag-over { background: rgba(0,120,215,0.04); }

/* Canvas 内组件 */
.cv-item {
    position: relative; cursor: pointer;
    border: 2px solid transparent; border-radius: 6px;
    transition: border-color 0.15s, box-shadow 0.15s;
    padding: 2px;
}
.cv-item:hover { border-color: rgba(0,120,215,0.3); }
.cv-item.selected { border-color: var(--vscode-focusBorder); box-shadow: 0 0 0 1px var(--vscode-focusBorder); }
.cv-item .drag-handle {
    position: absolute; left: -22px; top: 50%; transform: translateY(-50%);
    width: 18px; height: 18px; cursor: grab;
    display: none; align-items: center; justify-content: center;
    font-size: 12px; opacity: 0.5; color: var(--vscode-descriptionForeground);
    border-radius: 3px; background: var(--vscode-sideBar-background);
}
.cv-item:hover .drag-handle, .cv-item.selected .drag-handle { display: flex; }
.cv-item .drag-handle:hover { opacity: 1; background: var(--vscode-toolbar-hoverBackground); }

/* 模拟 Android Material Design 组件样式 */
/* 颜色常量: primary_lay=#264B6F, gray_light=#EAE9E9, ok=#008305 */

.cv-text {
    padding: 5px 0; font-size: 14px; color: #264B6F;
    font-family: 'Roboto', 'Noto Sans SC', monospace, sans-serif;
    word-break: break-all; line-height: 1.4;
}
.cv-div { height: 1px; background: #D5D7DA; margin: 4px 0; }
.cv-space { background: transparent; }

/* Checkbox — Android: text left, indicator right (drawableEnd) */
.cv-check {
    display: flex; align-items: center; padding: 5px 0;
}
.cv-check-label {
    flex: 1; font-size: 16px; color: #264B6F;
    font-family: 'Roboto', 'Noto Sans SC', sans-serif;
}
.cv-check-box {
    width: 22px; height: 22px; border: 2px solid #264B6F; border-radius: 3px;
    display: flex; align-items: center; justify-content: center;
    font-size: 14px; color: #fff; flex-shrink: 0; margin-left: 8px;
    transition: background 0.15s;
}
.cv-check-box.checked { background: #264B6F; border-color: #264B6F; }

/* Select/Spinner — Android: horizontal, title left (bold), dropdown right */
.cv-select {
    display: flex; align-items: center; padding: 8px 0; gap: 8px;
}
.cv-select-title {
    flex: 1; font-size: 15px; color: #264B6F; font-weight: bold;
    font-family: 'Roboto', 'Noto Sans SC', monospace, sans-serif;
}
.cv-select-box {
    border: 1px solid #bbb; border-radius: 4px; padding: 6px 28px 6px 10px;
    font-size: 14px; color: #333; background: #fff;
    position: relative; min-width: 100px;
}
.cv-select-arrow {
    position: absolute; right: 8px; top: 50%; transform: translateY(-50%);
    width: 0; height: 0; border-left: 5px solid transparent;
    border-right: 5px solid transparent; border-top: 6px solid #666;
}

/* Edit — Android Material TextInputLayout FilledBox style */
.cv-edit {
    display: flex; flex-direction: column; padding: 0; margin: 12px 0;
}
.cv-edit-box {
    background: #F5F5F5; border-radius: 4px 4px 0 0;
    border-bottom: 1px solid #ACACAC; padding: 0;
    position: relative; transition: border-color 0.2s;
}
.cv-edit-box.focused { border-bottom: 2px solid #264B6F; }
.cv-edit-label {
    font-size: 12px; color: #264B6F; font-weight: 500;
    padding: 8px 12px 0 12px; line-height: 1.3;
    font-family: 'Roboto', 'Noto Sans SC', sans-serif;
}
.cv-edit-input {
    border: none; padding: 4px 12px 8px 12px;
    font-size: 14px; color: #333; background: transparent; outline: none;
    width: 100%; min-height: 24px;
    font-family: 'Roboto', 'Noto Sans SC', sans-serif;
}
.cv-edit-input.placeholder { color: #ACACAC; }
.cv-edit-input.password { -webkit-text-security: disc; }
.cv-edit-input.multiline { min-height: 60px; }
.cv-edit-helper {
    font-size: 11px; color: #ACACAC; padding: 4px 12px 0;
    font-family: 'Roboto', 'Noto Sans SC', sans-serif;
}

/* Drop indicator */
.drop-indicator {
    height: 3px; background: var(--vscode-focusBorder); border-radius: 2px;
    margin: 2px 0; display: none;
}
.drop-indicator.visible { display: block; }

/* Empty state */
.canvas-empty {
    display: flex; flex-direction: column; align-items: center; justify-content: center;
    padding: 40px 20px; color: #999; text-align: center; flex: 1;
}
.canvas-empty .ce-icon { font-size: 48px; opacity: 0.3; margin-bottom: 12px; }
.canvas-empty .ce-title { font-size: 15px; margin-bottom: 6px; }
.canvas-empty .ce-hint { font-size: 12px; opacity: 0.6; }

/* ===== 右侧属性面板 ===== */
.props-panel {
    width: 280px; flex-shrink: 0;
    border-left: 1px solid var(--vscode-panel-border);
    background: var(--vscode-sideBar-background);
    display: flex; flex-direction: column;
    overflow-y: auto;
}
.props-header {
    font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px;
    color: var(--vscode-descriptionForeground); padding: 10px 12px 6px; font-weight: 600;
    border-bottom: 1px solid var(--vscode-panel-border);
}
.props-empty-state {
    display: flex; flex-direction: column; align-items: center; justify-content: center;
    padding: 40px 20px; color: var(--vscode-descriptionForeground); text-align: center; flex: 1;
    font-size: 12px; opacity: 0.6;
}
.props-body { padding: 8px 12px; display: flex; flex-direction: column; gap: 10px; }
.prop-group { display: flex; flex-direction: column; gap: 6px; }
.prop-group-title {
    font-size: 10px; text-transform: uppercase; letter-spacing: 0.5px;
    color: var(--vscode-descriptionForeground); padding-bottom: 3px;
    border-bottom: 1px solid var(--vscode-panel-border);
}
.prop-field { display: flex; flex-direction: column; gap: 3px; }
.prop-label { font-size: 11px; color: var(--vscode-descriptionForeground); }
.prop-input {
    background: var(--vscode-input-background); color: var(--vscode-input-foreground);
    border: 1px solid var(--vscode-input-border); padding: 4px 8px;
    font-size: 12px; border-radius: 3px; outline: none; width: 100%;
}
.prop-input:focus { border-color: var(--vscode-focusBorder); }
.prop-select {
    background: var(--vscode-input-background); color: var(--vscode-input-foreground);
    border: 1px solid var(--vscode-input-border); padding: 4px 6px;
    font-size: 12px; border-radius: 3px; outline: none; width: 100%;
}
.prop-checkbox-row {
    display: flex; align-items: center; gap: 6px; padding: 2px 0;
}
.prop-checkbox-row input[type="checkbox"] { accent-color: var(--vscode-focusBorder); }
.prop-checkbox-row label { font-size: 12px; cursor: pointer; }
.prop-color-row { display: flex; align-items: center; gap: 6px; }
.prop-color-row input[type="color"] { width: 28px; height: 24px; border: none; cursor: pointer; background: none; }
.prop-list-editor { display: flex; flex-direction: column; gap: 3px; }
.prop-list-item {
    display: flex; align-items: center; gap: 4px;
}
.prop-list-item input { flex: 1; }
.prop-list-item button {
    background: none; border: none; color: var(--vscode-descriptionForeground);
    cursor: pointer; font-size: 14px; padding: 0 2px; opacity: 0.6;
}
.prop-list-item button:hover { opacity: 1; color: #e45; }
.prop-list-add {
    background: none; border: 1px dashed var(--vscode-panel-border);
    color: var(--vscode-descriptionForeground); cursor: pointer; padding: 3px 8px;
    font-size: 11px; border-radius: 3px; text-align: center;
}
.prop-list-add:hover { border-color: var(--vscode-focusBorder); color: var(--vscode-foreground); }

.prop-delete-btn {
    margin-top: 12px; padding: 6px 12px; border-radius: 4px;
    background: rgba(244,67,54,0.1); color: #f44336;
    border: 1px solid rgba(244,67,54,0.3); cursor: pointer;
    font-size: 12px; text-align: center; transition: all 0.15s;
}
.prop-delete-btn:hover { background: rgba(244,67,54,0.2); }

/* Python snippet */
.prop-code-box {
    margin-top: 8px; padding: 8px;
    background: var(--vscode-textBlockQuote-background); border-radius: 4px;
    font-family: 'Consolas', 'Courier New', monospace; font-size: 11px;
    border: 1px solid var(--vscode-panel-border);
    position: relative; white-space: pre-wrap; word-break: break-all;
    color: var(--vscode-foreground);
}
.prop-code-box .copy-btn {
    position: absolute; top: 4px; right: 4px;
    background: var(--vscode-button-secondaryBackground);
    color: var(--vscode-button-secondaryForeground);
    border: none; padding: 2px 8px; border-radius: 3px;
    font-size: 10px; cursor: pointer; opacity: 0; transition: opacity 0.15s;
}
.prop-code-box:hover .copy-btn { opacity: 1; }

/* ===== 源码编辑器 ===== */
.source-view {
    flex: 1; display: none; flex-direction: column;
}
.source-view.active { display: flex; }
.source-editor {
    flex: 1; width: 100%; resize: none;
    background: var(--vscode-editor-background); color: var(--vscode-editor-foreground);
    border: none; padding: 16px; font-family: 'Consolas', 'Courier New', monospace;
    font-size: 13px; line-height: 1.5; outline: none;
    tab-size: 2;
}
.design-view { display: flex; flex: 1; overflow: hidden; }
.design-view.hidden { display: none; }

/* ===== 状态栏 ===== */
.status-bar {
    display: flex; align-items: center; gap: 12px;
    padding: 3px 12px; font-size: 11px;
    background: var(--vscode-statusBar-background);
    color: var(--vscode-statusBar-foreground);
    border-top: 1px solid var(--vscode-panel-border);
    flex-shrink: 0; min-height: 22px;
}
.hidden { display: none !important; }

/* ===== Toast ===== */
.toast-container {
    position: fixed; bottom: 30px; right: 16px; z-index: 200;
    display: flex; flex-direction: column-reverse; gap: 6px; pointer-events: none;
}
.toast {
    padding: 8px 14px; border-radius: 5px; font-size: 12px;
    background: var(--vscode-notifications-background, #252526);
    color: var(--vscode-notifications-foreground, #ccc);
    border: 1px solid var(--vscode-notifications-border, #333);
    box-shadow: 0 4px 12px rgba(0,0,0,0.3);
    animation: toastIn 0.25s ease-out; pointer-events: auto; max-width: 320px;
}
.toast.success { border-left: 3px solid #4caf50; }
.toast.error { border-left: 3px solid #f44336; }
.toast.info { border-left: 3px solid #2196f3; }
@keyframes toastIn { from { opacity:0; transform:translateY(8px); } to { opacity:1; transform:translateY(0); } }
`;
    }

    private getToolbarHtml(): string {
        return /* html */ `
<div class="toolbar">
    <div class="toolbar-group">
        <button class="tb-btn primary" id="btnSave" title="保存 ui.yml (Ctrl+S)">💾 保存</button>
    </div>
    <div class="toolbar-sep"></div>
    <div class="toolbar-group">
        <button class="tb-btn" id="btnUndo" title="撤销 (Ctrl+Z)">↩ 撤销</button>
        <button class="tb-btn" id="btnRedo" title="重做 (Ctrl+Shift+Z)">↪ 重做</button>
    </div>
    <div class="toolbar-sep"></div>
    <div class="toolbar-group">
        <button class="tb-btn" id="btnToggleView" title="切换设计视图/源码视图">📄 源码</button>
    </div>
    <div class="toolbar-spacer"></div>
    <div class="toolbar-group">
        <button class="tb-btn" id="btnOpenFile" title="在编辑器中打开 ui.yml">📂 打开文件</button>
    </div>
</div>`;
    }

    private getPaletteHtml(): string {
        return /* html */ `
<div class="palette" id="palette">
    <div class="palette-title">组件库</div>
    <div class="palette-item" draggable="true" data-type="text">
        <span class="pi-icon">📝</span><span class="pi-label">文本显示</span>
    </div>
    <div class="palette-item" draggable="true" data-type="div">
        <span class="pi-icon">➖</span><span class="pi-label">分割线</span>
    </div>
    <div class="palette-item" draggable="true" data-type="space">
        <span class="pi-icon">⬜</span><span class="pi-label">空行间距</span>
    </div>
    <div class="palette-item" draggable="true" data-type="check">
        <span class="pi-icon">☑️</span><span class="pi-label">开关按钮</span>
    </div>
    <div class="palette-item" draggable="true" data-type="select">
        <span class="pi-icon">📋</span><span class="pi-label">下拉选择</span>
    </div>
    <div class="palette-item" draggable="true" data-type="edit">
        <span class="pi-icon">✏️</span><span class="pi-label">输入框</span>
    </div>
</div>`;
    }

    private getCanvasHtml(): string {
        return /* html */ `
<div class="design-view" id="designView">
    <div class="canvas-area" id="canvasArea">
        <div class="phone-frame">
            <div class="phone-notch"><div class="phone-notch-dot"></div></div>
            <div class="phone-body" id="phoneBody">
                <div class="canvas-empty" id="canvasEmpty">
                    <div class="ce-icon">🎨</div>
                    <div class="ce-title">从左侧拖入组件开始设计</div>
                    <div class="ce-hint">或加载已有的 ui.yml 文件</div>
                </div>
            </div>
        </div>
    </div>
    <div class="props-panel" id="propsPanel">
        <div class="props-header">属性编辑器</div>
        <div class="props-empty-state" id="propsEmpty">选中画布上的组件<br>即可编辑其属性</div>
        <div class="props-body hidden" id="propsBody"></div>
    </div>
</div>
<div class="source-view" id="sourceView">
    <textarea class="source-editor" id="sourceEditor" spellcheck="false" placeholder="# 在此编辑 ui.yml 源码..."></textarea>
</div>`;
    }

    private getPropsHtml(): string {
        return '';
    }

    private getScript(): string {
        return /* javascript */ `
(function() {
    const vscode = acquireVsCodeApi();

    // ========================================
    // 数据模型
    // ========================================
    let components = [];  // [{type, name, props}, ...]
    let selectedIdx = -1;
    let isModified = false;
    let currentFilePath = '';
    let isSourceView = false;

    // 撤销/重做栈
    let undoStack = [];
    let redoStack = [];
    const MAX_UNDO = 50;

    function pushUndo() {
        undoStack.push(JSON.stringify(components));
        if (undoStack.length > MAX_UNDO) undoStack.shift();
        redoStack = [];
    }

    function undo() {
        if (undoStack.length === 0) return;
        redoStack.push(JSON.stringify(components));
        components = JSON.parse(undoStack.pop());
        selectedIdx = -1;
        setModified(true);
        renderCanvas();
        renderProps();
    }

    function redo() {
        if (redoStack.length === 0) return;
        undoStack.push(JSON.stringify(components));
        components = JSON.parse(redoStack.pop());
        selectedIdx = -1;
        setModified(true);
        renderCanvas();
        renderProps();
    }

    // ========================================
    // 组件默认值
    // ========================================
    const DEFAULTS = {
        text:   { value: '文本内容', color: '#333333', size: 14 },
        div:    {},
        space:  { height: 20 },
        check:  { title: '开关选项', value: true },
        select: { title: '下拉选择', value: ['选项一', '选项二', '选项三'] },
        edit:   { title: '输入标题', value: '', input: 'text', hint: '', required: false },
    };

    // 计数器用于生成唯一name
    let nameCounters = { text: 0, div: 0, space: 0, check: 0, select: 0, edit: 0 };

    function generateName(type) {
        nameCounters[type] = (nameCounters[type] || 0) + 1;
        let candidate = type === 'div' || type === 'space'
            ? String(nameCounters[type])
            : type + nameCounters[type];
        // Ensure unique
        while (components.some(c => c.type + '-' + c.name === type + '-' + candidate)) {
            nameCounters[type]++;
            candidate = type === 'div' || type === 'space'
                ? String(nameCounters[type])
                : type + nameCounters[type];
        }
        return candidate;
    }

    function createComponent(type) {
        return { type, name: generateName(type), props: JSON.parse(JSON.stringify(DEFAULTS[type] || {})) };
    }

    // ========================================
    // DOM refs
    // ========================================
    const phoneBody = document.getElementById('phoneBody');
    const canvasEmpty = document.getElementById('canvasEmpty');
    const propsBody = document.getElementById('propsBody');
    const propsEmpty = document.getElementById('propsEmpty');
    const toastContainer = document.getElementById('toastContainer');
    const sourceEditor = document.getElementById('sourceEditor');
    const designView = document.getElementById('designView');
    const sourceView = document.getElementById('sourceView');

    // ========================================
    // Toast
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

    function setModified(v) {
        isModified = v;
        document.getElementById('statusModified').classList.toggle('hidden', !v);
    }

    function updateStatusBar() {
        document.getElementById('statusCount').textContent = components.length + ' 个组件';
        if (currentFilePath) {
            const parts = currentFilePath.replace(/\\\\\\\\/g, '/').split('/');
            document.getElementById('statusFile').textContent = parts.slice(-2).join('/');
        }
    }

    // ========================================
    // 画布渲染
    // ========================================
    function renderCanvas() {
        // Remove all cv-items and drop indicators, keep canvasEmpty
        const existing = phoneBody.querySelectorAll('.cv-item, .drop-indicator');
        existing.forEach(el => el.remove());

        canvasEmpty.style.display = components.length === 0 ? 'flex' : 'none';

        components.forEach((comp, idx) => {
            const wrapper = document.createElement('div');
            wrapper.className = 'cv-item' + (idx === selectedIdx ? ' selected' : '');
            wrapper.dataset.idx = idx;

            // Drag handle
            const handle = document.createElement('div');
            handle.className = 'drag-handle';
            handle.textContent = '⠿';
            handle.draggable = true;
            wrapper.appendChild(handle);

            // Render component content
            const content = renderComponentPreview(comp);
            wrapper.appendChild(content);

            // Click to select
            wrapper.addEventListener('click', (e) => {
                e.stopPropagation();
                selectedIdx = idx;
                renderCanvas();
                renderProps();
            });

            // Drag handle for reorder
            handle.addEventListener('dragstart', (e) => {
                e.dataTransfer.setData('text/reorder', String(idx));
                e.dataTransfer.effectAllowed = 'move';
                wrapper.style.opacity = '0.4';
            });
            handle.addEventListener('dragend', () => {
                wrapper.style.opacity = '1';
            });

            phoneBody.appendChild(wrapper);
        });

        updateStatusBar();
    }

    function renderComponentPreview(comp) {
        const el = document.createElement('div');
        const p = comp.props;

        switch (comp.type) {
            case 'text': {
                el.className = 'cv-text';
                el.textContent = p.value || '文本';
                if (p.color) el.style.color = p.color;
                if (p.size) el.style.fontSize = p.size + 'px';
                break;
            }
            case 'div': {
                el.className = 'cv-div';
                break;
            }
            case 'space': {
                el.className = 'cv-space';
                el.style.height = (p.height || 20) + 'px';
                break;
            }
            case 'check': {
                // Android: text left, checkbox indicator right (drawableEnd)
                el.className = 'cv-check';
                const label = document.createElement('span');
                label.className = 'cv-check-label';
                label.textContent = p.title || '开关';
                const box = document.createElement('div');
                box.className = 'cv-check-box' + (p.value ? ' checked' : '');
                box.textContent = p.value ? '✓' : '';
                el.appendChild(label);
                el.appendChild(box);
                break;
            }
            case 'select': {
                // Android: horizontal layout, title left (bold), spinner right
                el.className = 'cv-select';
                const title = document.createElement('div');
                title.className = 'cv-select-title';
                title.textContent = p.title || '选择';
                if (p.color) title.style.color = p.color;
                const boxWrap = document.createElement('div');
                boxWrap.className = 'cv-select-box';
                boxWrap.style.position = 'relative';
                const items = Array.isArray(p.value) ? p.value : [];
                const textSpan = document.createElement('span');
                textSpan.textContent = items[0] || '请选择';
                const arrow = document.createElement('div');
                arrow.className = 'cv-select-arrow';
                boxWrap.appendChild(textSpan);
                boxWrap.appendChild(arrow);
                el.appendChild(title);
                el.appendChild(boxWrap);
                break;
            }
            case 'edit': {
                // Android Material TextInputLayout FilledBox style
                el.className = 'cv-edit';
                const editBox = document.createElement('div');
                editBox.className = 'cv-edit-box';
                const label = document.createElement('div');
                label.className = 'cv-edit-label';
                let labelText = p.title || '输入';
                if (p.required) labelText += ' *';
                label.textContent = labelText;
                const input = document.createElement('div');
                let inputCls = 'cv-edit-input';
                if (p.input === 'password') inputCls += ' password';
                if (p.input === 'multiline') inputCls += ' multiline';
                if (!p.value && p.hint) inputCls += ' placeholder';
                input.className = inputCls;
                if (p.input === 'password' && p.value) {
                    input.textContent = '•'.repeat(Math.min(p.value.length || 6, 12));
                } else {
                    input.textContent = p.value || p.hint || '';
                }
                editBox.appendChild(label);
                editBox.appendChild(input);
                el.appendChild(editBox);
                // Helper text below the box
                const helper = document.createElement('div');
                helper.className = 'cv-edit-helper';
                if (p.input === 'password') helper.textContent = '密码输入';
                else if (p.input === 'number') helper.textContent = '数字输入';
                else if (p.input === 'multiline') helper.textContent = '多行文本';
                if (helper.textContent) el.appendChild(helper);
                break;
            }
        }
        return el;
    }

    // ========================================
    // 属性面板渲染
    // ========================================
    function renderProps() {
        if (selectedIdx < 0 || selectedIdx >= components.length) {
            propsBody.classList.add('hidden');
            propsEmpty.classList.remove('hidden');
            return;
        }
        propsEmpty.classList.add('hidden');
        propsBody.classList.remove('hidden');

        const comp = components[selectedIdx];
        const p = comp.props;
        let html = '';

        // 组件名称
        html += '<div class="prop-group">';
        html += '<div class="prop-group-title">组件标识</div>';
        html += '<div class="prop-field">';
        html += '<span class="prop-label">类型</span>';
        html += '<input class="prop-input" value="' + esc(comp.type) + '" disabled>';
        html += '</div>';
        html += '<div class="prop-field">';
        html += '<span class="prop-label">名称 (唯一标识)</span>';
        html += '<input class="prop-input" id="propName" value="' + esc(comp.name) + '">';
        html += '</div>';
        html += '<div style="font-size:11px;color:var(--vscode-descriptionForeground);opacity:0.7;">key: ' + esc(comp.type + '-' + comp.name) + '</div>';
        html += '</div>';

        // Type-specific props
        html += '<div class="prop-group">';
        html += '<div class="prop-group-title">属性</div>';

        switch (comp.type) {
            case 'text':
                html += propField('value', '文本内容', p.value || '', 'text');
                html += propColorField('color', '文字颜色', p.color || '#333333');
                html += propField('size', '字号', p.size || 14, 'number');
                break;
            case 'div':
                html += '<div style="font-size:12px;color:var(--vscode-descriptionForeground);">分割线无可编辑属性</div>';
                break;
            case 'space':
                html += propField('height', '高度 (dp)', p.height || 20, 'number');
                break;
            case 'check':
                html += propField('title', '标题', p.title || '', 'text');
                html += propCheckbox('value', '默认选中', p.value !== false);
                break;
            case 'select':
                html += propField('title', '标题', p.title || '', 'text');
                html += propColorField('color', '标题颜色', p.color || '');
                html += propListEditor('value', '选项列表', Array.isArray(p.value) ? p.value : []);
                break;
            case 'edit':
                html += propField('title', '标题', p.title || '', 'text');
                html += propField('value', '默认值', p.value || '', 'text');
                html += propField('hint', '占位提示', p.hint || '', 'text');
                html += propSelectField('input', '输入模式', p.input || 'text', [
                    { value: 'text', label: '单行文本' },
                    { value: 'password', label: '密码' },
                    { value: 'number', label: '数字' },
                    { value: 'multiline', label: '多行文本' },
                ]);
                html += propCheckbox('required', '必填校验', p.required === true);
                break;
        }
        html += '</div>';

        // Python snippet
        if (comp.type === 'check' || comp.type === 'select' || comp.type === 'edit') {
            const key = comp.type + '-' + comp.name;
            html += '<div class="prop-group">';
            html += '<div class="prop-group-title">Python 读取</div>';
            html += '<div class="prop-code-box" id="codeBox">Config.read_config_value("' + esc(key) + '")<button class="copy-btn" id="copyCodeBtn">复制</button></div>';
            html += '</div>';
        }

        // Delete button
        html += '<button class="prop-delete-btn" id="btnDeleteComp">🗑 删除此组件</button>';

        propsBody.innerHTML = html;

        // Bind events
        bindPropEvents(comp);
    }

    function propField(key, label, value, type) {
        return '<div class="prop-field">'
            + '<span class="prop-label">' + esc(label) + '</span>'
            + '<input class="prop-input" data-key="' + esc(key) + '" type="' + type + '" value="' + esc(String(value)) + '">'
            + '</div>';
    }

    function propColorField(key, label, value) {
        return '<div class="prop-field">'
            + '<span class="prop-label">' + esc(label) + '</span>'
            + '<div class="prop-color-row">'
            + '<input type="color" data-key="' + esc(key) + '" value="' + esc(value || '#333333') + '">'
            + '<input class="prop-input" data-key="' + esc(key) + '" type="text" value="' + esc(value) + '" style="flex:1">'
            + '</div></div>';
    }

    function propCheckbox(key, label, checked) {
        return '<div class="prop-checkbox-row">'
            + '<input type="checkbox" id="prop_' + key + '" data-key="' + esc(key) + '"' + (checked ? ' checked' : '') + '>'
            + '<label for="prop_' + key + '">' + esc(label) + '</label>'
            + '</div>';
    }

    function propSelectField(key, label, value, options) {
        let html = '<div class="prop-field">'
            + '<span class="prop-label">' + esc(label) + '</span>'
            + '<select class="prop-select" data-key="' + esc(key) + '">';
        options.forEach(o => {
            html += '<option value="' + esc(o.value) + '"' + (o.value === value ? ' selected' : '') + '>' + esc(o.label) + '</option>';
        });
        html += '</select></div>';
        return html;
    }

    function propListEditor(key, label, items) {
        let html = '<div class="prop-field">';
        html += '<span class="prop-label">' + esc(label) + '</span>';
        html += '<div class="prop-list-editor" data-key="' + esc(key) + '">';
        items.forEach((item, i) => {
            html += '<div class="prop-list-item">'
                + '<input class="prop-input" data-list-idx="' + i + '" value="' + esc(item) + '">'
                + '<button data-list-remove="' + i + '" title="删除">✕</button>'
                + '</div>';
        });
        html += '<button class="prop-list-add" data-list-add="' + esc(key) + '">+ 添加选项</button>';
        html += '</div></div>';
        return html;
    }

    function bindPropEvents(comp) {
        // Name field
        const nameInput = document.getElementById('propName');
        if (nameInput) {
            nameInput.addEventListener('change', () => {
                const newName = nameInput.value.trim();
                if (newName && newName !== comp.name) {
                    const fullKey = comp.type + '-' + newName;
                    if (components.some((c, i) => i !== selectedIdx && c.type + '-' + c.name === fullKey)) {
                        showToast('名称已存在: ' + fullKey, 'error');
                        nameInput.value = comp.name;
                        return;
                    }
                    pushUndo();
                    comp.name = newName;
                    setModified(true);
                    renderCanvas();
                    renderProps();
                }
            });
        }

        // Regular prop inputs
        propsBody.querySelectorAll('.prop-input[data-key]').forEach(input => {
            input.addEventListener('change', () => {
                pushUndo();
                const key = input.dataset.key;
                let val = input.value;
                if (input.type === 'number') val = parseFloat(val) || 0;
                comp.props[key] = val;
                setModified(true);
                renderCanvas();
                // Re-render props to sync color pickers etc
                renderProps();
            });
        });

        // Color pickers
        propsBody.querySelectorAll('input[type="color"][data-key]').forEach(input => {
            input.addEventListener('input', () => {
                const key = input.dataset.key;
                comp.props[key] = input.value;
                // Sync text input
                const textInput = propsBody.querySelector('.prop-input[data-key="' + key + '"]');
                if (textInput) textInput.value = input.value;
                setModified(true);
                renderCanvas();
            });
        });

        // Select fields
        propsBody.querySelectorAll('.prop-select[data-key]').forEach(sel => {
            sel.addEventListener('change', () => {
                pushUndo();
                comp.props[sel.dataset.key] = sel.value;
                setModified(true);
                renderCanvas();
            });
        });

        // Checkboxes
        propsBody.querySelectorAll('input[type="checkbox"][data-key]').forEach(cb => {
            cb.addEventListener('change', () => {
                pushUndo();
                comp.props[cb.dataset.key] = cb.checked;
                setModified(true);
                renderCanvas();
            });
        });

        // List editor: item change
        propsBody.querySelectorAll('.prop-list-item input[data-list-idx]').forEach(input => {
            input.addEventListener('change', () => {
                pushUndo();
                const idx = parseInt(input.dataset.listIdx);
                if (Array.isArray(comp.props.value)) {
                    comp.props.value[idx] = input.value;
                    setModified(true);
                    renderCanvas();
                }
            });
        });

        // List editor: remove
        propsBody.querySelectorAll('button[data-list-remove]').forEach(btn => {
            btn.addEventListener('click', () => {
                pushUndo();
                const idx = parseInt(btn.dataset.listRemove);
                if (Array.isArray(comp.props.value)) {
                    comp.props.value.splice(idx, 1);
                    setModified(true);
                    renderCanvas();
                    renderProps();
                }
            });
        });

        // List editor: add
        propsBody.querySelectorAll('button[data-list-add]').forEach(btn => {
            btn.addEventListener('click', () => {
                pushUndo();
                if (Array.isArray(comp.props.value)) {
                    comp.props.value.push('新选项');
                    setModified(true);
                    renderCanvas();
                    renderProps();
                }
            });
        });

        // Delete button
        const delBtn = document.getElementById('btnDeleteComp');
        if (delBtn) {
            delBtn.addEventListener('click', () => {
                pushUndo();
                components.splice(selectedIdx, 1);
                selectedIdx = -1;
                setModified(true);
                renderCanvas();
                renderProps();
            });
        }

        // Copy code
        const copyBtn = document.getElementById('copyCodeBtn');
        if (copyBtn) {
            copyBtn.addEventListener('click', () => {
                const code = document.getElementById('codeBox').textContent.replace('复制', '').trim();
                navigator.clipboard.writeText(code).then(() => showToast('已复制', 'success'));
            });
        }
    }

    // ========================================
    // 拖拽: 从面板添加组件
    // ========================================
    document.querySelectorAll('.palette-item').forEach(item => {
        item.addEventListener('dragstart', (e) => {
            e.dataTransfer.setData('text/addtype', item.dataset.type);
            e.dataTransfer.effectAllowed = 'copy';
        });
    });

    // Phone body drop zone
    phoneBody.addEventListener('dragover', (e) => {
        e.preventDefault();
        e.dataTransfer.dropEffect = e.dataTransfer.types.includes('text/reorder') ? 'move' : 'copy';
        phoneBody.classList.add('drag-over');

        // Find insert position
        const items = phoneBody.querySelectorAll('.cv-item');
        let insertBefore = -1;
        const mouseY = e.clientY;
        items.forEach((item, i) => {
            const rect = item.getBoundingClientRect();
            const midY = rect.top + rect.height / 2;
            if (mouseY < midY && insertBefore === -1) {
                insertBefore = i;
            }
        });
        // Visual feedback — highlight border of insert position
        items.forEach((item, i) => {
            item.style.borderTopColor = (i === insertBefore) ? 'var(--vscode-focusBorder)' : '';
        });
    });

    phoneBody.addEventListener('dragleave', () => {
        phoneBody.classList.remove('drag-over');
        phoneBody.querySelectorAll('.cv-item').forEach(item => {
            item.style.borderTopColor = '';
        });
    });

    phoneBody.addEventListener('drop', (e) => {
        e.preventDefault();
        phoneBody.classList.remove('drag-over');
        phoneBody.querySelectorAll('.cv-item').forEach(item => {
            item.style.borderTopColor = '';
        });

        // Find insert index
        const items = phoneBody.querySelectorAll('.cv-item');
        let insertIdx = components.length;
        const mouseY = e.clientY;
        items.forEach((item, i) => {
            const rect = item.getBoundingClientRect();
            const midY = rect.top + rect.height / 2;
            if (mouseY < midY && insertIdx === components.length) {
                insertIdx = i;
            }
        });

        // Add new component
        const addType = e.dataTransfer.getData('text/addtype');
        if (addType) {
            pushUndo();
            const comp = createComponent(addType);
            components.splice(insertIdx, 0, comp);
            selectedIdx = insertIdx;
            setModified(true);
            renderCanvas();
            renderProps();
            showToast('已添加: ' + addType, 'success');
            return;
        }

        // Reorder existing
        const reorderIdx = e.dataTransfer.getData('text/reorder');
        if (reorderIdx !== '') {
            const fromIdx = parseInt(reorderIdx);
            if (fromIdx === insertIdx || fromIdx + 1 === insertIdx) return;
            pushUndo();
            const [moved] = components.splice(fromIdx, 1);
            const targetIdx = fromIdx < insertIdx ? insertIdx - 1 : insertIdx;
            components.splice(targetIdx, 0, moved);
            selectedIdx = targetIdx;
            setModified(true);
            renderCanvas();
            renderProps();
        }
    });

    // Click on empty area to deselect
    phoneBody.addEventListener('click', (e) => {
        if (e.target === phoneBody || e.target === canvasEmpty) {
            selectedIdx = -1;
            renderCanvas();
            renderProps();
        }
    });

    // ========================================
    // YAML 解析
    // ========================================
    function parseYaml(content) {
        components = [];
        nameCounters = { text: 0, div: 0, space: 0, check: 0, select: 0, edit: 0 };
        if (!content || !content.trim()) return;

        const lines = content.split('\\n');
        let currentKey = null;
        let currentProps = {};

        function flush() {
            if (currentKey) {
                const parts = currentKey.split('-');
                const type = parts[0];
                const name = parts.slice(1).join('-');
                if (['text','div','space','check','select','edit'].includes(type)) {
                    components.push({ type, name, props: currentProps });
                    // Update counter
                    const num = parseInt(name) || 0;
                    if (num >= (nameCounters[type] || 0)) nameCounters[type] = num;
                    const nameNum = parseInt(name.replace(/^\\D+/, '')) || 0;
                    if (nameNum >= (nameCounters[type] || 0)) nameCounters[type] = nameNum;
                }
            }
            currentKey = null;
            currentProps = {};
        }

        for (let i = 0; i < lines.length; i++) {
            const line = lines[i];
            // Skip comments and empty lines
            if (/^\\s*#/.test(line) || /^\\s*$/.test(line)) continue;

            // Top-level key (no leading whitespace, ends with ':')
            const topMatch = line.match(/^(\\S[\\w-]*)\\s*:/);
            if (topMatch) {
                flush();
                currentKey = topMatch[1];
                // Check if there's inline value (e.g. "div-1:" with nothing after)
                continue;
            }

            // Property line (indented)
            const propMatch = line.match(/^\\s+(\\w+)\\s*:\\s*(.+)/);
            if (propMatch && currentKey) {
                const propKey = propMatch[1];
                let propVal = propMatch[2].trim();

                // Parse value
                if (propVal.startsWith('[') && propVal.endsWith(']')) {
                    // Array: ["a", "b", "c"]
                    try {
                        propVal = JSON.parse(propVal.replace(/'/g, '"'));
                    } catch {
                        propVal = propVal.slice(1, -1).split(',').map(s => s.trim().replace(/^["']|["']$/g, ''));
                    }
                } else if (propVal === 'true') {
                    propVal = true;
                } else if (propVal === 'false') {
                    propVal = false;
                } else if (/^\\d+$/.test(propVal)) {
                    propVal = parseInt(propVal);
                } else if (/^\\d+\\.\\d+$/.test(propVal)) {
                    propVal = parseFloat(propVal);
                } else {
                    // Remove quotes
                    propVal = propVal.replace(/^["']|["']$/g, '');
                }

                currentProps[propKey] = propVal;
            }
        }
        flush();
    }

    // ========================================
    // YAML 序列化
    // ========================================
    function serializeYaml() {
        let lines = [];
        lines.push('# ============================================================');
        lines.push('# Yyds.Auto 用户界面配置文件');
        lines.push('# ============================================================');
        lines.push('# 支持 6 种 UI 类型:');
        lines.push('#   text-xxx  文本显示   div-xxx  分割线   space-xxx  空行');
        lines.push('#   check-xxx 开关按钮   select-xxx 下拉选择   edit-xxx 输入框');
        lines.push('#');
        lines.push('# 读取: Config.read_config_value("edit-user")');
        lines.push('# ============================================================');
        lines.push('');

        components.forEach((comp, idx) => {
            const key = comp.type + '-' + comp.name;
            const p = comp.props;

            lines.push(key + ':');

            switch (comp.type) {
                case 'text':
                    lines.push('  value: "' + escYaml(p.value || '') + '"');
                    if (p.color && p.color !== '#333333') lines.push('  color: "' + p.color + '"');
                    if (p.size && p.size !== 14) lines.push('  size: ' + p.size);
                    break;
                case 'div':
                    // No props
                    break;
                case 'space':
                    if (p.height) lines.push('  height: ' + p.height);
                    break;
                case 'check':
                    if (p.title) lines.push('  title: "' + escYaml(p.title) + '"');
                    lines.push('  value: ' + (p.value !== false ? 'true' : 'false'));
                    break;
                case 'select':
                    if (p.title) lines.push('  title: "' + escYaml(p.title) + '"');
                    if (p.color) lines.push('  color: "' + p.color + '"');
                    if (Array.isArray(p.value)) {
                        const items = p.value.map(v => '"' + escYaml(v) + '"').join(', ');
                        lines.push('  value: [' + items + ']');
                    }
                    break;
                case 'edit':
                    if (p.title) lines.push('  title: "' + escYaml(p.title) + '"');
                    lines.push('  value: "' + escYaml(p.value || '') + '"');
                    if (p.input && p.input !== 'text') lines.push('  input: ' + p.input);
                    if (p.hint) lines.push('  hint: "' + escYaml(p.hint) + '"');
                    if (p.required) lines.push('  required: true');
                    break;
            }

            if (idx < components.length - 1) lines.push('');
        });

        lines.push('');
        return lines.join('\\n');
    }

    function escYaml(str) {
        return String(str).replace(/\\\\/g, '\\\\\\\\').replace(/"/g, '\\\\"');
    }

    // ========================================
    // 源码视图切换
    // ========================================
    function toggleSourceView() {
        isSourceView = !isSourceView;
        const btn = document.getElementById('btnToggleView');

        if (isSourceView) {
            // Sync to source
            sourceEditor.value = serializeYaml();
            designView.classList.add('hidden');
            sourceView.classList.add('active');
            btn.textContent = '🎨 设计';
            btn.classList.add('active-toggle');
        } else {
            // Parse source back
            const content = sourceEditor.value;
            parseYaml(content);
            selectedIdx = -1;
            designView.classList.remove('hidden');
            sourceView.classList.remove('active');
            btn.textContent = '📄 源码';
            btn.classList.remove('active-toggle');
            renderCanvas();
            renderProps();
        }
    }

    // ========================================
    // 工具栏按钮
    // ========================================
    document.getElementById('btnSave').addEventListener('click', () => {
        const content = isSourceView ? sourceEditor.value : serializeYaml();
        vscode.postMessage({ command: 'save', content });
    });

    document.getElementById('btnUndo').addEventListener('click', undo);
    document.getElementById('btnRedo').addEventListener('click', redo);
    document.getElementById('btnToggleView').addEventListener('click', toggleSourceView);
    document.getElementById('btnOpenFile').addEventListener('click', () => {
        vscode.postMessage({ command: 'openFile' });
    });

    // ========================================
    // 键盘快捷键
    // ========================================
    document.addEventListener('keydown', (e) => {
        // Ctrl+S save
        if ((e.ctrlKey || e.metaKey) && e.key === 's') {
            e.preventDefault();
            const content = isSourceView ? sourceEditor.value : serializeYaml();
            vscode.postMessage({ command: 'save', content });
        }
        // Ctrl+Z undo
        if ((e.ctrlKey || e.metaKey) && e.key === 'z' && !e.shiftKey) {
            e.preventDefault();
            undo();
        }
        // Ctrl+Shift+Z redo
        if ((e.ctrlKey || e.metaKey) && e.key === 'z' && e.shiftKey) {
            e.preventDefault();
            redo();
        }
        // Ctrl+Y redo
        if ((e.ctrlKey || e.metaKey) && e.key === 'y') {
            e.preventDefault();
            redo();
        }
        // Delete selected
        if (e.key === 'Delete' && selectedIdx >= 0 && !isSourceView) {
            if (document.activeElement.tagName !== 'INPUT' && document.activeElement.tagName !== 'TEXTAREA' && document.activeElement.tagName !== 'SELECT') {
                e.preventDefault();
                pushUndo();
                components.splice(selectedIdx, 1);
                selectedIdx = -1;
                setModified(true);
                renderCanvas();
                renderProps();
            }
        }
        // Ctrl+D duplicate
        if ((e.ctrlKey || e.metaKey) && e.key === 'd' && selectedIdx >= 0 && !isSourceView) {
            e.preventDefault();
            pushUndo();
            const src = components[selectedIdx];
            const dup = { type: src.type, name: generateName(src.type), props: JSON.parse(JSON.stringify(src.props)) };
            components.splice(selectedIdx + 1, 0, dup);
            selectedIdx = selectedIdx + 1;
            setModified(true);
            renderCanvas();
            renderProps();
            showToast('已复制组件', 'success');
        }
    });

    // Source editor change tracking
    sourceEditor.addEventListener('input', () => {
        setModified(true);
    });

    // ========================================
    // VS Code 消息处理
    // ========================================
    window.addEventListener('message', (event) => {
        const msg = event.data;
        switch (msg.command) {
            case 'loadYaml': {
                currentFilePath = msg.filePath || '';
                parseYaml(msg.content || '');
                selectedIdx = -1;
                isModified = false;
                undoStack = [];
                redoStack = [];
                renderCanvas();
                renderProps();
                updateStatusBar();
                document.getElementById('statusModified').classList.add('hidden');
                if (msg.content) {
                    showToast('已加载 ui.yml', 'success');
                }
                break;
            }
            case 'saved': {
                setModified(false);
                showToast('已保存', 'success');
                break;
            }
        }
    });

    // Request file on load
    vscode.postMessage({ command: 'requestLoad' });

    // ========================================
    // 工具函数
    // ========================================
    function esc(str) {
        return String(str).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
    }

    // Initial render
    renderCanvas();
    renderProps();
})();
`;
    }
}
