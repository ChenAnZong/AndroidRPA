import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UiWidget, UiWidgetType } from '@/types';
import { parseUiYaml, serializeUiYaml, WIDGET_DEFAULTS } from './uiYamlCodec';
import UiPhoneMock from './UiPhoneMock';
import UiPropertyEditor from './UiPropertyEditor';

// ── Palette icons (simple SVG) ──
const PALETTE_ICONS: Record<UiWidgetType, React.ReactNode> = {
  text: (
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none"><text x="3" y="15" fontSize="14" fontWeight="700" fill="#e0e0e0">T</text></svg>
  ),
  div: (
    <svg width="20" height="20" viewBox="0 0 20 20"><line x1="2" y1="10" x2="18" y2="10" stroke="#888" strokeWidth="2" /></svg>
  ),
  space: (
    <svg width="20" height="20" viewBox="0 0 20 20"><line x1="2" y1="6" x2="18" y2="6" stroke="#555" strokeWidth="1" strokeDasharray="3 2" /><line x1="2" y1="14" x2="18" y2="14" stroke="#555" strokeWidth="1" strokeDasharray="3 2" /><path d="M10 8v4M8 10h4" stroke="#888" strokeWidth="1" /></svg>
  ),
  check: (
    <svg width="20" height="20" viewBox="0 0 20 20"><rect x="2" y="5" width="10" height="10" rx="2" stroke="#888" strokeWidth="1.5" fill="none" /><path d="M5 10l2 2 4-5" stroke="#3b82f6" strokeWidth="1.5" fill="none" /></svg>
  ),
  select: (
    <svg width="20" height="20" viewBox="0 0 20 20"><rect x="2" y="5" width="16" height="10" rx="2" stroke="#888" strokeWidth="1.5" fill="none" /><path d="M12 9l2 2 2-2" stroke="#3b82f6" strokeWidth="1.5" fill="none" /></svg>
  ),
  edit: (
    <svg width="20" height="20" viewBox="0 0 20 20"><rect x="2" y="5" width="16" height="10" rx="2" stroke="#888" strokeWidth="1.5" fill="none" /><line x1="5" y1="10" x2="11" y2="10" stroke="#555" strokeWidth="1" /></svg>
  ),
};

// ── Palette data ──
const PALETTE_ITEMS: { type: UiWidgetType; label: string }[] = [
  { type: 'text', label: 'designer:wText' },
  { type: 'div', label: 'designer:wDiv' },
  { type: 'space', label: 'designer:wSpace' },
  { type: 'check', label: 'designer:wCheck' },
  { type: 'select', label: 'designer:wSelect' },
  { type: 'edit', label: 'designer:wEdit' },
];

// ── Undo/Redo hook ──
const MAX_HISTORY = 50;

function useUndoRedo(initial: UiWidget[]) {
  const [stack, setStack] = useState<string[]>([JSON.stringify(initial)]);
  const [pointer, setPointer] = useState(0);

  const current: UiWidget[] = useMemo(() => {
    try { return JSON.parse(stack[pointer]); } catch { return []; }
  }, [stack, pointer]);

  const push = useCallback((widgets: UiWidget[]) => {
    const json = JSON.stringify(widgets);
    setStack((prev) => {
      const next = [...prev.slice(0, pointer + 1), json];
      if (next.length > MAX_HISTORY) next.shift();
      return next;
    });
    setPointer((p) => Math.min(p + 1, MAX_HISTORY - 1));
  }, [pointer]);

  const undo = useCallback(() => {
    setPointer((p) => Math.max(0, p - 1));
  }, []);

  const redo = useCallback(() => {
    setPointer((p) => Math.min(stack.length - 1, p + 1));
  }, [stack.length]);

  const canUndo = pointer > 0;
  const canRedo = pointer < stack.length - 1;

  const reset = useCallback((widgets: UiWidget[]) => {
    setStack([JSON.stringify(widgets)]);
    setPointer(0);
  }, []);

  return { current, push, undo, redo, canUndo, canRedo, reset };
}

