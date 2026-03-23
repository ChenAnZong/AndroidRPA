import type { Monaco } from '@monaco-editor/react';
import type * as monacoNs from 'monaco-editor';
import { YYDS_API_DATA, type YydsApiEntry } from './yydsApiData';

// Known class names for ClassName.method resolution
const CLASS_NAMES = ['DeviceScreen', 'Config', 'Node', 'EngineDebug', 'ProjectEnvironment', 'Color', 'console'];

// High-priority APIs for sort ordering
const HIGH_PRIORITY = new Set([
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
]);

function getSortPrefix(api: YydsApiEntry): string {
  if (HIGH_PRIORITY.has(api.name)) return '0';
  if (api.kind === 'class') return '1';
  if (api.kind === 'function') return '2';
  return '3';
}

/**
 * Register Yyds Python API completions, hover, and signature help into Monaco.
 * Ported from VSCode extension with full context-aware intelligence.
 */
export function setupYydsLanguage(monaco: Monaco) {
  // ── Completion Provider ──
  monaco.languages.registerCompletionItemProvider('python', {
    triggerCharacters: ['.', '_', ' '],
    provideCompletionItems: (model: monacoNs.editor.ITextModel, position: monacoNs.Position) => {
      const word = model.getWordUntilPosition(position);
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: word.startColumn,
        endColumn: word.endColumn,
      };

      const lineContent = model.getLineContent(position.lineNumber);
      const linePrefix = lineContent.substring(0, position.column - 1);
      const fullText = model.getValue();

      // ── "from yyds import ..." completions ──
      const importMatch = linePrefix.match(/from\s+yyds(?:\.\w+)?\s+import\s+([\w,\s]*)$/);
      if (importMatch) {
        const imported = new Set(importMatch[1].split(',').map(s => s.trim()).filter(Boolean));
        const items: ReturnType<typeof buildCompletionItem>[] = [];
        if (imported.size === 0) {
          items.push({
            label: '*',
            kind: monaco.languages.CompletionItemKind.Keyword,
            detail: 'Import all APIs',
            documentation: { value: '`from yyds import *`\n\nImport all yyds SDK APIs into current namespace' },
            insertText: '*',
            insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
            range,
            sortText: '0',
          });
        }
        const seen = new Set<string>();
        for (const api of YYDS_API_DATA) {
          if (api.module !== 'yyds') continue;
          const name = api.name.includes('.') ? api.name.split('.')[0] : api.name;
          if (seen.has(name) || imported.has(name)) continue;
          seen.add(name);
          items.push({
            label: name,
            kind: api.kind === 'class' ? monaco.languages.CompletionItemKind.Class : monaco.languages.CompletionItemKind.Function,
            detail: api.signature || api.name,
            documentation: { value: api.doc },
            insertText: name,
            insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
            range,
            sortText: getSortPrefix(api) + name,
          });
        }
        return { suggestions: items };
      }

      // ── "from yyds" hint ──
      if (linePrefix.match(/from\s+yyds\s*$/)) {
        return {
          suggestions: [
            {
              label: 'import *',
              kind: monaco.languages.CompletionItemKind.Keyword,
              detail: 'from yyds import *',
              documentation: { value: 'Import all yyds SDK APIs into current namespace' },
              insertText: 'import *',
              range,
              sortText: '0',
            },
            {
              label: 'console_shim',
              kind: monaco.languages.CompletionItemKind.Module,
              detail: 'from yyds.console_shim import console',
              documentation: { value: 'Import floating log console' },
              insertText: '.console_shim import console',
              range,
              sortText: '1',
            },
          ],
        };
      }

      // Require yyds import for subsequent completions
      if (!fullText.includes('from yyds') && !fullText.includes('import yyds')) {
        return { suggestions: [] };
      }

      // ── ClassName.method completions ──
      const classRegex = new RegExp(`(${CLASS_NAMES.join('|')})\\.\\w*$`);
      const dotMatch = linePrefix.match(classRegex);
      if (dotMatch) {
        const className = dotMatch[1];
        const suggestions = YYDS_API_DATA
          .filter(api => api.name.startsWith(className + '.'))
          .map(api => buildCompletionItem(monaco, api, true, range));
        return { suggestions };
      }

      // ── variable.method completions (Node/Color instance) ──
      const varDotMatch = linePrefix.match(/\w+(?:\[\d+\])?\.\w*$/);
      if (varDotMatch) {
        const suggestions = YYDS_API_DATA
          .filter(api => api.name.startsWith('Node.') || api.name.startsWith('Color.'))
          .map(api => buildCompletionItem(monaco, api, true, range));
        return { suggestions };
      }

      // ── Normal completions: all top-level functions, classes, decorators ──
      const suggestions = YYDS_API_DATA
        .filter(api => !api.name.includes('.'))
        .map(api => buildCompletionItem(monaco, api, false, range));
      return { suggestions };
    },
  });

  // ── Hover Provider (context-aware: ClassName.method, variable instance fallback) ──
  monaco.languages.registerHoverProvider('python', {
    provideHover: (model: monacoNs.editor.ITextModel, position: monacoNs.Position) => {
      const fullText = model.getValue();
      if (!fullText.includes('from yyds') && !fullText.includes('import yyds')) return null;

      // Use extended word pattern to match dotted names like "Config.read_config_value"
      const wordInfo = model.getWordAtPosition(position);
      if (!wordInfo) return null;

      const lineContent = model.getLineContent(position.lineNumber);

      // Try to build a dotted identifier: check if there's a dot before the word
      let fullWord = wordInfo.word;
      const charBefore = wordInfo.startColumn - 2; // 0-indexed
      if (charBefore >= 0 && lineContent[charBefore] === '.') {
        // Extract the part before the dot
        const beforeDot = lineContent.substring(0, charBefore);
        const objMatch = beforeDot.match(/([\w]+)(?:\[\d+\])?$/);
        if (objMatch) {
          fullWord = objMatch[1] + '.' + wordInfo.word;
        }
      }

      // Also check if the word itself is a class name and there's a dot + method after
      const afterWord = lineContent.substring(wordInfo.endColumn - 1);
      const afterDotMatch = afterWord.match(/^\.(\w+)/);
      if (afterDotMatch && CLASS_NAMES.includes(wordInfo.word)) {
        fullWord = wordInfo.word + '.' + afterDotMatch[1];
      }

      // Resolution chain (same as VSCode extension):
      // 1. Exact match (e.g. "DeviceScreen.init", "console.log")
      let api = YYDS_API_DATA.find(a => a.name === fullWord);

      // 2. Simple word match (e.g. "click", "ui_match")
      if (!api) {
        api = YYDS_API_DATA.find(a => a.name === wordInfo.word && !a.name.includes('.'));
      }

      // 3. Class name match
      if (!api) {
        api = YYDS_API_DATA.find(a => a.name === wordInfo.word && a.kind === 'class');
      }

      // 4. obj.method fallback: try known classes for the method name
      if (!api && charBefore >= 0 && lineContent[charBefore] === '.') {
        const methodName = wordInfo.word;
        // Try each known class
        for (const cls of CLASS_NAMES) {
          api = YYDS_API_DATA.find(a => a.name === `${cls}.${methodName}`);
          if (api) break;
        }
        // Fallback chain: Node → Color → console
        if (!api) api = YYDS_API_DATA.find(a => a.name === `Node.${methodName}`);
        if (!api) api = YYDS_API_DATA.find(a => a.name === `Color.${methodName}`);
        if (!api) api = YYDS_API_DATA.find(a => a.name === `console.${methodName}`);
      }

      if (!api) return null;

      const contents: { value: string }[] = [];
      if (api.signature) {
        contents.push({ value: `\`\`\`python\n${api.signature}\n\`\`\`` });
      } else {
        contents.push({ value: `\`\`\`python\n${api.name}\n\`\`\`` });
      }
      contents.push({ value: api.doc });
      if (api.params && api.params.length > 0) {
        contents.push({ value: '**Parameters:**' });
        for (const p of api.params) {
          const defaultStr = p.default ? ` = ${p.default}` : '';
          contents.push({ value: `- \`${p.name}\` *${p.type}${defaultStr}* — ${p.doc}` });
        }
      }
      if (api.returnType) {
        contents.push({ value: `**Returns:** \`${api.returnType}\`` });
      }
      if (api.detail) {
        contents.push({ value: api.detail });
      }
      contents.push({ value: `*From yyds SDK (${api.module})*` });

      return {
        range: {
          startLineNumber: position.lineNumber,
          endLineNumber: position.lineNumber,
          startColumn: wordInfo.startColumn,
          endColumn: wordInfo.endColumn,
        },
        contents: contents.filter(c => c.value),
      };
    },
  });

  // ── Signature Help Provider (multi-line, nested paren/string aware) ──
  monaco.languages.registerSignatureHelpProvider('python', {
    signatureHelpTriggerCharacters: ['(', ','],
    signatureHelpRetriggerCharacters: [','],
    provideSignatureHelp: (model: monacoNs.editor.ITextModel, position: monacoNs.Position) => {
      const fullText = model.getValue();
      if (!fullText.includes('from yyds') && !fullText.includes('import yyds')) return null;

      // Collect text from up to 10 lines before cursor (multi-line support)
      const startLine = Math.max(1, position.lineNumber - 10);
      const lines: string[] = [];
      for (let i = startLine; i < position.lineNumber; i++) {
        lines.push(model.getLineContent(i));
      }
      lines.push(model.getLineContent(position.lineNumber).substring(0, position.column - 1));
      const textBefore = lines.join('\n');

      // Find the unclosed opening paren from the end
      const callInfo = findFunctionCall(textBefore);
      if (!callInfo) return null;

      // Resolve API: exact match → ClassName.method → any class method → top-level
      const api = resolveApiForSignature(callInfo.funcName);
      if (!api || !api.params || api.params.length === 0) return null;

      return {
        value: {
          signatures: [{
            label: api.signature || api.name,
            documentation: { value: api.doc },
            parameters: api.params.map(p => {
              const defaultStr = p.default ? ` = ${p.default}` : '';
              return {
                label: p.name,
                documentation: { value: `*${p.type}${defaultStr}* — ${p.doc}` },
              };
            }),
          }],
          activeSignature: 0,
          activeParameter: Math.min(callInfo.paramIndex, api.params.length - 1),
        },
        dispose: () => {},
      };
    },
  });
}

