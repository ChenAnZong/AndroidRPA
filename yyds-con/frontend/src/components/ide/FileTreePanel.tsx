import { useState, useCallback, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { status } from '@/store';
import {
  ChevronRight, ChevronDown, File, Folder, FolderOpen,
  FilePlus, FolderPlus, Trash2, Pencil, RefreshCw, Play,
} from 'lucide-react';
import { ideApi, fileApi } from '@/services/api';
import type { IdeFileNode } from '@/types';

interface Props {
  imei: string;
  projectRoot: string;
  onOpenFile: (path: string, name: string) => void;
  onRunFile?: (path: string) => void;
}

export default function FileTreePanel({ imei, projectRoot, onOpenFile, onRunFile }: Props) {
  const { t } = useTranslation(['file', 'common']);
  const [tree, setTree] = useState<IdeFileNode[]>([]);
  const [loading, setLoading] = useState(false);
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; node: IdeFileNode | null } | null>(null);
  const [renaming, setRenaming] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [creating, setCreating] = useState<{ parentPath: string; type: 'file' | 'dir' } | null>(null);
  const [createValue, setCreateValue] = useState('');
  const panelRef = useRef<HTMLDivElement>(null);

  const loadDir = useCallback(async (dirPath: string): Promise<IdeFileNode[]> => {
    try {
      const res = await fileApi.list(imei, dirPath);
      const items = (res as any)?.data || res;
      const arr = Array.isArray(items) ? items : [];
      return arr
        .map((f: any) => ({
          name: f.name,
          path: f.path,
          is_dir: f.is_dir,
          size: f.size || 0,
          modified: f.modified || 0,
          children: f.is_dir ? undefined : undefined,
          expanded: false,
        }))
        .sort((a: IdeFileNode, b: IdeFileNode) => {
          if (a.is_dir !== b.is_dir) return a.is_dir ? -1 : 1;
          return a.name.localeCompare(b.name);
        });
    } catch {
      return [];
    }
  }, [imei]);

  const refresh = useCallback(async () => {
    if (!projectRoot) return;
    setLoading(true);
    const items = await loadDir(projectRoot);
    setTree(items);
    setLoading(false);
  }, [projectRoot, loadDir]);

  useEffect(() => { refresh(); }, [refresh]);

  const toggleDir = useCallback(async (node: IdeFileNode) => {
    if (!node.is_dir) return;
    if (node.expanded && node.children) {
      // Collapse
      setTree((prev) => updateNode(prev, node.path, { expanded: false }));
    } else {
      // Expand & load
      const children = await loadDir(node.path);
      setTree((prev) => updateNode(prev, node.path, { expanded: true, children }));
    }
  }, [loadDir]);

  const handleClick = useCallback((node: IdeFileNode) => {
    if (node.is_dir) {
      toggleDir(node);
    } else {
      onOpenFile(node.path, node.name);
    }
  }, [toggleDir, onOpenFile]);

  const handleContextMenu = useCallback((e: React.MouseEvent, node: IdeFileNode | null) => {
    e.preventDefault();
    e.stopPropagation();
    setContextMenu({ x: e.clientX, y: e.clientY, node });
  }, []);

  // Close context menu on click outside
  useEffect(() => {
    const handler = () => setContextMenu(null);
    document.addEventListener('click', handler);
    return () => document.removeEventListener('click', handler);
  }, []);

  const handleDelete = useCallback(async (node: IdeFileNode) => {
    if (!confirm(t('confirmDelete', { name: node.name }))) return;
    try {
      await fileApi.delete(imei, node.path);
      status.success(t('deleted', { name: node.name }));
      refresh();
    } catch (e: any) {
      status.error(e.message);
    }
  }, [imei, refresh]);

  const handleRenameSubmit = useCallback(async () => {
    if (!renaming || !renameValue.trim()) { setRenaming(null); return; }
    const oldPath = renaming;
    const parts = oldPath.split('/');
    parts[parts.length - 1] = renameValue.trim();
    const newPath = parts.join('/');
    try {
      await ideApi.renameFile(imei, oldPath, newPath);
      status.success(t('renamed'));
      setRenaming(null);
      refresh();
    } catch (e: any) {
      status.error(e.message);
    }
  }, [imei, renaming, renameValue, refresh]);

  const handleCreateSubmit = useCallback(async () => {
    if (!creating || !createValue.trim()) { setCreating(null); return; }
    const fullPath = `${creating.parentPath}/${createValue.trim()}`;
    try {
      if (creating.type === 'dir') {
        await fileApi.mkdir(imei, fullPath);
      } else {
        await ideApi.writeFile(imei, fullPath, '');
      }
      status.success(t('created', { name: createValue.trim() }));
      setCreating(null);
      setCreateValue('');
      refresh();
    } catch (e: any) {
      status.error(e.message);
    }
  }, [imei, creating, createValue, refresh]);

  const fileIcon = (name: string) => {
    const ext = name.split('.').pop()?.toLowerCase();
    const colors: Record<string, string> = {
      py: 'text-yellow-400', yml: 'text-pink-400', yaml: 'text-pink-400',
      json: 'text-green-400', jpg: 'text-purple-400', png: 'text-purple-400',
      config: 'text-blue-400', txt: 'text-gray-400',
    };
    return colors[ext || ''] || 'text-gray-500';
  };

  return (
    <div ref={panelRef} className="flex flex-col h-full text-[13px]" onContextMenu={(e) => handleContextMenu(e, null)}>
      {/* Header */}
      <div className="flex items-center justify-between h-8 px-2 bg-[#252526] border-b border-[#3c3c3c] shrink-0">
        <span className="text-[11px] text-[#cccccc] font-semibold uppercase tracking-wide truncate">
          {projectRoot ? projectRoot.split('/').pop() || t('files') : t('files')}
        </span>
        <div className="flex items-center gap-0.5">
          <button onClick={() => { setCreating({ parentPath: projectRoot, type: 'file' }); setCreateValue(''); }} className="p-0.5 hover:bg-[#3c3c3c] rounded" title={t('newFileBtn')}><FilePlus size={14} /></button>
          <button onClick={() => { setCreating({ parentPath: projectRoot, type: 'dir' }); setCreateValue(''); }} className="p-0.5 hover:bg-[#3c3c3c] rounded" title={t('newFolderBtn')}><FolderPlus size={14} /></button>
          <button onClick={refresh} className="p-0.5 hover:bg-[#3c3c3c] rounded" title={t('refreshBtn')}><RefreshCw size={14} /></button>
        </div>
      </div>

      {/* Create input */}
      {creating && (
        <div className="px-2 py-1 bg-[#2d2d2d] border-b border-[#3c3c3c]">
          <input
            autoFocus
            className="w-full bg-[#3c3c3c] text-white text-xs px-2 py-1 rounded outline-none focus:ring-1 focus:ring-[#007acc]"
            placeholder={creating.type === 'file' ? t('fileNamePlaceholder') : t('folderNamePlaceholder')}
            value={createValue}
            onChange={(e) => setCreateValue(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') handleCreateSubmit(); if (e.key === 'Escape') setCreating(null); }}
            onBlur={handleCreateSubmit}
          />
        </div>
      )}

      {/* Tree */}
      <div className="flex-1 overflow-auto py-1">
        {loading && <div className="px-3 py-2 text-xs text-gray-500">{t('common:loading')}</div>}
        {!loading && tree.length === 0 && projectRoot && (
          <div className="px-3 py-4 text-xs text-gray-500 text-center">{t('emptyDir')}</div>
        )}
        {!loading && !projectRoot && (
          <div className="px-3 py-4 text-xs text-gray-500 text-center">{t('selectProjectFirst')}</div>
        )}
        {tree.map((node) => (
          <TreeNode
            key={node.path}
            node={node}
            depth={0}
            onClick={handleClick}
            onContextMenu={handleContextMenu}
            renaming={renaming}
            renameValue={renameValue}
            onRenameChange={setRenameValue}
            onRenameSubmit={handleRenameSubmit}
            onRenameCancel={() => setRenaming(null)}
            fileIcon={fileIcon}
          />
        ))}
      </div>

      {/* Context menu */}
      {contextMenu && (
        <div
          className="fixed z-50 bg-[#2d2d2d] border border-[#454545] rounded shadow-lg py-1 min-w-[160px]"
          style={{ left: contextMenu.x, top: contextMenu.y }}
        >
          {contextMenu.node && (
            <>
              {!contextMenu.node.is_dir && /\.py$/i.test(contextMenu.node.name) && onRunFile && (
                <>
                  <CtxItem icon={<Play size={13} />} label={t('runThisFile')} onClick={() => {
                    onRunFile(contextMenu.node!.path);
                    setContextMenu(null);
                  }} />
                  <div className="border-t border-[#454545] my-1" />
                </>
              )}
              <CtxItem icon={<Pencil size={13} />} label={t('rename')} onClick={() => {
                setRenaming(contextMenu.node!.path);
                setRenameValue(contextMenu.node!.name);
                setContextMenu(null);
              }} />
              <CtxItem icon={<Trash2 size={13} />} label={t('common:delete')} danger onClick={() => {
                handleDelete(contextMenu.node!);
                setContextMenu(null);
              }} />
              <div className="border-t border-[#454545] my-1" />
            </>
          )}
          <CtxItem icon={<FilePlus size={13} />} label={t('newFileBtn')} onClick={() => {
            const parent = contextMenu.node?.is_dir ? contextMenu.node.path : projectRoot;
            setCreating({ parentPath: parent, type: 'file' });
            setCreateValue('');
            setContextMenu(null);
          }} />
          <CtxItem icon={<FolderPlus size={13} />} label={t('newFolderBtn')} onClick={() => {
            const parent = contextMenu.node?.is_dir ? contextMenu.node.path : projectRoot;
            setCreating({ parentPath: parent, type: 'dir' });
            setCreateValue('');
            setContextMenu(null);
          }} />
        </div>
      )}
    </div>
  );
}

function TreeNode({
  node, depth, onClick, onContextMenu, renaming, renameValue, onRenameChange, onRenameSubmit, onRenameCancel, fileIcon,
}: {
  node: IdeFileNode; depth: number;
  onClick: (n: IdeFileNode) => void;
  onContextMenu: (e: React.MouseEvent, n: IdeFileNode) => void;
  renaming: string | null; renameValue: string;
  onRenameChange: (v: string) => void; onRenameSubmit: () => void; onRenameCancel: () => void;
  fileIcon: (name: string) => string;
}) {
  const isRenaming = renaming === node.path;
  return (
    <>
      <div
        className="flex items-center gap-1 px-1 py-[2px] cursor-pointer hover:bg-[#2a2d2e] group"
        style={{ paddingLeft: 8 + depth * 16 }}
        onClick={() => onClick(node)}
        onContextMenu={(e) => onContextMenu(e, node)}
      >
        {node.is_dir ? (
          <>
            {node.expanded ? <ChevronDown size={14} className="shrink-0 text-gray-500" /> : <ChevronRight size={14} className="shrink-0 text-gray-500" />}
            {node.expanded ? <FolderOpen size={14} className="shrink-0 text-yellow-500" /> : <Folder size={14} className="shrink-0 text-yellow-600" />}
          </>
        ) : (
          <>
            <span className="w-[14px] shrink-0" />
            <File size={14} className={`shrink-0 ${fileIcon(node.name)}`} />
          </>
        )}
        {isRenaming ? (
          <input
            autoFocus
            className="flex-1 bg-[#3c3c3c] text-white text-xs px-1 rounded outline-none"
            value={renameValue}
            onChange={(e) => onRenameChange(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') onRenameSubmit(); if (e.key === 'Escape') onRenameCancel(); }}
            onBlur={onRenameSubmit}
            onClick={(e) => e.stopPropagation()}
          />
        ) : (
          <span className="truncate text-[#cccccc]">{node.name}</span>
        )}
      </div>
      {node.is_dir && node.expanded && node.children?.map((child) => (
        <TreeNode
          key={child.path}
          node={child}
          depth={depth + 1}
          onClick={onClick}
          onContextMenu={onContextMenu}
          renaming={renaming}
          renameValue={renameValue}
          onRenameChange={onRenameChange}
          onRenameSubmit={onRenameSubmit}
          onRenameCancel={onRenameCancel}
          fileIcon={fileIcon}
        />
      ))}
    </>
  );
}

function CtxItem({ icon, label, onClick, danger }: { icon: React.ReactNode; label: string; onClick: () => void; danger?: boolean }) {
  return (
    <button
      onClick={onClick}
      className={`flex items-center gap-2 w-full px-3 py-1.5 text-xs hover:bg-[#094771] ${danger ? 'text-red-400' : 'text-[#cccccc]'}`}
    >
      {icon}
      {label}
    </button>
  );
}

// Helper: update a node in the tree by path
function updateNode(nodes: IdeFileNode[], path: string, updates: Partial<IdeFileNode>): IdeFileNode[] {
  return nodes.map((n) => {
    if (n.path === path) return { ...n, ...updates };
    if (n.children) return { ...n, children: updateNode(n.children, path, updates) };
    return n;
  });
}
