import { useEffect, useRef, useCallback, useState } from 'react';
import { useAppStore, useAuthStore } from '@/store';
import type { ConnectionMode } from '@/types';

// ── Frame protocol constants (must match Android ScreenCapture.java) ──
const FRAME_KEY   = 0x01;
const FRAME_SKIP  = 0x02;
const FRAME_DELTA = 0x03;

// ── Adaptive quality tiers ──
interface QualityTier {
  quality: number;
  maxHeight: number;
  interval: number;
  label: string;
}

const QUALITY_TIERS: QualityTier[] = [
  { quality: 75, maxHeight: 1280, interval: 33,  label: 'ultra'  },
  { quality: 65, maxHeight: 1280, interval: 33,  label: 'high'   },
  { quality: 55, maxHeight: 960,  interval: 33,  label: 'medium' },
  { quality: 45, maxHeight: 720,  interval: 40,  label: 'low'    },
  { quality: 35, maxHeight: 640,  interval: 50,  label: 'min'    },
];

interface StreamOptions {
  imei: string;
  quality?: number;
  interval?: number;
  enabled?: boolean;
}

interface StreamState {
  frame: string | null;
  mode: ConnectionMode;
  browserId: string;
  error: string | null;
  fps: number;
  tier: string;
  bandwidth: number;
}

const MAX_RECONNECT_DELAY = 16_000;
const INITIAL_RECONNECT_DELAY = 1_000;
const FPS_SAMPLE_WINDOW = 2_000;
const ADAPTIVE_INTERVAL = 3_000;
const STABLE_UPGRADE_DELAY = 8_000;

// ── OffscreenCanvas feature detection ──
const HAS_OFFSCREEN_CANVAS = typeof OffscreenCanvas !== 'undefined';