// ── Helper: find unclosed function call with nested paren/string awareness ──
function findFunctionCall(textBefore: string): { funcName: string; paramIndex: number } | null {
  let depth = 0;
  let parenPos = -1;

  // Scan backwards for unclosed '('
  for (let i = textBefore.length - 1; i >= 0; i--) {
    const ch = textBefore[i];
    if (ch === ')') depth++;
    else if (ch === '(') {
      if (depth === 0) { parenPos = i; break; }
      depth--;
    }
  }
  if (parenPos < 0) return null;

  // Extract function name (supports ClassName.method_name across lines)
  const beforeParen = textBefore.substring(0, parenPos).trimEnd();
  const funcMatch = beforeParen.match(/([\w.]+)\s*$/);
  if (!funcMatch) return null;

  // Count active parameter (commas at depth 0, ignoring strings and nested brackets)
  const argsText = textBefore.substring(parenPos + 1);
  let paramIndex = 0;
  let nestDepth = 0;
  let inString = false;
  let stringChar = '';

  for (let i = 0; i < argsText.length; i++) {
    const ch = argsText[i];
    if (inString) {
      if (ch === '\\') { i++; continue; }
      if (ch === stringChar) {
        // Check triple-quote end
        if (argsText.substring(i, i + 3) === stringChar.repeat(3)) {
          i += 2;
        }
        inString = false;
      }
      continue;
    }
    if (ch === '"' || ch === "'") {
      if (argsText.substring(i, i + 3) === ch.repeat(3)) {
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

// ── Helper: resolve API for signature help ──
function resolveApiForSignature(funcName: string): YydsApiEntry | undefined {
  const hasParams = (a: YydsApiEntry) => a.params && a.params.length > 0;
  const isCallable = (a: YydsApiEntry) =>
    a.kind === 'function' || a.kind === 'method' || a.kind === 'decorator' || a.kind === 'class';

  // 1. Exact match
  let api = YYDS_API_DATA.find(a => a.name === funcName && isCallable(a) && hasParams(a));
  if (api) return api;

  // 2. obj.method → try matching method name across all known classes
  if (funcName.includes('.')) {
    const methodName = funcName.split('.').pop()!;
    api = YYDS_API_DATA.find(a =>
      a.name.endsWith('.' + methodName) && (a.kind === 'method' || a.kind === 'function') && hasParams(a)
    );
    if (api) return api;
  }

  // 3. Top-level function
  api = YYDS_API_DATA.find(a =>
    a.name === funcName && !a.name.includes('.') &&
    (a.kind === 'function' || a.kind === 'decorator' || a.kind === 'class') && hasParams(a)
  );
  return api;
}

// ── Helper: build completion item ──
function buildCompletionItem(
  monaco: Monaco,
  api: YydsApiEntry,
  isMethod: boolean,
  range: { startLineNumber: number; endLineNumber: number; startColumn: number; endColumn: number },
) {
  const label = isMethod ? api.name.split('.').pop()! : api.name;

  let kind: number;
  switch (api.kind) {
    case 'class': kind = monaco.languages.CompletionItemKind.Class; break;
    case 'method': kind = monaco.languages.CompletionItemKind.Method; break;
    case 'property': kind = monaco.languages.CompletionItemKind.Property; break;
    case 'decorator': kind = monaco.languages.CompletionItemKind.Snippet; break;
    default: kind = monaco.languages.CompletionItemKind.Function;
  }

  // Build snippet insert text
  let insertText: string;
  if (api.kind === 'property') {
    insertText = label;
  } else if (api.kind === 'decorator') {
    insertText = api.params && api.params.length > 0 ? `${label}($1)` : label;
  } else if (!api.params || api.params.length === 0) {
    insertText = (api.kind === 'function' || api.kind === 'method') ? `${label}()` : label;
  } else {
    const required = api.params.filter(p => !p.default && !p.name.startsWith('*'));
    if (required.length === 0) {
      insertText = `${label}($0)`;
    } else {
      const placeholders = required.map((p, i) => `\${${i + 1}:${p.name}}`).join(', ');
      insertText = `${label}(${placeholders})`;
    }
  }

  // Build documentation
  const docParts: string[] = [];
  if (api.signature) docParts.push(`\`\`\`python\n${api.signature}\n\`\`\``);
  docParts.push(api.doc);
  if (api.params && api.params.length > 0) {
    docParts.push('');
    for (const p of api.params) {
      docParts.push(`- **${p.name}** *(${p.type})*: ${p.doc}${p.default ? ` [default: ${p.default}]` : ''}`);
    }
  }
  if (api.returnType) docParts.push(`\n**Returns:** \`${api.returnType}\``);
  if (api.detail) docParts.push(`\n${api.detail}`);

  return {
    label,
    kind,
    detail: api.signature || api.name,
    documentation: { value: docParts.join('\n') },
    insertText,
    insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
    range,
    sortText: getSortPrefix(api) + label,
  };
}
