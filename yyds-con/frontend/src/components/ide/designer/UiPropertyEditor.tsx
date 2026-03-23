import React from 'react';
import { useTranslation } from 'react-i18next';
import type { UiWidget, UiWidgetType } from '@/types';

interface Props {
  widget: UiWidget | null;
  onChange: (props: Record<string, unknown>) => void;
}

// ── Reusable field components ──

function FieldLabel({ children }: { children: React.ReactNode }) {
  return <label style={{ display: 'block', fontSize: 11, color: '#888', marginBottom: 4, marginTop: 10 }}>{children}</label>;
}

function TextInput({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <input
      type="text" value={value} onChange={(e) => onChange(e.target.value)}
      style={{ width: '100%', background: '#2a2a3e', border: '1px solid #444', borderRadius: 4, padding: '6px 8px', color: '#e0e0e0', fontSize: 13, outline: 'none' }}
    />
  );
}

function NumberInput({ value, onChange, min }: { value: number; onChange: (v: number) => void; min?: number }) {
  return (
    <input
      type="number" value={value} min={min} onChange={(e) => onChange(Number(e.target.value))}
      style={{ width: '100%', background: '#2a2a3e', border: '1px solid #444', borderRadius: 4, padding: '6px 8px', color: '#e0e0e0', fontSize: 13, outline: 'none' }}
    />
  );
}

function Toggle({ value, onChange }: { value: boolean; onChange: (v: boolean) => void }) {
  return (
    <div onClick={() => onChange(!value)} style={{ cursor: 'pointer', width: 40, height: 22, borderRadius: 11, background: value ? '#3b82f6' : '#555', position: 'relative', transition: 'background .2s' }}>
      <div style={{ width: 18, height: 18, borderRadius: 9, background: '#fff', position: 'absolute', top: 2, left: value ? 20 : 2, transition: 'left .2s' }} />
    </div>
  );
}

function TextArea({ value, onChange, rows }: { value: string; onChange: (v: string) => void; rows?: number }) {
  return (
    <textarea
      value={value} rows={rows ?? 3} onChange={(e) => onChange(e.target.value)}
      style={{ width: '100%', background: '#2a2a3e', border: '1px solid #444', borderRadius: 4, padding: '6px 8px', color: '#e0e0e0', fontSize: 13, outline: 'none', resize: 'vertical', fontFamily: 'inherit' }}
    />
  );
}

function ColorInput({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
      <input type="color" value={value} onChange={(e) => onChange(e.target.value)} style={{ width: 32, height: 28, border: 'none', background: 'none', cursor: 'pointer', padding: 0 }} />
      <input type="text" value={value} onChange={(e) => onChange(e.target.value)}
        style={{ flex: 1, background: '#2a2a3e', border: '1px solid #444', borderRadius: 4, padding: '6px 8px', color: '#e0e0e0', fontSize: 13, outline: 'none' }} />
    </div>
  );
}

function SelectInput({ value, options, onChange }: { value: string; options: string[]; onChange: (v: string) => void }) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)}
      style={{ width: '100%', background: '#2a2a3e', border: '1px solid #444', borderRadius: 4, padding: '6px 8px', color: '#e0e0e0', fontSize: 13, outline: 'none' }}>
      {options.map((o) => <option key={o} value={o}>{o}</option>)}
    </select>
  );
}

// ── Tag/chip editor for arrays ──

function TagEditor({ tags, onChange, t }: { tags: string[]; onChange: (v: string[]) => void; t: (key: string) => string }) {
  const [draft, setDraft] = React.useState('');
  const add = () => { const v = draft.trim(); if (v && !tags.includes(v)) { onChange([...tags, v]); setDraft(''); } };
  return (
    <div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 6 }}>
        {tags.map((tag, i) => (
          <span key={i} style={{ background: '#3b82f6', color: '#fff', borderRadius: 12, padding: '2px 10px', fontSize: 12, display: 'flex', alignItems: 'center', gap: 4 }}>
            {tag}
            <span style={{ cursor: 'pointer', fontWeight: 700, fontSize: 14, lineHeight: 1 }} onClick={() => onChange(tags.filter((_, j) => j !== i))}>×</span>
          </span>
        ))}
      </div>
      <div style={{ display: 'flex', gap: 4 }}>
        <input type="text" value={draft} onChange={(e) => setDraft(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }}
          placeholder={t('designer:addOption')}
          style={{ flex: 1, background: '#2a2a3e', border: '1px solid #444', borderRadius: 4, padding: '5px 8px', color: '#e0e0e0', fontSize: 12, outline: 'none' }} />
        <button onClick={add} style={{ background: '#3b82f6', color: '#fff', border: 'none', borderRadius: 4, padding: '4px 10px', fontSize: 12, cursor: 'pointer' }}>+</button>
      </div>
    </div>
  );
}

// ── Per-type editors ──