// ── Widget Palette component ──
function WidgetPalette() {
  const { t } = useTranslation(['designer']);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: '12px 8px' }}>
      <div style={{ fontSize: 11, color: '#888', marginBottom: 4, padding: '0 4px' }}>{t('designer:widgets')}</div>
      {PALETTE_ITEMS.map((item) => (
        <div
          key={item.type}
          draggable
          onDragStart={(e) => {
            e.dataTransfer.setData('palette-widget-type', item.type);
            e.dataTransfer.effectAllowed = 'copy';
          }}
          style={{
            display: 'flex', alignItems: 'center', gap: 8, padding: '8px 8px',
            borderRadius: 6, cursor: 'grab', background: '#2a2a3e', border: '1px solid #333',
            transition: 'border-color .15s',
          }}
          onMouseEnter={(e) => (e.currentTarget.style.borderColor = '#3b82f6')}
          onMouseLeave={(e) => (e.currentTarget.style.borderColor = '#333')}
        >
          {PALETTE_ICONS[item.type]}
          <span style={{ fontSize: 12, color: '#ccc' }}>{t(item.label)}</span>
        </div>
      ))}
    </div>
  );
}

// ── Source editor component ──
function SourceEditor({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <textarea
      value={value}
      onChange={(e) => onChange(e.target.value)}
      spellCheck={false}
      style={{
        width: '100%', height: '100%', background: '#1a1a2e', color: '#a5d6ff',
        border: 'none', outline: 'none', resize: 'none', padding: '16px',
        fontFamily: "'JetBrains Mono', 'Fira Code', 'Consolas', monospace",
        fontSize: 13, lineHeight: 1.6, tabSize: 2,
      }}
    />
  );
}

// ── Toolbar component ──
interface ToolbarProps {
  sourceMode: boolean;
  onToggleMode: () => void;
  canUndo: boolean;
  canRedo: boolean;
  onUndo: () => void;
  onRedo: () => void;
  onSave: () => void;
}

function Toolbar({ sourceMode, onToggleMode, canUndo, canRedo, onUndo, onRedo, onSave }: ToolbarProps) {
  const { t } = useTranslation(['designer']);
  const btnStyle = (enabled: boolean): React.CSSProperties => ({
    background: 'none', border: '1px solid #444', borderRadius: 4, color: enabled ? '#e0e0e0' : '#555',
    padding: '4px 10px', fontSize: 12, cursor: enabled ? 'pointer' : 'default', opacity: enabled ? 1 : 0.5,
  });
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 12px', background: '#1a1a2e', borderBottom: '1px solid #333' }}>
      <button style={{ ...btnStyle(true), background: sourceMode ? '#3b82f6' : 'transparent', color: '#e0e0e0', borderColor: sourceMode ? '#3b82f6' : '#444' }} onClick={onToggleMode}>
        {sourceMode ? t('designer:design') : t('designer:source')}
      </button>
      <div style={{ width: 1, height: 20, background: '#333' }} />
      <button style={btnStyle(canUndo)} onClick={onUndo} disabled={!canUndo} title={t('designer:undoTitle')}>↩</button>
      <button style={btnStyle(canRedo)} onClick={onRedo} disabled={!canRedo} title={t('designer:redoTitle')}>↪</button>
      <div style={{ flex: 1 }} />
      <button
        onClick={onSave}
        style={{ background: '#3b82f6', border: 'none', borderRadius: 4, color: '#fff', padding: '5px 16px', fontSize: 12, cursor: 'pointer', fontWeight: 500 }}
        title={t('designer:saveTitle')}
      >
        {t('designer:save')}
      </button>
    </div>
  );
}

// ── Main panel ──
interface PanelProps {
  content: string;
  onSave: (content: string) => void;
  onDirty: () => void;
}

let nameCounters: Record<string, number> = {};

function nextName(type: UiWidgetType, existing: UiWidget[]): string {
  if (!nameCounters[type]) {
    nameCounters[type] = existing.filter((w) => w.type === type).length;
  }
  nameCounters[type]++;
  return `${type}${nameCounters[type]}`;
}

