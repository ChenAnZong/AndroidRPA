import { useState, useEffect, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Smartphone,
  Wifi,
  WifiOff,
  MonitorPlay,
  MousePointer,
  Type,
  Terminal,
  Upload,
  RotateCcw,
  Power,
  Loader2,
  X,
  Calendar,
} from 'lucide-react';
import { useDevices } from '@/hooks/useDevices';
import { useStreamConnection } from '@/hooks/useStreamConnection';
import { deviceApi, taskRunApi } from '@/services/api';
import { useAppStore, status } from '@/store';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import type { Device } from '@/types';

function StatCard({
  icon: Icon,
  label,
  value,
  accent,
}: {
  icon: React.ComponentType<{ size?: number; className?: string }>;
  label: string;
  value: number | string;
  accent: string;
}) {
  return (
    <div className="card flex items-center gap-4 relative overflow-hidden">
      <span className={`absolute left-0 top-0 bottom-0 w-[2px] ${accent}`} />
      <Icon size={18} className={`${accent.replace('bg-', 'text-')} shrink-0 ml-1`} />
      <div>
        <p className="text-2xl font-semibold text-text-primary">{value}</p>
        <p className="text-xs text-text-muted">{label}</p>
      </div>
    </div>
  );
}

function TaskRunStats() {
  const { t } = useTranslation('dashboard');
  const navigate = useNavigate();
  const { data, isLoading } = useQuery({
    queryKey: ['task-run-stats'],
    queryFn: taskRunApi.stats,
    refetchInterval: 30000,
  });

  if (isLoading || !data) return null;
  if (data.total_today === 0) return null;

  const successColor = data.success_rate >= 90 ? 'text-success' : data.success_rate >= 60 ? 'text-warning' : 'text-danger';
  const barColor = data.success_rate >= 90 ? 'bg-success' : data.success_rate >= 60 ? 'bg-warning' : 'bg-danger';

  return (
    <div
      className="card cursor-pointer hover:border-brand/30 transition-colors"
      onClick={() => navigate('/task-runs')}
    >
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <Calendar size={14} className="text-text-muted" />
          <span className="text-xs font-medium text-text-muted">
            {t('taskStatsTitle')}
          </span>
        </div>
        <span className={`text-xs font-bold ${successColor}`}>
          {data.success_rate}%
        </span>
      </div>
      <div className="grid grid-cols-3 gap-3">
        <div className="text-center">
          <p className="text-xl font-semibold text-text-primary">{data.total_today}</p>
          <p className="text-[10px] text-text-muted">{t('taskStatsRuns')}</p>
        </div>
        <div className="text-center">
          <p className="text-xl font-semibold text-success">{data.success_runs}</p>
          <p className="text-[10px] text-text-muted">{t('taskStatsSuccess')}</p>
        </div>
        <div className="text-center">
          <p className={`text-xl font-semibold ${data.failed_runs > 0 ? 'text-danger' : 'text-text-hint'}`}>
            {data.failed_runs}
          </p>
          <p className="text-[10px] text-text-muted">{t('taskStatsFailed')}</p>
        </div>
      </div>
      {data.total_devices > 0 && (
        <div className="mt-3 space-y-1">
          <div className="flex justify-between text-[10px] text-text-muted">
            <span>{t('taskStatsDeviceRate')}</span>
            <span>{t('taskStatsDeviceCount', { success: data.success_devices, total: data.total_devices })}</span>
          </div>
          <div className="h-1.5 rounded-full bg-hover overflow-hidden">
            <div
              className={`h-full rounded-full transition-all ${barColor}`}
              style={{ width: `${data.success_rate}%` }}
            />
          </div>
        </div>
      )}
    </div>
  );
}

