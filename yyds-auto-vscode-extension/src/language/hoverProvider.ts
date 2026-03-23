/**
 * Yyds.Auto Python API 悬停文档提供器
 * 鼠标悬停在 yyds API 函数名上时显示文档
 */

import * as vscode from 'vscode';
import { YYDS_API_DATA, YydsApiEntry } from './yydsApiData';

export class YydsHoverProvider implements vscode.HoverProvider {

    provideHover(
        document: vscode.TextDocument,
        position: vscode.Position,
        _token: vscode.CancellationToken,
    ): vscode.Hover | undefined {
        if (document.languageId !== 'python') { return undefined; }

        const text = document.getText();
        if (!text.includes('from yyds') && !text.includes('import yyds')) {
            return undefined;
        }

        const wordRange = document.getWordRangeAtPosition(position, /[\w.]+/);
        if (!wordRange) { return undefined; }

        const word = document.getText(wordRange);

        // 尝试精确匹配 (如 "DeviceScreen.init", "Config.read_config_value", "Node.click")
        let api = YYDS_API_DATA.find(a => a.name === word);

        // 尝试匹配单词 (如 "click", "ui_match")
        if (!api) {
            api = YYDS_API_DATA.find(a => a.name === word && !a.name.includes('.'));
        }

        // 如果悬停在类名上面, 匹配类
        if (!api) {
            api = YYDS_API_DATA.find(a => a.name === word && a.kind === 'class');
        }

        // 检查是否在 obj.method 模式中悬停在 method 上
        if (!api) {
            const lineText = document.lineAt(position).text;
            const charBefore = wordRange.start.character;
            if (charBefore > 0 && lineText[charBefore - 1] === '.') {
                // 提取 "." 前面的对象名
                const beforeDot = lineText.substring(0, charBefore - 1);
                const objMatch = beforeDot.match(/([\w]+)(?:\[\d+\])?$/);
                const objName = objMatch ? objMatch[1] : '';

                // 优先精确匹配已知类名
                const classNames = ['DeviceScreen', 'Config', 'Node', 'EngineDebug', 'ProjectEnvironment', 'Color', 'console'];
                const matchedClass = classNames.find(c => c === objName);
                if (matchedClass) {
                    api = YYDS_API_DATA.find(a => a.name === `${matchedClass}.${word}`);
                }

                // 回退: 尝试 Node.method (变量实例)
                if (!api) {
                    api = YYDS_API_DATA.find(a => a.name === `Node.${word}`);
                }
                // 回退: 尝试 Color.method
                if (!api) {
                    api = YYDS_API_DATA.find(a => a.name === `Color.${word}`);
                }
                // 回退: 尝试 console.method
                if (!api) {
                    api = YYDS_API_DATA.find(a => a.name === `console.${word}`);
                }
            }
        }

        if (!api) { return undefined; }

        const md = this.buildMarkdown(api);
        return new vscode.Hover(md, wordRange);
    }

    private buildMarkdown(api: YydsApiEntry): vscode.MarkdownString {
        const md = new vscode.MarkdownString();
        md.isTrusted = true;

        // 签名
        if (api.signature) {
            md.appendCodeblock(api.signature, 'python');
        } else {
            md.appendCodeblock(api.name, 'python');
        }

        // 描述
        md.appendMarkdown(`${api.doc}\n\n`);

        // 参数表
        if (api.params && api.params.length > 0) {
            md.appendMarkdown('**参数:**\n\n');
            for (const p of api.params) {
                const defaultStr = p.default ? ` = ${p.default}` : '';
                md.appendMarkdown(`- \`${p.name}\` *${p.type}${defaultStr}* — ${p.doc}\n`);
            }
            md.appendMarkdown('\n');
        }

        // 返回值
        if (api.returnType) {
            md.appendMarkdown(`**返回值:** \`${api.returnType}\`\n\n`);
        }

        // 额外说明
        if (api.detail) {
            md.appendMarkdown(`${api.detail}\n`);
        }

        md.appendMarkdown(`\n*来自 yyds SDK (${api.module})*`);

        return md;
    }
}
