import { useState, useCallback, useRef, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, Package, Terminal, ScrollText, FolderPlus, Loader2, PanelRightClose, PanelRightOpen, Wifi, WifiOff } from 'lucide-react';
import RunToolbar from '@/components/ide/RunToolbar';
import FileTreePanel from '@/components/ide/FileTreePanel';
import EditorPanel from '@/components/ide/EditorPanel';
import DevToolPanel from '@/components/ide/DevToolPanel';
import LogPanel from '@/components/ide/LogPanel';
import PipManagerPanel from '@/components/ide/PipManagerPanel';
import ApkBuilderPanel from '@/components/ide/ApkBuilderPanel';
import ProjectInitDialog from '@/components/ide/ProjectInitDialog';
import type { IdeTab } from '@/types';
import { ideApi } from '@/services/api';
import { deviceApi } from '@/services/api';
import { status, useStatusStore } from '@/store';
import type { StatusType } from '@/store';

type BottomTab = 'log' | 'pip';

export default function IdeWorkbench({ backTo }: { backTo?: string }) {
  const { t } = useTranslation(['ide', 'common']);
  const { imei } = useParams<{ imei: string }>();
  const navigate = useNavigate();

  // Panel sizes (persisted in state, draggable)
  const [leftW, setLeftW] = useState(220);
  const [rightW, setRightW] = useState(480);
  const [bottomH, setBottomH] = useState(200);
  const [rightCollapsed, setRightCollapsed] = useState(false);

  // Bottom panel tab
  const [bottomTab, setBottomTab] = useState<BottomTab>('log');

  // Modals
  const [showApkBuilder, setShowApkBuilder] = useState(false);
  const [showNewProject, setShowNewProject] = useState(false);

  // Editor tabs
  const [tabs, setTabs] = useState<IdeTab[]>([]);
  const [activeTab, setActiveTab] = useState<string | null>(null);

  // Current project
  const [projectName, setProjectName] = useState('');
  const [projectRoot, setProjectRoot] = useState('');

  // Editor ref for inserting code snippets from DevTool
  const insertCodeRef = useRef<(code: string) => void>(() => {});

  // Device online/offline status
  const [deviceOnline, setDeviceOnline] = useState(true);
  useEffect(() => {
    let alive = true;
    const check = async () => {
      try {
        await deviceApi.get(imei!);
        if (alive) setDeviceOnline(true);
      } catch {
        if (alive) setDeviceOnline(false);
      }
    };
    check();
    const timer = setInterval(check, 5000);
    return () => { alive = false; clearInterval(timer); };
  }, [imei]);

  const openFile = useCallback((path: string, name: string) => {
    const existing = tabs.find((t) => t.path === path);
    if (existing) {
      setActiveTab(path);
      return;
    }
    const ext = name.split('.').pop()?.toLowerCase() || '';
    const lang = ext === 'py' ? 'python' : ext === 'yml' || ext === 'yaml' ? 'yaml' : ext === 'json' ? 'json' : ext === 'txt' ? 'plaintext' : 'plaintext';
    setTabs((prev) => [...prev, { path, name, content: '', dirty: false, language: lang }]);
    setActiveTab(path);
  }, [tabs]);

  // Run a .py file from file tree context menu
  const runFileFromTree = useCallback(async (path: string) => {
    try {
      if (!imei) return;
      const res = await ideApi.readFile(imei, path);
      const content = (res as any)?.data?.content ?? (res as any)?.data ?? '';
      if (!content) {
        status.error(t('fileEmpty'));
        return;
      }
      await ideApi.runCode(imei, content);
      status.success(t('running', { name: path.split('/').pop() }));
    } catch (e: any) {
      status.error(t('runFailed', { msg: e.message }));
    }
  }, [imei]);

  const closeTab = useCallback((path: string) => {
    setTabs((prev) => {
      const next = prev.filter((t) => t.path !== path);
      if (activeTab === path) {
        setActiveTab(next.length > 0 ? next[next.length - 1].path : null);
      }
      return next;
    });
  }, [activeTab]);

  const updateTabContent = useCallback((path: string, content: string) => {
    setTabs((prev) => prev.map((t) => t.path === path ? { ...t, content, dirty: true } : t));
  }, []);

  const markTabClean = useCallback((path: string) => {
    setTabs((prev) => prev.map((t) => t.path === path ? { ...t, dirty: false } : t));
  }, []);

  const setTabLoaded = useCallback((path: string, content: string) => {
    setTabs((prev) => prev.map((t) => t.path === path && t.content === '' ? { ...t, content } : t));
  }, []);

  if (!imei) return null;

  return (
    <div className="flex flex-col h-screen bg-[#1e1e1e] text-white overflow-hidden">
      {/* Top toolbar */}
      <div className="flex items-center h-10 bg-[#252526] border-b border-[#3c3c3c] px-2 gap-2 shrink-0">
        <button onClick={() => navigate(backTo ?? `/devices/${imei}`)} className="p-1 hover:bg-[#3c3c3c] rounded" title={t('common:back')}>
          <ArrowLeft size={16} />
        </button>
        <div className={`flex items-center gap-1 px-2 py-0.5 rounded text-[11px] ${deviceOnline ? 'text-emerald-400' : 'text-red-400 animate-pulse'}`} title={deviceOnline ? t('deviceOnline') : t('deviceOffline')}>
          {deviceOnline ? <Wifi size={13} /> : <WifiOff size={13} />}
          <span>{deviceOnline ? t('connected') : t('deviceOffline')}</span>
        </div>
        <RunToolbar
          imei={imei}
          projectName={projectName}
          onProjectChange={(name, root) => { setProjectName(name); setProjectRoot(root); }}
          activeTab={activeTab}
          tabs={tabs}
        />
        <button
          onClick={() => setShowNewProject(true)}
          className="flex items-center gap-1 px-2 py-0.5 bg-[#3c3c3c] hover:bg-[#4c4c4c] rounded text-[12px]"
          title={t('newProject')}
        >
          <FolderPlus size={12} />
        </button>
        <button
          onClick={() => setShowApkBuilder(true)}
          disabled={!projectName}
          className="flex items-center gap-1 px-2 py-0.5 bg-[#3c3c3c] hover:bg-[#4c4c4c] disabled:opacity-30 rounded text-[12px]"
          title={t('apkBuild')}
        >
          <Package size={12} />
        </button>
      </div>

      {/* Main area */}
      <div className="flex flex-1 overflow-hidden">
        {/* Left: File tree */}
        <div style={{ width: leftW }} className="shrink-0 border-r border-[#3c3c3c] overflow-hidden">
          <FileTreePanel imei={imei} projectRoot={projectRoot} onOpenFile={openFile} onRunFile={runFileFromTree} />
        </div>
        <DragHandle axis="x" onDrag={(dx) => setLeftW((w) => Math.max(140, Math.min(400, w + dx)))} />

        {/* Center: Editor */}
        <div className="flex-1 flex flex-col overflow-hidden min-w-[200px]">
          <div className="flex-1 overflow-hidden">
            <EditorPanel
              imei={imei}
              tabs={tabs}
              activeTab={activeTab}
              onSelectTab={setActiveTab}
              onCloseTab={closeTab}
              onContentChange={updateTabContent}
              onTabLoaded={setTabLoaded}
              onMarkClean={markTabClean}
              insertCodeRef={insertCodeRef}
            />
          </div>
          <DragHandle axis="y" onDrag={(dy) => setBottomH((h) => Math.max(80, Math.min(500, h - dy)))} />
          {/* Bottom: Log / Pip tabs */}
          <div style={{ height: bottomH }} className="shrink-0 border-t border-[#3c3c3c] overflow-hidden flex flex-col">
            <div className="flex items-center h-7 bg-[#252526] border-b border-[#3c3c3c] shrink-0 px-1 gap-0.5">
              <button
                onClick={() => setBottomTab('log')}
                className={`flex items-center gap-1 px-2 py-0.5 text-[11px] rounded-t ${
                  bottomTab === 'log' ? 'bg-[#1e1e1e] text-white' : 'text-gray-500 hover:text-gray-300'
                }`}
              >
                <ScrollText size={11} />{t('logTab')}
              </button>
              <button
                onClick={() => setBottomTab('pip')}
                className={`flex items-center gap-1 px-2 py-0.5 text-[11px] rounded-t ${
                  bottomTab === 'pip' ? 'bg-[#1e1e1e] text-white' : 'text-gray-500 hover:text-gray-300'
                }`}
              >
                <Terminal size={11} />Pip
              </button>
            </div>
            <div className="flex-1 overflow-hidden">
              {bottomTab === 'log' && <LogPanel imei={imei} />}
              {bottomTab === 'pip' && <PipManagerPanel imei={imei} />}
            </div>
          </div>
        </div>

        {!rightCollapsed && (
          <DragHandle axis="x" onDrag={(dx) => setRightW((w) => Math.max(360, Math.min(700, w - dx)))} />
        )}
        {/* Right: DevTool (collapsible) */}
        <div
          style={{ width: rightCollapsed ? 24 : rightW }}
          className="shrink-0 border-l border-[#3c3c3c] overflow-hidden flex flex-col"
        >
          <button
            onClick={() => setRightCollapsed((v) => !v)}
            className="flex items-center justify-center h-6 shrink-0 bg-[#252526] hover:bg-[#3c3c3c] border-b border-[#3c3c3c]"
            title={rightCollapsed ? t('expandToolPanel') : t('collapseToolPanel')}
          >
            {rightCollapsed ? <PanelRightOpen size={14} /> : <PanelRightClose size={14} />}
          </button>
          {!rightCollapsed && (
            <div className="flex-1 overflow-hidden">
              <DevToolPanel imei={imei} onInsertCode={(code) => insertCodeRef.current(code)} />
            </div>
          )}
        </div>
      </div>

      {/* Modals */}
      {showApkBuilder && projectName && (
        <ApkBuilderPanel imei={imei} projectName={projectName} onClose={() => setShowApkBuilder(false)} />
      )}
      <ProjectInitDialog
        imei={imei}
        isOpen={showNewProject}
        onClose={() => setShowNewProject(false)}
        onCreated={(name, root) => {
          setProjectName(name);
          setProjectRoot(root);
        }}
      />

      {/* IDE Status Bar */}
      <IdeStatusBar />
    </div>
  );
}

