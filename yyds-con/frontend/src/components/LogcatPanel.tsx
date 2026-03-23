import { useRef, useState, useCallback, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Play, Square, Trash2, Download, Search, Settings2,
  ChevronDown, ChevronUp,
} from 'lucide-react';
import { logcatApi } from '@/services/api';

/** Logcat log levels */
const LOG_LEVELS = ['V', 'D', 'I', 'W', 'E', 'F'] as const;
type LogLevel = (typeof LOG_LEVELS)[number];

const LEVEL_LABELS: Record<LogLevel, string> = {
  V: 'Verbose',
  D: 'Debug',
  I: 'Info',
  W: 'Warning',
  E: 'Error',
  F: 'Fatal',
};

/* Log level colors — adapted for dark terminal background */
const LEVEL_COLORS: Record<string, string> = {
  V: 'text-gray-400',
  D: 'text-blue-400',
  I: 'text-emerald-400',
  W: 'text-amber-400',
  E: 'text-red-400',
  F: 'text-red-500 font-bold',
};

/** Extract log level letter from a logcat line (brief/threadtime format) */
function parseLogLevel(line: string): string | null {
  // threadtime: "01-01 00:00:00.000  1234  1234 I Tag: message"
  const m = line.match(/^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+\d+\s+([VDIWEF])\s/);
  if (m) return m[1];
  // brief: "I/Tag(  1234): message"
  const m2 = line.match(/^([VDIWEF])\/\S+/);
  if (m2) return m2[1];
  return null;
}

const POLL_INTERVAL = 2000;

interface LogcatPanelProps {
  imei: string;
}

