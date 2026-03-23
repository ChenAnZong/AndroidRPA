import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { FlowNode, FlowEdge, FlowGraph, FlowViewport, FlowNodeType } from '@/types';
import { NODE_DEFAULTS } from './flowTypes';
import FlowNodeRenderer from './FlowNodeRenderer';
import FlowNodePalette from './FlowNodePalette';
import FlowNodeConfig from './FlowNodeConfig';
import { generatePythonCode } from './flowCodeGen';
import { serializeGraph, deserializeGraph, createEmptyGraph } from './flowSerializer';

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

interface FlowEditorPanelProps {
  content: string;
  onSave: (content: string) => void;
  onDirty: () => void;
  insertCode?: (code: string) => void;
}

interface DragState {
  nodeId: string;
  startX: number;
  startY: number;
  offsetX: number;
  offsetY: number;
}

interface ConnectingState {
  sourceId: string;
  sourcePort: string;
  isOutput: boolean;
  mouseX: number;
  mouseY: number;
}

interface PanState {
  startX: number;
  startY: number;
  startPanX: number;
  startPanY: number;
}

interface SelectionBox {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

interface ContextMenu {
  x: number;
  y: number;
  type: 'canvas' | 'node';
  nodeId?: string;
}

/* ------------------------------------------------------------------ */
/*  Constants                                                          */
/* ------------------------------------------------------------------ */

const MAX_UNDO = 50;
const GRID_SIZE = 20;
const ZOOM_MIN = 0.2;
const ZOOM_MAX = 3.0;
const ZOOM_STEP = 0.1;
const BEZIER_OFFSET = 80;

/* ------------------------------------------------------------------ */
/*  Helpers (placeholder – filled next)                                */
/* ------------------------------------------------------------------ */

function generateId(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
  return 'n_' + Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
}

function snapToGrid(x: number, y: number, grid = GRID_SIZE): { x: number; y: number } {
  return { x: Math.round(x / grid) * grid, y: Math.round(y / grid) * grid };
}

function getPortPosition(
  node: FlowNode,
  portId: string,
  isOutput: boolean,
): { x: number; y: number } {
  const defaults = (NODE_DEFAULTS as Record<string, typeof NODE_DEFAULTS[keyof typeof NODE_DEFAULTS]>)[node.type] ?? NODE_DEFAULTS['log'];
  const ports = isOutput ? (defaults.outputs ?? []) : (defaults.inputs ?? []);
  const idx = ports.findIndex((p: { id: string }) => p.id === portId);
  const total = ports.length || 1;
  const nodeW = node.width ?? 180;
  const nodeH = node.height ?? 80;
  const spacing = nodeH / (total + 1);
  return {
    x: node.x + (isOutput ? nodeW : 0),
    y: node.y + spacing * (idx + 1),
  };
}

function buildBezierPath(
  x1: number, y1: number,
  x2: number, y2: number,
): string {
  const dx = Math.abs(x2 - x1) * 0.5;
  const cx1 = x1 + Math.max(dx, BEZIER_OFFSET);
  const cx2 = x2 - Math.max(dx, BEZIER_OFFSET);
  return `M ${x1} ${y1} C ${cx1} ${y1}, ${cx2} ${y2}, ${x2} ${y2}`;
}

function clientToSvg(
  clientX: number,
  clientY: number,
  svgEl: SVGSVGElement | null,
  panX: number,
  panY: number,
  zoom: number,
): { x: number; y: number } {
  if (!svgEl) return { x: 0, y: 0 };
  const rect = svgEl.getBoundingClientRect();
  return {
    x: (clientX - rect.left - panX) / zoom,
    y: (clientY - rect.top - panY) / zoom,
  };
}

function cloneGraph(g: FlowGraph): FlowGraph {
  return JSON.parse(JSON.stringify(g));
}

/* ------------------------------------------------------------------ */
/*  Component                                                          */
/* ------------------------------------------------------------------ */

const FlowEditorPanel: React.FC<FlowEditorPanelProps> = ({ content, onSave, onDirty, insertCode }) => {
  const { t } = useTranslation(['flow']);
  /* ---- state ---- */
  const [graph, setGraph] = useState<FlowGraph>(() => {
    try { return deserializeGraph(content); } catch { return createEmptyGraph(); }
  });
  const [selectedNodeIds, setSelectedNodeIds] = useState<Set<string>>(new Set());
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);
  const [undoStack, setUndoStack] = useState<FlowGraph[]>([]);
  const [redoStack, setRedoStack] = useState<FlowGraph[]>([]);
  const [dragging, setDragging] = useState<DragState | null>(null);
  const [connecting, setConnecting] = useState<ConnectingState | null>(null);
  const [panning, setPanning] = useState<PanState | null>(null);
  const [selectionBox, setSelectionBox] = useState<SelectionBox | null>(null);
  const [contextMenu, setContextMenu] = useState<ContextMenu | null>(null);
  const [clipboard, setClipboard] = useState<FlowNode[]>([]);
  const [spaceHeld, setSpaceHeld] = useState(false);

