import type { FlowNodeType } from '@/types';

// ── Category definitions ──

export interface NodeCategoryItem {
  type: FlowNodeType;
  label: string;
}

export interface NodeCategory {
  name: string;
  nodes: NodeCategoryItem[];
}

// i18n keys – resolved at render time via t('flow:xxx')
export const NODE_CATEGORIES: NodeCategory[] = [
  {
    name: 'flow:catStartEnd',
    nodes: [
      { type: 'start', label: 'flow:nodeStart' },
      { type: 'end', label: 'flow:nodeEnd' },
    ],
  },
  {
    name: 'flow:catTouch',
    nodes: [
      { type: 'click', label: 'flow:nodeClick' },
      { type: 'swipe', label: 'flow:nodeSwipe' },
      { type: 'input_text', label: 'flow:nodeInputText' },
    ],
  },
  {
    name: 'flow:catRecognition',
    nodes: [
      { type: 'ocr_find', label: 'flow:nodeOcrFind' },
      { type: 'ocr_click', label: 'flow:nodeOcrClick' },
      { type: 'find_image', label: 'flow:nodeFindImage' },
    ],
  },
  {
    name: 'flow:catFlowControl',
    nodes: [
      { type: 'condition', label: 'flow:nodeCondition' },
      { type: 'loop', label: 'flow:nodeLoop' },
      { type: 'wait', label: 'flow:nodeWait' },
      { type: 'sleep', label: 'flow:nodeSleep' },
    ],
  },
  {
    name: 'flow:catSystem',
    nodes: [
      { type: 'shell', label: 'flow:nodeShell' },
      { type: 'open_app', label: 'flow:nodeOpenApp' },
      { type: 'screenshot', label: 'flow:nodeScreenshot' },
      { type: 'log', label: 'flow:nodeLog' },
    ],
  },
];

// ── Node defaults ──

export interface NodeDefault {
  label: string;
  width: number;
  height: number;
  data: Record<string, unknown>;
  inputs: { id: string; label?: string }[];
  outputs: { id: string; label?: string }[];
}

export const NODE_DEFAULTS: Record<FlowNodeType, NodeDefault> = {
  start:      { label: 'flow:nodeStart', width: 120, height: 50, data: {}, inputs: [], outputs: [{ id: 'out' }] },
  end:        { label: 'flow:nodeEnd', width: 120, height: 50, data: {}, inputs: [{ id: 'in' }], outputs: [] },
  click:      { label: 'flow:nodeClick', width: 160, height: 60, data: { x: 0, y: 0, clickTime: 1, interval: 50 }, inputs: [{ id: 'in' }], outputs: [{ id: 'out' }] },
  swipe:      { label: 'flow:nodeSwipe', width: 160, height: 60, data: { x1: 0, y1: 0, x2: 0, y2: 0, duration: 300 }, inputs: [{ id: 'in' }], outputs: [{ id: 'out' }] },
  input_text: { label: 'flow:nodeInputText', width: 160, height: 60, data: { text: '' }, inputs: [{ id: 'in' }], outputs: [{ id: 'out' }] },
  ocr_find:   { label: 'flow:nodeOcrFind', width: 160, height: 60, data: { text: '', timeout: 10, region: '' }, inputs: [{ id: 'in' }], outputs: [{ id: 'found', label: 'flow:portFound' }, { id: 'not_found', label: 'flow:portNotFound' }] },
  ocr_click:  { label: 'flow:nodeOcrClick', width: 160, height: 60, data: { text: '', timeout: 10 }, inputs: [{ id: 'in' }], outputs: [{ id: 'out' }] },
  find_image: { label: 'flow:nodeFindImage', width: 160, height: 60, data: { template: '', threshold: 0.8 }, inputs: [{ id: 'in' }], outputs: [{ id: 'found', label: 'flow:portFound' }, { id: 'not_found', label: 'flow:portNotFound' }] },
  condition:  { label: 'flow:nodeCondition', width: 170, height: 70, data: { expression: '' }, inputs: [{ id: 'in' }], outputs: [{ id: 'true', label: 'flow:portYes' }, { id: 'false', label: 'flow:portNo' }] },
  loop:       { label: 'flow:nodeLoop', width: 170, height: 70, data: { mode: 'count', count: 10, expression: '' }, inputs: [{ id: 'in' }], outputs: [{ id: 'body', label: 'flow:portBody' }, { id: 'done', label: 'flow:portDone' }] },
  wait:       { label: 'flow:nodeWait', width: 160, height: 60, data: { timeout: 10, condition: '' }, inputs: [{ id: 'in' }], outputs: [{ id: 'out' }] },
  sleep:      { label: 'flow:nodeSleep', width: 140, height: 50, data: { seconds: 1 }, inputs: [{ id: 'in' }], outputs: [{ id: 'out' }] },
  shell:      { label: 'flow:nodeShell', width: 160, height: 60, data: { command: '' }, inputs: [{ id: 'in' }], outputs: [{ id: 'out' }] },
  open_app:   { label: 'flow:nodeOpenApp', width: 160, height: 60, data: { package: '' }, inputs: [{ id: 'in' }], outputs: [{ id: 'out' }] },
  screenshot: { label: 'flow:nodeScreenshot', width: 160, height: 60, data: { savePath: '' }, inputs: [{ id: 'in' }], outputs: [{ id: 'out' }] },
  log:        { label: 'flow:nodeLog', width: 140, height: 50, data: { message: '' }, inputs: [{ id: 'in' }], outputs: [{ id: 'out' }] },
  sub_flow:   { label: 'flow:nodeSubFlow', width: 170, height: 70, data: { flowFile: '' }, inputs: [{ id: 'in' }], outputs: [{ id: 'out' }] },
};

// ── Colors by category ──

export const NODE_COLORS: Record<FlowNodeType, string> = {
  // start/end
  start:      '#4caf50',
  end:        '#f44336',
  // touch
  click:      '#2196f3',
  swipe:      '#2196f3',
  input_text: '#2196f3',
  // recognition
  ocr_find:   '#ff9800',
  ocr_click:  '#ff9800',
  find_image: '#ff9800',
  // flow control
  condition:  '#9c27b0',
  loop:       '#9c27b0',
  wait:       '#9c27b0',
  sleep:      '#9c27b0',
  // system
  shell:      '#607d8b',
  open_app:   '#607d8b',
  screenshot: '#607d8b',
  log:        '#607d8b',
  // other
  sub_flow:   '#795548',
};

// ── Icons ──

export const NODE_ICONS: Record<FlowNodeType, string> = {
  start:      '▶️',
  end:        '⏹️',
  click:      '👆',
  swipe:      '👉',
  input_text: '⌨️',
  ocr_find:   '🔍',
  ocr_click:  '🔎',
  find_image: '🖼️',
  condition:  '❓',
  loop:       '🔄',
  wait:       '⏳',
  sleep:      '💤',
  shell:      '💻',
  open_app:   '📱',
  screenshot: '📸',
  log:        '📝',
  sub_flow:   '📂',
};
