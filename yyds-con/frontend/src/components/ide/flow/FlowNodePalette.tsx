import React, { useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import type { FlowNodeType } from '@/types';
import { NODE_CATEGORIES, NODE_DEFAULTS, NODE_ICONS } from './flowTypes';

interface FlowNodePaletteProps {
  onAddNode: (type: FlowNodeType) => void;
}

export const FlowNodePalette: React.FC<FlowNodePaletteProps> = ({ onAddNode }) => {
  const { t } = useTranslation(['flow']);
  const [search, setSearch] = useState('');
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  const toggleCategory = useCallback((name: string) => {
    setCollapsed(prev => ({ ...prev, [name]: !prev[name] }));
  }, []);

  const handleDragStart = useCallback(
    (e: React.DragEvent, type: FlowNodeType) => {
      e.dataTransfer.setData('application/yyds-flow-node', type);
      e.dataTransfer.effectAllowed = 'copy';
    },
    [],
  );

  const filtered = NODE_CATEGORIES.map(cat => ({
    ...cat,
    nodes: cat.nodes.filter(n => {
      if (!search) return true;
      const q = search.toLowerCase();
      const def = NODE_DEFAULTS[n.type];
      return (
        n.type.toLowerCase().includes(q) ||
        n.label.includes(search) ||
        def.label.includes(search)
      );
    }),
  })).filter(cat => cat.nodes.length > 0);

  return (
    <div style={styles.container}>
      {/* Search */}
      <div style={styles.searchWrap}>
        <input
          type="text"
          placeholder={t('searchNodes')}
          value={search}
          onChange={e => setSearch(e.target.value)}
          style={styles.searchInput}
        />
      </div>

      {/* Categories */}
      <div style={styles.list}>
        {filtered.map(cat => {
          const isCollapsed = collapsed[cat.name] ?? false;
          return (
            <div key={cat.name}>
              <div
                style={styles.categoryHeader}
                onClick={() => toggleCategory(cat.name)}
              >
                <span style={styles.arrow}>{isCollapsed ? '▶' : '▼'}</span>
                <span>{t(cat.name)}</span>
                <span style={styles.badge}>{cat.nodes.length}</span>
              </div>
              {!isCollapsed &&
                cat.nodes.map(n => {
                  const def = NODE_DEFAULTS[n.type];
                  const icon = NODE_ICONS[n.type];
                  return (
                    <div
                      key={n.type}
                      draggable
                      onDragStart={e => handleDragStart(e, n.type)}
                      onClick={() => onAddNode(n.type)}
                      style={styles.nodeItem}
                      onMouseEnter={e =>
                        ((e.currentTarget as HTMLDivElement).style.backgroundColor = '#3c3c3c')
                      }
                      onMouseLeave={e =>
                        ((e.currentTarget as HTMLDivElement).style.backgroundColor = 'transparent')
                      }
                    >
                      <span style={styles.icon}>{icon}</span>
                      <span style={styles.nodeLabel}>{t(def.label)}</span>
                    </div>
                  );
                })}
            </div>
          );
        })}
        {filtered.length === 0 && (
          <div style={styles.empty}>{t('noMatchingNodes')}</div>
        )}
      </div>
    </div>
  );
};

// ── Styles ──

const styles: Record<string, React.CSSProperties> = {
  container: {
    width: 200,
    height: '100%',
    backgroundColor: '#252526',
    borderRight: '1px solid #3c3c3c',
    display: 'flex',
    flexDirection: 'column',
    userSelect: 'none',
    fontFamily: 'system-ui, sans-serif',
    fontSize: 13,
    color: '#cccccc',
  },
  searchWrap: {
    padding: '8px 8px 4px',
    borderBottom: '1px solid #3c3c3c',
  },
  searchInput: {
    width: '100%',
    boxSizing: 'border-box' as const,
    padding: '5px 8px',
    backgroundColor: '#3c3c3c',
    border: '1px solid #555',
    borderRadius: 3,
    color: '#cccccc',
    fontSize: 12,
    outline: 'none',
  },
  list: {
    flex: 1,
    overflowY: 'auto' as const,
    padding: '4px 0',
  },
  categoryHeader: {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    padding: '6px 10px',
    cursor: 'pointer',
    fontWeight: 600,
    fontSize: 12,
    color: '#e0e0e0',
    letterSpacing: 0.5,
  },
  arrow: {
    fontSize: 9,
    width: 12,
    textAlign: 'center' as const,
    color: '#888',
  },
  badge: {
    marginLeft: 'auto',
    fontSize: 10,
    color: '#888',
    backgroundColor: '#333',
    borderRadius: 8,
    padding: '1px 6px',
  },
  nodeItem: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '5px 10px 5px 28px',
    cursor: 'grab',
    borderRadius: 3,
    transition: 'background-color 0.1s',
  },
  icon: {
    fontSize: 14,
    width: 20,
    textAlign: 'center' as const,
  },
  nodeLabel: {
    fontSize: 12,
    color: '#cccccc',
  },
  empty: {
    padding: '20px 10px',
    textAlign: 'center' as const,
    color: '#666',
    fontSize: 12,
  },
};

export default FlowNodePalette;
