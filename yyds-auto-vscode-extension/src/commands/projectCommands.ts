/**
 * 项目命令 — 移植自 ProjectActions.java
 * 注册所有 VS Code 命令（推送、运行、停止、打包等）
 */

import * as vscode from 'vscode';
import { ProjectServer } from '../engine/projectServer';

export function registerCommands(
    context: vscode.ExtensionContext,
    getServer: () => ProjectServer | undefined
): void {

    // 推送并运行
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.pushAndRun', async () => {
            const server = getServer();
            if (!server) { return showNoProject(); }

            await vscode.window.withProgress({
                location: vscode.ProgressLocation.Notification,
                title: 'Yyds.Auto: 推送并运行工程...',
                cancellable: false,
            }, async () => {
                const pushOk = await server.sendProject();
                if (pushOk) {
                    await server.startProject();
                }
            });
        })
    );

    // 运行工程
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.run', async () => {
            const server = getServer();
            if (!server) { return showNoProject(); }
            await server.startProject();
        })
    );

    // 停止工程
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.stop', async () => {
            const server = getServer();
            if (!server) { return showNoProject(); }
            await server.stopProject();
        })
    );

    // 推送工程
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.push', async () => {
            const server = getServer();
            if (!server) { return showNoProject(); }

            await vscode.window.withProgress({
                location: vscode.ProgressLocation.Notification,
                title: 'Yyds.Auto: 推送工程到设备...',
                cancellable: false,
            }, async () => {
                await server.sendProject();
            });
        })
    );

    // 打包工程
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.zip', async () => {
            const server = getServer();
            if (!server) { return showNoProject(); }
            await server.zipProject();
        })
    );

    // 设备重连
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.reconnect', async () => {
            const server = getServer();
            if (!server) { return showNoProject(); }
            server.reConnect();
            vscode.window.showInformationMessage('Yyds.Auto: 正在重新连接设备...');
        })
    );

    // 断开连接
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.disconnect', async () => {
            const server = getServer();
            if (!server) { return showNoProject(); }
            server.disConnect();
            vscode.window.showInformationMessage('Yyds.Auto: 已断开设备连接');
        })
    );

    // 运行选中代码 (无选中时运行当前行)
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.runSelectedCode', async () => {
            const server = getServer();
            if (!server) { return showNoProject(); }

            const editor = vscode.window.activeTextEditor;
            if (!editor) {
                vscode.window.showWarningMessage('没有打开的编辑器');
                return;
            }

            let code: string;
            const selection = editor.selection;

            if (selection.isEmpty) {
                // 无选中时运行当前行
                const line = editor.document.lineAt(selection.active.line);
                code = line.text;
                // 闪烁高亮当前行给用户视觉反馈
                const lineRange = line.range;
                const decoration = vscode.window.createTextEditorDecorationType({
                    backgroundColor: 'rgba(255, 213, 79, 0.3)',
                    isWholeLine: true,
                });
                editor.setDecorations(decoration, [lineRange]);
                setTimeout(() => decoration.dispose(), 500);
            } else {
                code = editor.document.getText(selection);
            }

            if (!code || code.trim().length === 0) {
                vscode.window.showWarningMessage('当前行为空，无法执行');
                return;
            }

            await server.runCodeSnippet(code);
        })
    );

    // 运行当前文件
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.runCurrentFile', async () => {
            const server = getServer();
            if (!server) { return showNoProject(); }

            const editor = vscode.window.activeTextEditor;
            if (!editor) {
                vscode.window.showWarningMessage('没有打开的编辑器');
                return;
            }

            const code = editor.document.getText();
            if (!code || code.trim().length === 0) {
                vscode.window.showWarningMessage('文件内容为空');
                return;
            }

            const fileName = editor.document.fileName.split(/[\/]/).pop() || '当前文件';
            await vscode.window.withProgress({
                location: vscode.ProgressLocation.Notification,
                title: `Yyds.Auto: 正在运行 ${fileName}...`,
                cancellable: false,
            }, async () => {
                await server.runCodeSnippet(code);
            });
        })
    );

    // 切换 OCR 模型版本
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.setOcrVersion', async () => {
            const server = getServer();
            if (!server) { return showNoProject(); }

            const pick = await vscode.window.showQuickPick([
                { label: 'v5_mobile', description: 'PP-OCRv5 Mobile（推荐，速度快精度高）' },
                { label: 'v5_server', description: 'PP-OCRv5 Server（最高精度，较慢）' },
                { label: 'v2', description: 'PaddleOCR v2.0（旧版兼容）' },
            ], { placeHolder: '选择 OCR 模型版本' });
            if (!pick) { return; }

            const res = await server.getEngine().setOcrVersion(pick.label as any);
            vscode.window.showInformationMessage(`OCR 模型切换: ${res}`);
        })
    );

}

function showNoProject(): void {
    vscode.window.showErrorMessage('Yyds.Auto: 未找到项目（需要 project.config 文件）');
}