function DeviceThumbnail({
  device,
  tick,
  selected,
  onToggleSelect,
}: {
  device: Device;
  tick: number;
  selected: boolean;
  onToggleSelect: (imei: string) => void;
}) {
  const { t } = useTranslation(['dashboard', 'common']);
  const navigate = useNavigate();

  return (
    <div className="card group relative overflow-hidden p-0 hover:border-brand/30 transition-all duration-200 hover:scale-[1.02]">
      <div
        className="relative cursor-pointer"
        onClick={() => navigate(`/devices/${device.imei}`)}
      >
        <div
          className="absolute left-2 top-2 z-10"
          onClick={(e) => {
            e.stopPropagation();
            onToggleSelect(device.imei);
          }}
        >
          <input
            type="checkbox"
            checked={selected}
            readOnly
            className="h-4 w-4 rounded border-divider accent-brand bg-white/90 pointer-events-none"
          />
        </div>
        <div className="aspect-[9/16] w-full bg-black/40 relative">
          <img
            src={deviceApi.thumbnailUrl(device.imei, tick)}
            alt={device.model}
            className="h-full w-full object-cover"
            loading="lazy"
            onError={(e) => {
              (e.target as HTMLImageElement).style.display = 'none';
            }}
          />
          <div className="absolute inset-0 bg-gradient-to-t from-black/70 via-transparent to-transparent" />
          <div className="absolute bottom-0 left-0 right-0 p-3">
            <p className="text-xs font-medium text-white truncate">{device.model}</p>
            <p className="text-[10px] text-white/70 truncate">{device.imei}</p>
            <div className="mt-1 flex items-center gap-2">
              {device.online ? (
                <span className="inline-flex items-center gap-1 rounded-full bg-success/20 px-2 py-0.5 text-[10px] font-medium text-white">
                  <span className="h-1.5 w-1.5 rounded-full bg-success" />
                  {t('online')}
                </span>
              ) : (
                <span className="inline-flex items-center gap-1 rounded-full bg-white/20 px-2 py-0.5 text-[10px] font-medium text-white/70">
                  <span className="h-1.5 w-1.5 rounded-full bg-white/50" />
                  {t('offline')}
                </span>
              )}
              {device.running_project && (
                <span className="text-[10px] text-white/80 truncate">
                  ▶ {device.running_project}
                </span>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

/** Modal: click on stream to sync touch to all selected devices */
function SyncClickModal({
  open,
  onClose,
  deviceIds,
  firstDevice,
}: {
  open: boolean;
  onClose: () => void;
  deviceIds: string[];
  firstDevice: Device | null;
}) {
  const { t } = useTranslation(['dashboard', 'common']);
  const imei = firstDevice?.imei ?? '';
  const { frame, mode } = useStreamConnection({
    imei,
    enabled: open && !!imei,
  });
  const imgRef = useRef<HTMLImageElement>(null);
  const dragStart = useRef<{ x: number; y: number; time: number } | null>(null);

  // Convert mouse/touch position to device coordinates,
  // accounting for object-contain letterboxing
  const toDeviceCoords = useCallback(
    (clientX: number, clientY: number) => {
      const img = imgRef.current;
      if (!img || !firstDevice) return null;
      const sw = firstDevice.screen_width ?? 1080;
      const sh = firstDevice.screen_height ?? 1920;
      // Compute actual rendered image rect inside the element (object-contain)
      const rect = img.getBoundingClientRect();
      const imgAspect = sw / sh;
      const elemAspect = rect.width / rect.height;
      let renderW: number, renderH: number, offsetX: number, offsetY: number;
      if (imgAspect > elemAspect) {
        // image wider than container → pillarbox (top/bottom bars)
        renderW = rect.width;
        renderH = rect.width / imgAspect;
        offsetX = 0;
        offsetY = (rect.height - renderH) / 2;
      } else {
        // image taller → letterbox (left/right bars)
        renderH = rect.height;
        renderW = rect.height * imgAspect;
        offsetX = (rect.width - renderW) / 2;
        offsetY = 0;
      }
      const relX = (clientX - rect.left - offsetX) / renderW;
      const relY = (clientY - rect.top - offsetY) / renderH;
      if (relX < 0 || relX > 1 || relY < 0 || relY > 1) return null; // clicked on black bar
      return { x: Math.round(relX * sw), y: Math.round(relY * sh) };
    },
    [firstDevice],
  );

  const handlePointerDown = useCallback(
    (e: React.PointerEvent) => {
      const coords = toDeviceCoords(e.clientX, e.clientY);
      if (coords) {
        dragStart.current = { ...coords, time: Date.now() };
        (e.target as HTMLElement).setPointerCapture?.(e.pointerId);
      }
    },
    [toDeviceCoords],
  );

  const handlePointerUp = useCallback(
    async (e: React.PointerEvent) => {
      const start = dragStart.current;
      dragStart.current = null;
      if (!start || deviceIds.length === 0) return;
      const end = toDeviceCoords(e.clientX, e.clientY);
      if (!end) return;
      const dist = Math.hypot(end.x - start.x, end.y - start.y);
      try {
        if (dist < 15) {
          // Tap
          await deviceApi.batchTouch(deviceIds, end.x, end.y);
          status.success(t('tapResult', { x: end.x, y: end.y, count: deviceIds.length }));
        } else {
          // Swipe
          const duration = Math.min(Math.max(Date.now() - start.time, 100), 2000);
          const promises = deviceIds.map((id) =>
            deviceApi.swipe(id, start.x, start.y, end.x, end.y, duration).catch(() => null),
          );
          await Promise.all(promises);
          status.success(
            t('swipeResult', { x1: start.x, y1: start.y, x2: end.x, y2: end.y, count: deviceIds.length }),
          );
        }
      } catch (err: unknown) {
        status.error(String(err instanceof Error ? err.message : err));
      }
    },
    [deviceIds, toDeviceCoords],
  );

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-modal-overlay backdrop-blur-sm" onClick={onClose}>
      <div
        className="relative max-h-[90vh] w-full max-w-2xl rounded-[3px] border border-card-border bg-modal-bg p-4 animate-scale-in"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-2 flex items-center justify-between">
          <h3 className="text-sm font-medium text-text-primary">
            {t('syncClickModalTitle', { count: deviceIds.length })}
          </h3>
          <button
            type="button"
            onClick={onClose}
            className="rounded-[3px] p-1 hover:bg-hover-strong text-text-muted transition-colors duration-150"
          >
            <X size={20} />
          </button>
        </div>
        <div
          className="relative w-full max-w-sm mx-auto bg-black rounded overflow-hidden cursor-crosshair select-none touch-none"
          style={{ aspectRatio: firstDevice ? (firstDevice.screen_width ?? 9) / (firstDevice.screen_height ?? 16) : 9 / 16 }}
          onPointerDown={handlePointerDown}
          onPointerUp={handlePointerUp}
        >
          {frame ? (
            <img
              ref={imgRef}
              src={frame}
              alt={t('deviceScreen')}
              className="h-full w-full object-contain"
              draggable={false}
            />
          ) : (
            <div className="flex h-full items-center justify-center text-text-muted text-sm">
              {mode === 'connecting' ? t('common:connecting') : t('common:waitingScreen')}
            </div>
          )}
        </div>
        <p className="mt-2 text-center text-xs text-text-muted">{t('syncClickModalHint')}</p>
      </div>
    </div>
  );
}

/** Modal: file + path for batch upload */
function InputModal({
  open,
  onClose,
  title,
  placeholder,
  multiline,
  onConfirm,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  placeholder?: string;
  multiline?: boolean;
  onConfirm: (value: string) => void;
}) {
  const { t } = useTranslation(['dashboard', 'common']);
  const [value, setValue] = useState('');
  const inputRef = useRef<HTMLInputElement | HTMLTextAreaElement>(null);

  useEffect(() => {
    if (open) { setValue(''); setTimeout(() => inputRef.current?.focus(), 50); }
  }, [open]);

  if (!open) return null;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!value.trim()) return;
    onConfirm(value.trim());
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-modal-overlay backdrop-blur-sm" onClick={onClose}>
      <div className="w-full max-w-md rounded-[3px] border border-card-border bg-modal-bg p-4 animate-scale-in" onClick={(e) => e.stopPropagation()}>
        <div className="mb-3 flex items-center justify-between">
          <h3 className="text-sm font-medium text-text-primary">{title}</h3>
          <button type="button" onClick={onClose} className="rounded-[3px] p-1 hover:bg-hover-strong text-text-muted transition-colors duration-150"><X size={18} /></button>
        </div>
        <form onSubmit={handleSubmit} className="space-y-3">
          {multiline ? (
            <textarea
              ref={inputRef as React.RefObject<HTMLTextAreaElement>}
              value={value}
              onChange={(e) => setValue(e.target.value)}
              placeholder={placeholder}
              rows={4}
              className="input w-full text-sm resize-none"
            />
          ) : (
            <input
              ref={inputRef as React.RefObject<HTMLInputElement>}
              type="text"
              value={value}
              onChange={(e) => setValue(e.target.value)}
              placeholder={placeholder}
              className="input w-full text-sm"
            />
          )}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} className="btn-secondary text-xs px-3 py-1.5">{t('common:cancel')}</button>
            <button type="submit" disabled={!value.trim()} className="btn-primary text-xs px-3 py-1.5">{t('common:confirm')}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

/** Modal: file + path for batch upload */
function UploadFileModal({
  open,
  onClose,
  onConfirm,
  loading,
  initialFile,
}: {
  open: boolean;
  onClose: () => void;
  onConfirm: (file: File, path: string, installApk: boolean) => Promise<void>;
  loading: boolean;
  initialFile?: File | null;
}) {
  const { t } = useTranslation(['dashboard', 'common']);
  const [file, setFile] = useState<File | null>(null);
  const [path, setPath] = useState('/sdcard/Download/');
  const [installAfterUpload, setInstallAfterUpload] = useState(false);

  const isApk = file?.name.toLowerCase().endsWith('.apk') ?? false;

  // Sync initialFile when modal opens with a dragged file
  useEffect(() => {
    if (open && initialFile) {
      setFile(initialFile);
      const isApkFile = initialFile.name.toLowerCase().endsWith('.apk');
      setPath((isApkFile ? '/data/local/tmp/' : '/sdcard/Download/') + initialFile.name);
      setInstallAfterUpload(isApkFile);
    }
  }, [open, initialFile]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file || !path.trim()) {
      status.error(t('uploadSelectFileError'));
      return;
    }
    try {
      await onConfirm(file, path.trim(), installAfterUpload && isApk);
      setFile(null);
      setPath('/sdcard/Download/');
      setInstallAfterUpload(false);
      onClose();
    } catch (err: unknown) {
      status.error(t('common:uploadFailed', { msg: err instanceof Error ? err.message : String(err) }));
    }
  };

  const handleClose = () => {
    setFile(null);
    setPath('/sdcard/Download/');
    setInstallAfterUpload(false);
    onClose();
  };

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-modal-overlay backdrop-blur-sm" onClick={handleClose}>
      <div
        className="w-full max-w-md rounded-[3px] border border-card-border bg-modal-bg p-4 animate-scale-in"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="mb-3 text-sm font-medium text-text-primary">{t('uploadTitle')}</h3>
        <form onSubmit={handleSubmit} className="space-y-3">
          <div>
            <label className="block text-xs text-text-muted mb-1">{t('uploadSelectFile')}</label>
            {file ? (
              <div className="flex items-center gap-2 rounded border border-divider px-3 py-2 text-sm">
                <span className="truncate flex-1">{file.name}</span>
                <span className="text-text-hint text-xs">({(file.size / 1024 / 1024).toFixed(1)} MB)</span>
                <button type="button" onClick={() => { setFile(null); setInstallAfterUpload(false); }} className="text-text-muted hover:text-red-500">
                  <X size={14} />
                </button>
              </div>
            ) : (
              <>
                <input
                  id="upload-file-input"
                  type="file"
                  className="hidden"
                  onChange={(e) => {
                    const f = e.target.files?.[0] ?? null;
                    setFile(f);
                    if (f) {
                      const isApkFile = f.name.toLowerCase().endsWith('.apk');
                      setPath((isApkFile ? '/data/local/tmp/' : '/sdcard/Download/') + f.name);
                      setInstallAfterUpload(isApkFile);
                    }
                  }}
                />
                <button
                  type="button"
                  onClick={() => document.getElementById('upload-file-input')?.click()}
                  className="w-full rounded border border-dashed border-divider px-3 py-3 text-sm text-text-muted hover:border-brand hover:text-brand transition-colors"
                >
                  {t('uploadClickToSelect')}
                </button>
              </>
            )}
          </div>
          <div>
            <label className="block text-xs text-text-muted mb-1">{t('uploadTargetPath')}</label>
            <input
              type="text"
              value={path}
              onChange={(e) => setPath(e.target.value)}
              placeholder="/sdcard/Download/xxx.apk"
              className="input w-full text-sm"
            />
          </div>
          {isApk && (
            <label className="flex items-center gap-2 text-xs text-text-secondary cursor-pointer">
              <input
                type="checkbox"
                checked={installAfterUpload}
                onChange={(e) => setInstallAfterUpload(e.target.checked)}
                className="h-3.5 w-3.5 rounded accent-brand"
              />
              {t('uploadAutoInstall')}
            </label>
          )}
          <div className="flex gap-2 justify-end">
            <button type="button" onClick={handleClose} className="btn-secondary text-xs">
              {t('common:cancel')}
            </button>
            <button type="submit" disabled={loading || !file} className="btn-primary text-xs">
              {loading ? <Loader2 size={14} className="animate-spin" /> : isApk && installAfterUpload ? t('common:uploadAndInstall') : t('common:upload')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default function Dashboard() {
  const { t } = useTranslation(['dashboard', 'common']);
  const { data, isLoading } = useDevices();
  const selectedDevices = useAppStore((s) => s.selectedDevices);
  const toggleDevice = useAppStore((s) => s.toggleDevice);
  const selectAll = useAppStore((s) => s.selectAll);
  const clearSelection = useAppStore((s) => s.clearSelection);

  const [tick, setTick] = useState(0);
  const [syncClickOpen, setSyncClickOpen] = useState(false);
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [batchLoading, setBatchLoading] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [droppedFile, setDroppedFile] = useState<File | null>(null);
  const [inputModal, setInputModal] = useState<{ type: 'text' | 'shell'; } | null>(null);
  const [pasteModal, setPasteModal] = useState(false);
  const [imeModal, setImeModal] = useState(false);
  const [imeList, setImeList] = useState<string[]>([]);
  const [imeLoading, setImeLoading] = useState(false);
  const dragCounter = useRef(0);

  useEffect(() => {
    const id = setInterval(() => setTick((t) => t + 1), 5000);
    return () => clearInterval(id);
  }, []);

  const devices = data?.devices ?? [];
  const total = data?.total ?? 0;
  const online = data?.online ?? 0;
  const offline = total - online;
  const streaming = data?.devices.filter((d) => d.stream_viewers > 0).length ?? 0;

  const allSelected = devices.length > 0 && devices.every((d) => selectedDevices.has(d.imei));
  const hasSelection = selectedDevices.size > 0;

  const handleSelectAll = () => {
    if (allSelected) clearSelection();
    else selectAll(devices.map((d) => d.imei));
  };

  const firstSelectedDevice = hasSelection
    ? devices.find((d) => selectedDevices.has(d.imei)) ?? null
    : null;

  const runBatch = async (label: string, fn: () => Promise<unknown>) => {
    setBatchLoading(label);
    try {
      const res = await fn();
      const results = res && typeof res === 'object' && 'results' in res ? (res as { results: { success: boolean }[] }).results : null;
      const ok = results ? results.filter((r) => r.success).length : selectedDevices.size;
      const fail = results ? results.filter((r) => !r.success).length : 0;
      if (fail > 0) {
        status.warning(t('batchPartialResult', { label, ok, fail }));
      } else {
        status.success(t('batchSuccess', { label, count: selectedDevices.size }));
      }
    } catch (e: unknown) {
      status.error(t('batchFailed', { label, msg: e instanceof Error ? e.message : String(e) }));
    } finally {
      setBatchLoading(null);
    }
  };

  const selectedArray = [...selectedDevices];

  // --- Drag & Drop ---
  const handleDragEnter = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    dragCounter.current++;
    if (e.dataTransfer.types.includes('Files')) setDragOver(true);
  }, []);

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    dragCounter.current--;
    if (dragCounter.current === 0) setDragOver(false);
  }, []);

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
  }, []);

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      e.stopPropagation();
      dragCounter.current = 0;
      setDragOver(false);
      const file = e.dataTransfer.files?.[0];
      if (!file) return;
      if (!hasSelection) {
        // Auto-select all online devices if none selected
        const onlineImeis = devices.filter((d) => d.online).map((d) => d.imei);
        if (onlineImeis.length === 0) {
          status.error(t('noOnlineDevices'));
          return;
        }
        selectAll(onlineImeis);
        status.info(t('autoSelectedOnline', { count: onlineImeis.length }));
      }
      setDroppedFile(file);
      setUploadModalOpen(true);
    },
    [hasSelection, devices, selectAll],
  );

  return (
    <div
      className="p-6 space-y-6 relative min-h-full animate-in"
      onDragEnter={handleDragEnter}
      onDragLeave={handleDragLeave}
      onDragOver={handleDragOver}
      onDrop={handleDrop}
    >
      <h1 className="text-xl font-semibold text-text-primary">
        {t('title')}
      </h1>

      {/* Drag overlay */}
      {dragOver && (
        <div className="pointer-events-none absolute inset-0 z-40 flex items-center justify-center rounded-[3px] border-2 border-dashed border-brand bg-brand/5">
          <div className="flex flex-col items-center gap-2 text-brand">
            <Upload size={48} className="opacity-70" />
            <p className="text-sm font-medium">{t('dragDropHint')}</p>
            <p className="text-xs text-text-muted">{t('dragDropSubHint')}</p>
          </div>
        </div>
      )}

      {/* Stats */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <StatCard icon={Smartphone} label={t('totalDevices')} value={total} accent="bg-brand" />
        <StatCard icon={Wifi} label={t('online')} value={online} accent="bg-success" />
        <StatCard icon={WifiOff} label={t('offline')} value={offline} accent="bg-text-hint" />
        <StatCard icon={MonitorPlay} label={t('streaming')} value={streaming} accent="bg-purple-500" />
      </div>

      {/* Task execution stats (24h) */}
      <TaskRunStats />

      {/* Batch actions */}
      {hasSelection && (
        <div className="flex flex-wrap items-center gap-2 rounded-[3px] border border-brand/20 bg-brand/8 px-4 py-2">
          <span className="text-sm text-text-secondary">
            {t('selectedDevices', { count: selectedDevices.size })}
          </span>
          <div className="ml-auto flex flex-wrap gap-2">
            <button
              disabled={!!batchLoading}
              onClick={() => setSyncClickOpen(true)}
              className="btn-secondary text-xs"
              title={t('syncClickTitle')}
            >
              <MousePointer size={14} /> {t('syncClick')}
            </button>
            <button
              disabled={!!batchLoading}
              onClick={() => setInputModal({ type: 'text' })}
              className="btn-secondary text-xs"
            >
              {batchLoading === t('syncInput') ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <Type size={14} />
              )}{' '}
              {t('syncInput')}
            </button>
            <input
              id="apk-install-input"
              type="file"
              accept=".apk"
              className="hidden"
              onChange={async (e) => {
                const f = e.target.files?.[0];
                e.target.value = '';
                if (!f) return;
                const ids = selectedArray;
                const path = '/data/local/tmp/' + f.name;
                status.loading(t('apkInstalling', { name: f.name, count: ids.length }));
                setBatchLoading(t('apkInstall'));
                try {
                  await deviceApi.batchUploadFile(ids, f, path, (pct) => {
                    status.loading(t('apkUploading', { pct }));
                  });
                  status.loading(t('apkUploadedInstalling'));
                  const res = await deviceApi.batchInstallApk(ids, path);
                  const results = res && typeof res === 'object' && 'results' in res
                    ? (res as { results: { success: boolean }[] }).results : null;
                  const ok = results ? results.filter((r) => r.success).length : ids.length;
                  const fail = results ? results.filter((r) => !r.success).length : 0;
                  if (fail > 0) {
                    status.warning(t('apkInstallPartial', { ok, fail }));
                  } else {
                    status.success(t('apkInstallSuccess', { count: ok }));
                  }
                } catch (err: unknown) {
                  status.error(t('apkInstallFailed', { msg: err instanceof Error ? err.message : String(err) }));
                } finally {
                  setBatchLoading(null);
                }
              }}
            />
            <button
              disabled={!!batchLoading}
              onClick={() => document.getElementById('apk-install-input')?.click()}
              className="btn-secondary text-xs"
            >
              {batchLoading === t('apkInstall') ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <Smartphone size={14} />
              )}{' '}
              {t('apkInstall')}
            </button>
            <button
              disabled={!!batchLoading}
              onClick={() => setInputModal({ type: 'shell' })}
              className="btn-secondary text-xs"
            >
              {batchLoading === t('batchShell') ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <Terminal size={14} />
              )}{' '}
              {t('batchShell')}
            </button>
            <button
              disabled={!!batchLoading}
              onClick={() => { setDroppedFile(null); setUploadModalOpen(true); }}
              className="btn-secondary text-xs"
            >
              {batchLoading === t('fileUpload') ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <Upload size={14} />
              )}{' '}
              {t('fileUpload')}
            </button>
            <button
              disabled={!!batchLoading}
              onClick={() => {
                if (!confirm(t('confirmRebootEngine', { count: selectedDevices.size }))) return;
                runBatch(t('rebootEngine'), () => deviceApi.batchRebootEngine(selectedArray));
              }}
              className="btn-secondary text-xs"
            >
              {batchLoading === t('rebootEngine') ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <RotateCcw size={14} />
              )}{' '}
              {t('rebootEngine')}
            </button>
            <button
              disabled={!!batchLoading}
              onClick={() => setPasteModal(true)}
              className="btn-secondary text-xs"
            >
              {batchLoading === t('pasteText') ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <Type size={14} />
              )}{' '}
              {t('pasteText')}
            </button>
            <button
              disabled={!!batchLoading || imeLoading}
              onClick={async () => {
                if (!firstSelectedDevice) return;
                setImeLoading(true);
                try {
                  const res = await deviceApi.imeList(firstSelectedDevice.imei);
                  const list = (res as any)?.data ?? [];
                  setImeList(Array.isArray(list) ? list : []);
                  setImeModal(true);
                } catch {
                  status.error(t('imeListFailed'));
                } finally {
                  setImeLoading(false);
                }
              }}
              className="btn-secondary text-xs"
            >
              {imeLoading ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <Smartphone size={14} />
              )}{' '}
              {t('switchIme')}
            </button>
            <button
              disabled={!!batchLoading}
              onClick={() => {
                if (!confirm(t('confirmFullReboot', { count: selectedDevices.size }))) return;
                runBatch(t('fullReboot'), () => deviceApi.batchShell(selectedArray, 'reboot'));
              }}
              className="btn-secondary text-xs text-danger"
              title={t('dangerOp')}
            >
              {batchLoading === t('fullReboot') ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <Power size={14} />
              )}{' '}
              {t('fullReboot')}
            </button>
          </div>
        </div>
      )}

      {/* Device grid */}
      <div>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-sm font-medium text-text-secondary">{t('deviceOverview')}</h2>
          {devices.length > 0 && (
            <button
              type="button"
              onClick={handleSelectAll}
              className="text-xs text-brand hover:underline"
            >
              {allSelected ? t('deselectAll') : t('selectAll')}
            </button>
          )}
        </div>
        {isLoading ? (
          <div className="flex items-center justify-center py-20 text-text-muted">{t('common:loading')}</div>
        ) : !data?.devices.length ? (
          <div className="flex flex-col items-center justify-center py-20 text-text-muted">
            <Smartphone size={40} className="mb-3 opacity-30" />
            <p>{t('noDevices')}</p>
            <p className="mt-1 text-xs text-text-hint">
              {t('noDevicesHint')}
            </p>
          </div>
        ) : (
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 xl:grid-cols-8">
            {data.devices.map((device) => (
              <DeviceThumbnail
                key={device.imei}
                device={device}
                tick={tick}
                selected={selectedDevices.has(device.imei)}
                onToggleSelect={toggleDevice}
              />
            ))}
          </div>
        )}
      </div>

      <SyncClickModal
        open={syncClickOpen}
        onClose={() => setSyncClickOpen(false)}
        deviceIds={selectedArray}
        firstDevice={firstSelectedDevice}
      />

      <InputModal
        open={inputModal?.type === 'text'}
        onClose={() => setInputModal(null)}
        title={t('syncInputTitle')}
        placeholder={t('syncInputPlaceholder')}
        multiline
        onConfirm={(v) => runBatch(t('syncInput'), () => deviceApi.batchText(selectedArray, v))}
      />
      <InputModal
        open={inputModal?.type === 'shell'}
        onClose={() => setInputModal(null)}
        title={t('batchShellTitle')}
        placeholder={t('batchShellPlaceholder')}
        onConfirm={(v) => runBatch(t('batchShell'), () => deviceApi.batchShell(selectedArray, v))}
      />

      <UploadFileModal
        open={uploadModalOpen}
        onClose={() => { setUploadModalOpen(false); setDroppedFile(null); }}
        loading={batchLoading === t('fileUpload')}
        initialFile={droppedFile}
        onConfirm={async (file, path, installApk) => {
          const ids = [...selectedDevices];
          setBatchLoading(t('fileUpload'));
          status.loading(t('uploading', { pct: 0 }));
          try {
            await deviceApi.batchUploadFile(ids, file, path, (pct) => {
              status.loading(t('uploading', { pct }));
            });
            status.success(t('filePushed', { count: ids.length }));
            if (installApk) {
              setBatchLoading(t('apkInstall'));
              const res = await deviceApi.batchInstallApk(ids, path);
              const results = res && typeof res === 'object' && 'results' in res
                ? (res as { results: { success: boolean }[] }).results : null;
              const ok = results ? results.filter((r) => r.success).length : ids.length;
              const fail = results ? results.filter((r) => !r.success).length : 0;
              if (fail > 0) {
                status.warning(t('apkInstallPartial', { ok, fail }));
              } else {
                status.success(t('apkInstallSuccess', { count: ok }));
              }
            }
          } catch (e: unknown) {
            status.error(String(e instanceof Error ? e.message : e));
          } finally {
            setBatchLoading(null);
            setDroppedFile(null);
          }
        }}
      />

      {/* Paste text modal */}
      {pasteModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-modal-overlay backdrop-blur-sm" onClick={() => setPasteModal(false)}>
          <div className="rounded-[3px] border border-card-border bg-modal-bg p-5 w-full max-w-md animate-scale-in" onClick={(e) => e.stopPropagation()}>
            <h3 className="text-sm font-medium mb-3">{t('pasteTextTitle')}</h3>
            <textarea
              id="paste-text-input"
              className="input w-full h-28 resize-none"
              placeholder={t('pasteTextPlaceholder')}
              autoFocus
            />
            <div className="flex gap-2 justify-end mt-3">
              <button onClick={() => setPasteModal(false)} className="btn-secondary text-xs">{t('common:cancel')}</button>
              <button
                className="btn-primary text-xs"
                onClick={() => {
                  const text = (document.getElementById('paste-text-input') as HTMLTextAreaElement)?.value;
                  if (!text?.trim()) return;
                  setPasteModal(false);
                  runBatch(t('pasteText'), () => deviceApi.batchPaste(selectedArray, text));
                }}
              >
                {t('common:paste')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* IME selection modal */}
      {imeModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-modal-overlay backdrop-blur-sm" onClick={() => setImeModal(false)}>
          <div className="rounded-[3px] border border-card-border bg-modal-bg p-5 w-full max-w-md animate-scale-in" onClick={(e) => e.stopPropagation()}>
            <h3 className="text-sm font-medium mb-3 text-text-primary">{t('switchIme')}</h3>
            {imeList.length === 0 ? (
              <p className="text-text-muted text-xs py-4 text-center">{t('imeListEmpty')}</p>
            ) : (
              <div className="space-y-1 max-h-60 overflow-auto">
                {imeList.map((ime) => (
                  <button
                    key={ime}
                    className="w-full text-left px-3 py-2 rounded text-sm text-text-primary hover:bg-accent-blue-bg/40 transition-colors"
                    onClick={() => {
                      setImeModal(false);
                      runBatch(t('switchIme'), () => deviceApi.batchImeSet(selectedArray, ime));
                    }}
                  >
                    {ime}
                  </button>
                ))}
              </div>
            )}
            <div className="flex justify-end mt-3">
              <button onClick={() => setImeModal(false)} className="btn-secondary text-xs">{t('common:cancel')}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