  const svgRef = useRef<SVGSVGElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  /* ---- viewport shorthand ---- */
  const viewport = graph.viewport ?? { x: 0, y: 0, zoom: 1 };
  const zoom = viewport.zoom;
  const panX = viewport.x;
  const panY = viewport.y;

  /* ---- undo / redo helpers ---- */
  const pushUndo = useCallback(() => {
    setUndoStack(prev => [...prev.slice(-(MAX_UNDO - 1)), cloneGraph(graph)]);
    setRedoStack([]);
  }, [graph]);

  const handleUndo = useCallback(() => {
    if (undoStack.length === 0) return;
    setRedoStack(prev => [...prev, cloneGraph(graph)]);
    const prev = undoStack[undoStack.length - 1];
    setUndoStack(s => s.slice(0, -1));
    setGraph(prev);
  }, [undoStack, graph]);

  const handleRedo = useCallback(() => {
    if (redoStack.length === 0) return;
    setUndoStack(prev => [...prev, cloneGraph(graph)]);
    const next = redoStack[redoStack.length - 1];
    setRedoStack(s => s.slice(0, -1));
    setGraph(next);
  }, [redoStack, graph]);

  /* ---- viewport helpers ---- */
  const setViewport = useCallback((v: Partial<FlowViewport>) => {
    setGraph(prev => ({
      ...prev,
      viewport: { ...prev.viewport ?? { x: 0, y: 0, zoom: 1 }, ...v },
    }));
  }, []);