export function useStreamConnection({
  imei,
  quality = 70,
  interval = 33,
  enabled = true,
}: StreamOptions): StreamState {
  const [frame, setFrame] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fps, setFps] = useState(0);
  const [tier, setTier] = useState('high');
  const [bandwidth, setBandwidth] = useState(0);

  const browserId = useRef(
    `browser_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  ).current;

  const mode = useAppStore((s) => s.connectionModes.get(imei) ?? 'disconnected');
  const updateMode = useCallback(
    (m: ConnectionMode) => useAppStore.getState().setConnectionMode(imei, m),
    [imei],
  );

  const wsRef = useRef<WebSocket | null>(null);
  const frameUrlRef = useRef<string | null>(null);
  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const reconnectDelay = useRef(INITIAL_RECONNECT_DELAY);
  const mountedRef = useRef(true);
  const enabledRef = useRef(enabled);
  enabledRef.current = enabled;

  // ── Canvas for delta frame compositing (OffscreenCanvas or fallback HTMLCanvas) ──
  const canvasRef = useRef<OffscreenCanvas | HTMLCanvasElement | null>(null);
  const ctxRef = useRef<OffscreenCanvasRenderingContext2D | CanvasRenderingContext2D | null>(null);

  // ── FPS & bandwidth tracking (ring buffer style) ──
  const frameTimestamps = useRef<number[]>([]);
  const bytesReceived = useRef<{ ts: number; bytes: number }[]>([]);

  // ── Adaptive state ──
  const currentTierIdx = useRef(1);
  const adaptiveTimer = useRef<ReturnType<typeof setInterval> | undefined>(undefined);
  const lastDowngradeTime = useRef(0);
  const stableStartTime = useRef(0);

  // ── Frame processing queue to prevent race conditions ──
  const processingFrame = useRef(false);
  const pendingFrame = useRef<ArrayBuffer | null>(null);

  const cleanup = useCallback(() => {
    if (reconnectTimer.current) {
      clearTimeout(reconnectTimer.current);
      reconnectTimer.current = undefined as ReturnType<typeof setTimeout> | undefined;
    }
    if (adaptiveTimer.current) {
      clearInterval(adaptiveTimer.current);
      adaptiveTimer.current = undefined as ReturnType<typeof setInterval> | undefined;
    }
    if (wsRef.current) {
      wsRef.current.onopen = null;
      wsRef.current.onclose = null;
      wsRef.current.onerror = null;
      wsRef.current.onmessage = null;
      if (wsRef.current.readyState <= WebSocket.OPEN) {
        wsRef.current.close(1000, 'cleanup');
      }
      wsRef.current = null;
    }
    if (frameUrlRef.current) {
      URL.revokeObjectURL(frameUrlRef.current);
      frameUrlRef.current = null;
    }
    processingFrame.current = false;
    pendingFrame.current = null;
  }, []);

  // ── FPS measurement (optimized: avoid filter on every frame) ──
  const recordFrame = useCallback((byteSize: number) => {
    const now = performance.now();
    const cutoff = now - FPS_SAMPLE_WINDOW;

    const ts = frameTimestamps.current;
    const br = bytesReceived.current;
    ts.push(now);
    br.push({ ts: now, bytes: byteSize });

    // Trim old entries (shift from front is O(n) but arrays are small ~60 items)
    while (ts.length > 0 && ts[0] <= cutoff) ts.shift();
    while (br.length > 0 && br[0].ts <= cutoff) br.shift();

    setFps(Math.round((ts.length / FPS_SAMPLE_WINDOW) * 1000));
    let total = 0;
    for (let i = 0; i < br.length; i++) total += br[i].bytes;
    setBandwidth(Math.round((total / FPS_SAMPLE_WINDOW) * 1000 / 1024));
  }, []);

  // ── Ensure canvas is sized correctly (with OffscreenCanvas fallback) ──
  const ensureCanvas = useCallback((w: number, h: number) => {
    const cur = canvasRef.current;
    if (cur && cur.width === w && cur.height === h) return;

    if (HAS_OFFSCREEN_CANVAS) {
      canvasRef.current = new OffscreenCanvas(w, h);
      ctxRef.current = canvasRef.current.getContext('2d') as OffscreenCanvasRenderingContext2D;
    } else {
      // Fallback for Safari < 16.4 / older browsers
      const c = document.createElement('canvas');
      c.width = w;
      c.height = h;
      canvasRef.current = c;
      ctxRef.current = c.getContext('2d');
    }
  }, []);

  // ── Export canvas to blob URL for display (optimized path) ──
  const flushCanvas = useCallback(() => {
    const canvas = canvasRef.current;
    const ctx = ctxRef.current;
    if (!canvas || !ctx) return;

    if (HAS_OFFSCREEN_CANVAS && canvas instanceof OffscreenCanvas) {
      // Fast path: convertToBlob with lower re-encode quality for display
      canvas.convertToBlob({ type: 'image/webp', quality: 0.85 }).then((b) => {
        if (!mountedRef.current) return;
        if (frameUrlRef.current) URL.revokeObjectURL(frameUrlRef.current);
        const url = URL.createObjectURL(b);
        frameUrlRef.current = url;
        setFrame(url);
      }).catch(() => { /* canvas may have been replaced */ });
    } else if (canvas instanceof HTMLCanvasElement) {
      // Fallback: toDataURL (synchronous but universally supported)
      const dataUrl = canvas.toDataURL('image/webp', 0.85);
      setFrame(dataUrl);
    }
  }, []);

  // ── Decode key frame: [0x01] + WebP data ──
  const handleKeyFrame = useCallback(async (data: ArrayBuffer) => {
    const webpBlob = new Blob([new Uint8Array(data, 1)], { type: 'image/webp' });
    const bmp = await createImageBitmap(webpBlob);
    ensureCanvas(bmp.width, bmp.height);
    ctxRef.current!.drawImage(bmp, 0, 0);
    bmp.close();
    flushCanvas();
    recordFrame(data.byteLength);
  }, [ensureCanvas, flushCanvas, recordFrame]);

  // ── Decode delta frame with bounds checking ──
  const handleDeltaFrame = useCallback(async (data: ArrayBuffer) => {
    const totalLen = data.byteLength;
    if (totalLen < 3) return; // minimum: header(1) + count(2)

    const view = new DataView(data);
    const regionCount = view.getUint16(1);
    let offset = 3;

    for (let i = 0; i < regionCount; i++) {
      // Bounds check: need at least 12 bytes for region header
      if (offset + 12 > totalLen) {
        console.warn(`[Stream] Delta frame truncated at region ${i}/${regionCount}`);
        break;
      }

      const rx = view.getUint16(offset); offset += 2;
      const ry = view.getUint16(offset); offset += 2;
      const rw = view.getUint16(offset); offset += 2;
      const rh = view.getUint16(offset); offset += 2;
      const dataLen = view.getUint32(offset); offset += 4;

      // Bounds check: ensure data doesn't exceed buffer
      if (offset + dataLen > totalLen || dataLen === 0) {
        console.warn(`[Stream] Delta region ${i} data overflow: ${offset}+${dataLen} > ${totalLen}`);
        break;
      }

      const regionData = new Uint8Array(data, offset, dataLen);
      offset += dataLen;

      try {
        const regionBlob = new Blob([regionData], { type: 'image/webp' });
        const bmp = await createImageBitmap(regionBlob);
        if (ctxRef.current) {
          ctxRef.current.drawImage(bmp, rx, ry, rw, rh);
        }
        bmp.close();
      } catch (e) {
        console.warn(`[Stream] Failed to decode delta region ${i}:`, e);
        // Continue with remaining regions
      }
    }

    flushCanvas();
    recordFrame(data.byteLength);
  }, [flushCanvas, recordFrame]);

  // ── Process binary frame with error boundary + dedup ──
  const processFrame = useCallback(async (data: ArrayBuffer) => {
    // Drop frame if still processing previous one (prevent queue buildup)
    if (processingFrame.current) {
      pendingFrame.current = data; // keep only latest
      return;
    }

    processingFrame.current = true;
    try {
      await processFrameInner(data);
    } catch (e) {
      console.warn('[Stream] Frame processing error:', e);
    }
    processingFrame.current = false;

    // Process the latest pending frame if any
    const next = pendingFrame.current;
    if (next) {
      pendingFrame.current = null;
      // Use queueMicrotask to avoid deep recursion
      queueMicrotask(() => { processFrame(next); });
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const processFrameInner = useCallback(async (data: ArrayBuffer) => {
    if (data.byteLength < 1) return;
    const header = new Uint8Array(data, 0, 1)[0];

    switch (header) {
      case FRAME_KEY:
        await handleKeyFrame(data);
        break;
      case FRAME_SKIP:
        recordFrame(1);
        break;
      case FRAME_DELTA:
        if (ctxRef.current) {
          await handleDeltaFrame(data);
        }
        // If no canvas yet, silently skip — next key frame will init it
        break;
      default:
        // Legacy: raw image blob without protocol header
        try {
          const blob = new Blob([data], { type: 'image/webp' });
          if (frameUrlRef.current) URL.revokeObjectURL(frameUrlRef.current);
          const url = URL.createObjectURL(blob);
          frameUrlRef.current = url;
          setFrame(url);
          recordFrame(data.byteLength);
        } catch {
          // Silently ignore malformed frames
        }
        break;
    }
  }, [handleKeyFrame, handleDeltaFrame, recordFrame]);

  // ── Send adaptive adjustment to server ──
  const sendAdjust = useCallback((tierIdx: number) => {
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    const t = QUALITY_TIERS[tierIdx];
    try {
      ws.send(JSON.stringify({
        type: 'adjust_stream',
        quality: t.quality,
        interval: t.interval,
        max_height: t.maxHeight,
      }));
    } catch { /* ws may have closed between check and send */ }
    currentTierIdx.current = tierIdx;
    setTier(t.label);
  }, []);

  // ── Adaptive quality control loop ──
  const startAdaptiveLoop = useCallback(() => {
    if (adaptiveTimer.current) clearInterval(adaptiveTimer.current);
    stableStartTime.current = performance.now();

    adaptiveTimer.current = setInterval(() => {
      const now = performance.now();
      const currentTier = QUALITY_TIERS[currentTierIdx.current];
      const targetFps = Math.round(1000 / currentTier.interval);

      const cutoff = now - FPS_SAMPLE_WINDOW;
      const recentFrames = frameTimestamps.current.filter((t) => t > cutoff);
      const actualFps = Math.round((recentFrames.length / FPS_SAMPLE_WINDOW) * 1000);
      const fpsRatio = actualFps / Math.max(targetFps, 1);

      if (fpsRatio < 0.6 && currentTierIdx.current < QUALITY_TIERS.length - 1) {
        sendAdjust(currentTierIdx.current + 1);
        lastDowngradeTime.current = now;
        stableStartTime.current = now;
      } else if (fpsRatio > 0.9 && currentTierIdx.current > 0) {
        if (now - stableStartTime.current > STABLE_UPGRADE_DELAY
            && now - lastDowngradeTime.current > STABLE_UPGRADE_DELAY * 2) {
          sendAdjust(currentTierIdx.current - 1);
          stableStartTime.current = now;
        }
      } else if (fpsRatio < 0.6) {
        stableStartTime.current = now;
      }
    }, ADAPTIVE_INTERVAL);
  }, [sendAdjust]);

  const connect = useCallback(() => {
    if (!mountedRef.current || !enabledRef.current) return;

    cleanup();
    setError(null);
    updateMode('connecting');

    const token = useAuthStore.getState().token;
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const params = new URLSearchParams({
      quality: String(quality),
      interval: String(interval),
      browser_id: browserId,
    });
    if (token) params.set('token', token);

    const url = `${proto}//${location.host}/api/devices/${imei}/stream?${params}`;
    const ws = new WebSocket(url);
    ws.binaryType = 'arraybuffer';
    wsRef.current = ws;

    ws.onopen = () => {
      if (!mountedRef.current) return;
      reconnectDelay.current = INITIAL_RECONNECT_DELAY;
      updateMode('ws');
      setError(null);

      const startTier = QUALITY_TIERS[currentTierIdx.current];
      try {
        ws.send(JSON.stringify({
          type: 'start_stream',
          quality: startTier.quality,
          interval: startTier.interval,
          max_height: startTier.maxHeight,
        }));
      } catch { /* ws closed immediately */ }

      startAdaptiveLoop();
    };

    ws.onmessage = (ev) => {
      if (!mountedRef.current) return;
      if (ev.data instanceof ArrayBuffer && ev.data.byteLength > 0) {
        processFrame(ev.data);
      } else if (typeof ev.data === 'string') {
        try {
          const msg = JSON.parse(ev.data);
          if (msg.type === 'error') {
            setError(msg.message || 'Device error');
          }
        } catch {
          // ignore non-JSON text frames
        }
      }
    };

    ws.onerror = () => {
      if (!mountedRef.current) return;
      setError('WebSocket connection error');
    };

    ws.onclose = (ev) => {
      if (!mountedRef.current) return;
      wsRef.current = null;

      if (ev.code === 1000) {
        updateMode('disconnected');
        return;
      }

      updateMode('disconnected');
      setError(`Connection lost (${ev.code}), reconnecting...`);

      reconnectTimer.current = setTimeout(() => {
        reconnectDelay.current = Math.min(
          reconnectDelay.current * 2,
          MAX_RECONNECT_DELAY
        );
        connect();
      }, reconnectDelay.current);
    };
  }, [imei, quality, interval, browserId, cleanup, processFrame, updateMode, startAdaptiveLoop]);

  useEffect(() => {
    mountedRef.current = true;

    if (!imei || !enabled) {
      cleanup();
      updateMode('disconnected');
      setFrame(null);
      setError(null);
      return;
    }

    connect();

    return () => {
      mountedRef.current = false;
      cleanup();
      setFrame((prev) => {
        if (prev?.startsWith('blob:')) URL.revokeObjectURL(prev);
        return null;
      });
      updateMode('disconnected');
    };
  }, [imei, enabled, quality, interval, connect, cleanup, updateMode]);

  return { frame, mode, browserId, error, fps, tier, bandwidth };
}