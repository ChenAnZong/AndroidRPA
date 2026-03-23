import React, { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import type { FlowNode, FlowNodeType } from '@/types';
import { NODE_ICONS } from './flowTypes';

// ── Props ──

interface FlowNodeConfigProps {
  node: FlowNode | null;
  onChange: (nodeId: string, data: Record<string, unknown>) => void;
  onLabelChange: (nodeId: string, label: string) => void;
  onDelete: (nodeId: string) => void;
}

// ── Styles ──

const S = {
  panel: {
    width: '100%',
    height: '100%',
    backgroundColor: '#1e1e1e',
    color: '#cccccc',
    fontSize: 13,
    display: 'flex',
    flexDirection: 'column' as const,
    overflow: 'hidden',
  },
  placeholder: {
    display: 'flex',
    alignItems: 'center' as const,
    justifyContent: 'center' as const,
    height: '100%',
    color: '#666',
    fontSize: 13,
  },
  header: {
    display: 'flex',
    alignItems: 'center' as const,
    gap: 8,
    padding: '8px 12px',
    borderBottom: '1px solid #333',
    flexShrink: 0,
  },
  icon: { fontSize: 16, flexShrink: 0 },
  labelInput: {
    flex: 1,
    background: '#2d2d2d',
    border: '1px solid #3c3c3c',
    borderRadius: 3,
    color: '#cccccc',
    fontSize: 13,
    padding: '3px 6px',
    outline: 'none',
  },
  deleteBtn: {
    background: 'none',
    border: 'none',
    color: '#f44336',
    cursor: 'pointer',
    fontSize: 15,
    padding: '2px 4px',
    borderRadius: 3,
    flexShrink: 0,
  },
  body: {
    flex: 1,
    overflowY: 'auto' as const,
    padding: '10px 12px',
  },
  fieldRow: {
    marginBottom: 10,
  },
  fieldLabel: {
    display: 'block',
    marginBottom: 3,
    fontSize: 11,
    color: '#999',
  },
  input: {
    width: '100%',
    boxSizing: 'border-box' as const,
    background: '#2d2d2d',
    border: '1px solid #3c3c3c',
    borderRadius: 3,
    color: '#cccccc',
    fontSize: 12,
    padding: '4px 6px',
    outline: 'none',
  },
  textarea: {
    width: '100%',
    boxSizing: 'border-box' as const,
    background: '#2d2d2d',
    border: '1px solid #3c3c3c',
    borderRadius: 3,
    color: '#cccccc',
    fontSize: 12,
    padding: '4px 6px',
    outline: 'none',
    resize: 'vertical' as const,
    minHeight: 56,
    fontFamily: 'monospace',
  },
  select: {
    width: '100%',
    boxSizing: 'border-box' as const,
    background: '#2d2d2d',
    border: '1px solid #3c3c3c',
    borderRadius: 3,
    color: '#cccccc',
    fontSize: 12,
    padding: '4px 6px',
    outline: 'none',
  },
  footer: {
    padding: '6px 12px',
    borderTop: '1px solid #333',
    fontSize: 10,
    color: '#555',
    flexShrink: 0,
  },
  infoText: {
    color: '#666',
    fontSize: 12,
    fontStyle: 'italic' as const,
  },
} as const;

// ── Field definitions per node type ──

interface FieldDef {
  key: string;
  label: string;
  type: 'number' | 'string' | 'textarea' | 'select';
  placeholder?: string;
  step?: number;
  min?: number;
  max?: number;
  options?: { value: string; label: string }[];
  /** Show this field only when a sibling field matches a value */
  showWhen?: { field: string; value: unknown };
}

const FIELD_DEFS: Partial<Record<FlowNodeType, FieldDef[]>> = {
  click: [
    { key: 'x', label: 'X', type: 'number' },
    { key: 'y', label: 'Y', type: 'number' },
    { key: 'clickTime', label: 'flow:clickCount', type: 'number', min: 1 },
    { key: 'interval', label: 'flow:intervalMs', type: 'number', min: 0 },
  ],
  swipe: [
    { key: 'x1', label: 'X1', type: 'number' },
    { key: 'y1', label: 'Y1', type: 'number' },
    { key: 'x2', label: 'X2', type: 'number' },
    { key: 'y2', label: 'Y2', type: 'number' },
    { key: 'duration', label: 'flow:durationMs', type: 'number', min: 0 },
  ],
  ocr_find: [
    { key: 'text', label: 'flow:findText', type: 'string' },
    { key: 'timeout', label: 'flow:timeoutSec', type: 'number', min: 0 },
    { key: 'region', label: 'flow:region', type: 'string', placeholder: 'x1,y1,x2,y2' },
  ],
  ocr_click: [
    { key: 'text', label: 'flow:clickText', type: 'string' },
    { key: 'timeout', label: 'flow:timeoutSec', type: 'number', min: 0 },
  ],
  find_image: [
    { key: 'template', label: 'flow:templatePath', type: 'string' },
    { key: 'threshold', label: 'flow:threshold', type: 'number', step: 0.1, min: 0, max: 1 },
  ],
  condition: [
    { key: 'expression', label: 'flow:conditionExpr', type: 'textarea' },
  ],
  loop: [
    {
      key: 'mode', label: 'flow:loopMode', type: 'select',
      options: [
        { value: 'count', label: 'flow:fixedCount' },
        { value: 'condition', label: 'flow:conditionLoop' },
        { value: 'infinite', label: 'flow:infiniteLoop' },
      ],
    },
    { key: 'count', label: 'flow:count', type: 'number', min: 1, showWhen: { field: 'mode', value: 'count' } },
    { key: 'expression', label: 'flow:conditionExpr', type: 'string', showWhen: { field: 'mode', value: 'condition' } },
  ],
  wait: [
    { key: 'timeout', label: 'flow:timeoutSec', type: 'number', min: 0 },
    { key: 'condition', label: 'flow:waitCondition', type: 'string' },
  ],
  sleep: [
    { key: 'seconds', label: 'flow:seconds', type: 'number', min: 0 },
  ],
  shell: [
    { key: 'command', label: 'flow:shellCommand', type: 'textarea' },
  ],
  open_app: [
    { key: 'package', label: 'flow:packageName', type: 'string' },
  ],
  input_text: [
    { key: 'text', label: 'flow:textContent', type: 'string' },
  ],
  screenshot: [
    { key: 'savePath', label: 'flow:savePath', type: 'string' },
  ],
  log: [
    { key: 'message', label: 'flow:logContent', type: 'textarea' },
  ],
  sub_flow: [
    { key: 'flowFile', label: 'flow:subFlowFile', type: 'string' },
  ],
};

// ── Field renderer ──

interface FieldInputProps {
  def: FieldDef;
  value: unknown;
  onFieldChange: (key: string, value: unknown) => void;
}

const FieldInput: React.FC<FieldInputProps> = React.memo(({ def, value, onFieldChange }) => {
  const { t } = useTranslation(['flow']);
  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => {
      const raw = e.target.value;
      onFieldChange(def.key, def.type === 'number' ? (raw === '' ? 0 : Number(raw)) : raw);
    },
    [def.key, def.type, onFieldChange],
  );

  if (def.type === 'select') {
    return (
      <select style={S.select} value={String(value ?? '')} onChange={handleChange}>
        {def.options!.map((o) => (
          <option key={o.value} value={o.value}>{t(o.label)}</option>
        ))}
      </select>
    );
  }

  if (def.type === 'textarea') {
    return (
      <textarea
        style={S.textarea}
        value={String(value ?? '')}
        placeholder={def.placeholder}
        onChange={handleChange}
      />
    );
  }

  return (
    <input
      style={S.input}
      type={def.type === 'number' ? 'number' : 'text'}
      value={def.type === 'number' ? (Number(value ?? 0)) : String(value ?? '')}
      placeholder={def.placeholder}
      step={def.step}
      min={def.min as number | undefined}
      max={def.max as number | undefined}
      onChange={handleChange}
    />
  );
});

