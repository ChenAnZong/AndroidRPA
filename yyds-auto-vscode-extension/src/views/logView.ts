/**
 * 日志输出面板 — 移植自 LogForm.java + LogWindowFactory.java
 * 使用 VS Code WebView 实现实时日志显示（支持颜色分类）
 */

import * as vscode from 'vscode';
import { ProjectServer } from '../engine/projectServer';

export class LogViewProvider implements vscode.WebviewViewProvider {
    public static readonly viewType = 'yyds.logView';

    private _view?: vscode.WebviewView;
    private server: ProjectServer | undefined;

    constructor(private readonly extensionUri: vscode.Uri) {}

    setServer(server: ProjectServer): void {
        this.server = server;

        // 监听日志事件
        server.getConnector().on('log', (logText: string) => {
            this.appendLog(logText);
        });
    }

    resolveWebviewView(
        webviewView: vscode.WebviewView,
        _context: vscode.WebviewViewResolveContext,
        _token: vscode.CancellationToken,
    ): void {
        this._view = webviewView;

        webviewView.webview.options = {
            enableScripts: true,
            localResourceRoots: [this.extensionUri],
        };

        webviewView.webview.html = this.getHtmlContent();

        // 处理 WebView 发来的消息
        webviewView.webview.onDidReceiveMessage((message) => {
            switch (message.command) {
                case 'clear':
                    // 清空日志（WebView 内部处理）
                    break;
            }
        });
    }

    /**
     * 向日志面板追加日志
     */
    private appendLog(logText: string): void {
        if (!this._view) { return; }

        // 判断日志类型以确定颜色
        let color: string;
        let text = logText;

        if (text.includes('err:')) {
            color = '#ff4444';
            text = text.replace('err:', '');
        } else if (text.includes('Error') || text.includes('Exception')) {
            color = '#ff4444';
        } else if (text.startsWith('out:')) {
            color = 'var(--vscode-foreground)';
            text = text.substring(4);
        } else if (text.startsWith('\nout:')) {
            color = 'var(--vscode-foreground)';
            text = text.substring(5);
        } else if (text.includes('来自插件:')) {
            text = text.replace('来自插件:', '');
            color = '#4488ff';
        } else {
            color = '#888888';
        }

        this._view.webview.postMessage({
            command: 'appendLog',
            text: text,
            color: color,
        });
    }

    private getHtmlContent(): string {
        return /* html */ `<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: 'Consolas', 'Courier New', monospace;
            font-size: 13px;
            background: var(--vscode-editor-background);
            color: var(--vscode-foreground);
            overflow: hidden;
            height: 100vh;
            display: flex;
            flex-direction: column;
        }
        .toolbar {
            display: flex;
            gap: 6px;
            padding: 4px 8px;
            background: var(--vscode-titleBar-activeBackground);
            border-bottom: 1px solid var(--vscode-panel-border);
            flex-shrink: 0;
        }
        .toolbar button {
            background: var(--vscode-button-secondaryBackground);
            color: var(--vscode-button-secondaryForeground);
            border: none;
            padding: 2px 10px;
            cursor: pointer;
            border-radius: 3px;
            font-size: 12px;
        }
        .toolbar button:hover {
            background: var(--vscode-button-secondaryHoverBackground);
        }
        #logContainer {
            flex: 1;
            overflow-y: auto;
            padding: 6px 10px;
            white-space: pre-wrap;
            word-break: break-all;
            line-height: 1.5;
        }
        .log-line {
            margin: 0;
        }
    </style>
</head>
<body>
    <div class="toolbar">
        <button id="btnClear">清空日志</button>
        <button id="btnCopy">复制全部</button>
        <button id="btnBottom">滚到底部</button>
    </div>
    <div id="logContainer"></div>

    <script>
        const vscode = acquireVsCodeApi();
        const logContainer = document.getElementById('logContainer');
        let autoScroll = true;
        let lineCount = 0;
        const MAX_LINES = 5000;

        window.addEventListener('message', (event) => {
            const msg = event.data;
            if (msg.command === 'appendLog') {
                appendLog(msg.text, msg.color);
            }
        });

        function appendLog(text, color) {
            const span = document.createElement('span');
            span.className = 'log-line';
            span.style.color = color;
            span.textContent = text;
            logContainer.appendChild(span);
            lineCount++;

            // 限制最大行数
            if (lineCount > MAX_LINES) {
                const toRemove = lineCount - MAX_LINES;
                for (let i = 0; i < toRemove; i++) {
                    if (logContainer.firstChild) {
                        logContainer.removeChild(logContainer.firstChild);
                    }
                }
                lineCount = MAX_LINES;
            }

            if (autoScroll) {
                logContainer.scrollTop = logContainer.scrollHeight;
            }
        }

        document.getElementById('btnClear').addEventListener('click', () => {
            logContainer.innerHTML = '';
            lineCount = 0;
        });

        document.getElementById('btnCopy').addEventListener('click', () => {
            const text = logContainer.textContent;
            navigator.clipboard.writeText(text || '');
        });

        document.getElementById('btnBottom').addEventListener('click', () => {
            autoScroll = true;
            logContainer.scrollTop = logContainer.scrollHeight;
        });

        logContainer.addEventListener('scroll', () => {
            const isAtBottom = logContainer.scrollHeight - logContainer.scrollTop - logContainer.clientHeight < 50;
            autoScroll = isAtBottom;
        });
    </script>
</body>
</html>`;
    }
}
