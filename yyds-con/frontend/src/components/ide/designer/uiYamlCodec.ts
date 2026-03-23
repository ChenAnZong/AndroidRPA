import type { UiWidget, UiWidgetType } from '@/types';

// ── Default props for each widget type ──

export const WIDGET_DEFAULTS: Record<UiWidgetType, { props: Record<string, unknown> }> = {
  text:   { props: { value: 'Text content', color: '#333333', size: 14 } },
  div:    { props: {} },
  space:  { props: { height: 20 } },
  check:  { props: { title: 'Toggle option', value: true } },
  select: { props: { title: 'Dropdown', value: ['Option 1', 'Option 2', 'Option 3'] } },
  edit:   { props: { title: 'Input title', value: '', input: 'text', hint: '', required: false } },
};

// ── Parser ──

const RE_TOP = /^(\S[\w-]*)\s*:/;
const RE_PROP = /^\s+(\w+)\s*:\s*(.+)/;

function parseValue(raw: string): unknown {
  const v = raw.trim();
  if (v === 'true') return true;
  if (v === 'false') return false;
  if (/^\[.*\]$/.test(v)) {
    return v
      .slice(1, -1)
      .split(',')
      .map((s) => s.trim().replace(/^['"]|['"]$/g, ''));
  }
  if (/^\d+(\.\d+)?$/.test(v)) return Number(v);
  return v.replace(/^['"]|['"]$/g, '');
}

export function parseUiYaml(yaml: string): UiWidget[] {
  const widgets: UiWidget[] = [];
  let current: UiWidget | null = null;

  for (const line of yaml.split('\n')) {
    if (line.trim() === '' || line.trim().startsWith('#')) continue;

    const topMatch = line.match(RE_TOP);
    if (topMatch) {
      if (current) widgets.push(current);
      const key = topMatch[1];
      const dashIdx = key.indexOf('-');
      let type: string;
      let name: string;
      if (dashIdx !== -1) {
        type = key.slice(0, dashIdx);
        name = key.slice(dashIdx + 1);
      } else {
        type = key;
        name = key;
      }
      current = { type: type as UiWidgetType, name, props: {} };
      continue;
    }

    const propMatch = line.match(RE_PROP);
    if (propMatch && current) {
      current.props[propMatch[1]] = parseValue(propMatch[2]);
    }
  }
  if (current) widgets.push(current);
  return widgets;
}

// ── Serializer ──

const HEADER = `# ── UI Config (ui.yml) ──
# Supported widget types:
#   text   - Text display (value, color, size)
#   div    - Divider
#   space  - Spacer (height)
#   check  - Toggle (title, value)
#   select - Dropdown (title, value[])
#   edit   - Input (title, value, input, hint, required)
`;

function serializeValue(v: unknown): string {
  if (Array.isArray(v)) return `[${v.map((i) => String(i)).join(', ')}]`;
  return String(v);
}

export function serializeUiYaml(widgets: UiWidget[]): string {
  const lines: string[] = [HEADER];
  for (const w of widgets) {
    const key = w.type === w.name ? w.type : `${w.type}-${w.name}`;
    lines.push(`${key}:`);
    for (const [k, v] of Object.entries(w.props)) {
      lines.push(`  ${k}: ${serializeValue(v)}`);
    }
    lines.push('');
  }
  return lines.join('\n');
}