export default function LogcatPanel({ imei }: LogcatPanelProps) {
  const { t } = useTranslation(['log', 'common']);
  const [lines, setLines] = useState<string[]>([]);  const [recording, setRecording] = useState(false);
  const [autoScroll, setAutoScroll] = useState(true);
  const [filter, setFilter] = useState('');

  // Filter settings
  const [showSettings, setShowSettings] = useState(false);
  const [level, setLevel] = useState<LogLevel>('V');
  const [pid, setPid] = useState('');
  const [tag, setTag] = useState('');
  const [format, setFormat] = useState<'brief' | 'threadtime'>('threadtime');

  const containerRef = useRef<HTMLDivElement>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const knownCountRef = useRef(0);
  const recordingRef = useRef(false);

  // Keep ref in sync so interval callback sees latest value
  useEffect(() => {
    recordingRef.current = recording;
  }, [recording]);

  // Auto-scroll
  useEffect(() => {
    if (autoScroll && containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  }, [lines, autoScroll]);

  const handleScroll = useCallback(() => {
    if (!containerRef.current) return;
    const { scrollTop, scrollHeight, clientHeight } = containerRef.current;
    setAutoScroll(scrollHeight - scrollTop - clientHeight < 50);
  }, []);

  /** Build filter params for the dedicated logcat API */
  const buildParams = useCallback(() => {
    const params: { level?: string; pid?: string; tag?: string; format?: string } = { format };
    if (level !== 'V') params.level = level;
    if (pid.trim()) params.pid = pid.trim();
    if (tag.trim()) params.tag = tag.trim();
    return params;
  }, [format, pid, tag, level]);

  /** Poll logcat via dedicated API and append new lines */
  const poll = useCallback(async () => {
    if (!recordingRef.current) return;
    try {
      const res = await logcatApi.dump(imei, { ...buildParams(), level: level || 'V' });
      const output = res.data || '';
      if (!output.trim()) return;

      const allLines = output.split('\n').filter((l) => l.trim().length > 0);
      const newLines = allLines.slice(knownCountRef.current);
      if (newLines.length > 0) {
        knownCountRef.current = allLines.length;
        setLines((prev) => {
          const next = [...prev, ...newLines];
          return next.length > 10000 ? next.slice(-10000) : next;
        });
      }
    } catch {
      // Silently ignore poll errors
    }
  }, [imei, buildParams, level]);

  /** Start recording logcat */
  const startRecording = useCallback(async () => {
    // Clear device logcat buffer first for a clean start
    try {
      await logcatApi.clear(imei);
    } catch { /* ignore */ }

    knownCountRef.current = 0;
    setLines([t('recording', { info: new Date().toLocaleTimeString() })]);
    recordingRef.current = true;   // sync ref immediately so first poll() works
    setRecording(true);
    setAutoScroll(true);

    // Start polling
    if (timerRef.current) clearInterval(timerRef.current);
    // Do first poll immediately
    setTimeout(() => poll(), 500);
    timerRef.current = setInterval(() => poll(), POLL_INTERVAL);
  }, [imei, poll]);

  /** Stop recording */
  const stopRecording = useCallback(() => {
    recordingRef.current = false;  // sync ref immediately so poll() stops
    setRecording(false);
    if (timerRef.current) {
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
    setLines((prev) => [...prev, t('recordingStopped', { info: new Date().toLocaleTimeString() })]);
  }, []);

  /** Clear logcat on device + local */
  const clearLogcat = useCallback(async () => {
    try {
      await logcatApi.clear(imei);
    } catch { /* ignore */ }
    knownCountRef.current = 0;
    setLines([]);
  }, [imei]);

  /** Export logs to file */
  const exportLogs = useCallback(() => {
    const blob = new Blob([lines.join('\n')], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `logcat_${imei}_${new Date().toISOString().slice(0, 19).replace(/:/g, '-')}.txt`;
    a.click();
    URL.revokeObjectURL(url);
  }, [lines, imei]);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, []);

  // Text filter
  const filteredLines = filter
    ? lines.filter((l) => l.toLowerCase().includes(filter.toLowerCase()))
    : lines;

  return (
    <div className="flex flex-col h-full min-h-0">
      {/* Toolbar */}
      <div className="flex items-center gap-1.5 px-3 py-2 border-b border-divider shrink-0 flex-wrap">
        {/* Start / Stop */}
        {!recording ? (
          <button onClick={startRecording} className="btn-primary text-xs gap-1">
            <Play size={13} /> {t('startRecording')}
          </button>
        ) : (
          <button onClick={stopRecording} className="btn-danger text-xs gap-1">
            <Square size={13} /> {t('common:stop')}
          </button>
        )}

        {/* Clear */}
        <button onClick={clearLogcat} className="btn-ghost text-xs text-text-muted gap-1">
          <Trash2 size={13} /> {t('clear')}
        </button>

        {/* Export */}
        <button onClick={exportLogs} className="btn-ghost text-xs text-text-muted gap-1" disabled={lines.length === 0}>
          <Download size={13} /> {t('export')}
        </button>

        <div className="w-px h-5 bg-divider mx-1" />

        {/* Filter settings toggle */}
        <button
          onClick={() => setShowSettings((s) => !s)}
          className={`btn-ghost text-xs gap-1 ${showSettings ? 'text-brand' : 'text-text-muted'}`}
        >
          <Settings2 size={13} />
          {t('filterSettings')}
          {showSettings ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
        </button>

        {/* Quick level indicator */}
        {level !== 'V' && (
          <span className={`text-[10px] px-1.5 py-0.5 rounded ${LEVEL_COLORS[level]} bg-page-bg`}>
            ≥{level}
          </span>
        )}
        {pid && (
          <span className="text-[10px] px-1.5 py-0.5 rounded text-cyan-600 bg-page-bg">
            PID:{pid}
          </span>
        )}
        {tag && (
          <span className="text-[10px] px-1.5 py-0.5 rounded text-purple-600 bg-accent-purple-bg">
            {tag}
          </span>
        )}

        {/* Spacer */}
        <div className="flex-1" />

        {/* Text search */}
        <div className="relative">
          <Search size={12} className="absolute left-2 top-1/2 -translate-y-1/2 text-text-hint" />
          <input
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder={t('searchPlaceholder')}
            className="input pl-7 w-36 text-xs h-7"
          />
        </div>

        {/* Line count */}
        <span className="text-[10px] text-text-hint tabular-nums">
          {filter ? `${filteredLines.length}/` : ''}{lines.length}{t('lines')}
        </span>

        {/* Recording indicator */}
        {recording && (
          <span className="flex items-center gap-1 text-[10px] text-danger">
            <span className="h-1.5 w-1.5 rounded-full bg-danger animate-pulse" />
            REC
          </span>
        )}
      </div>

      {/* Filter settings panel */}
      {showSettings && (
        <div className="px-3 py-2.5 border-b border-divider bg-page-bg space-y-2.5 shrink-0">
          <div className="flex items-center gap-4 flex-wrap">
            {/* Log level */}
            <div className="flex items-center gap-2">
              <label className="text-[11px] text-text-muted whitespace-nowrap">{t('logLevel')}</label>
              <div className="flex gap-0.5">
                {LOG_LEVELS.map((l) => (
                  <button
                    key={l}
                    onClick={() => setLevel(l)}
                    className={`px-2 py-0.5 text-[11px] rounded transition-colors ${
                      level === l
                        ? 'bg-brand text-white'
                        : 'text-text-hint hover:text-text-primary hover:bg-gray-100'
                    }`}
                    title={LEVEL_LABELS[l]}
                  >
                    {l}
                  </button>
                ))}
              </div>
            </div>

            {/* PID */}
            <div className="flex items-center gap-2">
              <label className="text-[11px] text-text-muted whitespace-nowrap">--pid</label>
              <input
                value={pid}
                onChange={(e) => setPid(e.target.value.replace(/[^\d]/g, ''))}
                placeholder={t('processId')}
                className="input w-24 text-xs h-7 font-mono"
              />
            </div>

            {/* Tag */}
            <div className="flex items-center gap-2">
              <label className="text-[11px] text-text-muted whitespace-nowrap">Tag</label>
              <input
                value={tag}
                onChange={(e) => setTag(e.target.value)}
                placeholder={t('tagFilter')}
                className="input w-32 text-xs h-7 font-mono"
              />
            </div>

            {/* Format */}
            <div className="flex items-center gap-2">
              <label className="text-[11px] text-text-muted whitespace-nowrap">{t('format')}</label>
              <select
                value={format}
                onChange={(e) => setFormat(e.target.value as 'brief' | 'threadtime')}
                className="input text-xs h-7 w-28"
              >
                <option value="threadtime">threadtime</option>
                <option value="brief">brief</option>
              </select>
            </div>
          </div>

          <p className="text-[10px] text-text-hint">
            {t('filterHint')}
          </p>
        </div>
      )}

      {/* Log output — dark terminal (matches Android floating console #192129) */}
      <div
        ref={containerRef}
        onScroll={handleScroll}
        className="flex-1 overflow-auto px-3 py-2 font-mono text-[11px] leading-[18px] min-h-0"
        style={{ backgroundColor: '#192129', color: '#E0E8F0' }}
      >
        {filteredLines.length === 0 ? (
          <div className="flex items-center justify-center h-full text-gray-500 text-xs">
            {recording ? t('waiting') : t('waitingHint')}
          </div>
        ) : (
          filteredLines.map((line, i) => {
            const lvl = parseLogLevel(line);
            const colorClass = lvl ? (LEVEL_COLORS[lvl] ?? '') : (
              line.startsWith('---') ? 'text-gray-500 italic' : ''
            );
            return (
              <div key={i} className={`whitespace-pre-wrap break-all ${colorClass}`}>
                {line}
              </div>
            );
          })
        )}
      </div>

      {/* Footer status */}
      <div className="flex items-center justify-between px-3 py-1 border-t border-divider text-[10px] text-text-hint shrink-0">
        <span>
          {recording ? t('pollingInterval') : t('stopped')}
          {' · '}
          {format}
          {level !== 'V' ? ` · *:${level}` : ''}
          {pid ? ` · pid=${pid}` : ''}
          {tag ? ` · tag=${tag}` : ''}
        </span>
        <span>{autoScroll ? t('autoScroll') : t('scrollPaused')}</span>
      </div>
    </div>
  );
}
