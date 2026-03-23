import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  ArrowLeft, Wifi, WifiOff, Terminal, Play, Square,
  RotateCcw, FolderOpen, ScrollText, Smartphone, Loader2, AlertCircle,
  Home, ChevronLeft, AppWindow, Volume2, VolumeX, Power,
  PanelRightOpen, PanelRightClose,
} from 'lucide-react';
import LogcatPanel from '@/components/LogcatPanel';
import { useDevice } from '@/hooks/useDevices';
import { useStreamConnection } from '@/hooks/useStreamConnection';
import { deviceApi, projectApi } from '@/services/api';
import { status } from '@/store';
import { useRef, useState, useCallback, useEffect } from 'react';
import type { ConnectionMode } from '@/types';

const SWIPE_THRESHOLD = 8;

/* ── Connection status indicator ── */
function ConnectionBadge({ mode, fps, error, tier, bandwidth }: { mode: ConnectionMode; fps: number; error: string | null; tier: string; bandwidth: number }) {
  const { t } = useTranslation(['device', 'common']);
  if (error) {
    return (
      <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-medium bg-red-500/20 text-red-400 backdrop-blur-sm">
        <AlertCircle size={11} /> {error.length > 30 ? error.slice(0, 30) + '…' : error}
      </span>
    );
  }
  switch (mode) {
    case 'p2p':
      return (
        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-medium bg-emerald-500/20 text-emerald-400 backdrop-blur-sm">
          <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse" /> P2P · {fps} fps
        </span>
      );
    case 'ws':
      return (
        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-medium bg-blue-500/20 text-blue-400 backdrop-blur-sm">
          <span className="w-1.5 h-1.5 rounded-full bg-blue-400 animate-pulse" /> WS · {fps}fps · {tier} · {bandwidth}KB/s
        </span>
      );
    case 'connecting':
      return (
        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-medium bg-amber-500/20 text-amber-400 backdrop-blur-sm">
          <Loader2 size={11} className="animate-spin" /> {t('common:connecting')}
        </span>
      );
    default:
      return (
        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-medium bg-zinc-500/20 text-zinc-400 backdrop-blur-sm">
          <span className="w-1.5 h-1.5 rounded-full bg-zinc-500" /> {t('device:notConnected')}
        </span>
      );
  }
}

/* ── Android nav button ── */
function NavButton({ icon: Icon, label, size = 18, onClick }: {
  icon: React.ComponentType<{ size?: number; className?: string }>;
  label: string; size?: number; onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      className="flex flex-col items-center justify-center gap-0.5 py-2.5 px-3 text-white/50 hover:text-white hover:bg-white/10 active:bg-white/20 transition-all rounded-lg"
      title={label}
    >
      <Icon size={size} />
      <span className="text-[9px] leading-none opacity-60">{label}</span>
    </button>
  );
}

