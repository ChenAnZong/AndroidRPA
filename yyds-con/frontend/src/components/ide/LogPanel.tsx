import { useState, useCallback, useRef, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Trash2, Download, Search, ArrowDownToLine, Terminal, Cog } from 'lucide-react';
import { useAuthStore } from '@/store';

interface Props {
  imei: string;
}

type LogCategory = 'script' | 'system';
type LogMode = 'all' | 'script' | 'system';
interface LogEntry { text: string; category: LogCategory; }

const MAX_LINES = 5000;

/** System-log content patterns (matched AFTER stripping out:/err: prefix) */
const SYS_RE = /^\[WsClient\]|^\[组\d|^checkAutoEngine:|^扫描工程文件夹:|^扫描目录$|^\tat |^Caused by:|^java\.|^android\.|^\.\.\. \d+ more$|^#\s|^---/;

/** Classify a raw WS log line by content, not just prefix */
function classify(raw: string): LogEntry {
  // Strip prefix to inspect actual content
  const content = raw.startsWith('out:') || raw.startsWith('err:')
    ? raw.slice(4)
    : raw;
  // No out:/err: prefix at all → system (from ExtSystem.printDebugLog directly)
  if (!raw.startsWith('out:') && !raw.startsWith('err:')) {
    return { text: raw, category: 'system' };
  }
  // Has prefix but content matches system patterns → system
  if (SYS_RE.test(content.trimStart())) {
    return { text: raw, category: 'system' };
  }
  return { text: raw, category: 'script' };
}

export default function LogPanel({ imei }: Props) {
  const { t } = useTranslation(['log', 'common']);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [filter, setFilter] = useState('');
  const [autoScroll, setAutoScroll] = useState(true);
  const [connected, setConnected] = useState(false);
  const [logMode, setLogMode] = useState<LogMode>('script');
  const containerRef = useRef<HTMLDivElement>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const connect = useCallback(() => {
    if (!imei) return;
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const token = useAuthStore.getState().token;
    const tokenParam = token ? `?token=${encodeURIComponent(token)}` : '';
    const ws = new WebSocket(`${protocol}//${window.location.host}/api/devices/${imei}/log${tokenParam}`);
    wsRef.current = ws;

    ws.onopen = () => setConnected(true);

    ws.onmessage = (ev) => {
      if (typeof ev.data === 'string') {
        const entry = classify(ev.data);
        setLogs((prev) => {
          const next = [...prev, entry];
          return next.length > MAX_LINES ? next.slice(-MAX_LINES) : next;
        });
      }
    };

    ws.onclose = () => {
      setConnected(false);
      reconnectTimer.current = setTimeout(connect, 5000);
    };

    ws.onerror = () => ws.close();
  }, [imei]);

  useEffect(() => {
    connect();
    return () => {
      if (reconnectTimer.current) clearTimeout(reconnectTimer.current);
      wsRef.current?.close();
    };
  }, [connect]);

  // Auto scroll
  useEffect(() => {
    if (autoScroll && containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  }, [logs, autoScroll]);

  const clearLogs = useCallback(() => setLogs([]), []);

  const exportLogs = useCallback(() => {
    const blob = new Blob([logs.map((l) => l.text).join('\n')], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `log_${imei}_${Date.now()}.txt`;
    a.click();
    URL.revokeObjectURL(url);
  }, [logs, imei]);

  const filtered = useMemo(() => {
    let list = logs;
    if (logMode !== 'all') list = list.filter((l) => l.category === logMode);
    if (filter) {
      const lf = filter.toLowerCase();
      list = list.filter((l) => l.text.toLowerCase().includes(lf));
    }
    return list;
  }, [logs, logMode, filter]);

  const modeButtons: { mode: LogMode; label: string; icon: typeof Terminal }[] = [
    { mode: 'script', label: t('scriptMode'), icon: Terminal },
    { mode: 'system', label: t('systemMode'), icon: Cog },
    { mode: 'all', label: t('allMode'), icon: Terminal },
  ];

  return (
    <div className="flex flex-col h-full text-[12px]">
      {/* Toolbar */}
      <div className="flex items-center gap-1 h-7 px-2 bg-[#252526] border-b border-[#3c3c3c] shrink-0">
        <div className={`w-2 h-2 rounded-full shrink-0 ${connected ? 'bg-green-500' : 'bg-red-500'}`} />
        <span className="text-[10px] text-gray-500 mr-1">{connected ? t('connected') : t('disconnected')}</span>

        {/* Log mode toggle */}
        <div className="flex items-center bg-[#1e1e1e] rounded overflow-hidden border border-[#3c3c3c] mr-1">
          {modeButtons.map(({ mode, label }) => (
            <button
              key={mode}
              onClick={() => setLogMode(mode)}
              className={`px-2 py-0.5 text-[10px] transition-colors ${
                logMode === mode
                  ? 'bg-[#007acc] text-white'
                  : 'text-gray-400 hover:text-white hover:bg-[#3c3c3c]'
              }`}
            >
              {label}
            </button>
          ))}
        </div>

        <div className="flex items-center bg-[#3c3c3c] rounded px-1.5 flex-1 max-w-[200px]">
          <Search size={11} className="text-gray-500 shrink-0" />
          <input
            className="bg-transparent text-white text-[11px] px-1 py-0.5 outline-none w-full"
            placeholder={t('filterPlaceholder')}
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
        </div>
        <div className="flex-1" />
        <button
          onClick={() => setAutoScroll(!autoScroll)}
          className={`p-0.5 rounded ${autoScroll ? 'bg-[#007acc]' : 'hover:bg-[#3c3c3c]'}`}
          title={autoScroll ? t('autoScrollOn') : t('autoScrollOff')}
        >
          <ArrowDownToLine size={12} />
        </button>
        <button onClick={clearLogs} className="p-0.5 hover:bg-[#3c3c3c] rounded" title={t('clear')}>
          <Trash2 size={12} />
        </button>
        <button onClick={exportLogs} className="p-0.5 hover:bg-[#3c3c3c] rounded" title={t('export')}>
          <Download size={12} />
        </button>
      </div>

      {/* Log content */}
      <div
        ref={containerRef}
        className="flex-1 overflow-auto font-mono bg-[#192129] px-2 py-1"
      >
        {filtered.length === 0 && (
          <div className="text-gray-600 text-center py-4">{t('waitingLogs')}</div>
        )}
        {filtered.map((entry, i) => (
          <LogLine key={i} text={entry.text} category={entry.category} />
        ))}
      </div>
    </div>
  );
}

function LogLine({ text, category }: { text: string; category: LogCategory }) {
  // Strip out:/err: prefix from PyOut
  let display = text;
  let isErr = false;
  if (text.startsWith('err:')) {
    display = text.slice(4);
    isErr = true;
  } else if (text.startsWith('out:')) {
    display = text.slice(4);
  }

  // Skip console control commands (handled by FloatingLogService)
  if (display.startsWith('##YYDS_CONSOLE##')) return null;

  const lower = display.toLowerCase();
  let color = '#cccccc';
  if (isErr || lower.includes('error') || lower.includes('exception') || lower.includes('traceback')) {
    color = '#f44747';
  } else if (lower.includes('warn')) {
    color = '#cca700';
  } else if (lower.includes('info')) {
    color = '#3dc9b0';
  } else if (display.startsWith('---')) {
    color = '#666666';
  }

  // System logs get dimmed style
  if (category === 'system') {
    color = color === '#cccccc' ? '#6a6a6a' : color;
  }

  return (
    <div className="leading-[18px] whitespace-pre-wrap break-all" style={{ color }}>
      {category === 'system' && <span className="text-[#555] mr-1 text-[10px]">[sys]</span>}
      {display}
    </div>
  );
}