function editProps(widget: UiWidget, onChange: (p: Record<string, unknown>) => void, t: (key: string) => string) {
  const p = widget.props;
  const set = (key: string, val: unknown) => onChange({ ...p, [key]: val });

  switch (widget.type) {
    case 'text':
      return (<>
        <FieldLabel>{t('designer:textContent')}</FieldLabel><TextArea value={String(p.value ?? '')} onChange={(v) => set('value', v)} />
        <FieldLabel>{t('designer:color')}</FieldLabel><ColorInput value={String(p.color ?? '#333333')} onChange={(v) => set('color', v)} />
        <FieldLabel>{t('designer:fontSize')}</FieldLabel><NumberInput value={Number(p.size ?? 14)} onChange={(v) => set('size', v)} min={8} />
      </>);
    case 'div':
      return <div style={{ color: '#666', fontSize: 12, marginTop: 12 }}>{t('designer:divNoProps')}</div>;
    case 'space':
      return (<>
        <FieldLabel>{t('designer:heightPx')}</FieldLabel><NumberInput value={Number(p.height ?? 20)} onChange={(v) => set('height', v)} min={1} />
      </>);
    case 'check':
      return (<>
        <FieldLabel>{t('designer:title')}</FieldLabel><TextInput value={String(p.title ?? '')} onChange={(v) => set('title', v)} />
        <FieldLabel>{t('designer:defaultValue')}</FieldLabel><Toggle value={!!p.value} onChange={(v) => set('value', v)} />
      </>);
    case 'select':
      return (<>
        <FieldLabel>{t('designer:title')}</FieldLabel><TextInput value={String(p.title ?? '')} onChange={(v) => set('title', v)} />
        <FieldLabel>{t('designer:optionList')}</FieldLabel><TagEditor tags={Array.isArray(p.value) ? (p.value as string[]) : []} onChange={(v) => set('value', v)} t={t} />
      </>);
    case 'edit':
      return (<>
        <FieldLabel>{t('designer:title')}</FieldLabel><TextInput value={String(p.title ?? '')} onChange={(v) => set('title', v)} />
        <FieldLabel>{t('designer:defaultValue')}</FieldLabel><TextInput value={String(p.value ?? '')} onChange={(v) => set('value', v)} />
        <FieldLabel>{t('designer:inputType')}</FieldLabel><SelectInput value={String(p.input ?? 'text')} options={['text', 'password', 'number', 'multiline']} onChange={(v) => set('input', v)} />
        <FieldLabel>{t('designer:hintText')}</FieldLabel><TextInput value={String(p.hint ?? '')} onChange={(v) => set('hint', v)} />
        <FieldLabel>{t('designer:required')}</FieldLabel><Toggle value={!!p.required} onChange={(v) => set('required', v)} />
      </>);
    default:
      return null;
  }
}

// ── Snippet display ──

const TYPE_LABELS: Record<UiWidgetType, string> = { text: 'designer:wText', div: 'designer:wDiv', space: 'designer:wSpace', check: 'designer:wCheck', select: 'designer:wSelect', edit: 'designer:wEdit' };

function CodeSnippet({ widget, t }: { widget: UiWidget; t: (key: string) => string }) {
  const code = `Config.read_config_value("${widget.type}-${widget.name}")`;
  const [copied, setCopied] = React.useState(false);
  const copy = () => { navigator.clipboard.writeText(code).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500); }); };
  return (
    <div style={{ marginTop: 16, background: '#181825', borderRadius: 6, padding: '10px 12px', border: '1px solid #333' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
        <span style={{ fontSize: 11, color: '#888' }}>{t('designer:pythonReadCode')}</span>
        <button onClick={copy} style={{ background: 'none', border: '1px solid #444', borderRadius: 4, color: '#aaa', fontSize: 11, padding: '2px 8px', cursor: 'pointer' }}>
          {copied ? t('designer:copied') : t('designer:copy')}
        </button>
      </div>
      <code style={{ fontSize: 12, color: '#a5d6ff', wordBreak: 'break-all' }}>{code}</code>
    </div>
  );
}

// ── Main component ──

export default function UiPropertyEditor({ widget, onChange }: Props) {
  const { t } = useTranslation(['designer']);
  if (!widget) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', color: '#555', fontSize: 13 }}>
        {t('designer:selectWidgetToEdit')}
      </div>
    );
  }

  return (
    <div style={{ padding: '12px 14px', height: '100%', overflowY: 'auto' }}>
      <div style={{ fontSize: 13, color: '#e0e0e0', fontWeight: 600, marginBottom: 2 }}>
        {t(TYPE_LABELS[widget.type])} — <span style={{ color: '#3b82f6', fontWeight: 400 }}>{widget.name}</span>
      </div>
      <div style={{ fontSize: 11, color: '#666', marginBottom: 8 }}>{t('designer:type')}: {widget.type}</div>
      {editProps(widget, onChange, t)}
      <CodeSnippet widget={widget} t={t} />
    </div>
  );
}