export default function DeviceDetail() {
  const { t } = useTranslation(['device', 'common']);
  const { imei } = useParams<{ imei: string }>();
  const navigate = useNavigate();
  const { data: device } = useDevice(imei ?? '');
  const { frame, mode, error, fps, tier, bandwidth } = useStreamConnection({ imei: imei ?? '', enabled: !!imei });

  const screenRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [shellInput, setShellInput] = useState('');
  const [shellOutput, setShellOutput] = useState<string[]>([]);
  const [activeTab, setActiveTab] = useState<'logcat' | 'shell'>('logcat');
  const [screenSize, setScreenSize] = useState({ w: 0, h: 0 });
  const [panelOpen, setPanelOpen] = useState(true);
  const shellEndRef = useRef<HTMLDivElement>(null);

  // ── Gesture state ──
  const dragState = useRef<{
    startX: number; startY: number;
    startTime: number; active: boolean;
  } | null>(null);
  const [dragLine, setDragLine] = useState<{
    x1: number; y1: number; x2: number; y2: number;
  } | null>(null);
  const [tapRipple, setTapRipple] = useState<{ x: number; y: number; id: number } | null>(null);

  const sw = device?.screen_width ?? 1080;
  const sh = device?.screen_height ?? 1920;

  // ── Responsive screen sizing ──
  useEffect(() => {
    if (!containerRef.current) return;
    const ro = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (!entry) return;
      const { width: cw, height: ch } = entry.contentRect;
      const aspect = sw / sh;
      let w: number, h: number;
      if (cw / ch > aspect) {
        h = ch;
        w = h * aspect;
      } else {
        w = cw;
        h = w / aspect;
      }
      setScreenSize({ w: Math.floor(w), h: Math.floor(h) });
    });
    ro.observe(containerRef.current);
    return () => ro.disconnect();
  }, [sw, sh]);

  useEffect(() => {
    shellEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [shellOutput]);

  const toDeviceCoords = useCallback(
    (e: React.MouseEvent | MouseEvent) => {
      if (!screenRef.current || !device) return null;
      const rect = screenRef.current.getBoundingClientRect();
      const relX = (e.clientX - rect.left) / rect.width;
      const relY = (e.clientY - rect.top) / rect.height;
      if (relX < 0 || relX > 1 || relY < 0 || relY > 1) return null;
      return { x: Math.round(relX * sw), y: Math.round(relY * sh), cx: e.clientX - rect.left, cy: e.clientY - rect.top };
    },
    [device, sw, sh],
  );

  const handlePointerDown = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      if (e.button !== 0) return;
      const pt = toDeviceCoords(e);
      if (!pt) return;
      dragState.current = { startX: pt.cx, startY: pt.cy, startTime: Date.now(), active: true };
      setDragLine({ x1: pt.cx, y1: pt.cy, x2: pt.cx, y2: pt.cy });
      e.preventDefault();
    },
    [toDeviceCoords],
  );

  const handlePointerMove = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      const ds = dragState.current;
      if (!ds?.active) return;
      const pt = toDeviceCoords(e);
      if (!pt) return;
      if (Math.hypot(pt.cx - ds.startX, pt.cy - ds.startY) > SWIPE_THRESHOLD) {
        setDragLine({ x1: ds.startX, y1: ds.startY, x2: pt.cx, y2: pt.cy });
      }
    },
    [toDeviceCoords],
  );

  const handlePointerUp = useCallback(
    async (e: React.MouseEvent<HTMLDivElement>) => {
      const ds = dragState.current;
      dragState.current = null;
      setDragLine(null);
      if (!ds?.active || !imei || !screenRef.current || !device) return;
      const rect = screenRef.current.getBoundingClientRect();
      const endPt = toDeviceCoords(e);
      if (!endPt) return;
      const x1 = Math.round((ds.startX / rect.width) * sw);
      const y1 = Math.round((ds.startY / rect.height) * sh);
      const screenDist = Math.hypot(endPt.cx - ds.startX, endPt.cy - ds.startY);
      try {
        if (screenDist < SWIPE_THRESHOLD) {
          setTapRipple({ x: ds.startX, y: ds.startY, id: Date.now() });
          setTimeout(() => setTapRipple(null), 500);
          await deviceApi.touch(imei, x1, y1);
        } else {
          const elapsed = Date.now() - ds.startTime;
          const duration = Math.max(150, Math.min(800, elapsed));
          await deviceApi.swipe(imei, x1, y1, endPt.x, endPt.y, duration);
        }
      } catch (err: any) {
        status.error(err?.message || t('common:operationFailed'));
      }
    },
    [imei, device, sw, sh, toDeviceCoords],
  );

  const handlePointerLeave = useCallback(() => {
    if (dragState.current?.active) { dragState.current = null; setDragLine(null); }
  }, []);

  const handleContextMenu = useCallback(
    (e: React.MouseEvent) => { e.preventDefault(); if (imei) deviceApi.key(imei, 4); },
    [imei],
  );

  const handleShell = useCallback(async () => {
    if (!imei || !shellInput.trim()) return;
    const cmd = shellInput.trim();
    setShellInput('');
    setShellOutput((prev) => [...prev, `$ ${cmd}`]);
    try {
      const res = await deviceApi.shell(imei, cmd);
      const output = typeof res.data === 'string' ? res.data : JSON.stringify(res.data);
      if (output.trim()) setShellOutput((prev) => [...prev, output]);
    } catch (err: any) {
      setShellOutput((prev) => [...prev, `Error: ${err?.message || err}`]);
    }
  }, [imei, shellInput]);

  if (!imei) return null;
  const online = device?.online;
  const model = device?.model ?? imei;
  const runningProject = device?.running_project ?? '';

  return (
    <div className="flex h-full overflow-hidden animate-in">
      {/* ══════ Main: Screen Area (fills all available space) ══════ */}
      <div className="flex-1 flex flex-col min-w-0 bg-[#0a0a0a]">
        {/* ── Top bar ── */}
        <div className="flex h-10 items-center gap-2 px-3 shrink-0 bg-black/60 border-b border-white/5">
          <button onClick={() => navigate(-1)} className="p-1 text-white/40 hover:text-white/80 transition-colors rounded" title={t('common:back')}>
            <ArrowLeft size={15} />
          </button>
          <div className="flex items-center gap-2 min-w-0">
            <span className="text-xs text-white/70 font-medium truncate">{model}</span>
            {online ? (
              <span className="inline-flex items-center gap-1 text-[10px] text-emerald-400"><Wifi size={10} /></span>
            ) : (
              <span className="inline-flex items-center gap-1 text-[10px] text-zinc-500"><WifiOff size={10} /></span>
            )}
          </div>
          <div className="ml-auto flex items-center gap-2">
            <ConnectionBadge mode={mode} fps={fps} error={error} tier={tier} bandwidth={bandwidth} />
            <span className="text-[10px] text-white/25 tabular-nums">{sw}×{sh}</span>
            <button
              onClick={() => setPanelOpen((v) => !v)}
              className="p-1 text-white/30 hover:text-white/70 transition-colors rounded"
              title={panelOpen ? '收起面板' : '展开面板'}
            >
              {panelOpen ? <PanelRightClose size={15} /> : <PanelRightOpen size={15} />}
            </button>
          </div>
        </div>

        {/* ── Screen + Nav keys ── */}
        <div className="flex-1 flex flex-col min-h-0">
          {/* Screen canvas container */}
          <div
            ref={containerRef}
            className="flex-1 flex items-center justify-center p-2 min-h-0 overflow-hidden relative"
          >
            <div
              ref={screenRef}
              className="relative rounded-lg overflow-hidden cursor-crosshair select-none shadow-2xl shadow-black/50"
              style={{
                width: screenSize.w || '100%',
                height: screenSize.h || '100%',
                maxWidth: '100%',
                maxHeight: '100%',
                aspectRatio: screenSize.w ? undefined : `${sw}/${sh}`,
              }}
              onMouseDown={handlePointerDown}
              onMouseMove={handlePointerMove}
              onMouseUp={handlePointerUp}
              onMouseLeave={handlePointerLeave}
              onContextMenu={handleContextMenu}
            >
              {frame ? (
                <img
                  src={frame}
                  alt="Screen"
                  className="absolute inset-0 w-full h-full object-contain pointer-events-none"
                  draggable={false}
                />
              ) : (
                <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-zinc-900/80 pointer-events-none">
                  {mode === 'connecting' ? (
                    <>
                      <Loader2 size={32} className="text-white/15 animate-spin" />
                      <span className="text-white/20 text-xs">{t('device:connectingDevice')}</span>
                    </>
                  ) : (
                    <>
                      <Smartphone size={32} className="text-white/10" />
                      <span className="text-white/15 text-xs">
                        {error ? t('device:connectFailed') : t('common:waitingScreen')}
                      </span>
                    </>
                  )}
                </div>
              )}

              {/* Swipe line overlay */}
              {dragLine && (
                <svg className="absolute inset-0 w-full h-full pointer-events-none z-10">
                  <line x1={dragLine.x1} y1={dragLine.y1} x2={dragLine.x2} y2={dragLine.y2}
                    stroke="rgba(59,130,246,0.7)" strokeWidth={2} strokeLinecap="round" />
                  <circle cx={dragLine.x1} cy={dragLine.y1} r={4} fill="rgba(59,130,246,0.5)" />
                  <circle cx={dragLine.x2} cy={dragLine.y2} r={4} fill="rgba(96,165,250,0.8)" />
                </svg>
              )}

              {/* Tap ripple */}
              {tapRipple && (
                <div
                  key={tapRipple.id}
                  className="absolute w-8 h-8 -ml-4 -mt-4 rounded-full border-2 border-blue-400/60 animate-ping pointer-events-none z-10"
                  style={{ left: tapRipple.x, top: tapRipple.y }}
                />
              )}
            </div>
          </div>

          {/* ── Android Navigation Bar ── */}
          <div className="shrink-0 flex items-center justify-center gap-1 py-1.5 bg-black/80 border-t border-white/5">
            {/* Left group: Vol */}
            <div className="flex items-center gap-0.5 mr-4">
              <NavButton icon={VolumeX} label="Vol-" size={15} onClick={() => imei && deviceApi.key(imei, 25)} />
              <NavButton icon={Volume2} label="Vol+" size={15} onClick={() => imei && deviceApi.key(imei, 24)} />
            </div>

            {/* Center: Android 3-key nav */}
            <div className="flex items-center gap-2 px-4 py-0.5 rounded-xl bg-white/[0.03] border border-white/[0.06]">
              <NavButton icon={ChevronLeft} label="Back" size={20} onClick={() => imei && deviceApi.key(imei, 4)} />
              <div className="w-px h-6 bg-white/5" />
              <NavButton icon={Home} label="Home" size={20} onClick={() => imei && deviceApi.key(imei, 3)} />
              <div className="w-px h-6 bg-white/5" />
              <NavButton icon={AppWindow} label="Recent" size={20} onClick={() => imei && deviceApi.key(imei, 187)} />
            </div>

            {/* Right group: Power */}
            <div className="flex items-center gap-0.5 ml-4">
              <NavButton icon={Power} label="Power" size={15} onClick={() => imei && deviceApi.key(imei, 26)} />
            </div>
          </div>
        </div>
      </div>

      {/* ══════ Right: Collapsible Controls Panel ══════ */}
      {panelOpen && (
        <div className="w-[360px] xl:w-[400px] shrink-0 flex flex-col overflow-hidden border-l border-divider bg-card-bg">
          {/* Status + actions */}
          <div className="p-3 border-b border-divider shrink-0 space-y-2">
            <div className="flex items-center gap-3 flex-wrap">
              <div className="flex items-center gap-1.5 text-xs">
                <span className="text-text-muted">{t('device:project')}</span>
                <span className="text-text-primary font-medium">{runningProject || '—'}</span>
              </div>
              <div className="flex items-center gap-1.5 text-xs min-w-0">
                <span className="text-text-muted">{t('device:foreground')}</span>
                <span className="text-text-primary truncate max-w-[160px]">{device?.foreground_app || '—'}</span>
              </div>
            </div>
            <div className="flex gap-1.5 flex-wrap">
              <button onClick={async () => {
                const name = prompt(t('device:inputProjectName'));
                if (!name || !imei) return;
                try { await projectApi.start(imei, name); status.success(t('common:started', { name })); } catch (e: any) { status.error(e.message); }
              }} className="btn-primary text-xs py-1.5">
                <Play size={12} /> {t('common:start')}
              </button>
              <button onClick={async () => {
                if (!imei) return;
                try { await projectApi.stop(imei); status.success(t('common:stopped')); } catch (e: any) { status.error(e.message); }
              }} className="btn-secondary text-xs py-1.5">
                <Square size={12} /> {t('common:stop')}
              </button>
              <button
                onClick={() => imei && deviceApi.rebootEngine(imei).then(() => status.success(t('device:rebootEngine')))}
                className="btn-secondary text-xs py-1.5" title={t('device:rebootEngine')}
              >
                <RotateCcw size={12} />
              </button>
              <div className="flex-1" />
              <button onClick={() => navigate(`/devices/${imei}/files`)} className="btn-secondary text-xs py-1.5">
                <FolderOpen size={12} />
              </button>
              <button onClick={() => navigate(`/devices/${imei}/logs`)} className="btn-secondary text-xs py-1.5">
                <ScrollText size={12} />
              </button>
              <button onClick={() => navigate(`/devices/${imei}/agent`)} className="btn-secondary text-xs py-1.5 flex items-center gap-1" title="Agent 控制台">
                🤖 <span>Agent</span>
              </button>
            </div>
          </div>

          {/* Tabs */}
          <div className="flex items-center border-b border-divider shrink-0">
            <button
              onClick={() => setActiveTab('logcat')}
              className={`flex items-center gap-1.5 px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
                activeTab === 'logcat' ? 'border-brand text-brand' : 'border-transparent text-text-muted hover:text-text-primary'
              }`}
            >
              <Smartphone size={12} /> Logcat
            </button>
            <button
              onClick={() => setActiveTab('shell')}
              className={`flex items-center gap-1.5 px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
                activeTab === 'shell' ? 'border-brand text-brand' : 'border-transparent text-text-muted hover:text-text-primary'
              }`}
            >
              <Terminal size={12} /> Shell
            </button>
          </div>

          {/* Tab content */}
          <div className="flex-1 flex flex-col min-h-0 overflow-hidden">
            {activeTab === 'logcat' ? (
              <LogcatPanel imei={imei} />
            ) : (
              <div className="flex-1 flex flex-col min-h-0">
                <div className="flex-1 overflow-auto p-3 bg-terminal-bg font-mono text-xs text-terminal-text">
                  {shellOutput.length === 0 ? (
                    <span className="text-white/25">{t('device:shellHint')}</span>
                  ) : (
                    shellOutput.map((line, i) => (
                      <div key={i} className={`whitespace-pre-wrap break-all ${line.startsWith('$') ? 'text-blue-400' : ''}`}>
                        {line}
                      </div>
                    ))
                  )}
                  <div ref={shellEndRef} />
                </div>
                <div className="flex gap-2 p-2 border-t border-divider shrink-0 bg-card-bg">
                  <input
                    value={shellInput}
                    onChange={(e) => setShellInput(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && handleShell()}
                    placeholder={t('device:shellPlaceholder')}
                    className="input font-mono text-xs"
                  />
                  <button onClick={handleShell} className="btn-primary text-xs shrink-0 py-1.5">
                    {t('common:execute')}
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
