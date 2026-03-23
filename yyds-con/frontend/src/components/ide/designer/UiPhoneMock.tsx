import React, { useCallback, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UiWidget, UiWidgetType } from '@/types';

// ── Icons (inline SVG paths) ──
const DragHandleIcon = () => (
  <svg width="16" height="16" viewBox="0 0 16 16" fill="#888" className="shrink-0 cursor-grab">
    <circle cx="5" cy="3" r="1.2" /><circle cx="11" cy="3" r="1.2" />
    <circle cx="5" cy="8" r="1.2" /><circle cx="11" cy="8" r="1.2" />
    <circle cx="5" cy="13" r="1.2" /><circle cx="11" cy="13" r="1.2" />
  </svg>
);

// ── Drop zone indicator between items ──
interface DropZoneProps { index: number; onDrop: (type: UiWidgetType, index: number) => void; onReorderDrop: (from: number, to: number) => void; }

function DropZone({ index, onDrop, onReorderDrop }: DropZoneProps) {
  const [over, setOver] = useState(false);
  return (
    <div
      className="transition-all"
      style={{ height: over ? 6 : 2, background: over ? '#3b82f6' : 'transparent', borderRadius: 3, margin: '0 12px' }}
      onDragOver={(e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; setOver(true); }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault(); setOver(false);
        const paletteType = e.dataTransfer.getData('palette-widget-type') as UiWidgetType;
        if (paletteType) { onDrop(paletteType, index); return; }
        const fromStr = e.dataTransfer.getData('reorder-index');
        if (fromStr !== '') onReorderDrop(Number(fromStr), index);
      }}
    />
  );
}

// ── Widget renderers ──
// PLACEHOLDER_RENDERERS
function RenderText({ props }: { props: Record<string, unknown> }) {
  return <div style={{ color: (props.color as string) || '#333', fontSize: (props.size as number) || 14, padding: '8px 0', wordBreak: 'break-word' }}>{String(props.value ?? '')}</div>;
}
function RenderDiv() {
  return <div style={{ borderBottom: '1px solid #444', margin: '4px 0' }} />;
}
function RenderSpace({ props }: { props: Record<string, unknown> }) {
  return <div style={{ height: (props.height as number) || 20 }} />;
}
function RenderCheck({ props }: { props: Record<string, unknown> }) {
  const on = props.value as boolean;
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 0' }}>
      <span style={{ color: '#e0e0e0', fontSize: 14 }}>{String(props.title ?? '')}</span>
      <div style={{ width: 40, height: 22, borderRadius: 11, background: on ? '#3b82f6' : '#555', position: 'relative', transition: 'background .2s' }}>
        <div style={{ width: 18, height: 18, borderRadius: 9, background: '#fff', position: 'absolute', top: 2, left: on ? 20 : 2, transition: 'left .2s' }} />
      </div>
    </div>
  );
}
function RenderSelect({ props }: { props: Record<string, unknown> }) {
  const opts = Array.isArray(props.value) ? props.value : [];
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 0' }}>
      <span style={{ color: '#e0e0e0', fontSize: 14 }}>{String(props.title ?? '')}</span>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
        <span style={{ color: '#3b82f6', fontSize: 13 }}>{opts[0] ?? ''}</span>
        <svg width="12" height="12" viewBox="0 0 12 12" fill="#3b82f6"><path d="M3 5l3 3 3-3" stroke="#3b82f6" strokeWidth="1.5" fill="none" /></svg>
      </div>
    </div>
  );
}
function RenderEdit({ props }: { props: Record<string, unknown> }) {
  const title = String(props.title ?? '');
  const hint = String(props.hint ?? '');
  const val = String(props.value ?? '');
  return (
    <div style={{ padding: '8px 0' }}>
      <div style={{ border: '1px solid #555', borderRadius: 4, padding: '14px 12px 8px', position: 'relative' }}>
        <span style={{ position: 'absolute', top: -8, left: 10, background: '#1e1e2e', padding: '0 4px', fontSize: 11, color: '#3b82f6' }}>{title}</span>
        <span style={{ color: val ? '#e0e0e0' : '#666', fontSize: 14 }}>{val || hint || ' '}</span>
      </div>
    </div>
  );
}

