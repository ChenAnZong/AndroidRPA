import { useState, useCallback, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { status } from '@/store';
import {
  Camera, MousePointer, ChevronRight, ChevronDown,
  Copy, RefreshCw, Crop, Download, Search, X, Play,
  MapPin, Code, Eye, EyeOff, Smartphone, ZoomIn,
  Trash2, ClipboardCopy,
} from 'lucide-react';
import { ideApi } from '@/services/api';
import type { UiNode } from '@/types';

// ── Constants ──

const HIGHLIGHT_COLORS = [
  '#3de495','#3d9cf5','#f5a623','#e84393','#00cec9','#fd79a8',
  '#6c5ce7','#ffeaa7','#55efc4','#74b9ff','#ff7675','#a29bfe',
  '#00b894','#fdcb6e','#e17055','#0984e3','#d63031','#636e72',
];

function nextColor(idx: number) { return HIGHLIGHT_COLORS[idx % HIGHLIGHT_COLORS.length]; }

function hexToRgba(hex: string, a: number) {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgba(${r},${g},${b},${a})`;
}

function getTypeIcon(cls: string): string {
  const c = cls.toLowerCase();
  if (c.includes('button')) return 'btn';
  if (c.includes('edittext') || c.includes('input')) return 'inp';
  if (c.includes('image') || c.includes('icon')) return 'img';
  if (c.includes('recycler') || c.includes('listview')) return 'lst';
  if (c.includes('scroll')) return 'scr';
  if (c.includes('webview')) return 'web';
  if (c.includes('switch') || c.includes('checkbox')) return 'chk';
  if (c.includes('viewpager') || c.includes('tab')) return 'tab';
  return '';
}

function getTypeColor(node: UiNode): string {
  if (node.clickable) return '#3de495';
  if (node.scrollable) return '#3d9cf5';
  if (node.text) return '#ffeaa7';
  return '#888';
}

// ── Props ──

interface Props {
  imei: string;
  onInsertCode: (code: string) => void;
}

type RightTab = 'tree' | 'props' | 'filter' | 'colors' | 'findimg' | 'ocr';

// ── Flat node with color for hit-test results ──
interface HitNode { node: UiNode; color: string; area: number; }

// ── Collected color for multi-point comparison ──
interface CollectedColor { hex: string; x: number; y: number; tolerance: string; }

/** Parse OCR TSV string into structured array.
 *  Each line: "confidence\ttext\tx1,y1 x2,y2 x3,y3 x4,y4" */
function parseOcrTsv(raw: string): { text: string; x: number; y: number; w: number; h: number; prob: number }[] {
  if (!raw || !raw.trim()) return [];
  return raw.trim().split('\n').map(line => {
    const parts = line.split('\t');
    if (parts.length < 3) return null;
    const prob = parseFloat(parts[0]) / 100;
    const text = parts[1];
    const coords = parts[2].split(' ').map(p => {
      const [x, y] = p.split(',').map(Number);
      return { x, y };
    });
    if (coords.length < 4) return null;
    const xs = coords.map(c => c.x), ys = coords.map(c => c.y);
    const minX = Math.min(...xs), minY = Math.min(...ys);
    return { text, x: minX, y: minY, w: Math.max(...xs) - minX, h: Math.max(...ys) - minY, prob };
  }).filter((r): r is NonNullable<typeof r> => r !== null && r.text !== '[EMPTY]');
}

export default function DevToolPanel({ imei, onInsertCode }: Props) {
  const { t } = useTranslation(['devtool', 'common']);
  // Screenshot state
  const [screenshotSrc, setScreenshotSrc] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [imgNatW, setImgNatW] = useState(1080);
  const [imgNatH, setImgNatH] = useState(2400);

  // UI tree state
  const [uiNodes, setUiNodes] = useState<UiNode[]>([]);
  const [flatNodes, setFlatNodes] = useState<UiNode[]>([]);
  const [selectedNode, setSelectedNode] = useState<UiNode | null>(null);
  const [hoveredNode, setHoveredNode] = useState<UiNode | null>(null);
  const [hitNodes, setHitNodes] = useState<HitNode[]>([]);
  const [searchResults, setSearchResults] = useState<UiNode[]>([]);

  // Mode state
  const [clickMode, setClickMode] = useState(false);
  const [cropMode, setCropMode] = useState(false);
  const [rightTab, setRightTab] = useState<RightTab>('tree');
  const [searchKeyword, setSearchKeyword] = useState('');
  const [showAllNodes, setShowAllNodes] = useState(false);

  // Coordinates
  const [mouseCoords, setMouseCoords] = useState<{x:number;y:number}|null>(null);
  const [pixelColor, setPixelColor] = useState<string|null>(null);
  const [cropRect, setCropRect] = useState<{x1:number;y1:number;x2:number;y2:number}|null>(null);
  const [cropStart, setCropStart] = useState<{x:number;y:number}|null>(null);
  const [cropPreview, setCropPreview] = useState<string|null>(null);

  // Magnifier
  const [showMagnifier, setShowMagnifier] = useState(true);
  const [magnifierPos, setMagnifierPos] = useState<{x:number;y:number}|null>(null);
  const bottomMagRef = useRef<HTMLCanvasElement>(null);

  // Color picker
  const [collectedColors, setCollectedColors] = useState<CollectedColor[]>([]);
  const [globalTolerance, setGlobalTolerance] = useState('0x0d0d0d');

  // OCR
  const [ocrResults, setOcrResults] = useState<{ text: string; x: number; y: number; w: number; h: number; prob: number }[]>([]);
  const [ocrLoading, setOcrLoading] = useState(false);
  const [ocrFilter, setOcrFilter] = useState('');

  // Find image
  const [matchThreshold, setMatchThreshold] = useState(0.8);

  // Foreground app
  const [fgApp, setFgApp] = useState<{pkg:string;activity:string;pid:string}|null>(null);

  // Refs
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const rawCanvasRef = useRef<HTMLCanvasElement>(null); // hidden canvas for pixel color picking
  const magnifierCanvasRef = useRef<HTMLCanvasElement>(null);
  const imgRef = useRef<HTMLImageElement|null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const treeScrollRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);

  // Canvas layout (contain-fit offsets)
  const layoutRef = useRef({ scale: 1, offsetX: 0, offsetY: 0, drawW: 0, drawH: 0 });

  // ── Flatten nodes for search/hit-test ──
  useEffect(() => {
    const flat: UiNode[] = [];
    const walk = (list: UiNode[]) => { for (const n of list) { flat.push(n); if (n.children) walk(n.children); } };
    walk(uiNodes);
    setFlatNodes(flat);
  }, [uiNodes]);

  // ── Screenshot ──
  const takeScreenshot = useCallback(async () => {
    setLoading(true);
    try {
      const res = await ideApi.screenshot(imei);
      const data = res.data as string;
      if (data) {
        const src = data.startsWith('data:') ? data : `data:image/jpeg;base64,${data}`;
        setScreenshotSrc(src);
      }
    } catch (e: any) { status.error(t('screenshotFailed', { msg: e.message })); }
    finally { setLoading(false); }
  }, [imei]);

  // ── Fetch UI dump ──
  const fetchUiDump = useCallback(async () => {
    setLoading(true);
    try {
      const res = await ideApi.uiDump(imei);
      const xml = typeof res.data === 'string' ? res.data : '';
      const nodes = parseUiXml(xml);
      setUiNodes(nodes);
      setSelectedNode(null);
      setHitNodes([]);
      setSearchResults([]);
      setSearchKeyword('');
      status.success(t('nodesCount', { count: countNodes(nodes) }));
    } catch (e: any) { status.error(t('nodesFailed', { msg: e.message })); }
    finally { setLoading(false); }
  }, [imei]);

  // ── Fetch foreground app ──
  const fetchForeground = useCallback(async () => {
    try {
      const res = await ideApi.foreground(imei);
      const raw = (res.data as string) || '';
      const parts = raw.trim().split(/\s+/);
      setFgApp({ pkg: parts[0] || '', activity: parts[1] || '', pid: parts[2] || '' });
    } catch { /* ignore */ }
  }, [imei]);

  // ── Combined refresh ──
  const refreshAll = useCallback(async () => {
    await Promise.all([takeScreenshot(), fetchUiDump(), fetchForeground()]);
  }, [takeScreenshot, fetchUiDump, fetchForeground]);

  // ── Draw canvas ──
  const scaleRef = useRef(1);

  const drawCanvas = useCallback(() => {
    const canvas = canvasRef.current;
    const img = imgRef.current;
    const container = containerRef.current;
    if (!canvas || !img || !container) return;

    const cw = container.clientWidth;
    const ch = container.clientHeight;
    if (cw <= 0 || ch <= 0) return;

    // Contain-fit: scale to fit both width and height
    const scale = Math.min(cw / img.naturalWidth, ch / img.naturalHeight);
    const drawW = img.naturalWidth * scale;
    const drawH = img.naturalHeight * scale;
    const offsetX = Math.round((cw - drawW) / 2);
    const offsetY = Math.round((ch - drawH) / 2);

    scaleRef.current = scale;
    layoutRef.current = { scale, offsetX, offsetY, drawW, drawH };

    canvas.width = cw;
    canvas.height = ch;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    // Clear
    ctx.clearRect(0, 0, cw, ch);

    // Draw screenshot centered
    ctx.drawImage(img, 0, 0, img.naturalWidth, img.naturalHeight, offsetX, offsetY, drawW, drawH);

    // Also draw to raw canvas for pixel color picking (no overlays)
    const rawCanvas = rawCanvasRef.current;
    if (rawCanvas) {
      rawCanvas.width = img.naturalWidth;
      rawCanvas.height = img.naturalHeight;
      const rctx = rawCanvas.getContext('2d');
      if (rctx) rctx.drawImage(img, 0, 0);
    }

    // Draw all search result highlights
    if (searchResults.length > 0) {
      searchResults.forEach((n, i) => {
        drawNodeHighlight(ctx, n, nextColor(i), scale, offsetX, offsetY, false);
      });
    }

    // Draw hit-test nodes
    if (hitNodes.length > 0) {
      hitNodes.forEach(({ node, color }) => {
        drawNodeHighlight(ctx, node, color, scale, offsetX, offsetY, false);
      });
    }

    // Draw hovered node (preview)
    if (hoveredNode && hoveredNode !== selectedNode) {
      drawNodeHighlight(ctx, hoveredNode, '#ffffff', scale, offsetX, offsetY, true);
    }

    // Draw selected node (persistent)
    if (selectedNode) {
      drawNodeHighlight(ctx, selectedNode, '#00ff00', scale, offsetX, offsetY, false);
    }

    // Draw show-all overlay
    if (showAllNodes && flatNodes.length > 0) {
      flatNodes.forEach((n, i) => {
        const b = n.bounds;
        if (b.x2 - b.x1 > 0 && b.y2 - b.y1 > 0) {
          ctx.strokeStyle = hexToRgba(nextColor(i), 0.3);
          ctx.lineWidth = 1;
          ctx.strokeRect(b.x1 * scale + offsetX, b.y1 * scale + offsetY, (b.x2 - b.x1) * scale, (b.y2 - b.y1) * scale);
        }
      });
    }

    // Draw crop selection
    if (cropRect) {
      const cx1 = Math.min(cropRect.x1, cropRect.x2) * scale + offsetX;
      const cy1 = Math.min(cropRect.y1, cropRect.y2) * scale + offsetY;
      const cw2 = Math.abs(cropRect.x2 - cropRect.x1) * scale;
      const ch2 = Math.abs(cropRect.y2 - cropRect.y1) * scale;
      // Dim outside
      ctx.fillStyle = 'rgba(0,0,0,0.45)';
      ctx.fillRect(offsetX, offsetY, drawW, cy1 - offsetY);
      ctx.fillRect(offsetX, cy1, cx1 - offsetX, ch2);
      ctx.fillRect(cx1 + cw2, cy1, drawW - (cx1 - offsetX) - cw2, ch2);
      ctx.fillRect(offsetX, cy1 + ch2, drawW, (offsetY + drawH) - (cy1 + ch2));
      // Dashed border
      ctx.strokeStyle = '#007acc';
      ctx.lineWidth = 2;
      ctx.setLineDash([5, 3]);
      ctx.strokeRect(cx1, cy1, cw2, ch2);
      ctx.setLineDash([]);
      // Size label
      const rw = Math.abs(cropRect.x2 - cropRect.x1);
      const rh = Math.abs(cropRect.y2 - cropRect.y1);
      if (rw > 5 && rh > 5) {
        const label = `${rw}×${rh}`;
        ctx.font = '11px monospace';
        const tw = ctx.measureText(label).width + 8;
        ctx.fillStyle = 'rgba(0,122,204,0.85)';
        ctx.fillRect(cx1, cy1 - 18, tw, 16);
        ctx.fillStyle = '#fff';
        ctx.fillText(label, cx1 + 4, cy1 - 5);
      }
    }

    // Draw crosshair
    if (mouseCoords) {
      const cx = mouseCoords.x * scale + offsetX;
      const cy = mouseCoords.y * scale + offsetY;
      ctx.save();
      ctx.strokeStyle = 'rgba(255,255,255,0.35)';
      ctx.setLineDash([4, 3]);
      ctx.lineWidth = 0.5;
      ctx.beginPath();
      ctx.moveTo(offsetX, cy); ctx.lineTo(offsetX + drawW, cy);
      ctx.moveTo(cx, offsetY); ctx.lineTo(cx, offsetY + drawH);
      ctx.stroke();
      ctx.setLineDash([]);
      // Center dot with outline
      ctx.strokeStyle = 'rgba(0,0,0,0.6)';
      ctx.lineWidth = 1.5;
      ctx.beginPath(); ctx.arc(cx, cy, 3, 0, Math.PI * 2); ctx.stroke();
      ctx.fillStyle = '#fff';
      ctx.beginPath(); ctx.arc(cx, cy, 2, 0, Math.PI * 2); ctx.fill();
      ctx.restore();
    }
  }, [selectedNode, hoveredNode, hitNodes, searchResults, cropRect, showAllNodes, flatNodes, mouseCoords]);

  // Load image when screenshot changes
  useEffect(() => {
    if (!screenshotSrc) return;
    const img = new Image();
    img.onload = () => {
      imgRef.current = img;
      setImgNatW(img.naturalWidth);
      setImgNatH(img.naturalHeight);
      drawCanvas();
    };
    img.src = screenshotSrc;
  }, [screenshotSrc]);

  // Redraw on state changes
  useEffect(() => { drawCanvas(); }, [drawCanvas]);

  // ── Canvas coordinate mapping ──
  const canvasToDevice = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas || !imgRef.current) return null;
    const rect = canvas.getBoundingClientRect();
    const { scale, offsetX, offsetY, drawW, drawH } = layoutRef.current;
    const cx = e.clientX - rect.left - offsetX;
    const cy = e.clientY - rect.top - offsetY;
    if (cx < 0 || cy < 0 || cx > drawW || cy > drawH) return null;
    return {
      x: Math.round(cx / scale),
      y: Math.round(cy / scale),
      canvasX: e.clientX - rect.left,
      canvasY: e.clientY - rect.top,
    };
  }, []);

  // ── Pick pixel color from raw canvas ──
  const pickPixelColor = useCallback((deviceX: number, deviceY: number): string | null => {
    const rawCanvas = rawCanvasRef.current;
    if (!rawCanvas) return null;
    const rctx = rawCanvas.getContext('2d');
    if (!rctx) return null;
    try {
      const px = rctx.getImageData(deviceX, deviceY, 1, 1).data;
      return '#' + [px[0], px[1], px[2]].map(v => v.toString(16).padStart(2, '0')).join('');
    } catch { return null; }
  }, []);

  // ── Draw magnifier ──
  const drawMagnifier = useCallback((deviceX: number, deviceY: number) => {
    const magCanvas = magnifierCanvasRef.current;
    const img = imgRef.current;
    if (!magCanvas || !img || !showMagnifier) return;
    const magCtx = magCanvas.getContext('2d');
    if (!magCtx) return;
    const size = 150;
    const srcSize = 50;
    magCanvas.width = size;
    magCanvas.height = size;
    magCtx.imageSmoothingEnabled = false;
    // Draw zoomed region from original image
    magCtx.drawImage(img,
      deviceX - srcSize / 2, deviceY - srcSize / 2, srcSize, srcSize,
      0, 0, size, size,
    );
    // Pixel grid lines
    const cellSize = size / srcSize; // 3px per pixel
    magCtx.strokeStyle = 'rgba(255,255,255,0.08)';
    magCtx.lineWidth = 0.5;
    for (let i = 0; i <= srcSize; i++) {
      const p = i * cellSize;
      magCtx.beginPath(); magCtx.moveTo(p, 0); magCtx.lineTo(p, size); magCtx.stroke();
      magCtx.beginPath(); magCtx.moveTo(0, p); magCtx.lineTo(size, p); magCtx.stroke();
    }
    // Center crosshair
    const mid = size / 2;
    magCtx.strokeStyle = 'rgba(255,255,255,0.7)';
    magCtx.lineWidth = 1;
    magCtx.beginPath();
    magCtx.moveTo(mid, 0); magCtx.lineTo(mid, mid - cellSize);
    magCtx.moveTo(mid, mid + cellSize); magCtx.lineTo(mid, size);
    magCtx.moveTo(0, mid); magCtx.lineTo(mid - cellSize, mid);
    magCtx.moveTo(mid + cellSize, mid); magCtx.lineTo(size, mid);
    magCtx.stroke();
    // Center pixel highlight
    magCtx.strokeStyle = '#ff0';
    magCtx.lineWidth = 1.5;
    magCtx.strokeRect(mid - cellSize / 2, mid - cellSize / 2, cellSize, cellSize);

    // Also draw to bottom magnifier panel (larger, 100x100 display of 50x50 src)
    const bMag = bottomMagRef.current;
    if (bMag) {
      const bCtx = bMag.getContext('2d');
      if (bCtx) {
        const bSize = 100;
        const bSrc = 25;
        bMag.width = bSize; bMag.height = bSize;
        bCtx.imageSmoothingEnabled = false;
        bCtx.drawImage(img, deviceX - bSrc / 2, deviceY - bSrc / 2, bSrc, bSrc, 0, 0, bSize, bSize);
        // Grid
        const bCell = bSize / bSrc;
        bCtx.strokeStyle = 'rgba(255,255,255,0.06)';
        bCtx.lineWidth = 0.5;
        for (let i = 0; i <= bSrc; i++) {
          const p = i * bCell;
          bCtx.beginPath(); bCtx.moveTo(p, 0); bCtx.lineTo(p, bSize); bCtx.stroke();
          bCtx.beginPath(); bCtx.moveTo(0, p); bCtx.lineTo(bSize, p); bCtx.stroke();
        }
        // Crosshair
        const bMid = bSize / 2;
        bCtx.strokeStyle = 'rgba(255,255,255,0.6)';
        bCtx.lineWidth = 1;
        bCtx.beginPath();
        bCtx.moveTo(bMid, 0); bCtx.lineTo(bMid, bMid - bCell);
        bCtx.moveTo(bMid, bMid + bCell); bCtx.lineTo(bMid, bSize);
        bCtx.moveTo(0, bMid); bCtx.lineTo(bMid - bCell, bMid);
        bCtx.moveTo(bMid + bCell, bMid); bCtx.lineTo(bSize, bMid);
        bCtx.stroke();
        bCtx.strokeStyle = '#ff0';
        bCtx.lineWidth = 1.5;
        bCtx.strokeRect(bMid - bCell / 2, bMid - bCell / 2, bCell, bCell);
      }
    }
  }, [showMagnifier]);

  // ── Canvas mouse handlers ──
  const handleCanvasMouseMove = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    const coords = canvasToDevice(e);
    if (coords) {
      setMouseCoords({ x: coords.x, y: coords.y });
      setMagnifierPos({ x: coords.canvasX, y: coords.canvasY });
      // Pick color
      const color = pickPixelColor(coords.x, coords.y);
      if (color) setPixelColor(color);
      // Draw magnifier
      drawMagnifier(coords.x, coords.y);
    } else {
      setMouseCoords(null);
      setMagnifierPos(null);
      setPixelColor(null);
    }
    if (cropMode && cropStart && coords) {
      setCropRect({
        x1: Math.min(cropStart.x, coords.x), y1: Math.min(cropStart.y, coords.y),
        x2: Math.max(cropStart.x, coords.x), y2: Math.max(cropStart.y, coords.y),
      });
    }
  }, [canvasToDevice, pickPixelColor, drawMagnifier, cropMode, cropStart]);

  const handleCanvasMouseDown = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!cropMode || !imgRef.current) return;
    const coords = canvasToDevice(e);
    if (!coords) return;
    setCropStart({ x: coords.x, y: coords.y });
    setCropRect(null);
    setCropPreview(null);
  }, [cropMode, canvasToDevice]);

  const handleCanvasMouseUp = useCallback(() => {
    if (cropMode && cropStart && cropRect) {
      setCropStart(null);
      // Generate crop preview
      const img = imgRef.current;
      if (img) {
        const x = Math.min(cropRect.x1, cropRect.x2);
        const y = Math.min(cropRect.y1, cropRect.y2);
        const w = Math.abs(cropRect.x2 - cropRect.x1);
        const h = Math.abs(cropRect.y2 - cropRect.y1);
        if (w > 5 && h > 5) {
          const off = document.createElement('canvas');
          off.width = w; off.height = h;
          off.getContext('2d')!.drawImage(img, x, y, w, h, 0, 0, w, h);
          setCropPreview(off.toDataURL('image/jpeg', 0.92));
        }
      }
    }
  }, [cropMode, cropStart, cropRect]);

  // ── Canvas click: hit-test or device click ──
  const handleCanvasClick = useCallback(async (e: React.MouseEvent<HTMLCanvasElement>) => {
    const coords = canvasToDevice(e);
    if (!coords) return;
    if (cropMode) return;

    if (clickMode) {
      try {
        await ideApi.click(imei, coords.x, coords.y);
        status.success(t('clickAt', { x: coords.x, y: coords.y }));
        setTimeout(() => { takeScreenshot(); fetchUiDump(); }, 500);
      } catch (err: any) { status.error(err.message); }
      return;
    }

    // Hit-test: find ALL nodes containing this point
    if (flatNodes.length > 0) {
      const matching = flatNodes
        .filter(n => {
          const b = n.bounds;
          return coords.x >= b.x1 && coords.x <= b.x2 && coords.y >= b.y1 && coords.y <= b.y2
            && (b.x2 - b.x1) > 0 && (b.y2 - b.y1) > 0;
        })
        .map((node, i) => ({
          node,
          color: nextColor(i),
          area: (node.bounds.x2 - node.bounds.x1) * (node.bounds.y2 - node.bounds.y1),
        }))
        .sort((a, b) => a.area - b.area);

      if (matching.length > 0) {
        setHitNodes(matching);
        setSelectedNode(matching[0].node);
        if (matching.length > 1) {
          setRightTab('filter');
        } else {
          setRightTab('props');
        }
      }
    }
  }, [canvasToDevice, flatNodes, clickMode, cropMode, imei, takeScreenshot, fetchUiDump]);

  // ── Double-click: always send tap ──
  const handleCanvasDblClick = useCallback(async (e: React.MouseEvent<HTMLCanvasElement>) => {
    const coords = canvasToDevice(e);
    if (!coords) return;
    try {
      await ideApi.click(imei, coords.x, coords.y);
      status.success(t('clickAt', { x: coords.x, y: coords.y }));
      setTimeout(() => { takeScreenshot(); fetchUiDump(); }, 500);
    } catch (err: any) { status.error(err.message); }
  }, [canvasToDevice, imei, takeScreenshot, fetchUiDump]);

  // ── Mouse leave: hide magnifier & crosshair ──
  const handleCanvasMouseLeave = useCallback(() => {
    setMouseCoords(null);
    setMagnifierPos(null);
    setPixelColor(null);
  }, []);

  // ── Spacebar: collect color ──
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.code === 'Space' && mouseCoords && pixelColor && rightTab === 'colors') {
        e.preventDefault();
        setCollectedColors(prev => [...prev, {
          hex: pixelColor, x: mouseCoords.x, y: mouseCoords.y, tolerance: globalTolerance,
        }]);
        status.success(t('colorPicked', { color: pixelColor, x: mouseCoords.x, y: mouseCoords.y }));
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [mouseCoords, pixelColor, rightTab, globalTolerance]);

  // ── ResizeObserver for container ──
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const ro = new ResizeObserver(() => drawCanvas());
    ro.observe(container);
    return () => ro.disconnect();
  }, [drawCanvas]);

  // ── Search ──
  const doSearch = useCallback((keyword: string) => {
    if (!keyword.trim() || flatNodes.length === 0) {
      setSearchResults([]);
      return;
    }
    const kw = keyword.trim().toLowerCase();
    const matches = flatNodes.filter(n => {
      return (n.className || '').toLowerCase().includes(kw)
        || (n.resourceId || '').toLowerCase().includes(kw)
        || (n.text || '').toLowerCase().includes(kw)
        || (n.contentDesc || '').toLowerCase().includes(kw);
    });
    setSearchResults(matches);
    setHitNodes([]);
    if (matches.length > 0) {
      setSelectedNode(matches[0]);
      status.success(t('matchesFound', { count: matches.length }));
    } else {
      status.info(t('noMatch'));
    }
  }, [flatNodes]);

  // ── Save crop ──
  const saveCropImage = useCallback(() => {
    if (!cropRect || !imgRef.current) return;
    const img = imgRef.current;
    const x = Math.min(cropRect.x1, cropRect.x2);
    const y = Math.min(cropRect.y1, cropRect.y2);
    const w = Math.abs(cropRect.x2 - cropRect.x1);
    const h = Math.abs(cropRect.y2 - cropRect.y1);
    if (w < 5 || h < 5) { status.error(t('cropTooSmall')); return; }
    const off = document.createElement('canvas');
    off.width = w; off.height = h;
    off.getContext('2d')!.drawImage(img, x, y, w, h, 0, 0, w, h);
    off.toBlob((blob) => {
      if (!blob) return;
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `crop_${x}_${y}_${w}x${h}.jpg`;
      a.click();
      URL.revokeObjectURL(url);
      status.success(t('cropSaved', { w, h }));
      setCropMode(false); setCropRect(null); setCropPreview(null);
    }, 'image/jpeg', 0.95);
  }, [cropRect]);

  // ── XPath generation ──
  const buildXPath = useCallback((target: UiNode): string => {
    const path: string[] = [];
    const walk = (nodes: UiNode[], ancestors: string[]): boolean => {
      for (const n of nodes) {
        const seg = n.resourceId
          ? `//*[@resource-id="${n.resourceId}"]`
          : n.text
            ? `//*[@text="${n.text}"]`
            : n.contentDesc
              ? `//*[@content-desc="${n.contentDesc}"]`
              : `//${n.className.split('.').pop() || 'node'}[${n.index + 1}]`;
        const cur = [...ancestors, seg];
        if (n === target) { path.push(...cur); return true; }
        if (n.children && walk(n.children, cur)) return true;
      }
      return false;
    };
    walk(uiNodes, []);
    // Simplify: use last unique identifier
    if (target.resourceId) return `//*[@resource-id="${target.resourceId}"]`;
    if (target.text) return `//*[@text="${target.text}"]`;
    if (target.contentDesc) return `//*[@content-desc="${target.contentDesc}"]`;
    return path[path.length - 1] || '//node';
  }, [uiNodes]);

  // ── Code generation ──
  const generateCodeSnippets = useCallback((node: UiNode) => {
    const b = node.bounds;
    const cx = Math.round((b.x1 + b.x2) / 2);
    const cy = Math.round((b.y1 + b.y2) / 2);
    const h = b.y2 - b.y1;
    const idShort = node.resourceId ? node.resourceId.split('/').pop() || '' : '';
    const clsShort = node.className.split('.').pop() || '';

    // Build ui_match params
    const matchParams: string[] = [];
    if (idShort) matchParams.push(`id="${idShort}"`);
    if (node.text) matchParams.push(`text="${node.text.replace(/"/g, '\\"')}"`);
    if (node.contentDesc) matchParams.push(`desc="${node.contentDesc.replace(/"/g, '\\"')}"`);
    if (matchParams.length === 0) matchParams.push(`cls="${clsShort}"`);
    const findExpr = `ui_match(${matchParams.join(', ')})`;

    const snippets: { label: string; code: string }[] = [
      { label: t('codeCoordClick'), code: `auto.click(${cx}, ${cy})` },
      { label: t('codeNodeFind'), code: `node = ${findExpr}\nif node:\n    auto.click(node.center_x, node.center_y)` },
      { label: t('codeRatioClick'), code: `# ${t('codeRatioClickDesc')}\nauto.click_ratio(${(cx / imgNatW).toFixed(4)}, ${(cy / imgNatH).toFixed(4)})` },
      { label: t('codeRegionLimit'), code: `region = (${b.x1}, ${b.y1}, ${b.x2}, ${b.y2})` },
    ];

    if (node.text) {
      snippets.push({ label: t('codeOcrFind'), code: `# ${t('codeOcrFindDesc')}\nresult = auto.ocr_find("${node.text}")\nif result:\n    auto.click(result.center_x, result.center_y)` });
      snippets.push({ label: t('codeNodeOcr'), code: `# ${t('codeNodeOcrDesc')}\nnode = ${findExpr}\nif node:\n    auto.click(node.center_x, node.center_y)\nelse:\n    result = auto.ocr_find("${node.text}")\n    if result:\n        auto.click(result.center_x, result.center_y)` });
    }
    if (node.scrollable) {
      snippets.push({ label: t('codeSwipe'), code: `auto.swipe(${cx}, ${cy + Math.round(h * 0.3)}, ${cx}, ${cy - Math.round(h * 0.3)}, 300)` });
    }
    if (node.clickable) {
      snippets.push({ label: t('codeWaitClick'), code: `# ${t('codeWaitClickDesc')}\nauto.wait_and_click(${findExpr}, timeout=10)` });
    }
    // image+color combined strategy
    snippets.push({ label: t('codeRegionColor'), code: `# ${t('codeRegionColorDesc')}\nresult = auto.find_color("#FFFFFF", region=(${b.x1}, ${b.y1}, ${b.x2}, ${b.y2}))` });
    snippets.push({ label: t('codeRegionOcr'), code: `# ${t('codeRegionOcrDesc')}\nresult = auto.ocr(region=(${b.x1}, ${b.y1}, ${b.x2}, ${b.y2}))\nfor item in result:\n    print(item.text)` });

    return snippets;
  }, [imgNatW, imgNatH]);

  // ── Select node from tree ──
  const selectNode = useCallback((node: UiNode) => {
    setSelectedNode(node);
    setRightTab('props');
    setHitNodes([]);
  }, []);

  // ── Locate node in tree ──
  const locateInTree = useCallback((_node: UiNode) => {
    setRightTab('tree');
    // Expand parents - handled by tree component via selectedNode
  }, []);

  // ── JSX ──
  return (
    <div className="flex h-full text-[13px] bg-[#1e1e1e]">
      {/* ── Left: Screenshot Canvas ── */}
      <div className="flex flex-col w-[45%] min-w-[200px] border-r border-[#3c3c3c]">
        {/* Toolbar */}
        <div className="flex items-center gap-1 h-8 px-2 bg-[#252526] border-b border-[#3c3c3c] shrink-0">
          <button onClick={refreshAll} disabled={loading} className="p-1 hover:bg-[#3c3c3c] rounded" title={t('refreshAll')}>
            <RefreshCw size={13} className={loading ? 'animate-spin' : ''} />
          </button>
          <button onClick={takeScreenshot} disabled={loading} className="p-1 hover:bg-[#3c3c3c] rounded" title={t('screenshotOnly')}>
            <Camera size={13} />
          </button>
          <div className="w-px h-4 bg-[#3c3c3c] mx-0.5" />
          <button
            onClick={() => setClickMode(!clickMode)}
            className={`p-1 rounded ${clickMode ? 'bg-[#007acc] text-white' : 'hover:bg-[#3c3c3c]'}`}
            title={clickMode ? t('clickModeOn') : t('clickModeOff')}
          >
            <MousePointer size={13} />
          </button>
          <button
            onClick={() => { setCropMode(!cropMode); if (cropMode) { setCropRect(null); setCropStart(null); setCropPreview(null); } }}
            className={`p-1 rounded ${cropMode ? 'bg-[#007acc] text-white' : 'hover:bg-[#3c3c3c]'}`}
            title={cropMode ? t('cropModeOn') : t('cropModeOff')}
          >
            <Crop size={13} />
          </button>
          <button
            onClick={() => setShowAllNodes(!showAllNodes)}
            className={`p-1 rounded ${showAllNodes ? 'bg-[#6c5ce7] text-white' : 'hover:bg-[#3c3c3c]'}`}
            title={showAllNodes ? t('showAllBordersOn') : t('showAllBordersOff')}
          >
            {showAllNodes ? <Eye size={13} /> : <EyeOff size={13} />}
          </button>
          <button
            onClick={() => setShowMagnifier(!showMagnifier)}
            className={`p-1 rounded ${showMagnifier ? 'bg-[#e17055] text-white' : 'hover:bg-[#3c3c3c]'}`}
            title={showMagnifier ? t('magnifierOn') : t('magnifierOff')}
          >
            <ZoomIn size={13} />
          </button>
          <div className="flex-1" />
        </div>

        {/* Foreground app info */}
        {fgApp && fgApp.pkg && (
          <div className="flex items-center gap-2 h-6 px-2 bg-[#1a1a2e] border-b border-[#3c3c3c] text-[10px] shrink-0">
            <Smartphone size={10} className="text-gray-500 shrink-0" />
            <span className="text-[#74b9ff] cursor-pointer truncate hover:underline" onClick={() => { navigator.clipboard.writeText(fgApp.pkg); status.success(t('copiedPkg')); }} title={t('clickToCopyPkg')}>{fgApp.pkg}</span>
            {fgApp.activity && <span className="text-gray-500 truncate">{fgApp.activity.split('.').pop()}</span>}
            {fgApp.pid && <span className="text-gray-600">PID:{fgApp.pid}</span>}
          </div>
        )}

        {/* Canvas area */}
        <div ref={containerRef} className="flex-1 overflow-hidden relative">
          {screenshotSrc ? (
            <>
              <canvas
                ref={canvasRef}
                className={`absolute inset-0 w-full h-full ${cropMode ? 'cursor-crosshair' : clickMode ? 'cursor-pointer' : 'cursor-crosshair'}`}
                onMouseDown={handleCanvasMouseDown}
                onMouseMove={handleCanvasMouseMove}
                onMouseUp={handleCanvasMouseUp}
                onClick={handleCanvasClick}
                onDoubleClick={handleCanvasDblClick}
                onMouseLeave={handleCanvasMouseLeave}
              />
              {/* Hidden raw canvas for pixel color picking */}
              <canvas ref={rawCanvasRef} className="hidden" />
              {/* Magnifier */}
              {showMagnifier && magnifierPos && mouseCoords && (
                <div
                  className="absolute pointer-events-none z-10 border border-[#555] rounded shadow-lg"
                  style={{
                    left: magnifierPos.x > (containerRef.current?.clientWidth ?? 0) / 2
                      ? magnifierPos.x - 166 : magnifierPos.x + 16,
                    top: magnifierPos.y > (containerRef.current?.clientHeight ?? 0) / 2
                      ? magnifierPos.y - 166 : magnifierPos.y + 16,
                  }}
                >
                  <canvas ref={magnifierCanvasRef} width={150} height={150} className="rounded" />
                  {pixelColor && (
                    <div className="absolute bottom-0 left-0 right-0 bg-black/70 text-[9px] text-center py-0.5 rounded-b font-mono" style={{ color: pixelColor }}>
                      {pixelColor.toUpperCase()}
                    </div>
                  )}
                </div>
              )}
            </>
          ) : (
            <div className="flex flex-col items-center justify-center h-full text-gray-500">
              <Camera size={36} className="mb-3 opacity-30" />
              <p className="text-xs mb-2">{t('clickToScreenshot')}</p>
              <button onClick={refreshAll} className="text-xs px-3 py-1 bg-[#007acc] text-white rounded hover:bg-[#005f9e]">
                {t('screenshotAndDump')}
              </button>
            </div>
          )}
        </div>

        {/* Bottom info panel: magnifier + color + coords */}
        <div className="flex items-stretch gap-2 px-2 py-1.5 bg-[#1a1a2e] border-t border-[#3c3c3c] shrink-0">
          {/* Magnifier preview */}
          <div className="shrink-0">
            <canvas ref={bottomMagRef} width={100} height={100}
              className="rounded border border-[#444] bg-black"
              style={{ width: 80, height: 80, imageRendering: 'pixelated' }}
            />
          </div>
          {/* Color + Coords info */}
          <div className="flex flex-col justify-center gap-1 min-w-0 flex-1 text-[10px] font-mono tabular-nums">
            {/* Coordinates */}
            <div className="flex items-center gap-2 text-gray-400">
              {mouseCoords ? (
                <>
                  <span className="text-[#dcdcaa]">X:<span className="text-white">{mouseCoords.x}</span></span>
                  <span className="text-[#dcdcaa]">Y:<span className="text-white">{mouseCoords.y}</span></span>
                  {imgNatW > 0 && (
                    <span className="text-gray-600">
                      ({(mouseCoords.x / imgNatW).toFixed(2)},{(mouseCoords.y / imgNatH).toFixed(2)})
                    </span>
                  )}
                </>
              ) : (
                <span className="text-gray-600">{t('moveMouseForCoords')}</span>
              )}
            </div>
            {/* Color HEX */}
            <div className="flex items-center gap-2">
              {pixelColor ? (
                <>
                  <span className="inline-block w-5 h-5 rounded border border-[#555] shrink-0" style={{ backgroundColor: pixelColor }} />
                  <span className="text-[12px] font-bold tracking-wide" style={{ color: pixelColor }}>{pixelColor.toUpperCase()}</span>
                  <button
                    onClick={() => { navigator.clipboard.writeText(pixelColor.toUpperCase()); status.success(t('copied') + ' ' + pixelColor.toUpperCase()); }}
                    className="text-[9px] px-1.5 py-0.5 bg-[#333] hover:bg-[#444] text-gray-300 rounded"
                  >{t('copy')}</button>
                </>
              ) : (
                <span className="text-gray-600 text-[10px]">—</span>
              )}
            </div>
            {/* Resolution + crop info */}
            <div className="flex items-center gap-2 text-gray-500">
              {imgNatW > 0 && <span>{imgNatW}×{imgNatH}</span>}
              {cropRect && (
                <span className="text-[#007acc]">
                  {t('cropArea')} {Math.abs(cropRect.x2 - cropRect.x1)}×{Math.abs(cropRect.y2 - cropRect.y1)}
                  {' '}@({Math.min(cropRect.x1, cropRect.x2)},{Math.min(cropRect.y1, cropRect.y2)})
                </span>
              )}
            </div>
          </div>
        </div>

        {/* Crop preview bar */}
        {cropMode && cropRect && (
          <div className="flex items-center gap-2 h-10 px-2 bg-[#252526] border-t border-[#3c3c3c] shrink-0">
            {cropPreview && <img src={cropPreview} alt="crop" className="h-8 w-8 object-contain border border-[#555] rounded" />}
            <span className="text-[10px] text-gray-400 font-mono">
              {Math.abs(cropRect.x2 - cropRect.x1)}×{Math.abs(cropRect.y2 - cropRect.y1)}
              {' '}({Math.min(cropRect.x1, cropRect.x2)},{Math.min(cropRect.y1, cropRect.y2)})
            </span>
            <div className="flex-1" />
            {cropPreview && (
              <button onClick={() => {
                // Copy crop to clipboard
                const c = document.createElement('canvas');
                const img = imgRef.current;
                if (!img) return;
                const rx = Math.min(cropRect.x1, cropRect.x2), ry = Math.min(cropRect.y1, cropRect.y2);
                const rw = Math.abs(cropRect.x2 - cropRect.x1), rh = Math.abs(cropRect.y2 - cropRect.y1);
                c.width = rw; c.height = rh;
                const cx = c.getContext('2d');
                if (cx) { cx.drawImage(img, rx, ry, rw, rh, 0, 0, rw, rh); }
                c.toBlob(blob => { if (blob) navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]); status.success(t('copiedToClipboard')); });
              }} className="text-[11px] px-2 py-0.5 bg-[#6c5ce7] hover:bg-[#5b4cdb] text-white rounded flex items-center gap-1">
                <ClipboardCopy size={11} /> {t('copy')}
              </button>
            )}
            <button onClick={() => {
              // Generate findImage code
              const rx = Math.min(cropRect.x1, cropRect.x2), ry = Math.min(cropRect.y1, cropRect.y2);
              const rw = Math.abs(cropRect.x2 - cropRect.x1), rh = Math.abs(cropRect.y2 - cropRect.y1);
              const code = `# ${t('findImageCrop')} ${rw}×${rh}\nresult = auto.find_image("template.png", region=(${rx}, ${ry}, ${rx + rw}, ${ry + rh}), threshold=${matchThreshold})`;
              onInsertCode(code);
              status.success(t('insertedFindImageCode'));
            }} className="text-[11px] px-2 py-0.5 bg-[#007acc] hover:bg-[#005f9e] text-white rounded flex items-center gap-1">
              <Code size={11} /> {t('findImageCode')}
            </button>
            <button onClick={saveCropImage} className="text-[11px] px-2 py-0.5 bg-green-700 hover:bg-green-600 text-white rounded flex items-center gap-1">
              <Download size={11} /> {t('common:save')}
            </button>
            <button onClick={() => { setCropMode(false); setCropRect(null); setCropPreview(null); }} className="text-[11px] px-2 py-0.5 bg-[#3c3c3c] hover:bg-[#555] rounded">{t('cancel')}</button>
          </div>
        )}
      </div>

      {/* ── Right: Tree / Props / Filter ── */}
      <div className="flex flex-col flex-1 min-w-0">
        {/* Right tabs + search */}
        <div className="flex items-center h-8 bg-[#252526] border-b border-[#3c3c3c] shrink-0">
          {([
            ['tree', `${t('tabNodeTree')}${flatNodes.length ? ` (${flatNodes.length})` : ''}`],
            ['props', t('tabProperties')],
            ['filter', `${t('tabHitNodes')}${hitNodes.length ? ` (${hitNodes.length})` : ''}`],
            ['colors', `${t('tabColorPick')}${collectedColors.length ? ` (${collectedColors.length})` : ''}`],
            ['findimg', t('tabFindImage')],
            ['ocr', 'OCR'],
          ] as [RightTab, string][]).map(([t, label]) => (
            <button key={t} onClick={() => setRightTab(t)}
              className={`px-2 h-full text-[11px] border-b-2 whitespace-nowrap ${rightTab === t ? 'text-white border-[#007acc]' : 'text-gray-500 border-transparent hover:text-gray-300'}`}
            >
              {label}
            </button>
          ))}
          <div className="flex-1" />
          {rightTab === 'tree' && (
            <div className="flex items-center mr-1">
              <div className="flex items-center bg-[#3c3c3c] rounded h-5 px-1.5">
                <Search size={11} className="text-gray-400 shrink-0" />
                <input
                  ref={searchInputRef}
                  value={searchKeyword}
                  onChange={(e) => setSearchKeyword(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter') doSearch(searchKeyword); if (e.key === 'Escape') { setSearchKeyword(''); setSearchResults([]); } }}
                  placeholder={t('searchNodes')}
                  className="bg-transparent text-[11px] text-white outline-none w-24 ml-1 placeholder-gray-600"
                />
                {searchKeyword && (
                  <button onClick={() => { setSearchKeyword(''); setSearchResults([]); }} className="text-gray-500 hover:text-white"><X size={11} /></button>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Right content */}
        <div ref={treeScrollRef} className="flex-1 overflow-auto">
          {rightTab === 'tree' && (
            <div className="py-0.5">
              {flatNodes.length === 0 ? (
                <div className="px-3 py-8 text-xs text-gray-500 text-center">{t('clickToGrabNodes')}</div>
              ) : (
                <>
                  {searchResults.length > 0 && (
                    <div className="px-2 py-1 text-[10px] text-[#f5a623] bg-[#2a2a1e] border-b border-[#3c3c3c]">
                      {t('searchResults', { count: searchResults.length })}
                    </div>
                  )}
                  {uiNodes.map((node, i) => (
                    <UiTreeNode key={i} node={node} depth={0} selected={selectedNode} searchResults={searchResults} onSelect={selectNode} onHover={setHoveredNode} />
                  ))}
                </>
              )}
            </div>
          )}

          {rightTab === 'props' && selectedNode && (
            <PropsPanel node={selectedNode} imgW={imgNatW} imgH={imgNatH} onInsertCode={onInsertCode}
              generateCodeSnippets={generateCodeSnippets} buildXPath={buildXPath} onLocate={locateInTree}
              onClickNode={async (n) => {
                const cx = Math.round((n.bounds.x1 + n.bounds.x2) / 2);
                const cy = Math.round((n.bounds.y1 + n.bounds.y2) / 2);
                try { await ideApi.click(imei, cx, cy); status.success(t('clickAt', { x: cx, y: cy })); setTimeout(() => { takeScreenshot(); fetchUiDump(); }, 500); }
                catch (err: any) { status.error(err.message); }
              }}
            />
          )}
          {rightTab === 'props' && !selectedNode && (
            <div className="px-3 py-8 text-xs text-gray-500 text-center">{t('clickOrSelectNode')}</div>
          )}

          {rightTab === 'filter' && (
            <FilterPanel hitNodes={hitNodes} selectedNode={selectedNode}
              onSelect={(hn) => { setSelectedNode(hn.node); setRightTab('props'); }}
              onHover={(hn) => setHoveredNode(hn?.node || null)}
            />
          )}

          {/* ── Color picker panel ── */}
          {rightTab === 'colors' && (
            <div className="py-1 px-2 space-y-2">
              {/* Current color preview */}
              <div className="flex items-center gap-2 p-2 bg-[#1e1e1e] rounded">
                <div className="w-8 h-8 rounded border border-[#555]" style={{ backgroundColor: pixelColor || '#000' }} />
                <div className="text-[11px] font-mono">
                  <div style={{ color: pixelColor || '#888' }}>{pixelColor?.toUpperCase() || t('moveToPickColor')}</div>
                  {mouseCoords && <div className="text-gray-500">({mouseCoords.x}, {mouseCoords.y})</div>}
                </div>
                <div className="flex-1" />
                <span className="text-[9px] text-gray-600">{t('spaceToCollect')}</span>
              </div>

              {/* Tolerance setting */}
              <div className="flex items-center gap-2 text-[11px]">
                <span className="text-gray-400">{t('colorTolerance')}</span>
                <input type="text" value={globalTolerance} onChange={e => setGlobalTolerance(e.target.value)}
                  className="w-16 bg-[#1e1e1e] border border-[#3c3c3c] rounded px-1 py-0.5 text-[11px] text-white font-mono"
                  placeholder="0x101010" />
              </div>

              {/* Collected colors list */}
              <div className="text-[11px] text-gray-400 flex items-center justify-between">
                <span>{t('collected')} ({collectedColors.length})</span>
                {collectedColors.length > 0 && (
                  <button onClick={() => setCollectedColors([])} className="text-red-400 hover:text-red-300 flex items-center gap-0.5">
                    <Trash2 size={10} /> {t('clear')}
                  </button>
                )}
              </div>
              <div className="space-y-1 max-h-[200px] overflow-auto">
                {collectedColors.map((c, i) => (
                  <div key={i} className="flex items-center gap-2 px-2 py-1 bg-[#1e1e1e] rounded text-[10px] font-mono group">
                    <span className="text-gray-500 w-4">{i + 1}</span>
                    <span className="w-3 h-3 rounded-sm border border-[#555]" style={{ backgroundColor: c.hex }} />
                    <span style={{ color: c.hex }}>{c.hex}</span>
                    <span className="text-gray-500">({c.x},{c.y})</span>
                    <div className="flex-1" />
                    <button onClick={() => setCollectedColors(prev => prev.filter((_, j) => j !== i))}
                      className="text-gray-600 hover:text-red-400 opacity-0 group-hover:opacity-100">
                      <X size={10} />
                    </button>
                  </div>
                ))}
              </div>

              {/* Code generation buttons */}
              {collectedColors.length > 0 && (
                <div className="space-y-1 pt-1 border-t border-[#3c3c3c]">
                  <button onClick={() => {
                    if (collectedColors.length === 1) {
                      const c = collectedColors[0];
                      const code = `# find_color\nresult = auto.find_color("${c.hex}", tolerance="${c.tolerance}")`;
                      onInsertCode(code); status.success(t('insertedColorCode'));
                    } else {
                      const first = collectedColors[0];
                      const rest = collectedColors.slice(1).map(c => `("${c.hex}", ${c.x - first.x}, ${c.y - first.y})`).join(', ');
                      const code = `# find_multi_color (anchor: ${first.x},${first.y})\nresult = auto.find_multi_color("${first.hex}", [${rest}], tolerance="${first.tolerance}")`;
                      onInsertCode(code); status.success(t('insertedMultiColorCode'));
                    }
                  }} className="w-full text-[11px] px-2 py-1.5 bg-[#007acc] hover:bg-[#005f9e] text-white rounded flex items-center justify-center gap-1">
                    <Code size={11} /> {collectedColors.length === 1 ? t('genColorCode') : t('genMultiColorCode')}
                  </button>
                  <button onClick={() => {
                    const text = collectedColors.map(c => `${c.hex} (${c.x},${c.y})`).join('\n');
                    navigator.clipboard.writeText(text); status.success(t('copiedColorList'));
                  }} className="w-full text-[11px] px-2 py-1 bg-[#3c3c3c] hover:bg-[#555] text-gray-300 rounded flex items-center justify-center gap-1">
                    <Copy size={11} /> {t('copyList')}
                  </button>
                  {/* Verify color online */}
                  <button onClick={async () => {
                    if (!imei) { status.error(t('notConnected')); return; }
                    try {
                      const first = collectedColors[0];
                      const rest = collectedColors.slice(1).map(c => `${c.hex},${c.x - first.x},${c.y - first.y}`).join('\n');
                      const res = await ideApi.findColor(imei, first.hex, rest, { prob: 3 });
                      if (res.data) {
                        status.success(`${t('colorResult')} ${JSON.stringify(res.data)}`);
                      } else {
                        status.info(t('colorNotFound'));
                      }
                    } catch (e: unknown) { status.error(`${t('colorFailed')} ${e instanceof Error ? e.message : e}`); }
                  }} className="w-full text-[11px] px-2 py-1 bg-[#2d7d46] hover:bg-[#236b38] text-white rounded flex items-center justify-center gap-1">
                    <Play size={11} /> {t('verifyColor')}
                  </button>
                </div>
              )}
            </div>
          )}

          {/* ── Find image panel ── */}
          {rightTab === 'findimg' && (
            <div className="py-1 px-2 space-y-2">
              <div className="text-[11px] text-gray-400">
                {t('cropHint')}
              </div>

              {/* Template preview */}
              {cropPreview ? (
                <div className="flex items-center gap-2 p-2 bg-[#1e1e1e] rounded">
                  <img src={cropPreview} alt="template" className="h-12 w-12 object-contain border border-[#555] rounded" />
                  <div className="text-[10px] font-mono text-gray-400">
                    {cropRect && `${Math.abs(cropRect.x2 - cropRect.x1)}×${Math.abs(cropRect.y2 - cropRect.y1)}`}
                    {cropRect && <div>@({Math.min(cropRect.x1, cropRect.x2)},{Math.min(cropRect.y1, cropRect.y2)})</div>}
                  </div>
                </div>
              ) : (
                <div className="p-3 bg-[#1e1e1e] rounded text-center text-[11px] text-gray-600">
                  <Crop size={16} className="mx-auto mb-1 opacity-40" />
                  {t('enableCropMode')}
                </div>
              )}

              {/* Parameters */}
              <div className="space-y-1.5">
                <div className="flex items-center gap-2 text-[11px]">
                  <span className="text-gray-400 w-14">{t('similarity')}</span>
                  <input type="range" min="0.5" max="1" step="0.05" value={matchThreshold}
                    onChange={e => setMatchThreshold(parseFloat(e.target.value))}
                    className="flex-1 h-1 accent-[#007acc]" />
                  <span className="text-white font-mono w-8 text-right">{matchThreshold}</span>
                </div>
                <div className="flex items-center gap-2 text-[11px]">
                  <span className="text-gray-400 w-14">{t('searchRegion')}</span>
                  {cropRect ? (
                    <span className="text-white font-mono text-[10px]">
                      ({Math.min(cropRect.x1, cropRect.x2)},{Math.min(cropRect.y1, cropRect.y2)},{Math.max(cropRect.x1, cropRect.x2)},{Math.max(cropRect.y1, cropRect.y2)})
                    </span>
                  ) : (
                    <span className="text-gray-600 text-[10px]">{t('fullScreen')}</span>
                  )}
                </div>
              </div>

              {/* Generate code */}
              <div className="space-y-1 pt-1 border-t border-[#3c3c3c]">
                <button onClick={() => {
                  const region = cropRect
                    ? `region=(${Math.min(cropRect.x1, cropRect.x2)}, ${Math.min(cropRect.y1, cropRect.y2)}, ${Math.max(cropRect.x1, cropRect.x2)}, ${Math.max(cropRect.y1, cropRect.y2)}), `
                    : '';
                  const code = `# find_image\nresult = auto.find_image("template.png", ${region}threshold=${matchThreshold})\nif result:\n    auto.click(result[0], result[1])`;
                  onInsertCode(code); status.success(t('insertedFindImageCode'));
                }} className="w-full text-[11px] px-2 py-1.5 bg-[#007acc] hover:bg-[#005f9e] text-white rounded flex items-center justify-center gap-1">
                  <Code size={11} /> {t('genFindImageCode')}
                </button>
                <button onClick={() => {
                  const region = cropRect
                    ? `region=(${Math.min(cropRect.x1, cropRect.x2)}, ${Math.min(cropRect.y1, cropRect.y2)}, ${Math.max(cropRect.x1, cropRect.x2)}, ${Math.max(cropRect.y1, cropRect.y2)}), `
                    : '';
                  const code = `# OCR region\nresult = auto.ocr(${region.replace('region=', '').replace(', ', '')})\nfor item in result:\n    print(item.text, item.bounds)`;
                  onInsertCode(code); status.success(t('insertedOcrCode'));
                }} className="w-full text-[11px] px-2 py-1 bg-[#6c5ce7] hover:bg-[#5b4cdb] text-white rounded flex items-center justify-center gap-1">
                  <Code size={11} /> {t('genOcrCode')}
                </button>
                {/* Verify find image online */}
                <button onClick={async () => {
                  if (!imei) { status.error(t('notConnected')); return; }
                  if (!cropPreview) { status.error(t('pleaseSelectCrop')); return; }
                  try {
                    const res = await ideApi.matchImage(imei, cropPreview, {
                      prob: matchThreshold,
                      ...(cropRect ? {
                        x: String(Math.min(cropRect.x1, cropRect.x2)),
                        y: String(Math.min(cropRect.y1, cropRect.y2)),
                        w: String(Math.abs(cropRect.x2 - cropRect.x1)),
                        h: String(Math.abs(cropRect.y2 - cropRect.y1)),
                      } : {}),
                    });
                    if (res.data) {
                      status.success(`${t('colorResult')} ${JSON.stringify(res.data)}`);
                    } else {
                      status.info(t('imageNotFound'));
                    }
                  } catch (e: unknown) { status.error(`${t('imageFailed')} ${e instanceof Error ? e.message : e}`); }
                }} className="w-full text-[11px] px-2 py-1 bg-[#2d7d46] hover:bg-[#236b38] text-white rounded flex items-center justify-center gap-1">
                  <Play size={11} /> {t('verifyImage')}
                </button>
              </div>
            </div>
          )}

          {/* ── OCR panel ── */}
          {rightTab === 'ocr' && (
            <div className="py-1 px-2 space-y-2">
              {/* OCR action buttons */}
              <div className="flex gap-1">
                <button onClick={async () => {
                  if (!imei) { status.error(t('notConnected')); return; }
                  setOcrLoading(true);
                  try {
                    const res = await ideApi.screenOcr(imei);
                    const raw = (res as unknown as { data: unknown }).data ?? res;
                    const parsed = typeof raw === 'string' ? parseOcrTsv(raw) : Array.isArray(raw) ? raw : [];
                    if (parsed.length > 0) {
                      setOcrResults(parsed);
                      status.success(t('ocrFound', { count: parsed.length }));
                    } else {
                      setOcrResults([]); status.info(t('ocrEmpty'));
                    }
                  } catch (e: unknown) { status.error(`${t('ocrFailed')} ${e instanceof Error ? e.message : e}`); }
                  finally { setOcrLoading(false); }
                }} disabled={ocrLoading}
                  className="flex-1 text-[11px] px-2 py-1.5 bg-[#6c5ce7] hover:bg-[#5b4cdb] disabled:opacity-50 text-white rounded flex items-center justify-center gap-1">
                  {ocrLoading ? <RefreshCw size={11} className="animate-spin" /> : <Search size={11} />}
                  {ocrLoading ? t('ocrProcessing') : t('fullScreenOcr')}
                </button>
                {cropRect && (
                  <button onClick={async () => {
                    if (!imei) { status.error(t('notConnected')); return; }
                    setOcrLoading(true);
                    try {
                      const x = Math.min(cropRect.x1, cropRect.x2), y = Math.min(cropRect.y1, cropRect.y2);
                      const w = Math.abs(cropRect.x2 - cropRect.x1), h = Math.abs(cropRect.y2 - cropRect.y1);
                      const res = await ideApi.screenOcr(imei, { use_gpu: false });
                      const raw = (res as unknown as { data: unknown }).data ?? res;
                      const parsed = typeof raw === 'string' ? parseOcrTsv(raw) : Array.isArray(raw) ? raw : [];
                      const filtered = parsed.filter((r) =>
                        r.x >= x && r.y >= y && r.x + r.w <= x + w && r.y + r.h <= y + h
                      );
                      setOcrResults(filtered);
                      if (filtered.length > 0) {
                        status.success(t('ocrRegionFound', { count: filtered.length }));
                      } else {
                        status.info(t('ocrEmpty'));
                      }
                    } catch (e: unknown) { status.error(`${t('ocrFailed')} ${e instanceof Error ? e.message : e}`); }
                    finally { setOcrLoading(false); }
                  }} disabled={ocrLoading}
                    className="flex-1 text-[11px] px-2 py-1.5 bg-[#e17055] hover:bg-[#c0553d] disabled:opacity-50 text-white rounded flex items-center justify-center gap-1">
                    <Crop size={11} /> {t('regionOcr')}
                  </button>
                )}
              </div>

              {/* Filter */}
              {ocrResults.length > 0 && (
                <input type="text" value={ocrFilter} onChange={e => setOcrFilter(e.target.value)}
                  placeholder={t('filterText')} className="w-full bg-[#1e1e1e] border border-[#3c3c3c] rounded px-2 py-1 text-[11px] text-white" />
              )}

              {/* OCR results list */}
              <div className="text-[11px] text-gray-400">
                {t('ocrResults')} ({ocrResults.length})
                {ocrResults.length > 0 && (
                  <button onClick={() => setOcrResults([])} className="ml-2 text-red-400 hover:text-red-300">{t('clear')}</button>
                )}
              </div>
              <div className="space-y-0.5 max-h-[300px] overflow-auto">
                {ocrResults
                  .filter(r => !ocrFilter || r.text.toLowerCase().includes(ocrFilter.toLowerCase()))
                  .map((r, i) => (
                  <div key={i} className="flex items-center gap-1 px-2 py-1 bg-[#1e1e1e] rounded text-[10px] font-mono group hover:bg-[#2a2d2e] cursor-pointer"
                    onClick={() => {
                      setSelectedNode(null);
                      setMouseCoords({ x: r.x + r.w / 2, y: r.y + r.h / 2 });
                    }}
                  >
                    <span className="text-gray-500 w-4 shrink-0">{i + 1}</span>
                    <span className="text-[#dcdcaa] truncate flex-1" title={r.text}>{r.text}</span>
                    <span className="text-gray-600 shrink-0">({r.x},{r.y})</span>
                    <span className="text-gray-700 shrink-0">{r.w}×{r.h}</span>
                    <span className="text-[#6c5ce7] shrink-0">{(r.prob * 100).toFixed(0)}%</span>
                    <button onClick={(e) => {
                      e.stopPropagation();
                      const code = `# OCR find and click\nresult = auto.ocr()\nfor item in result:\n    if "${r.text}" in item.text:\n        auto.click(item.x + item.w // 2, item.y + item.h // 2)\n        break`;
                      onInsertCode(code); status.success(t('insertedOcrClickCode'));
                    }} className="text-gray-600 hover:text-[#007acc] opacity-0 group-hover:opacity-100 shrink-0" title={t('genClickCode')}>
                      <Code size={10} />
                    </button>
                    <button onClick={(e) => {
                      e.stopPropagation();
                      navigator.clipboard.writeText(r.text); status.success(t('copiedText', { text: r.text }));
                    }} className="text-gray-600 hover:text-white opacity-0 group-hover:opacity-100 shrink-0" title={t('copyText')}>
                      <Copy size={10} />
                    </button>
                  </div>
                ))}
              </div>

              {/* Code generation */}
              {ocrResults.length > 0 && (
                <div className="space-y-1 pt-1 border-t border-[#3c3c3c]">
                  <button onClick={() => {
                    const region = cropRect
                      ? `x=${Math.min(cropRect.x1, cropRect.x2)}, y=${Math.min(cropRect.y1, cropRect.y2)}, w=${Math.abs(cropRect.x2 - cropRect.x1)}, h=${Math.abs(cropRect.y2 - cropRect.y1)}, `
                      : '';
                    const code = `# OCR\nresult = auto.ocr(${region})\nfor item in result:\n    print(f"{item.text} @ ({item.x},{item.y},{item.w},{item.h}) prob={item.prob}")`;
                    onInsertCode(code); status.success(t('insertedOcrCode'));
                  }} className="w-full text-[11px] px-2 py-1.5 bg-[#6c5ce7] hover:bg-[#5b4cdb] text-white rounded flex items-center justify-center gap-1">
                    <Code size={11} /> {t('genOcrTraverseCode')}
                  </button>
                  <button onClick={() => {
                    const texts = ocrResults.map(r => r.text).join('\n');
                    navigator.clipboard.writeText(texts); status.success(t('copiedAllText'));
                  }} className="w-full text-[11px] px-2 py-1 bg-[#3c3c3c] hover:bg-[#555] text-gray-300 rounded flex items-center justify-center gap-1">
                    <Copy size={11} /> {t('copyAllText')}
                  </button>
                  <button onClick={() => {
                    const json = JSON.stringify(ocrResults, null, 2);
                    navigator.clipboard.writeText(json); status.success(t('copiedJson'));
                  }} className="w-full text-[11px] px-2 py-1 bg-[#3c3c3c] hover:bg-[#555] text-gray-300 rounded flex items-center justify-center gap-1">
                    <ClipboardCopy size={11} /> {t('copyJsonResult')}
                  </button>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ══════════════════════════════════════════════════════════════
// Sub-component: UiTreeNode
// ══════════════════════════════════════════════════════════════

function UiTreeNode({ node, depth, selected, searchResults, onSelect, onHover }: {
  node: UiNode; depth: number; selected: UiNode | null;
  searchResults: UiNode[]; onSelect: (n: UiNode) => void; onHover: (n: UiNode | null) => void;
}) {
  const [expanded, setExpanded] = useState(depth < 2);
  const hasChildren = node.children && node.children.length > 0;
  const isSelected = selected === node;
  const isSearchMatch = searchResults.includes(node);
  const clsShort = node.className.split('.').pop() || 'View';
  const typeIcon = getTypeIcon(node.className);
  const typeColor = getTypeColor(node);
  const idShort = node.resourceId ? node.resourceId.split('/').pop() : '';

  // Auto-expand to show selected node
  useEffect(() => {
    if (isSelected && !expanded && hasChildren) setExpanded(true);
  }, [isSelected]);

  return (
    <div>
      <div
        className={`flex items-center h-[22px] pr-2 cursor-pointer select-none group
          ${isSelected ? 'bg-[#094771]' : isSearchMatch ? 'bg-[#3a3a1e]' : 'hover:bg-[#2a2d2e]'}`}
        style={{ paddingLeft: depth * 14 + 4 }}
        onClick={() => onSelect(node)}
        onMouseEnter={() => onHover(node)}
        onMouseLeave={() => onHover(null)}
      >
        {/* Expand toggle */}
        <span className="w-4 shrink-0 flex items-center justify-center" onClick={(e) => { e.stopPropagation(); setExpanded(!expanded); }}>
          {hasChildren ? (expanded ? <ChevronDown size={12} className="text-gray-400" /> : <ChevronRight size={12} className="text-gray-400" />) : null}
        </span>

        {/* Type icon badge */}
        {typeIcon && (
          <span className="text-[9px] font-bold mr-1 px-1 rounded" style={{ color: typeColor, background: hexToRgba(typeColor, 0.15) }}>
            {typeIcon}
          </span>
        )}

        {/* Class name */}
        <span className="text-[#569cd6] text-[11px] truncate shrink-0">{clsShort}</span>

        {/* Resource ID */}
        {idShort && <span className="text-[#ce9178] text-[10px] ml-1 truncate">#{idShort}</span>}

        {/* Text preview */}
        {node.text && <span className="text-[#d4d4aa] text-[10px] ml-1 truncate max-w-[100px]">"{node.text}"</span>}

        {/* State badges */}
        <span className="ml-auto flex items-center gap-0.5 shrink-0 opacity-0 group-hover:opacity-100">
          {node.clickable && <span className="text-[8px] px-1 rounded bg-green-900 text-green-300">click</span>}
          {node.scrollable && <span className="text-[8px] px-1 rounded bg-blue-900 text-blue-300">scroll</span>}
          {node.checked && <span className="text-[8px] px-1 rounded bg-yellow-900 text-yellow-300">✓</span>}
        </span>
      </div>

      {/* Children */}
      {expanded && hasChildren && node.children.map((child, i) => (
        <UiTreeNode key={i} node={child} depth={depth + 1} selected={selected} searchResults={searchResults} onSelect={onSelect} onHover={onHover} />
      ))}
    </div>
  );
}

// ══════════════════════════════════════════════════════════════
// Sub-component: PropsPanel
// ══════════════════════════════════════════════════════════════

function PropsPanel({ node, imgW, imgH, onInsertCode, generateCodeSnippets, buildXPath, onLocate, onClickNode }: {
  node: UiNode; imgW: number; imgH: number;
  onInsertCode: (code: string) => void;
  generateCodeSnippets: (n: UiNode) => { label: string; code: string }[];
  buildXPath: (n: UiNode) => string;
  onLocate: (n: UiNode) => void;
  onClickNode: (n: UiNode) => void;
}) {
  const { t } = useTranslation(['devtool', 'common']);
  const b = node.bounds;
  const w = b.x2 - b.x1;
  const h = b.y2 - b.y1;
  const cx = Math.round((b.x1 + b.x2) / 2);
  const cy = Math.round((b.y1 + b.y2) / 2);
  const clsShort = node.className.split('.').pop() || '';
  const idShort = node.resourceId ? node.resourceId.split('/').pop() || '' : '';
  const xpath = buildXPath(node);
  const snippets = generateCodeSnippets(node);

  const copyText = (text: string, label: string) => {
    navigator.clipboard.writeText(text);
    status.success(t('copied', { label }));
  };

  return (
    <div className="text-[11px]">
      {/* Quick actions bar */}
      <div className="flex items-center gap-1 px-2 py-1.5 bg-[#252526] border-b border-[#3c3c3c]">
        <button onClick={() => onClickNode(node)} className="px-2 py-0.5 bg-[#007acc] hover:bg-[#005f9e] text-white rounded text-[10px] flex items-center gap-1">
          <Play size={9} /> {t('click')}
        </button>
        {idShort && (
          <button onClick={() => copyText(idShort, 'ID')} className="px-2 py-0.5 bg-[#3c3c3c] hover:bg-[#555] rounded text-[10px] flex items-center gap-1">
            <Copy size={9} /> ID
          </button>
        )}
        {node.text && (
          <button onClick={() => copyText(node.text, t('propText'))} className="px-2 py-0.5 bg-[#3c3c3c] hover:bg-[#555] rounded text-[10px] flex items-center gap-1">
            <Copy size={9} /> {t('propText')}
          </button>
        )}
        <button onClick={() => copyText(xpath, 'XPath')} className="px-2 py-0.5 bg-[#3c3c3c] hover:bg-[#555] rounded text-[10px] flex items-center gap-1">
          <Code size={9} /> XPath
        </button>
        <button onClick={() => onLocate(node)} className="px-2 py-0.5 bg-[#3c3c3c] hover:bg-[#555] rounded text-[10px] flex items-center gap-1">
          <MapPin size={9} /> {t('locate')}
        </button>
      </div>

      {/* Basic info */}
      <div className="px-2 py-1 border-b border-[#3c3c3c]">
        <div className="text-[10px] text-gray-500 mb-1 font-semibold">{t('propBasicInfo')}</div>
        <PropRow label={t('propClassName')} value={node.className} short={clsShort} />
        <PropRow label="ID" value={node.resourceId} short={idShort} />
        {node.text && <PropRow label={t('propText')} value={node.text} />}
        {node.contentDesc && <PropRow label={t('propDesc')} value={node.contentDesc} />}
        <PropRow label={t('propPackage')} value={node.packageName} />
        <PropRow label={t('propIndex')} value={`${node.index}`} />
        <PropRow label={t('propDepth')} value={`${node.depth}`} />
        <PropRow label={t('propChildren')} value={`${node.children?.length || 0}`} />
      </div>

      {/* Position & size */}
      <div className="px-2 py-1 border-b border-[#3c3c3c]">
        <div className="text-[10px] text-gray-500 mb-1 font-semibold">{t('propPosition')}</div>
        <PropRow label={t('propBounds')} value={`[${b.x1},${b.y1}][${b.x2},${b.y2}]`} />
        <PropRow label={t('propSize')} value={`${w} × ${h}`} />
        <PropRow label={t('propCenter')} value={`(${cx}, ${cy})`} />
        {imgW > 0 && (
          <>
            <PropRow label={t('propRatioCoord')} value={`(${(b.x1/imgW).toFixed(3)}, ${(b.y1/imgH).toFixed(3)})`} />
            <PropRow label={t('propRatioSize')} value={`(${(w/imgW).toFixed(3)}, ${(h/imgH).toFixed(3)})`} />
            <PropRow label={t('propRatioCenter')} value={`(${(cx/imgW).toFixed(3)}, ${(cy/imgH).toFixed(3)})`} />
          </>
        )}
      </div>

      {/* State badges */}
      <div className="px-2 py-1 border-b border-[#3c3c3c]">
        <div className="text-[10px] text-gray-500 mb-1 font-semibold">{t('propState')}</div>
        <div className="flex flex-wrap gap-1">
          <StateBadge label={t('propClickable')} active={node.clickable} color="green" />
          <StateBadge label={t('propScrollable')} active={node.scrollable} color="blue" />
          <StateBadge label={t('propLongClickable')} active={node.longClickable} color="purple" />
          <StateBadge label={t('propCheckable')} active={node.checkable} color="yellow" />
          <StateBadge label={t('propChecked')} active={node.checked} color="yellow" />
          <StateBadge label={t('propFocusable')} active={node.focusable} color="cyan" />
          <StateBadge label={t('propFocused')} active={node.focused} color="cyan" />
          <StateBadge label={t('propSelected')} active={node.selected} color="orange" />
          <StateBadge label={t('propPassword')} active={node.password} color="red" />
          <StateBadge label={t('propDisabled')} active={!node.enabled} color="red" />
          <StateBadge label={t('propInvisible')} active={!node.visible} color="red" />
        </div>
      </div>

      {/* XPath */}
      <div className="px-2 py-1 border-b border-[#3c3c3c]">
        <div className="text-[10px] text-gray-500 mb-1 font-semibold">XPath</div>
        <div className="flex items-center gap-1">
          <code className="text-[10px] text-[#ce9178] bg-[#1a1a1a] px-1.5 py-0.5 rounded flex-1 truncate">{xpath}</code>
          <button onClick={() => copyText(xpath, 'XPath')} className="shrink-0 p-0.5 hover:bg-[#3c3c3c] rounded"><Copy size={10} /></button>
        </div>
      </div>

      {/* Code snippets */}
      <div className="px-2 py-1">
        <div className="text-[10px] text-gray-500 mb-1 font-semibold">{t('codeSnippets')}</div>
        {snippets.map((s, i) => (
          <div key={i} className="mb-1.5">
            <div className="flex items-center justify-between mb-0.5">
              <span className="text-[9px] text-gray-500">{s.label}</span>
              <div className="flex gap-1">
                <button onClick={() => copyText(s.code, s.label)} className="text-[9px] text-gray-500 hover:text-white flex items-center gap-0.5">
                  <Copy size={9} /> {t('common:copy')}
                </button>
                <button onClick={() => onInsertCode(s.code)} className="text-[9px] text-[#007acc] hover:text-[#3d9cf5] flex items-center gap-0.5">
                  <Code size={9} /> {t('insert')}
                </button>
              </div>
            </div>
            <pre className="text-[10px] text-[#d4d4d4] bg-[#1a1a1a] px-2 py-1 rounded whitespace-pre-wrap break-all font-mono">{s.code}</pre>
          </div>
        ))}
      </div>
    </div>
  );
}

function PropRow({ label, value, short }: { label: string; value: string; short?: string }) {
  const { t } = useTranslation(['devtool', 'common']);
  const display = short || value;
  return (
    <div className="flex items-center py-0.5 group">
      <span className="text-gray-500 w-14 shrink-0">{label}</span>
      <span className="text-[#d4d4d4] truncate flex-1" title={value}>{display}</span>
      {value && (
        <button onClick={() => { navigator.clipboard.writeText(value); status.success(t('copiedValue')); }}
          className="opacity-0 group-hover:opacity-100 p-0.5 hover:bg-[#3c3c3c] rounded shrink-0">
          <Copy size={9} className="text-gray-500" />
        </button>
      )}
    </div>
  );
}

function StateBadge({ label, active, color }: { label: string; active: boolean; color: string }) {
  const colors: Record<string, string> = {
    green: active ? 'bg-green-900 text-green-300' : 'bg-[#2a2a2a] text-gray-600',
    blue: active ? 'bg-blue-900 text-blue-300' : 'bg-[#2a2a2a] text-gray-600',
    purple: active ? 'bg-purple-900 text-purple-300' : 'bg-[#2a2a2a] text-gray-600',
    yellow: active ? 'bg-yellow-900 text-yellow-300' : 'bg-[#2a2a2a] text-gray-600',
    cyan: active ? 'bg-cyan-900 text-cyan-300' : 'bg-[#2a2a2a] text-gray-600',
    orange: active ? 'bg-orange-900 text-orange-300' : 'bg-[#2a2a2a] text-gray-600',
    red: active ? 'bg-red-900 text-red-300' : 'bg-[#2a2a2a] text-gray-600',
  };
  return <span className={`text-[9px] px-1.5 py-0.5 rounded ${colors[color] || colors.green}`}>{label}</span>;
}

// ══════════════════════════════════════════════════════════════
// Sub-component: FilterPanel (hit-test results)
// ══════════════════════════════════════════════════════════════

function FilterPanel({ hitNodes, selectedNode, onSelect, onHover }: {
  hitNodes: HitNode[]; selectedNode: UiNode | null;
  onSelect: (hn: HitNode) => void; onHover: (hn: HitNode | null) => void;
}) {
  const { t } = useTranslation(['devtool', 'common']);
  if (hitNodes.length === 0) {
    return <div className="px-3 py-8 text-xs text-gray-500 text-center">{t('clickScreenToHit')}</div>;
  }

  return (
    <div className="text-[11px]">
      <div className="px-2 py-1 text-[10px] text-gray-400 bg-[#252526] border-b border-[#3c3c3c]">
        {t('hitNodesCount', { count: hitNodes.length })}
      </div>
      {hitNodes.map((hn, i) => {
        const n = hn.node;
        const clsShort = n.className.split('.').pop() || 'View';
        const idShort = n.resourceId ? n.resourceId.split('/').pop() : '';
        const w = n.bounds.x2 - n.bounds.x1;
        const h = n.bounds.y2 - n.bounds.y1;
        const isSelected = selectedNode === n;
        const typeIcon = getTypeIcon(n.className);

        return (
          <div
            key={i}
            className={`flex items-center gap-1.5 px-2 py-1 cursor-pointer border-b border-[#2a2a2a]
              ${isSelected ? 'bg-[#094771]' : 'hover:bg-[#2a2d2e]'}`}
            onClick={() => onSelect(hn)}
            onMouseEnter={() => onHover(hn)}
            onMouseLeave={() => onHover(null)}
          >
            {/* Color indicator */}
            <span className="w-2.5 h-2.5 rounded-sm shrink-0" style={{ background: hn.color }} />

            {/* Type icon */}
            {typeIcon && (
              <span className="text-[9px] font-bold px-1 rounded shrink-0"
                style={{ color: getTypeColor(n), background: hexToRgba(getTypeColor(n), 0.15) }}>
                {typeIcon}
              </span>
            )}

            {/* Info */}
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-1">
                <span className="text-[#569cd6] truncate">{clsShort}</span>
                {idShort && <span className="text-[#ce9178] text-[10px] truncate">#{idShort}</span>}
              </div>
              <div className="flex items-center gap-2 text-[9px] text-gray-500">
                <span>{w}×{h}</span>
                <span>depth:{n.depth}</span>
                {n.text && <span className="text-[#d4d4aa] truncate max-w-[80px]">"{n.text}"</span>}
              </div>
            </div>

            {/* Badges */}
            <div className="flex gap-0.5 shrink-0">
              {n.clickable && <span className="text-[8px] px-1 rounded bg-green-900 text-green-300">click</span>}
              {n.scrollable && <span className="text-[8px] px-1 rounded bg-blue-900 text-blue-300">scroll</span>}
            </div>
          </div>
        );
      })}
    </div>
  );
}

// ══════════════════════════════════════════════════════════════
// Canvas drawing helper
// ══════════════════════════════════════════════════════════════

function drawNodeHighlight(ctx: CanvasRenderingContext2D, node: UiNode, color: string, scale: number, offsetX: number, offsetY: number, isPreview: boolean) {
  const b = node.bounds;
  const x = b.x1 * scale + offsetX;
  const y = b.y1 * scale + offsetY;
  const w = (b.x2 - b.x1) * scale;
  const h = (b.y2 - b.y1) * scale;
  if (w <= 0 || h <= 0) return;

  // Semi-transparent fill (keep light so screenshot stays visible)
  ctx.fillStyle = hexToRgba(color, isPreview ? 0.04 : 0.08);
  ctx.fillRect(x, y, w, h);

  // Outer glow (3px semi-transparent)
  ctx.strokeStyle = hexToRgba(color, 0.5);
  ctx.lineWidth = 2;
  ctx.strokeRect(x - 1, y - 1, w + 2, h + 2);

  // Solid border
  ctx.strokeStyle = color;
  ctx.lineWidth = isPreview ? 1 : 1.5;
  ctx.strokeRect(x, y, w, h);

  // Label tag
  if (!isPreview) {
    const clsShort = node.className.split('.').pop() || '';
    const idShort = node.resourceId ? node.resourceId.split('/').pop() : '';
    const label = idShort ? `${clsShort} #${idShort}` : clsShort;
    if (label) {
      ctx.font = '10px monospace';
      const tw = ctx.measureText(label).width + 6;
      const lh = 14;
      const lx = x;
      const ly = y > lh + 2 ? y - lh - 1 : y + h + 1;
      ctx.fillStyle = hexToRgba(color, 0.85);
      ctx.fillRect(lx, ly, tw, lh);
      ctx.fillStyle = '#fff';
      ctx.fillText(label, lx + 3, ly + 10);
    }
  }
}

// ══════════════════════════════════════════════════════════════
// XML Parser
// ══════════════════════════════════════════════════════════════

function parseUiXml(xml: string): UiNode[] {
  try {
    const parser = new DOMParser();
    const doc = parser.parseFromString(xml, 'text/xml');
    const root = doc.documentElement;
    if (!root) return [];
    return parseChildren(root, 0);
  } catch { return []; }
}

function parseChildren(el: Element, depth: number): UiNode[] {
  const nodes: UiNode[] = [];
  for (let i = 0; i < el.children.length; i++) {
    const child = el.children[i];
    const bounds = parseBounds(child.getAttribute('bounds') || '');
    const node: UiNode = {
      index: parseInt(child.getAttribute('index') || '0') || 0,
      text: child.getAttribute('text') || '',
      resourceId: child.getAttribute('resource-id') || '',
      className: child.getAttribute('class') || '',
      packageName: child.getAttribute('package') || '',
      contentDesc: child.getAttribute('content-desc') || '',
      checkable: child.getAttribute('checkable') === 'true',
      checked: child.getAttribute('checked') === 'true',
      clickable: child.getAttribute('clickable') === 'true',
      enabled: child.getAttribute('enabled') !== 'false',
      focusable: child.getAttribute('focusable') === 'true',
      focused: child.getAttribute('focused') === 'true',
      scrollable: child.getAttribute('scrollable') === 'true',
      longClickable: child.getAttribute('long-clickable') === 'true',
      password: child.getAttribute('password') === 'true',
      selected: child.getAttribute('selected') === 'true',
      visible: child.getAttribute('visible-to-user') !== 'false',
      bounds,
      children: parseChildren(child, depth + 1),
      depth,
    };
    nodes.push(node);
  }
  return nodes;
}

function parseBounds(s: string): { x1: number; y1: number; x2: number; y2: number } {
  const m = s.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
  if (!m) return { x1: 0, y1: 0, x2: 0, y2: 0 };
  return { x1: +m[1], y1: +m[2], x2: +m[3], y2: +m[4] };
}

function countNodes(nodes: UiNode[]): number {
  let c = nodes.length;
  for (const n of nodes) {
    if (n.children) c += countNodes(n.children);
  }
  return c;
}