export default function UiDesignerPanel({ content, onSave, onDirty }: PanelProps) {
  const initialWidgets = useMemo(() => parseUiYaml(content), []);
  const { current: widgets, push, undo, redo, canUndo, canRedo } = useUndoRedo(initialWidgets);
  const [selectedIndex, setSelectedIndex] = useState<number | null>(null);
  const [sourceMode, setSourceMode] = useState(false);
  const [sourceText, setSourceText] = useState('');
  const dirtyRef = useRef(false);

  // Sync source text when entering source mode
  const toggleMode = useCallback(() => {
    setSourceMode((prev) => {
      if (!prev) {
        // entering source mode
        setSourceText(serializeUiYaml(widgets));
      } else {
        // leaving source mode — parse source back
        try {
          const parsed = parseUiYaml(sourceText);
          push(parsed);
          nameCounters = {};
        } catch { /* keep current widgets on parse error */ }
      }
      return !prev;
    });
  }, [widgets, sourceText, push]);

  // Mark dirty on widget changes
  const updateWidgets = useCallback((next: UiWidget[]) => {
    push(next);
    if (!dirtyRef.current) {
      dirtyRef.current = true;
      onDirty();
    }
  }, [push, onDirty]);

  // Handle drop from palette
  const handleDrop = useCallback((type: UiWidgetType, index: number) => {
    const defaults = WIDGET_DEFAULTS[type];
    const name = nextName(type, widgets);
    const newWidget: UiWidget = { type, name, props: { ...defaults.props } };
    const next = [...widgets];
    next.splice(index, 0, newWidget);
    updateWidgets(next);
    setSelectedIndex(index);
  }, [widgets, updateWidgets]);

  // Handle reorder
  const handleReorder = useCallback((from: number, to: number) => {
    const next = [...widgets];
    const [moved] = next.splice(from, 1);
    next.splice(to, 0, moved);
    updateWidgets(next);
    setSelectedIndex(to);
  }, [widgets, updateWidgets]);

  // Handle property change
  const handlePropChange = useCallback((props: Record<string, unknown>) => {
    if (selectedIndex === null || selectedIndex >= widgets.length) return;
    const next = widgets.map((w, i) => i === selectedIndex ? { ...w, props } : w);
    updateWidgets(next);
  }, [widgets, selectedIndex, updateWidgets]);

  // Handle save
  const handleSave = useCallback(() => {
    const yaml = sourceMode ? sourceText : serializeUiYaml(widgets);
    onSave(yaml);
    dirtyRef.current = false;
  }, [sourceMode, sourceText, widgets, onSave]);

  // Keyboard shortcuts
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'z' && !e.shiftKey) {
        e.preventDefault();
        undo();
      } else if ((e.ctrlKey || e.metaKey) && e.key === 'z' && e.shiftKey) {
        e.preventDefault();
        redo();
      } else if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        e.preventDefault();
        handleSave();
      } else if (e.key === 'Delete' && selectedIndex !== null && !sourceMode) {
        e.preventDefault();
        const next = widgets.filter((_, i) => i !== selectedIndex);
        updateWidgets(next);
        setSelectedIndex(null);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [undo, redo, handleSave, selectedIndex, widgets, updateWidgets, sourceMode]);

  // Clamp selected index
  useEffect(() => {
    if (selectedIndex !== null && selectedIndex >= widgets.length) {
      setSelectedIndex(widgets.length > 0 ? widgets.length - 1 : null);
    }
  }, [widgets.length, selectedIndex]);

  const selectedWidget = selectedIndex !== null && selectedIndex < widgets.length ? widgets[selectedIndex] : null;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', background: '#1e1e2e', color: '#e0e0e0' }}>
      <Toolbar
        sourceMode={sourceMode}
        onToggleMode={toggleMode}
        canUndo={canUndo}
        canRedo={canRedo}
        onUndo={undo}
        onRedo={redo}
        onSave={handleSave}
      />
      <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
        {/* Left: Palette */}
        <div style={{ width: 140, borderRight: '1px solid #333', overflowY: 'auto', background: '#1a1a2e', flexShrink: 0 }}>
          <WidgetPalette />
        </div>

        {/* Center: Phone mock or source editor */}
        <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'auto', padding: 16, background: '#16162a' }}>
          {sourceMode ? (
            <div style={{ width: '100%', height: '100%' }}>
              <SourceEditor value={sourceText} onChange={(v) => {
                setSourceText(v);
                if (!dirtyRef.current) { dirtyRef.current = true; onDirty(); }
              }} />
            </div>
          ) : (
            <UiPhoneMock
              widgets={widgets}
              selectedIndex={selectedIndex}
              onSelect={setSelectedIndex}
              onReorder={handleReorder}
              onDrop={handleDrop}
            />
          )}
        </div>

        {/* Right: Property editor */}
        {!sourceMode && (
          <div style={{ width: 280, borderLeft: '1px solid #333', overflowY: 'auto', background: '#1a1a2e', flexShrink: 0 }}>
            <UiPropertyEditor widget={selectedWidget} onChange={handlePropChange} />
          </div>
        )}
      </div>
    </div>
  );
}
