import { useEffect, useRef, useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import Editor, { type Monaco } from '@monaco-editor/react';
import type * as monacoNs from 'monaco-editor';
import { status } from '@/store';
import { X, Circle, RefreshCw } from 'lucide-react';
import { ideApi } from '@/services/api';
import { setupYydsLanguage } from '@/components/ide/monacoSetup';
import FlowEditorPanel from '@/components/ide/flow/FlowEditorPanel';
import UiDesignerPanel from '@/components/ide/designer/UiDesignerPanel';
import type { IdeTab } from '@/types';

interface Props {
  imei: string;
  tabs: IdeTab[];
  activeTab: string | null;
  onSelectTab: (path: string) => void;
  onCloseTab: (path: string) => void;
  onContentChange: (path: string, content: string) => void;
  onTabLoaded: (path: string, content: string) => void;
  onMarkClean: (path: string) => void;
  insertCodeRef: React.MutableRefObject<(code: string) => void>;
}

let languageSetup = false;

// Workaround: Monaco 0.55.1 crashes in _doHitTestWithCaretPositionFromPoint
// when document.caretPositionFromPoint returns null (mouse outside editor during drag).
// We wrap it to swallow the null case by returning a safe dummy, preserving normal usage.
if (typeof document.caretPositionFromPoint === 'function') {
  const _origCaretPos = document.caretPositionFromPoint.bind(document);
  (document as any).caretPositionFromPoint = function (x: number, y: number) {
    const pos = _origCaretPos(x, y);
    if (pos) return pos;
    // Return a minimal CaretPosition-like object so Monaco doesn't crash on .offsetNode
    const fallback = document.body;
    return { offsetNode: fallback, offset: 0, getClientRect: () => null } as CaretPosition;
  };
}

export default function EditorPanel({
  imei, tabs, activeTab, onSelectTab, onCloseTab,
  onContentChange, onTabLoaded, onMarkClean, insertCodeRef,
}: Props) {
  const { t } = useTranslation(['ide', 'common']);
  const editorRef = useRef<monacoNs.editor.IStandaloneCodeEditor | null>(null);
  const monacoRef = useRef<Monaco | null>(null);
  const [_saving, setSaving] = useState(false);
  const [loadingFile, setLoadingFile] = useState<string | null>(null);

  const currentTab = tabs.find((t) => t.path === activeTab);

  // Load file content when tab is opened with empty content
  useEffect(() => {
    if (!currentTab || currentTab.content !== '') { setLoadingFile(null); return; }
    let cancelled = false;
    setLoadingFile(currentTab.path);
    ideApi.readFile(imei, currentTab.path).then((res) => {
      if (cancelled) return;
      const content = typeof res.data === 'string' ? res.data : JSON.stringify(res.data ?? '', null, 2);
      onTabLoaded(currentTab.path, content);
      setLoadingFile(null);
    }).catch((e) => {
      if (!cancelled) { status.error(t('readFailed', { msg: e.message })); setLoadingFile(null); }
    });
    return () => { cancelled = true; };
  }, [imei, currentTab?.path, currentTab?.content === '']);

  // Save handler
  const saveFile = useCallback(async () => {
    if (!currentTab || !currentTab.dirty) return;
    setSaving(true);
    try {
      await ideApi.writeFile(imei, currentTab.path, currentTab.content);
      onMarkClean(currentTab.path);
      status.success(t('saved'));
    } catch (e: any) {
      status.error(t('saveFailed', { msg: e.message }));
    } finally {
      setSaving(false);
    }
  }, [imei, currentTab]);

  // Run selected code
  const runSelected = useCallback(async () => {
    const editor = editorRef.current;
    if (!editor) return;
    const selection = editor.getSelection();
    let code = '';
    if (selection && !selection.isEmpty()) {
      code = editor.getModel()?.getValueInRange(selection) || '';
    } else {
      // Run current line
      const pos = editor.getPosition();
      if (pos) {
        code = editor.getModel()?.getLineContent(pos.lineNumber) || '';
      }
    }
    if (!code.trim()) return;
    try {
      await ideApi.runCode(imei, code);
      status.success(t('codeSent'));
    } catch (e: any) {
      status.error(e.message);
    }
  }, [imei]);

  // Run entire file
  const runFile = useCallback(async () => {
    if (!currentTab) return;
    try {
      await ideApi.runCode(imei, currentTab.content);
      status.success(t('fileSent'));
    } catch (e: any) {
      status.error(e.message);
    }
  }, [imei, currentTab]);

  // Insert code snippet from DevTool
  useEffect(() => {
    insertCodeRef.current = (code: string) => {
      const editor = editorRef.current;
      if (!editor) return;
      const pos = editor.getPosition();
      if (pos) {
        editor.executeEdits('devtool', [{
          range: new (monacoRef.current!.Range)(pos.lineNumber, pos.column, pos.lineNumber, pos.column),
          text: code,
        }]);
        editor.focus();
      }
    };
  }, [insertCodeRef]);

  const handleEditorMount = useCallback((editor: monacoNs.editor.IStandaloneCodeEditor, monaco: Monaco) => {
    editorRef.current = editor;
    monacoRef.current = monaco;

    // Register Yyds language features once
    if (!languageSetup) {
      setupYydsLanguage(monaco);
      languageSetup = true;
    }

    // Ctrl+S → save
    editor.addAction({
      id: 'yyds-save',
      label: 'Save File',
      keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS],
      run: () => { saveFile(); },
    });

    // Ctrl+Enter → run selected (also in context menu)
    editor.addAction({
      id: 'yyds-run-selected',
      label: t('runSelectedCode'),
      keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter],
      contextMenuGroupId: '1_run',
      contextMenuOrder: 1,
      precondition: undefined,
      run: () => { runSelected(); },
    });

    // Ctrl+Shift+Enter → run file (also in context menu)
    editor.addAction({
      id: 'yyds-run-file',
      label: t('runCurrentFile'),
      keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.Enter],
      contextMenuGroupId: '1_run',
      contextMenuOrder: 2,
      run: () => { runFile(); },
    });
  }, [saveFile, runSelected, runFile]);

  // Update actions when callbacks change
  useEffect(() => {
    const editor = editorRef.current;
    const monaco = monacoRef.current;
    if (!editor || !monaco) return;
    // Re-register to capture latest closures
    const d1 = editor.addAction({
      id: 'yyds-save-2',
      label: 'Save',
      keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS],
      run: () => { saveFile(); },
    });
    const d2 = editor.addAction({
      id: 'yyds-run-selected-2',
      label: t('runSelectedCode'),
      keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter],
      contextMenuGroupId: '1_run',
      contextMenuOrder: 1,
      run: () => { runSelected(); },
    });
    const d3 = editor.addAction({
      id: 'yyds-run-file-2',
      label: t('runCurrentFile'),
      keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.Enter],
      contextMenuGroupId: '1_run',
      contextMenuOrder: 2,
      run: () => { runFile(); },
    });
    return () => { d1.dispose(); d2.dispose(); d3.dispose(); };
  }, [saveFile, runSelected, runFile]);

  if (tabs.length === 0) {
    return (
      <div className="flex items-center justify-center h-full text-gray-500 text-sm">
        <div className="text-center">
          <p className="text-lg mb-1">Yyds IDE</p>
          <p className="text-xs">{t('openFileHint')}</p>
          <p className="text-xs mt-2 text-gray-600">{t('editorShortcuts')}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full">
      {/* Tab bar */}
      <div className="flex items-center h-[35px] bg-[#252526] overflow-x-auto shrink-0">
        {tabs.map((tab) => (
          <div
            key={tab.path}
            onClick={() => onSelectTab(tab.path)}
            className={`flex items-center gap-1.5 px-3 h-full text-[12px] cursor-pointer border-r border-[#252526] shrink-0 ${
              activeTab === tab.path
                ? 'bg-[#1e1e1e] text-white border-t-2 border-t-[#007acc]'
                : 'bg-[#2d2d2d] text-[#969696] hover:bg-[#2a2a2a]'
            }`}
          >
            {tab.dirty && <Circle size={8} className="text-white fill-white shrink-0" />}
            <span className="truncate max-w-[120px]">{tab.name}</span>
            <button
              onClick={(e) => { e.stopPropagation(); onCloseTab(tab.path); }}
              className="ml-1 p-0.5 hover:bg-[#3c3c3c] rounded opacity-60 hover:opacity-100"
            >
              <X size={12} />
            </button>
          </div>
        ))}
      </div>

      {/* Editor */}
      <div className="flex-1">
        {currentTab && currentTab.name.endsWith('.flow.json') ? (
          <FlowEditorPanel
            content={currentTab.content}
            onSave={async (content) => {
              onContentChange(currentTab.path, content);
              try {
                await ideApi.writeFile(imei, currentTab.path, content);
                onMarkClean(currentTab.path);
                status.success(t('saved'));
              } catch (e: any) {
                status.error(t('saveFailed', { msg: e.message }));
              }
            }}
            onDirty={() => onContentChange(currentTab.path, currentTab.content)}
            insertCode={(code) => insertCodeRef.current(code)}
          />
        ) : currentTab && currentTab.name === 'ui.yml' ? (
          <UiDesignerPanel
            content={currentTab.content}
            onSave={async (content) => {
              onContentChange(currentTab.path, content);
              try {
                await ideApi.writeFile(imei, currentTab.path, content);
                onMarkClean(currentTab.path);
                status.success(t('saved'));
              } catch (e: any) {
                status.error(t('saveFailed', { msg: e.message }));
              }
            }}
            onDirty={() => onContentChange(currentTab.path, currentTab.content)}
          />
        ) : currentTab ? (
          loadingFile === currentTab.path ? (
            <div className="flex items-center justify-center h-full text-gray-400">
              <div className="flex flex-col items-center gap-2">
                <RefreshCw size={20} className="animate-spin text-[#007acc]" />
                <span className="text-xs">{t('codeLoading')}</span>
              </div>
            </div>
          ) : (
          <Editor
            key={currentTab.path}
            language={currentTab.language}
            value={currentTab.content}
            theme="vs-dark"
            onChange={(value) => onContentChange(currentTab.path, value || '')}
            onMount={handleEditorMount}
            options={{
              fontSize: 14,
              lineHeight: 22,
              minimap: { enabled: true, maxColumn: 80 },
              scrollBeyondLastLine: false,
              wordWrap: 'on',
              tabSize: 4,
              insertSpaces: true,
              automaticLayout: true,
              suggestOnTriggerCharacters: true,
              quickSuggestions: true,
              renderWhitespace: 'selection',
              bracketPairColorization: { enabled: true },
              guides: { bracketPairs: true, indentation: true },
              padding: { top: 8 },
            }}
          />
          )
        ) : null}
      </div>
    </div>
  );
}