const IDE_STATUS_DOT: Record<StatusType, string> = {
  idle: 'bg-gray-500',
  success: 'bg-emerald-400',
  error: 'bg-red-400',
  warning: 'bg-amber-400',
  info: 'bg-blue-400',
  loading: '',
};

function IdeStatusBar() {
  const message = useStatusStore((s) => s.message);
  const type = useStatusStore((s) => s.type);
  const { t } = useTranslation(['common']);

  return (
    <div className="flex h-6 shrink-0 items-center gap-2 border-t border-[#3c3c3c] bg-[#007acc] px-3 text-[11px] text-white">
      {type === 'loading' ? (
        <Loader2 size={11} className="shrink-0 animate-spin" />
      ) : (
        <span className={`inline-block h-1.5 w-1.5 shrink-0 rounded-full ${IDE_STATUS_DOT[type]}`} />
      )}
      <span className="truncate">{message || t('common:ready')}</span>
    </div>
  );
}

/** Draggable divider handle */
function DragHandle({ axis, onDrag }: { axis: 'x' | 'y'; onDrag: (delta: number) => void }) {
  const dragging = useRef(false);
  const lastPos = useRef(0);

  const onMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    dragging.current = true;
    lastPos.current = axis === 'x' ? e.clientX : e.clientY;

    const onMouseMove = (ev: MouseEvent) => {
      if (!dragging.current) return;
      const pos = axis === 'x' ? ev.clientX : ev.clientY;
      const delta = pos - lastPos.current;
      lastPos.current = pos;
      onDrag(delta);
    };
    const onMouseUp = () => {
      dragging.current = false;
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
    document.body.style.cursor = axis === 'x' ? 'col-resize' : 'row-resize';
    document.body.style.userSelect = 'none';
  }, [axis, onDrag]);

  return (
    <div
      onMouseDown={onMouseDown}
      className={
        axis === 'x'
          ? 'w-1 cursor-col-resize hover:bg-[#007acc] active:bg-[#007acc] shrink-0'
          : 'h-1 cursor-row-resize hover:bg-[#007acc] active:bg-[#007acc] shrink-0'
      }
    />
  );
}