const RENDERERS: Record<UiWidgetType, React.FC<{ props: Record<string, unknown> }>> = {
  text: RenderText, div: RenderDiv, space: RenderSpace, check: RenderCheck, select: RenderSelect, edit: RenderEdit,
};

// ── Main component ──
interface Props {
  widgets: UiWidget[];
  selectedIndex: number | null;
  onSelect: (index: number) => void;
  onReorder: (from: number, to: number) => void;
  onDrop: (type: UiWidgetType, index: number) => void;
}

export default function UiPhoneMock({ widgets, selectedIndex, onSelect, onReorder, onDrop }: Props) {
  const { t } = useTranslation(['designer']);
  const scrollRef = useRef<HTMLDivElement>(null);
  const [dragIdx, setDragIdx] = useState<number | null>(null);

  const handleReorderDrop = useCallback((from: number, to: number) => {
    if (from === to || from === to - 1) return;
    onReorder(from, to > from ? to - 1 : to);
  }, [onReorder]);

  return (
    <div className="flex flex-col items-center">
      {/* Phone frame */}
      <div style={{ width: 360, height: 640, border: '3px solid #333', borderRadius: 32, background: '#1e1e2e', display: 'flex', flexDirection: 'column', overflow: 'hidden', boxShadow: '0 8px 32px rgba(0,0,0,.4)' }}>
        {/* Notch */}
        <div style={{ height: 28, background: '#111', display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
          <div style={{ width: 80, height: 6, borderRadius: 3, background: '#222' }} />
        </div>
        {/* Status bar */}
        <div style={{ height: 24, background: '#1a1a2e', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 16px', fontSize: 11, color: '#888' }}>
          <span>12:00</span>
          <div style={{ display: 'flex', gap: 4 }}>
            <span>WiFi</span><span>100%</span>
          </div>
        </div>
        {/* Title bar */}
        <div style={{ height: 44, background: '#252540', display: 'flex', alignItems: 'center', padding: '0 16px', borderBottom: '1px solid #333' }}>
          <span style={{ color: '#e0e0e0', fontSize: 16, fontWeight: 500 }}>{t('designer:uiPreview')}</span>
        </div>
        {/* Content area */}
        <div
          ref={scrollRef}
          style={{ flex: 1, overflowY: 'auto', padding: '4px 16px 16px' }}
          onDragOver={(e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; }}
          onDrop={(e) => {
            const paletteType = e.dataTransfer.getData('palette-widget-type') as UiWidgetType;
            if (paletteType) { e.preventDefault(); onDrop(paletteType, widgets.length); }
          }}
        >
          <DropZone index={0} onDrop={onDrop} onReorderDrop={handleReorderDrop} />
          {widgets.map((w, i) => {
            const Renderer = RENDERERS[w.type];
            const selected = selectedIndex === i;
            return (
              <React.Fragment key={`${w.type}-${w.name}-${i}`}>
                <div
                  draggable
                  onDragStart={(e) => { e.dataTransfer.setData('reorder-index', String(i)); setDragIdx(i); }}
                  onDragEnd={() => setDragIdx(null)}
                  onClick={() => onSelect(i)}
                  style={{
                    display: 'flex', alignItems: 'flex-start', gap: 4,
                    border: selected ? '1.5px solid #3b82f6' : '1.5px solid transparent',
                    borderRadius: 6, padding: '2px 4px', cursor: 'pointer',
                    opacity: dragIdx === i ? 0.4 : 1, transition: 'border-color .15s, opacity .15s',
                    background: selected ? 'rgba(59,130,246,.08)' : 'transparent',
                  }}
                >
                  <div style={{ paddingTop: 6 }}><DragHandleIcon /></div>
                  <div style={{ flex: 1, minWidth: 0 }}><Renderer props={w.props} /></div>
                </div>
                <DropZone index={i + 1} onDrop={onDrop} onReorderDrop={handleReorderDrop} />
              </React.Fragment>
            );
          })}
          {widgets.length === 0 && (
            <div style={{ color: '#555', textAlign: 'center', marginTop: 80, fontSize: 13 }}>
              {t('designer:dragWidgetHint')}
            </div>
          )}
        </div>
        {/* Bottom bar */}
        <div style={{ height: 20, background: '#111', display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
          <div style={{ width: 100, height: 4, borderRadius: 2, background: '#333' }} />
        </div>
      </div>
    </div>
  );
}
