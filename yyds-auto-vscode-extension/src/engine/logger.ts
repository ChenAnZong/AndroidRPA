/**
 * 统一日志管理
 * 所有通讯层的日志输出到 VS Code OutputChannel "Yyds.Auto Debug"
 * 方便用户排查连接/通讯问题
 */

import * as vscode from 'vscode';

let outputChannel: vscode.OutputChannel | undefined;

export function initLogger(): vscode.OutputChannel {
    if (!outputChannel) {
        outputChannel = vscode.window.createOutputChannel('Yyds.Auto Debug');
    }
    return outputChannel;
}

export function log(tag: string, msg: string): void {
    const ts = new Date().toLocaleTimeString();
    const line = `[${ts}] [${tag}] ${msg}`;
    outputChannel?.appendLine(line);
    console.log(line);
}

export function logError(tag: string, msg: string, err?: any): void {
    const ts = new Date().toLocaleTimeString();
    const errMsg = err ? ` | ${err?.message || err}` : '';
    const line = `[${ts}] [${tag}] ERROR: ${msg}${errMsg}`;
    outputChannel?.appendLine(line);
    console.error(line);
}

export function showDebugChannel(): void {
    outputChannel?.show(true);
}

export function getChannel(): vscode.OutputChannel | undefined {
    return outputChannel;
}
