/**
 * Yyds.Auto Python API 函数签名提示提供器
 * 在输入函数参数时显示参数签名和文档
 */

import * as vscode from 'vscode';
import { YYDS_API_DATA, YydsApiEntry } from './yydsApiData';

export class YydsSignatureHelpProvider implements vscode.SignatureHelpProvider {

    provideSignatureHelp(
        document: vscode.TextDocument,
        position: vscode.Position,
        _token: vscode.CancellationToken,
        _context: vscode.SignatureHelpContext,
    ): vscode.SignatureHelp | undefined {
        if (document.languageId !== 'python') { return undefined; }

        const text = document.getText();
        if (!text.includes('from yyds') && !text.includes('import yyds')) {
            return undefined;
        }

        // 收集从当前行到往上最多 10 行的文本 (支持多行函数调用)
        const textBefore = this.getTextBeforeCursor(document, position, 10);
        const callInfo = this.findFunctionCall(textBefore);
        if (!callInfo) { return undefined; }

        // 查找 API: 精确匹配 > ClassName.method > 顶层函数名
        const api = this.resolveApi(callInfo.funcName);
        if (!api || !api.params) { return undefined; }

        const sigHelp = new vscode.SignatureHelp();
        const sigInfo = new vscode.SignatureInformation(
            api.signature || api.name,
            new vscode.MarkdownString(api.doc)
        );

        for (const p of api.params) {
            const defaultStr = p.default ? ` = ${p.default}` : '';
            const paramDoc = new vscode.MarkdownString(
                `*${p.type}${defaultStr}* — ${p.doc}`
            );
            sigInfo.parameters.push(
                new vscode.ParameterInformation(p.name, paramDoc)
            );
        }

        sigHelp.signatures = [sigInfo];
        sigHelp.activeSignature = 0;
        sigHelp.activeParameter = callInfo.paramIndex;

        return sigHelp;
    }

    private resolveApi(funcName: string): YydsApiEntry | undefined {
        // 精确匹配 (如 "DeviceScreen.init", "console.log")
        let api = YYDS_API_DATA.find(a =>
            a.name === funcName &&
            (a.kind === 'function' || a.kind === 'method' || a.kind === 'decorator' || a.kind === 'class') &&
            a.params && a.params.length > 0
        );
        if (api) { return api; }

        // 如果是 obj.method 形式, 尝试匹配所有已知类
        if (funcName.includes('.')) {
            const parts = funcName.split('.');
            const methodName = parts[parts.length - 1];
            // 对于未知的变量名 (如 nodes[0].click), 用方法名在所有类中查找
            api = YYDS_API_DATA.find(a =>
                a.name.endsWith('.' + methodName) &&
                (a.kind === 'method' || a.kind === 'function') &&
                a.params && a.params.length > 0
            );
            if (api) { return api; }
        }

        // 顶层函数匹配
        api = YYDS_API_DATA.find(a =>
            a.name === funcName && !a.name.includes('.') &&
            (a.kind === 'function' || a.kind === 'decorator' || a.kind === 'class') &&
            a.params && a.params.length > 0
        );
        return api;
    }

    private getTextBeforeCursor(document: vscode.TextDocument, position: vscode.Position, maxLines: number): string {
        const startLine = Math.max(0, position.line - maxLines);
        const lines: string[] = [];
        for (let i = startLine; i < position.line; i++) {
            lines.push(document.lineAt(i).text);
        }
        lines.push(document.lineAt(position).text.substring(0, position.character));
        return lines.join('\n');
    }

    private findFunctionCall(textBefore: string): { funcName: string; paramIndex: number } | undefined {
        // 从末尾往前找未闭合的 (
        let depth = 0;
        let parenPos = -1;

        for (let i = textBefore.length - 1; i >= 0; i--) {
            const ch = textBefore[i];
            if (ch === ')') { depth++; }
            else if (ch === '(') {
                if (depth === 0) {
                    parenPos = i;
                    break;
                }
                depth--;
            }
        }

        if (parenPos < 0) { return undefined; }

        // 提取函数名 (支持 ClassName.method_name 格式, 跨行也行)
        const beforeParen = textBefore.substring(0, parenPos).trimEnd();
        const funcMatch = beforeParen.match(/([\w.]+)\s*$/);
        if (!funcMatch) { return undefined; }

        // 计算当前参数索引 (数逗号, 忽略字符串和嵌套括号内的逗号)
        const argsText = textBefore.substring(parenPos + 1);
        let paramIndex = 0;
        let nestDepth = 0;
        let inString = false;
        let stringChar = '';

        for (let i = 0; i < argsText.length; i++) {
            const ch = argsText[i];
            if (inString) {
                if (ch === '\\') { i++; continue; } // 跳过转义
                if (ch === stringChar) { inString = false; }
                continue;
            }
            if (ch === '"' || ch === "'") {
                // 检查三引号
                if (argsText.substring(i, i + 3) === ch.repeat(3)) {
                    // 跳过三引号字符串 (简化处理: 不深入解析)
                    inString = true;
                    stringChar = ch;
                    i += 2;
                } else {
                    inString = true;
                    stringChar = ch;
                }
            } else if (ch === '(' || ch === '[' || ch === '{') {
                nestDepth++;
            } else if (ch === ')' || ch === ']' || ch === '}') {
                nestDepth--;
            } else if (ch === ',' && nestDepth === 0) {
                paramIndex++;
            }
        }

        return { funcName: funcMatch[1], paramIndex };
    }
}
