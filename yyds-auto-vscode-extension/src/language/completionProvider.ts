/**
 * Yyds.Auto Python API 自动补全提供器
 * 在 Python 文件中输入 yyds API 函数名时提供智能补全
 */

import * as vscode from 'vscode';
import { YYDS_API_DATA, YydsApiEntry } from './yydsApiData';

// 支持 ClassName.method 补全的类名集合
const CLASS_NAMES = ['DeviceScreen', 'Config', 'Node', 'EngineDebug', 'ProjectEnvironment', 'Color', 'console'];

export class YydsCompletionProvider implements vscode.CompletionItemProvider {

    provideCompletionItems(
        document: vscode.TextDocument,
        position: vscode.Position,
        _token: vscode.CancellationToken,
        _context: vscode.CompletionContext,
    ): vscode.CompletionItem[] {
        if (document.languageId !== 'python') { return []; }

        const lineText = document.lineAt(position).text;
        const linePrefix = lineText.substring(0, position.character);

        // ── import 补全: "from yyds import ..." ──
        const importMatch = linePrefix.match(/from\s+yyds(?:\.\w+)?\s+import\s+([\w,\s]*)$/);
        if (importMatch) {
            return this.buildImportCompletions(importMatch[1]);
        }

        // ── "from yyds" 提示 ──
        if (linePrefix.match(/from\s+yyds\s*$/)) {
            const item = new vscode.CompletionItem('import *', vscode.CompletionItemKind.Keyword);
            item.insertText = new vscode.SnippetString('import *');
            item.detail = 'from yyds import *';
            item.documentation = new vscode.MarkdownString('导入所有 yyds SDK API 到当前命名空间');
            item.sortText = '0';
            const itemConsole = new vscode.CompletionItem('console_shim', vscode.CompletionItemKind.Module);
            itemConsole.insertText = new vscode.SnippetString('.console_shim import console');
            itemConsole.detail = 'from yyds.console_shim import console';
            itemConsole.documentation = new vscode.MarkdownString('导入悬浮日志控制台');
            itemConsole.sortText = '1';
            return [item, itemConsole];
        }

        // 后续补全需要已导入 yyds
        const text = document.getText();
        if (!text.includes('from yyds') && !text.includes('import yyds')) {
            return [];
        }

        // ── ClassName.method 补全 ──
        const dotMatch = linePrefix.match(new RegExp(`(${CLASS_NAMES.join('|')})\\.\\w*$`));
        if (dotMatch) {
            const className = dotMatch[1];
            return YYDS_API_DATA
                .filter(api => api.name.startsWith(className + '.'))
                .map(api => this.createCompletionItem(api, true));
        }

        // ── 变量.method 补全 (可能是 Node / Color 实例) ──
        const varDotMatch = linePrefix.match(/\w+(?:\[\d+\])?\.\w*$/);
        if (varDotMatch) {
            const nodeItems = YYDS_API_DATA
                .filter(api => api.name.startsWith('Node.') || api.name.startsWith('Color.'))
                .map(api => this.createCompletionItem(api, true));
            return nodeItems;
        }

        // ── 正常补全: 所有顶层函数、类、装饰器 ──
        return YYDS_API_DATA
            .filter(api => !api.name.includes('.'))
            .map(api => this.createCompletionItem(api, false));
    }

    private buildImportCompletions(alreadyTyped: string): vscode.CompletionItem[] {
        // 已输入的名字 (逗号分割)
        const imported = new Set(alreadyTyped.split(',').map(s => s.trim()).filter(Boolean));

        const items: vscode.CompletionItem[] = [];

        // 通配符导入
        if (imported.size === 0) {
            const star = new vscode.CompletionItem('*', vscode.CompletionItemKind.Keyword);
            star.detail = '导入所有 API';
            star.sortText = '0';
            items.push(star);
        }

        // 所有顶层名称
        const seen = new Set<string>();
        for (const api of YYDS_API_DATA) {
            if (api.module !== 'yyds') { continue; }
            const name = api.name.includes('.') ? api.name.split('.')[0] : api.name;
            if (seen.has(name) || imported.has(name)) { continue; }
            seen.add(name);

            const item = new vscode.CompletionItem(name, api.kind === 'class' ? vscode.CompletionItemKind.Class : vscode.CompletionItemKind.Function);
            item.detail = api.signature || api.name;
            item.documentation = new vscode.MarkdownString(api.doc);
            item.sortText = this.getSortPrefix(api) + name;
            items.push(item);
        }

        return items;
    }

