import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, Trash2, Download, Search } from 'lucide-react';
import { useEffect, useRef, useState, useCallback } from 'react';
import { useAuthStore } from '@/store';

export default function LogViewer() {
  const { t } = useTranslation('log');
  const { imei } = useParams<{ imei: string }>();
  const navigate = useNavigate();
  const [logs, setLogs] = useState<string[]>([]);
  const [filter, setFilter] = useState('');
  const [autoScroll, setAutoScroll] = useState(true);
  const containerRef = useRef<HTMLDivElement>(null);
  const wsRef = useRef<WebSocket | null>(null);

  const [connected, setConnected] = useState(false);
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
        setLogs((prev) => {
          const next = [...prev, ev.data];
          if (next.length > 5000) return next.slice(-5000);
          return next;
        });
      }
    };

    ws.onclose = () => {
      setConnected(false);
      setLogs((prev) => [...prev, t('disconnectedAutoReconnect')]);
      reconnectTimer.current = setTimeout(connect, 5000);
    };

    ws.onerror = () => {
      ws.close();
    };
  }, [imei]);

  useEffect(() => {
    connect();
    return () => {
      if (reconnectTimer.current) clearTimeout(reconnectTimer.current);
      wsRef.current?.close();
      wsRef.current = null;
    };
  }, [connect]);

  // Auto-scroll
  useEffect(() => {
    if (autoScroll && containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  }, [logs, autoScroll]);

  const handleScroll = useCallback(() => {
    if (!containerRef.current) return;
    const { scrollTop, scrollHeight, clientHeight } = containerRef.current;
    const isAtBottom = scrollHeight - scrollTop - clientHeight < 50;
    setAutoScroll(isAtBottom);
  }, []);

  const filteredLogs = filter
    ? logs.filter((l) => l.toLowerCase().includes(filter.toLowerCase()))
    : logs;

  const handleClear = () => setLogs([]);

  const handleExport = () => {
    const blob = new Blob([logs.join('\n')], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${imei}_log_${new Date().toISOString().slice(0, 19).replace(/:/g, '-')}.txt`;
    a.click();
    URL.revokeObjectURL(url);
  };

  if (!imei) return null;

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="flex h-14 items-center gap-3 border-b border-divider px-4 shrink-0">
        <button onClick={() => navigate(`/devices/${imei}`)} className="btn-ghost p-1">
          <ArrowLeft size={18} />
        </button>
        <h2 className="text-sm font-medium text-text-primary">{t('title')}</h2>
        <span className="text-xs text-text-hint font-mono">{imei}</span>
        <span className="text-xs text-text-hint">{logs.length} {t('lines')}</span>
        <div className="ml-auto flex items-center gap-2">
          <div className="relative">
            <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-text-hint" />
            <input
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder={t('filterPlaceholder')}
              className="input pl-8 w-48 text-xs"
            />
          </div>
          <button onClick={handleExport} className="btn-secondary text-xs">
            <Download size={14} /> {t('export')}
          </button>
          <button onClick={handleClear} className="btn-ghost text-xs text-text-muted">
            <Trash2 size={14} /> {t('clear')}
          </button>
        </div>
      </div>

      {/* Log content — dark terminal style (matches Android floating console) */}
      <div
        ref={containerRef}
        onScroll={handleScroll}
        className="flex-1 overflow-auto p-3 font-mono text-xs leading-5"
        style={{ backgroundColor: '#192129', color: '#E0E8F0' }}
      >
        {filteredLogs.length === 0 ? (
          <div className="flex items-center justify-center h-full text-text-hint">
            {t('waiting')}
          </div>
        ) : (
          filteredLogs.map((line, i) => (
            <div
              key={i}
              className={`whitespace-pre-wrap break-all ${
                line.includes('Error') || line.includes(t('errorException'))
                  ? 'text-red-400'
                  : line.includes('Warning') || line.includes(t('warning'))
                    ? 'text-amber-400'
                    : line.startsWith('---')
                      ? 'text-text-hint italic'
                      : ''
              }`}
            >
              {line}
            </div>
          ))
        )}
      </div>

      {/* Footer */}
      <div className="flex items-center justify-between border-t border-divider px-4 py-1.5 text-xs text-text-hint shrink-0">
        <span className="flex items-center gap-2">
          <span className={`h-1.5 w-1.5 rounded-full ${connected ? 'bg-success' : 'bg-danger animate-pulse'}`} />
          {connected ? (autoScroll ? t('autoScroll') : t('scrollPaused')) : t('disconnectedReconnecting')}
        </span>
        <span>{filter ? `${filteredLogs.length} / ${logs.length}` : `${logs.length}`} {t('lines')}</span>
      </div>
    </div>
  );
}
