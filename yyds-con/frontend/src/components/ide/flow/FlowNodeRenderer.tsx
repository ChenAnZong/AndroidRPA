import React, { useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import type { FlowNode, FlowNodePort, FlowNodeType } from '@/types';
import { NODE_COLORS, NODE_ICONS } from './flowTypes';

// ── Constants ──

const HEADER_H = 28;
const PORT_R = 6;
const PORT_HIT_R = 10;
const CORNER_R = 6;
const BODY_FILL = '#2d2d2d';
const SELECTED_COLOR = '#007acc';
const PORT_FILL = '#888';
const PORT_HOVER_FILL = '#ccc';
const PORT_STROKE = '#555';

// ── Props ──

interface FlowNodeRendererProps {
  node: FlowNode;
  selected: boolean;
  onMouseDown: (e: React.MouseEvent, nodeId: string) => void;
  onPortMouseDown: (e: React.MouseEvent, nodeId: string, portId: string, isOutput: boolean) => void;
  onPortMouseUp: (e: React.MouseEvent, nodeId: string, portId: string, isOutput: boolean) => void;
  onDoubleClick: (nodeId: string) => void;
}

// ── Helpers ──

/** Compute evenly-spaced Y positions for ports along the node height (below header). */
function portPositions(ports: FlowNodePort[], nodeHeight: number): number[] {
  const bodyH = nodeHeight - HEADER_H;
  const count = ports.length;
  if (count === 0) return [];
  const gap = bodyH / (count + 1);
  return ports.map((_, i) => HEADER_H + gap * (i + 1));
}

/** Build a short summary string for the node body area. */
function getBodySummary(type: FlowNodeType, data: Record<string, unknown>, t: (key: string) => string): string {
  switch (type) {
    case 'click':
      return `(${data.x ?? 0}, ${data.y ?? 0})  ×${data.clickTime ?? 1}`;
    case 'swipe':
      return `(${data.x1},${data.y1}) → (${data.x2},${data.y2})`;
    case 'input_text':
      return truncate(String(data.text ?? ''), 20) || t('empty');
    case 'ocr_find':
    case 'ocr_click':
      return truncate(String(data.text ?? ''), 22) || t('empty');
    case 'find_image':
      return truncate(String(data.template ?? ''), 22) || t('empty');
    case 'condition':
      return truncate(String(data.expression ?? ''), 24) || t('empty');
    case 'loop': {
      const mode = data.mode as string;
      if (mode === 'count') return `${t('count')}: ${data.count ?? 10}`;
      if (mode === 'condition') return truncate(String(data.expression ?? ''), 20);
      return t('infiniteLoop');
    }
    case 'wait':
      return `${data.timeout ?? 10}s`;
    case 'sleep':
      return `${data.seconds ?? 1}s`;
    case 'shell':
      return truncate(String(data.command ?? ''), 22) || t('empty');
    case 'open_app':
      return truncate(String(data.package ?? ''), 22) || t('empty');
    case 'screenshot':
      return truncate(String(data.savePath ?? ''), 22) || t('empty');
    case 'log':
      return truncate(String(data.message ?? ''), 22) || t('empty');
    case 'sub_flow':
      return truncate(String(data.flowFile ?? ''), 22) || t('empty');
    case 'start':
      return t('flowEntry');
    case 'end':
      return t('flowExit');
    default:
      return '';
  }
}

function truncate(s: string, max: number): string {
  return s.length > max ? s.slice(0, max - 1) + '…' : s;
}

// ── Port sub-component ──

interface PortCircleProps {
  cx: number;
  cy: number;
  port: FlowNodePort;
  nodeId: string;
  isOutput: boolean;
  onPortMouseDown: FlowNodeRendererProps['onPortMouseDown'];
  onPortMouseUp: FlowNodeRendererProps['onPortMouseUp'];
}

const PortCircle: React.FC<PortCircleProps> = React.memo(
  ({ cx, cy, port, nodeId, isOutput, onPortMouseDown, onPortMouseUp }) => {
    const { t } = useTranslation(['flow']);
    const [hovered, setHovered] = React.useState(false);

    const handleMouseDown = useCallback(
      (e: React.MouseEvent) => {
        e.stopPropagation();
        onPortMouseDown(e, nodeId, port.id, isOutput);
      },
      [onPortMouseDown, nodeId, port.id, isOutput],
    );

    const handleMouseUp = useCallback(
      (e: React.MouseEvent) => {
        e.stopPropagation();
        onPortMouseUp(e, nodeId, port.id, isOutput);
      },
      [onPortMouseUp, nodeId, port.id, isOutput],
    );

    return (
      <g>
        {/* Invisible larger hit area */}
        <circle
          cx={cx}
          cy={cy}
          r={PORT_HIT_R}
          fill="transparent"
          style={{ cursor: 'crosshair' }}
          onMouseDown={handleMouseDown}
          onMouseUp={handleMouseUp}
          onMouseEnter={() => setHovered(true)}
          onMouseLeave={() => setHovered(false)}
        />
        {/* Visible port circle */}
        <circle
          cx={cx}
          cy={cy}
          r={PORT_R}
          fill={hovered ? PORT_HOVER_FILL : PORT_FILL}
          stroke={hovered ? '#fff' : PORT_STROKE}
          strokeWidth={1.5}
          style={{ pointerEvents: 'none' }}
        />
        {/* Port label */}
        {port.label && (
          <text
            x={isOutput ? cx - PORT_R - 4 : cx + PORT_R + 4}
            y={cy + 3.5}
            fill="#999"
            fontSize={9}
            textAnchor={isOutput ? 'end' : 'start'}
            style={{ pointerEvents: 'none', userSelect: 'none' }}
          >
            {t(port.label)}
          </text>
        )}
      </g>
    );
  },
);

PortCircle.displayName = 'PortCircle';

// ── SVG filter for selected glow ──

export const SELECTED_GLOW_FILTER_ID = 'flow-node-selected-glow';

export const SelectedGlowFilter: React.FC = () => (
  <filter id={SELECTED_GLOW_FILTER_ID} x="-20%" y="-20%" width="140%" height="140%">
    <feDropShadow dx="0" dy="0" stdDeviation="3" floodColor={SELECTED_COLOR} floodOpacity="0.8" />
  </filter>
);

// ── Main component ──

const FlowNodeRenderer: React.FC<FlowNodeRendererProps> = ({
  node,
  selected,
  onMouseDown,
  onPortMouseDown,
  onPortMouseUp,
  onDoubleClick,
}) => {
  const { t } = useTranslation(['flow']);
  const { id, type, label, x, y, width = 180, height = 80, data, inputs = [], outputs = [] } = node;
  const headerColor = NODE_COLORS[type];
  const icon = NODE_ICONS[type];

  const inputYs = useMemo(() => portPositions(inputs, height), [inputs, height]);
  const outputYs = useMemo(() => portPositions(outputs, height), [outputs, height]);
  const summary = useMemo(() => getBodySummary(type, data, t), [type, data, t]);

  const handleHeaderMouseDown = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      onMouseDown(e, id);
    },
    [onMouseDown, id],
  );

  const handleDoubleClick = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      onDoubleClick(id);
    },
    [onDoubleClick, id],
  );

  // Clip path id unique per node
  const clipId = `node-clip-${id}`;

  return (
    <g transform={`translate(${x}, ${y})`}>
      {/* Clip for rounded corners */}
      <defs>
        <clipPath id={clipId}>
          <rect width={width} height={height} rx={CORNER_R} ry={CORNER_R} />
        </clipPath>
      </defs>

      {/* Outer border / selection highlight */}
      <rect
        width={width}
        height={height}
        rx={CORNER_R}
        ry={CORNER_R}
        fill="none"
        stroke={selected ? SELECTED_COLOR : '#555'}
        strokeWidth={selected ? 2.5 : 1}
        filter={selected ? `url(#${SELECTED_GLOW_FILTER_ID})` : undefined}
      />

      {/* Body background */}
      <rect
        width={width}
        height={height}
        rx={CORNER_R}
        ry={CORNER_R}
        fill={BODY_FILL}
        clipPath={`url(#${clipId})`}
      />

      {/* Header bar */}
      <rect
        width={width}
        height={HEADER_H}
        fill={headerColor}
        clipPath={`url(#${clipId})`}
        style={{ cursor: 'grab' }}
        onMouseDown={handleHeaderMouseDown}
        onDoubleClick={handleDoubleClick}
      />

      {/* Header icon */}
      <text
        x={8}
        y={HEADER_H / 2 + 1}
        fontSize={13}
        dominantBaseline="central"
        style={{ pointerEvents: 'none', userSelect: 'none' }}
      >
        {icon}
      </text>

      {/* Header label */}
      <text
        x={26}
        y={HEADER_H / 2 + 1}
        fill="#fff"
        fontSize={12}
        fontWeight={500}
        dominantBaseline="central"
        style={{ pointerEvents: 'none', userSelect: 'none' }}
      >
        {truncate(label, 16)}
      </text>

      {/* Body summary text */}
      {summary && (
        <text
          x={width / 2}
          y={HEADER_H + (height - HEADER_H) / 2 + 1}
          fill="#999"
          fontSize={11}
          textAnchor="middle"
          dominantBaseline="central"
          style={{ pointerEvents: 'none', userSelect: 'none' }}
        >
          {summary}
        </text>
      )}

      {/* Input ports (left edge) */}
      {inputs.map((port, i) => (
        <PortCircle
          key={port.id}
          cx={0}
          cy={inputYs[i]}
          port={port}
          nodeId={id}
          isOutput={false}
          onPortMouseDown={onPortMouseDown}
          onPortMouseUp={onPortMouseUp}
        />
      ))}

      {/* Output ports (right edge) */}
      {outputs.map((port, i) => (
        <PortCircle
          key={port.id}
          cx={width}
          cy={outputYs[i]}
          port={port}
          nodeId={id}
          isOutput={true}
          onPortMouseDown={onPortMouseDown}
          onPortMouseUp={onPortMouseUp}
        />
      ))}
    </g>
  );
};

export default React.memo(FlowNodeRenderer);