    private createCompletionItem(api: YydsApiEntry, isMethod: boolean): vscode.CompletionItem {
        const label = isMethod ? api.name.split('.').pop()! : api.name;
        let kind: vscode.CompletionItemKind;

        switch (api.kind) {
            case 'class':
                kind = vscode.CompletionItemKind.Class;
                break;
            case 'method':
                kind = vscode.CompletionItemKind.Method;
                break;
            case 'property':
                kind = vscode.CompletionItemKind.Property;
                break;
            case 'decorator':
                kind = vscode.CompletionItemKind.Snippet;
                break;
            default:
                kind = vscode.CompletionItemKind.Function;
        }

        const item = new vscode.CompletionItem(label, kind);

        // 文档
        const md = new vscode.MarkdownString();
        if (api.signature) {
            md.appendCodeblock(api.signature, 'python');
        }
        md.appendMarkdown(api.doc);
        if (api.returnType) {
            md.appendMarkdown(`\n\n**返回值:** \`${api.returnType}\``);
        }
        if (api.detail) {
            md.appendMarkdown(`\n\n${api.detail}`);
        }
        item.documentation = md;

        // detail 行 (显示在补全列表右侧)
        item.detail = api.signature || api.name;

        // 插入代码片段 (自动加括号和参数占位)
        if (api.kind === 'function' || api.kind === 'method') {
            const snippet = this.buildSnippet(api, isMethod);
            item.insertText = new vscode.SnippetString(snippet);
        } else if (api.kind === 'decorator') {
            if (api.params && api.params.length > 0) {
                item.insertText = new vscode.SnippetString(`${label}($1)`);
            } else {
                item.insertText = new vscode.SnippetString(label);
            }
        } else if (api.kind === 'property') {
            // 属性不加括号
            item.insertText = label;
        }

        // 排序优先级
        item.sortText = this.getSortPrefix(api) + label;

        return item;
    }

    private buildSnippet(api: YydsApiEntry, isMethod: boolean): string {
        const funcName = isMethod ? api.name.split('.').pop()! : api.name;

        if (!api.params || api.params.length === 0) {
            return `${funcName}()`;
        }

        // 只为必填参数生成占位符
        const requiredParams = api.params.filter(p =>
            !p.default && !p.name.startsWith('*') && !p.name.startsWith('**')
        );

        if (requiredParams.length === 0) {
            return `${funcName}($0)`;
        }

        const placeholders = requiredParams.map((p, i) =>
            `\${${i + 1}:${p.name}}`
        ).join(', ');

        return `${funcName}(${placeholders})`;
    }

    private getSortPrefix(api: YydsApiEntry): string {
        const highPriority = [
            'click', 'swipe', 'sleep', 'log_d', 'toast',
            'wait_and_click_text', 'wait_and_click_ui',
            'wait_for_text', 'wait_for_ui', 'long_press',
            'ocr_click_any', 'ocr_click_if_found',
            'screen_find_image_x', 'find_image_click',
            'ui_match', 'key_back', 'key_home',
            'swipe_to_find_text', 'wait_screen_stable',
            'gesture', 'click_scaled', 'scale_pos',
            'DeviceScreen', 'Config', 'Node',
            'format_time', 'ui_dump_xml', 'screenshot',
            'Point', 'Color', 'ProjectEnvironment',
        ];
        if (highPriority.includes(api.name)) { return '0'; }
        if (api.kind === 'class') { return '1'; }
        if (api.kind === 'function') { return '2'; }
        return '3';
    }
}