  const zoomTo = useCallback((newZoom: number, cx?: number, cy?: number) => {
    const clamped = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, newZoom));
    if (cx !== undefined && cy !== undefined) {
      const scale = clamped / zoom;
      setViewport({
        zoom: clamped,
        x: cx - (cx - panX) * scale,
        y: cy - (cy - panY) * scale,
      });
    } else {
      setViewport({ zoom: clamped });
    }
  }, [zoom, panX, panY, setViewport]);

  const fitToCanvas = useCallback(() => {
    if (!svgRef.current || graph.nodes.length === 0) return;
    const rect = svgRef.current.getBoundingClientRect();
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const n of graph.nodes) {
      minX = Math.min(minX, n.x);
      minY = Math.min(minY, n.y);
      maxX = Math.max(maxX, n.x + (n.width ?? 180));
      maxY = Math.max(maxY, n.y + (n.height ?? 80));
    }
    const pad = 60;
    const w = maxX - minX + pad * 2;
    const h = maxY - minY + pad * 2;
    const z = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, Math.min(rect.width / w, rect.height / h)));
    setViewport({ zoom: z, x: -minX * z + (rect.width - w * z) / 2 + pad * z, y: -minY * z + (rect.height - h * z) / 2 + pad * z });
  }, [graph.nodes, setViewport]);

  /* ---- node mutation helpers ---- */
  const addNode = useCallback((type: FlowNodeType, x: number, y: number) => {
    pushUndo();
    const defaults = (NODE_DEFAULTS as Record<string, typeof NODE_DEFAULTS[keyof typeof NODE_DEFAULTS]>)[type] ?? NODE_DEFAULTS['log'];
    const newNode: FlowNode = {
      id: generateId(),
      type,
      label: t(defaults.label ?? type),
      x, y,
      width: defaults.width ?? 180,
      height: defaults.height ?? 80,
      data: { ...(defaults.data ?? {}) },
    };
    setGraph(prev => ({ ...prev, nodes: [...prev.nodes, newNode] }));
    onDirty();
    return newNode;
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  }, [pushUndo, onDirty]);

  const deleteNodes = useCallback((ids: Set<string>) => {
    if (ids.size === 0) return;
    pushUndo();
    setGraph(prev => ({
      ...prev,
      nodes: prev.nodes.filter(n => !ids.has(n.id)),
      edges: prev.edges.filter(e => !ids.has(e.source) && !ids.has(e.target)),
    }));
    setSelectedNodeIds(new Set());
    setSelectedEdgeId(null);
    onDirty();
  }, [pushUndo, onDirty]);

  const deleteEdge = useCallback((edgeId: string) => {
    pushUndo();
    setGraph(prev => ({ ...prev, edges: prev.edges.filter(e => e.id !== edgeId) }));
    setSelectedEdgeId(null);
    onDirty();
  }, [pushUndo, onDirty]);

  const disconnectNode = useCallback((nodeId: string) => {
    pushUndo();
    setGraph(prev => ({
      ...prev,
      edges: prev.edges.filter(e => e.source !== nodeId && e.target !== nodeId),
    }));
    onDirty();
  }, [pushUndo, onDirty]);

  const addEdge = useCallback((source: string, sourcePort: string, target: string, targetPort: string) => {
    const exists = graph.edges.some(
      e => e.source === source && e.sourcePort === sourcePort && e.target === target && e.targetPort === targetPort,
    );
    if (exists || source === target) return;
    pushUndo();
    const edge: FlowEdge = { id: generateId(), source, sourcePort, target, targetPort };
    setGraph(prev => ({ ...prev, edges: [...prev.edges, edge] }));
    onDirty();
  }, [graph.edges, pushUndo, onDirty]);

  /* ---- mouse handlers ---- */
  const handleSvgMouseDown = useCallback((e: React.MouseEvent<SVGSVGElement>) => {
    setContextMenu(null);
    // Middle button or space+left → pan
    if (e.button === 1 || (e.button === 0 && spaceHeld)) {
      e.preventDefault();
      setPanning({ startX: e.clientX, startY: e.clientY, startPanX: panX, startPanY: panY });
      return;
    }
    // Left click on empty canvas → start selection box
    if (e.button === 0 && (e.target as Element) === svgRef.current) {
      const pos = clientToSvg(e.clientX, e.clientY, svgRef.current, panX, panY, zoom);
      setSelectionBox({ x1: pos.x, y1: pos.y, x2: pos.x, y2: pos.y });
      if (!e.shiftKey) {
        setSelectedNodeIds(new Set());
        setSelectedEdgeId(null);
      }
    }
  }, [spaceHeld, panX, panY, zoom]);

  const handleSvgMouseMove = useCallback((e: React.MouseEvent<SVGSVGElement>) => {
    // Panning
    if (panning) {
      setViewport({
        x: panning.startPanX + (e.clientX - panning.startX),
        y: panning.startPanY + (e.clientY - panning.startY),
      });
      return;
    }
    // Node dragging
    if (dragging) {
      const dx = (e.clientX - dragging.startX) / zoom;
      const dy = (e.clientY - dragging.startY) / zoom;
      setGraph(prev => ({
        ...prev,
        nodes: prev.nodes.map(n => {
          if (n.id === dragging.nodeId || (selectedNodeIds.has(n.id) && selectedNodeIds.has(dragging.nodeId))) {
            return { ...n, x: n.x + dx, y: n.y + dy };
          }
          return n;
        }),
      }));
      setDragging(prev => prev ? { ...prev, startX: e.clientX, startY: e.clientY } : null);
      return;
    }
    // Connecting
    if (connecting) {
      const pos = clientToSvg(e.clientX, e.clientY, svgRef.current, panX, panY, zoom);
      setConnecting(prev => prev ? { ...prev, mouseX: pos.x, mouseY: pos.y } : null);
      return;
    }
    // Selection box
    if (selectionBox) {
      const pos = clientToSvg(e.clientX, e.clientY, svgRef.current, panX, panY, zoom);
      setSelectionBox(prev => prev ? { ...prev, x2: pos.x, y2: pos.y } : null);
    }
  }, [panning, dragging, connecting, selectionBox, zoom, panX, panY, selectedNodeIds, setViewport]);

  const handleSvgMouseUp = useCallback((_e: React.MouseEvent<SVGSVGElement>) => {
    if (panning) { setPanning(null); return; }
    if (dragging) {
      // Snap to grid on release
      setGraph(prev => ({
        ...prev,
        nodes: prev.nodes.map(n => {
          if (n.id === dragging.nodeId || (selectedNodeIds.has(n.id) && selectedNodeIds.has(dragging.nodeId))) {
            const snapped = snapToGrid(n.x, n.y);
            return { ...n, x: snapped.x, y: snapped.y };
          }
          return n;
        }),
      }));
      onDirty();
      setDragging(null);
      return;
    }
    if (connecting) {
      setConnecting(null);
      return;
    }
    if (selectionBox) {
      // Select nodes inside box
      const bx1 = Math.min(selectionBox.x1, selectionBox.x2);
      const by1 = Math.min(selectionBox.y1, selectionBox.y2);
      const bx2 = Math.max(selectionBox.x1, selectionBox.x2);
      const by2 = Math.max(selectionBox.y1, selectionBox.y2);
      const ids = new Set(selectedNodeIds);
      for (const n of graph.nodes) {
        if (n.x >= bx1 && n.y >= by1 && n.x + (n.width ?? 180) <= bx2 && n.y + (n.height ?? 80) <= by2) {
          ids.add(n.id);
        }
      }
      setSelectedNodeIds(ids);
      setSelectionBox(null);
    }
  }, [panning, dragging, connecting, selectionBox, selectedNodeIds, graph.nodes, onDirty]);

  const handleWheel = useCallback((e: React.WheelEvent<SVGSVGElement>) => {
    e.preventDefault();
    const delta = e.deltaY > 0 ? -ZOOM_STEP : ZOOM_STEP;
    zoomTo(zoom + delta, e.clientX - (svgRef.current?.getBoundingClientRect().left ?? 0), e.clientY - (svgRef.current?.getBoundingClientRect().top ?? 0));
  }, [zoom, zoomTo]);

  /* ---- node interaction callbacks ---- */
  const handleNodeMouseDown = useCallback((e: React.MouseEvent, nodeId: string) => {
    e.stopPropagation();
    setContextMenu(null);
    if (e.button !== 0) return;
    if (e.shiftKey) {
      setSelectedNodeIds(prev => {
        const next = new Set(prev);
        next.has(nodeId) ? next.delete(nodeId) : next.add(nodeId);
        return next;
      });
      return;
    }
    if (!selectedNodeIds.has(nodeId)) {
      setSelectedNodeIds(new Set([nodeId]));
    }
    setSelectedEdgeId(null);
    pushUndo();
    setDragging({ nodeId, startX: e.clientX, startY: e.clientY, offsetX: 0, offsetY: 0 });
  }, [selectedNodeIds, pushUndo]);

  const handlePortMouseDown = useCallback((e: React.MouseEvent, nodeId: string, portId: string, isOutput: boolean) => {
    e.stopPropagation();
    e.preventDefault();
    const node = graph.nodes.find(n => n.id === nodeId);
    if (!node) return;
    const pos = getPortPosition(node, portId, isOutput);
    setConnecting({ sourceId: nodeId, sourcePort: portId, isOutput, mouseX: pos.x, mouseY: pos.y });
  }, [graph.nodes]);

  const handlePortMouseUp = useCallback((e: React.MouseEvent, nodeId: string, portId: string, isOutput: boolean) => {
    e.stopPropagation();
    if (!connecting) return;
    // Must connect output→input
    if (connecting.isOutput && !isOutput) {
      addEdge(connecting.sourceId, connecting.sourcePort, nodeId, portId);
    } else if (!connecting.isOutput && isOutput) {
      addEdge(nodeId, portId, connecting.sourceId, connecting.sourcePort);
    }
    setConnecting(null);
  }, [connecting, addEdge]);

  const handleEdgeClick = useCallback((e: React.MouseEvent, edgeId: string) => {
    e.stopPropagation();
    setSelectedNodeIds(new Set());
    setSelectedEdgeId(edgeId);
  }, []);

  /* ---- save & code generation ---- */
  const handleSave = useCallback(() => {
    const json = serializeGraph(graph);
    onSave(json);
  }, [graph, onSave]);

  const handleGenerateCode = useCallback(() => {
    const code = generatePythonCode(graph);
    if (insertCode) {
      insertCode(code);
    } else {
      try { navigator.clipboard.writeText(code); } catch { /* ignore */ }
      alert(t('flow:codeCopied'));
    }
  }, [graph, insertCode]);

  /* ---- copy / paste ---- */
  const handleCopy = useCallback(() => {
    const nodes = graph.nodes.filter(n => selectedNodeIds.has(n.id));
    if (nodes.length > 0) setClipboard(nodes.map(n => ({ ...n })));
  }, [graph.nodes, selectedNodeIds]);

  const handlePaste = useCallback(() => {
    if (clipboard.length === 0) return;
    pushUndo();
    const idMap = new Map<string, string>();
    const newNodes = clipboard.map(n => {
      const newId = generateId();
      idMap.set(n.id, newId);
      return { ...n, id: newId, x: n.x + 40, y: n.y + 40 };
    });
    // Also duplicate edges between copied nodes
    const newEdges = graph.edges
      .filter(e => idMap.has(e.source) && idMap.has(e.target))
      .map(e => ({
        ...e,
        id: generateId(),
        source: idMap.get(e.source)!,
        target: idMap.get(e.target)!,
      }));
    setGraph(prev => ({
      ...prev,
      nodes: [...prev.nodes, ...newNodes],
      edges: [...prev.edges, ...newEdges],
    }));
    setSelectedNodeIds(new Set(newNodes.map(n => n.id)));
    onDirty();
  }, [clipboard, graph.edges, pushUndo, onDirty]);

  /* ---- keyboard handlers ---- */
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.code === 'Space' && !e.repeat) { setSpaceHeld(true); e.preventDefault(); }
      if (e.key === 'Delete' || e.key === 'Backspace') {
        if (selectedNodeIds.size > 0) deleteNodes(selectedNodeIds);
        else if (selectedEdgeId) deleteEdge(selectedEdgeId);
      }
      if (e.ctrlKey || e.metaKey) {
        if (e.key === 'z' && !e.shiftKey) { e.preventDefault(); handleUndo(); }
        if (e.key === 'z' && e.shiftKey) { e.preventDefault(); handleRedo(); }
        if (e.key === 'Z') { e.preventDefault(); handleRedo(); }
        if (e.key === 's') { e.preventDefault(); handleSave(); }
        if (e.key === 'c') { handleCopy(); }
        if (e.key === 'v') { e.preventDefault(); handlePaste(); }
      }
    };
    const onKeyUp = (e: KeyboardEvent) => {
      if (e.code === 'Space') setSpaceHeld(false);
    };
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('keyup', onKeyUp);
    return () => { window.removeEventListener('keydown', onKeyDown); window.removeEventListener('keyup', onKeyUp); };
  }, [selectedNodeIds, selectedEdgeId, handleUndo, handleRedo, handleSave, handleCopy, handlePaste, deleteNodes, deleteEdge]);

  /* ---- drop from palette ---- */
  const handleDragOver = useCallback((e: React.DragEvent) => { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; }, []);

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    const nodeType = e.dataTransfer.getData('application/flow-node-type') as FlowNodeType;
    if (!nodeType) return;
    const pos = clientToSvg(e.clientX, e.clientY, svgRef.current, panX, panY, zoom);
    const snapped = snapToGrid(pos.x, pos.y);
    const newNode = addNode(nodeType, snapped.x, snapped.y);
    setSelectedNodeIds(new Set([newNode.id]));
  }, [panX, panY, zoom, addNode]);

  /* ---- context menu ---- */
  const handleContextMenu = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    const pos = clientToSvg(e.clientX, e.clientY, svgRef.current, panX, panY, zoom);
    // Check if right-clicked on a node
    const clickedNode = graph.nodes.find(n =>
      pos.x >= n.x && pos.x <= n.x + (n.width ?? 180) &&
      pos.y >= n.y && pos.y <= n.y + (n.height ?? 80)
    );
    if (clickedNode) {
      if (!selectedNodeIds.has(clickedNode.id)) setSelectedNodeIds(new Set([clickedNode.id]));
      setContextMenu({ x: e.clientX, y: e.clientY, type: 'node', nodeId: clickedNode.id });
    } else {
      setContextMenu({ x: e.clientX, y: e.clientY, type: 'canvas' });
    }
  }, [panX, panY, zoom, graph.nodes, selectedNodeIds]);

  const nodeTypeEntries: { type: FlowNodeType; label: string }[] = useMemo(() => {
    return Object.entries(NODE_DEFAULTS).map(([type, def]) => ({
      type: type as FlowNodeType,
      label: t((def as any).label ?? type),
    }));
  }, [t]);

  /* ---- render ---- */
  return (
    <div className="flex h-full w-full bg-[#1e1e2e] text-gray-200 text-sm select-none">
      {/* Left palette */}
      <div className="w-[200px] flex-shrink-0 border-r border-[#313244] overflow-y-auto">
        <FlowNodePalette onAddNode={(type) => {
          const pos = clientToSvg(100, 100, svgRef.current, panX, panY, zoom);
          const snapped = snapToGrid(pos.x, pos.y);
          addNode(type, snapped.x, snapped.y);
        }} />
      </div>

      {/* Center area */}
      <div className="flex flex-col flex-1 min-w-0">
        {/* TOOLBAR */}
        <div className="h-10 flex items-center gap-2 px-3 border-b border-[#313244] bg-[#1e1e2e] flex-shrink-0">
          <button onClick={handleSave} className="px-3 py-1 rounded bg-[#89b4fa] text-[#1e1e2e] font-medium hover:bg-[#74c7ec] text-xs">{t('flow:save')}</button>
          <button onClick={handleGenerateCode} className="px-3 py-1 rounded bg-[#a6e3a1] text-[#1e1e2e] font-medium hover:bg-[#94e2d5] text-xs">{t('flow:genCode')}</button>
          <div className="w-px h-5 bg-[#313244] mx-1" />
          <button onClick={handleUndo} disabled={undoStack.length === 0} className="px-2 py-1 rounded bg-[#313244] hover:bg-[#45475a] disabled:opacity-30 text-xs">{t('flow:undo')}</button>
          <button onClick={handleRedo} disabled={redoStack.length === 0} className="px-2 py-1 rounded bg-[#313244] hover:bg-[#45475a] disabled:opacity-30 text-xs">{t('flow:redo')}</button>
          <div className="w-px h-5 bg-[#313244] mx-1" />
          <button onClick={() => zoomTo(zoom - ZOOM_STEP)} className="px-2 py-1 rounded bg-[#313244] hover:bg-[#45475a] text-xs">−</button>
          <span className="text-xs text-gray-400 w-12 text-center">{Math.round(zoom * 100)}%</span>
          <button onClick={() => zoomTo(zoom + ZOOM_STEP)} className="px-2 py-1 rounded bg-[#313244] hover:bg-[#45475a] text-xs">+</button>
          <button onClick={fitToCanvas} className="px-2 py-1 rounded bg-[#313244] hover:bg-[#45475a] text-xs">{t('flow:fitCanvas')}</button>
        </div>

        {/* SVG canvas */}
        <div ref={containerRef} className="flex-1 relative overflow-hidden bg-[#181825]">
          <svg
            ref={svgRef}
            className="w-full h-full"
            style={{ cursor: spaceHeld || panning ? 'grab' : connecting ? 'crosshair' : 'default' }}
            onMouseDown={handleSvgMouseDown}
            onMouseMove={handleSvgMouseMove}
            onMouseUp={handleSvgMouseUp}
            onWheel={handleWheel}
            onContextMenu={handleContextMenu}
            onDragOver={handleDragOver}
            onDrop={handleDrop}
          >
            {/* Grid pattern */}
            <defs>
              <pattern id="flow-grid-small" width={GRID_SIZE} height={GRID_SIZE} patternUnits="userSpaceOnUse"
                patternTransform={`translate(${panX},${panY}) scale(${zoom})`}>
                <path d={`M ${GRID_SIZE} 0 L 0 0 0 ${GRID_SIZE}`} fill="none" stroke="#313244" strokeWidth={0.5} />
              </pattern>
              <pattern id="flow-grid-large" width={GRID_SIZE * 5} height={GRID_SIZE * 5} patternUnits="userSpaceOnUse"
                patternTransform={`translate(${panX},${panY}) scale(${zoom})`}>
                <rect width={GRID_SIZE * 5} height={GRID_SIZE * 5} fill="url(#flow-grid-small)" />
                <path d={`M ${GRID_SIZE * 5} 0 L 0 0 0 ${GRID_SIZE * 5}`} fill="none" stroke="#45475a" strokeWidth={0.8} />
              </pattern>
            </defs>
            <rect width="100%" height="100%" fill="url(#flow-grid-large)" />

            {/* Transform group */}
            <g transform={`translate(${panX},${panY}) scale(${zoom})`}>
              {/* Edges */}
              {graph.edges.map(edge => {
                const srcNode = graph.nodes.find(n => n.id === edge.source);
                const tgtNode = graph.nodes.find(n => n.id === edge.target);
                if (!srcNode || !tgtNode) return null;
                const p1 = getPortPosition(srcNode, edge.sourcePort, true);
                const p2 = getPortPosition(tgtNode, edge.targetPort, false);
                const isSelected = selectedEdgeId === edge.id;
                return (
                  <g key={edge.id} onClick={(e) => handleEdgeClick(e, edge.id)} style={{ cursor: 'pointer' }}>
                    {/* Invisible wider path for easier clicking */}
                    <path d={buildBezierPath(p1.x, p1.y, p2.x, p2.y)} fill="none" stroke="transparent" strokeWidth={12} />
                    <path
                      d={buildBezierPath(p1.x, p1.y, p2.x, p2.y)}
                      fill="none"
                      stroke={isSelected ? '#89b4fa' : '#6c7086'}
                      strokeWidth={isSelected ? 2.5 : 2}
                      strokeLinecap="round"
                    />
                    {/* Arrow at target */}
                    <circle cx={p2.x} cy={p2.y} r={3} fill={isSelected ? '#89b4fa' : '#6c7086'} />
                  </g>
                );
              })}

              {/* In-progress connection line */}
              {connecting && (() => {
                const srcNode = graph.nodes.find(n => n.id === connecting.sourceId);
                if (!srcNode) return null;
                const p1 = getPortPosition(srcNode, connecting.sourcePort, connecting.isOutput);
                const p2x = connecting.mouseX;
                const p2y = connecting.mouseY;
                const path = connecting.isOutput
                  ? buildBezierPath(p1.x, p1.y, p2x, p2y)
                  : buildBezierPath(p2x, p2y, p1.x, p1.y);
                return (
                  <path d={path} fill="none" stroke="#89b4fa" strokeWidth={2} strokeDasharray="6 3" opacity={0.7} />
                );
              })()}

              {/* Nodes */}
              {graph.nodes.map(node => (
                <FlowNodeRenderer
                  key={node.id}
                  node={node}
                  selected={selectedNodeIds.has(node.id)}
                  onMouseDown={(e) => handleNodeMouseDown(e, node.id)}
                  onPortMouseDown={(e, portId, isOutput) => handlePortMouseDown(e, node.id, portId, !!isOutput)}
                  onPortMouseUp={(e, portId, isOutput) => handlePortMouseUp(e, node.id, portId, !!isOutput)}
                  onDoubleClick={(nodeId) => setSelectedNodeIds(new Set([nodeId]))}
                />
              ))}

              {/* Selection box */}
              {selectionBox && (() => {
                const x = Math.min(selectionBox.x1, selectionBox.x2);
                const y = Math.min(selectionBox.y1, selectionBox.y2);
                const w = Math.abs(selectionBox.x2 - selectionBox.x1);
                const h = Math.abs(selectionBox.y2 - selectionBox.y1);
                return (
                  <rect x={x} y={y} width={w} height={h}
                    fill="rgba(137,180,250,0.08)" stroke="#89b4fa" strokeWidth={1} strokeDasharray="4 2" />
                );
              })()}
            </g>
          </svg>

          {/* Context menu */}
          {/* Context menu overlay */}
          {contextMenu && (
            <>
              {/* Backdrop to close menu */}
              <div className="fixed inset-0 z-40" onClick={() => setContextMenu(null)} onContextMenu={(e) => { e.preventDefault(); setContextMenu(null); }} />
              <div
                className="fixed z-50 bg-[#1e1e2e] border border-[#313244] rounded-md shadow-xl py-1 min-w-[160px]"
                style={{ left: contextMenu.x, top: contextMenu.y }}
              >
                {contextMenu.type === 'canvas' && (
                  <>
                    <div className="px-3 py-1.5 text-xs text-gray-500">{t('flow:addNode')}</div>
                    {nodeTypeEntries.map(entry => (
                      <button
                        key={entry.type}
                        className="w-full text-left px-3 py-1.5 text-xs hover:bg-[#313244] text-gray-300"
                        onClick={() => {
                          const pos = clientToSvg(contextMenu.x, contextMenu.y, svgRef.current, panX, panY, zoom);
                          const snapped = snapToGrid(pos.x, pos.y);
                          addNode(entry.type, snapped.x, snapped.y);
                          setContextMenu(null);
                        }}
                      >
                        {t(entry.label)}
                      </button>
                    ))}
                    <div className="h-px bg-[#313244] my-1" />
                    <button
                      className="w-full text-left px-3 py-1.5 text-xs hover:bg-[#313244] text-gray-300 disabled:opacity-30"
                      disabled={clipboard.length === 0}
                      onClick={() => { handlePaste(); setContextMenu(null); }}
                    >
                      {t('flow:paste')}
                    </button>
                  </>
                )}
                {contextMenu.type === 'node' && contextMenu.nodeId && (
                  <>
                    <button
                      className="w-full text-left px-3 py-1.5 text-xs hover:bg-[#313244] text-gray-300"
                      onClick={() => { handleCopy(); setContextMenu(null); }}
                    >
                      {t('flow:copy')}
                    </button>
                    <button
                      className="w-full text-left px-3 py-1.5 text-xs hover:bg-[#313244] text-gray-300"
                      onClick={() => { disconnectNode(contextMenu.nodeId!); setContextMenu(null); }}
                    >
                      {t('flow:disconnectEdges')}
                    </button>
                    <div className="h-px bg-[#313244] my-1" />
                    <button
                      className="w-full text-left px-3 py-1.5 text-xs hover:bg-[#f38ba8] text-gray-300"
                      onClick={() => { deleteNodes(selectedNodeIds.size > 0 ? selectedNodeIds : new Set([contextMenu.nodeId!])); setContextMenu(null); }}
                    >
                      {t('flow:delete')}
                    </button>
                  </>
                )}
              </div>
            </>
          )}
        </div>
      </div>

      {/* Right config panel */}
      <div className="w-[280px] flex-shrink-0 border-l border-[#313244] overflow-y-auto">
        <FlowNodeConfig
          node={graph.nodes.find(n => selectedNodeIds.size === 1 && selectedNodeIds.has(n.id)) ?? null}
          onChange={(nodeId, data) => {
            pushUndo();
            setGraph(prev => ({
              ...prev,
              nodes: prev.nodes.map(n => n.id === nodeId ? { ...n, data } : n),
            }));
            onDirty();
          }}
          onLabelChange={(nodeId, label) => {
            pushUndo();
            setGraph(prev => ({
              ...prev,
              nodes: prev.nodes.map(n => n.id === nodeId ? { ...n, label } : n),
            }));
            onDirty();
          }}
          onDelete={(nodeId) => {
            deleteNodes(new Set([nodeId]));
          }}
        />
      </div>
    </div>
  );
};

export default FlowEditorPanel;