FieldInput.displayName = 'FieldInput';

// ── Main component ──

const FlowNodeConfig: React.FC<FlowNodeConfigProps> = ({ node, onChange, onLabelChange, onDelete }) => {
  const { t } = useTranslation(['flow']);
  const handleLabelChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      if (node) onLabelChange(node.id, e.target.value);
    },
    [node, onLabelChange],
  );

  const handleDelete = useCallback(() => {
    if (node) onDelete(node.id);
  }, [node, onDelete]);

  const handleFieldChange = useCallback(
    (key: string, value: unknown) => {
      if (!node) return;
      onChange(node.id, { ...node.data, [key]: value });
    },
    [node, onChange],
  );

  if (!node) {
    return (
      <div style={S.panel}>
        <div style={S.placeholder}>{t('selectNodeToEdit')}</div>
      </div>
    );
  }

  const fields = FIELD_DEFS[node.type];
  const isTerminal = node.type === 'start' || node.type === 'end';

  return (
    <div style={S.panel}>
      {/* Header */}
      <div style={S.header}>
        <span style={S.icon}>{NODE_ICONS[node.type]}</span>
        <input
          style={S.labelInput}
          value={node.label}
          onChange={handleLabelChange}
          spellCheck={false}
        />
        <button
          style={S.deleteBtn}
          onClick={handleDelete}
          title={t('deleteNodeTitle')}
        >
          🗑
        </button>
      </div>

      {/* Body */}
      <div style={S.body}>
        {isTerminal && (
          <p style={S.infoText}>
            {node.type === 'start' ? t('startNodeHint') : t('endNodeHint')}
          </p>
        )}

        {fields?.map((def) => {
          // Conditional visibility
          if (def.showWhen && node.data[def.showWhen.field] !== def.showWhen.value) {
            return null;
          }
          return (
            <div key={def.key} style={S.fieldRow}>
              <label style={S.fieldLabel}>{t(def.label)}</label>
              <FieldInput def={def} value={node.data[def.key]} onFieldChange={handleFieldChange} />
            </div>
          );
        })}
      </div>

      {/* Footer */}
      <div style={S.footer}>ID: {node.id}</div>
    </div>
  );
};

export default React.memo(FlowNodeConfig);
