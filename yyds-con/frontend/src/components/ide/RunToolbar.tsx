import { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { status } from '@/store';
import { Play, Square, Code, ChevronDown } from 'lucide-react';
import { projectApi, ideApi } from '@/services/api';
import type { IdeTab } from '@/types';

interface Props {
  imei: string;
  projectName: string;
  onProjectChange: (name: string, root: string) => void;
  activeTab: string | null;
  tabs: IdeTab[];
}

const PROJECT_BASE = '/storage/emulated/0/Yyds.Py';

export default function RunToolbar({ imei, projectName, onProjectChange, activeTab, tabs }: Props) {
  const { t } = useTranslation(['ide', 'common']);
  const [projects, setProjects] = useState<{ name: string; path: string }[]>([]);
  const [running, setRunning] = useState(false);
  const [runningName, setRunningName] = useState('');
  const [busy, setBusy] = useState(false);
  const [showDropdown, setShowDropdown] = useState(false);

  // Load project list
  const loadProjects = useCallback(async () => {
    try {
      const res = await projectApi.list(imei);
      const data = (res as any)?.data || res;
      const list = Array.isArray(data) ? data : [];
      setProjects(list);
      // Auto-select first project if none selected
      if (!projectName && list.length > 0) {
        onProjectChange(list[0].name, list[0].path || `${PROJECT_BASE}/${list[0].name}`);
      }
    } catch { /* ignore */ }
  }, [imei, projectName, onProjectChange]);

  // Poll running status
  const pollStatus = useCallback(async () => {
    try {
      const res = await projectApi.status(imei);
      const data = (res as any)?.data || res;
      if (data && typeof data === 'object') {
        const parsed = typeof data === 'string' ? JSON.parse(data) : data;
        setRunning(!!parsed.running);
        setRunningName(parsed.project || '');
      }
    } catch { /* ignore */ }
  }, [imei]);

  useEffect(() => {
    loadProjects();
    pollStatus();
    const interval = setInterval(pollStatus, 5000);
    return () => clearInterval(interval);
  }, [loadProjects, pollStatus]);

  const handleStart = useCallback(async () => {
    if (!projectName) { status.error(t('selectProjectFirst')); return; }
    setBusy(true);
    try {
      await projectApi.start(imei, projectName);
      status.success(t('started', { name: projectName }));
      setTimeout(pollStatus, 1000);
    } catch (e: any) {
      status.error(e.message);
    } finally {
      setBusy(false);
    }
  }, [imei, projectName, pollStatus]);

  const handleStop = useCallback(async () => {
    setBusy(true);
    try {
      await projectApi.stop(imei);
      status.success(t('stopped'));
      setTimeout(pollStatus, 1000);
    } catch (e: any) {
      status.error(e.message);
    } finally {
      setBusy(false);
    }
  }, [imei, pollStatus]);

  const handleRunSnippet = useCallback(async () => {
    const tab = tabs.find((t) => t.path === activeTab);
    if (!tab) { status.error(t('noOpenFile')); return; }
    setBusy(true);
    try {
      await ideApi.runCode(imei, tab.content);
      status.success(t('codeSent'));
    } catch (e: any) {
      status.error(e.message);
    } finally {
      setBusy(false);
    }
  }, [imei, activeTab, tabs]);

  return (
    <div className="flex items-center gap-2 flex-1 min-w-0">
      {/* Project selector */}
      <div className="relative">
        <button
          onClick={() => setShowDropdown(!showDropdown)}
          className="flex items-center gap-1 px-2 py-0.5 bg-[#3c3c3c] hover:bg-[#4c4c4c] rounded text-[12px] max-w-[180px]"
        >
          <span className="truncate">{projectName || t('selectProject')}</span>
          <ChevronDown size={12} className="shrink-0" />
        </button>
        {showDropdown && (
          <div className="absolute top-full left-0 mt-1 bg-[#2d2d2d] border border-[#454545] rounded shadow-lg z-50 min-w-[180px] max-h-[300px] overflow-auto">
            {projects.map((p) => (
              <button
                key={p.name}
                onClick={() => {
                  onProjectChange(p.name, p.path || `${PROJECT_BASE}/${p.name}`);
                  setShowDropdown(false);
                }}
                className={`block w-full text-left px-3 py-1.5 text-[12px] hover:bg-[#094771] ${
                  p.name === projectName ? 'bg-[#094771] text-white' : 'text-[#cccccc]'
                }`}
              >
                {p.name}
              </button>
            ))}
            {projects.length === 0 && (
              <div className="px-3 py-2 text-[11px] text-gray-500">{t('noProjects')}</div>
            )}
          </div>
        )}
      </div>

      {/* Run / Stop */}
      <button
        onClick={handleStart}
        disabled={busy || running}
        className="flex items-center gap-1 px-2 py-0.5 bg-[#2ea043] hover:bg-[#3fb950] disabled:opacity-50 rounded text-[12px]"
        title={t('runProject')}
      >
        <Play size={12} />
        <span>{t('run')}</span>
      </button>
      <button
        onClick={handleStop}
        disabled={busy || !running}
        className="flex items-center gap-1 px-2 py-0.5 bg-[#da3633] hover:bg-[#f85149] disabled:opacity-50 rounded text-[12px]"
        title={t('stopProject')}
      >
        <Square size={12} />
        <span>{t('stop')}</span>
      </button>

      {/* Run snippet */}
      <button
        onClick={handleRunSnippet}
        disabled={busy || !activeTab}
        className="flex items-center gap-1 px-2 py-0.5 bg-[#1f6feb] hover:bg-[#388bfd] disabled:opacity-50 rounded text-[12px]"
        title={t('runCurrentFile')}
      >
        <Code size={12} />
        <span>{t('execute')}</span>
      </button>

      {/* Status indicator */}
      <div className="flex-1" />
      {running && (
        <div className="flex items-center gap-1.5 text-[11px] text-green-400">
          <div className="w-2 h-2 rounded-full bg-green-500 animate-pulse" />
          <span className="truncate max-w-[120px]">{runningName || t('running')}</span>
        </div>
      )}
    </div>
  );
}
