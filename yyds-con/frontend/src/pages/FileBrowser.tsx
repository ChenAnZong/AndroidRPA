import { useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft,
  Folder,
  FileText,
  Download,
  Trash2,
  FolderPlus,
  Upload,
  ChevronRight,
  Home,
} from 'lucide-react';
import { fileApi } from '@/services/api';

interface FileEntry {
  name: string;
  path: string;
  is_dir: boolean;
  size: number;
  modified: number;
}

function formatSize(bytes: number): string {
  if (bytes === 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}

export default function FileBrowser() {
  const { t } = useTranslation('file');
  const { imei } = useParams<{ imei: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [currentPath, setCurrentPath] = useState('/sdcard');
  const [showMkdir, setShowMkdir] = useState(false);
  const [mkdirName, setMkdirName] = useState('');

  const { data, isLoading } = useQuery({
    queryKey: ['files', imei, currentPath],
    queryFn: () => fileApi.list(imei!, currentPath),
    enabled: !!imei,
  });

  const files: FileEntry[] = (() => {
    try {
      const d = data?.data;
      if (typeof d === 'string') return JSON.parse(d);
      if (Array.isArray(d)) return d;
      return [];
    } catch {
      return [];
    }
  })();

  // Sort: folders first, then by name
  const sorted = [...files].sort((a, b) => {
    if (a.is_dir !== b.is_dir) return a.is_dir ? -1 : 1;
    return a.name.localeCompare(b.name);
  });

  const pathParts = currentPath.split('/').filter(Boolean);

  const navigateTo = useCallback((path: string) => {
    setCurrentPath(path);
  }, []);

  const goUp = useCallback(() => {
    const parts = currentPath.split('/').filter(Boolean);
    if (parts.length > 1) {
      setCurrentPath('/' + parts.slice(0, -1).join('/'));
    }
  }, [currentPath]);

  const handleDelete = useCallback(
    async (path: string, name: string) => {
      if (!confirm(t('confirmDelete', { name }))) return;
      await fileApi.delete(imei!, path);
      queryClient.invalidateQueries({ queryKey: ['files', imei, currentPath] });
    },
    [imei, currentPath, queryClient],
  );

  const handleMkdir = useCallback(async () => {
    if (!mkdirName.trim()) return;
    const newPath = `${currentPath}/${mkdirName.trim()}`;
    await fileApi.mkdir(imei!, newPath);
    setShowMkdir(false);
    setMkdirName('');
    queryClient.invalidateQueries({ queryKey: ['files', imei, currentPath] });
  }, [imei, currentPath, mkdirName, queryClient]);

  if (!imei) return null;

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="flex h-14 items-center gap-3 border-b border-divider px-4 shrink-0">
        <button onClick={() => navigate(`/devices/${imei}`)} className="btn-ghost p-1">
          <ArrowLeft size={18} />
        </button>
        <h2 className="text-sm font-medium text-text-primary">{t('title')}</h2>
        <span className="text-xs text-text-hint font-mono">{imei}</span>
        <div className="ml-auto flex gap-2">
          <button onClick={() => setShowMkdir(true)} className="btn-secondary text-xs">
            <FolderPlus size={14} /> {t('newFolder')}
          </button>
          <button className="btn-secondary text-xs" disabled>
            <Upload size={14} /> {t('upload')}
          </button>
        </div>
      </div>

      {/* Breadcrumb */}
      <div className="flex items-center gap-1 border-b border-divider-light px-4 py-2 text-xs overflow-x-auto shrink-0">
        <button
          onClick={() => navigateTo('/sdcard')}
          className="text-text-muted hover:text-brand shrink-0"
        >
          <Home size={14} />
        </button>
        {pathParts.map((part, i) => (
          <div key={i} className="flex items-center gap-1 shrink-0">
            <ChevronRight size={12} className="text-text-hint" />
            <button
              onClick={() => navigateTo('/' + pathParts.slice(0, i + 1).join('/'))}
              className={`${
                i === pathParts.length - 1 ? 'text-text-primary' : 'text-text-muted hover:text-brand'
              }`}
            >
              {part}
            </button>
          </div>
        ))}
      </div>

      {/* Mkdir dialog */}
      {showMkdir && (
        <div className="flex items-center gap-2 border-b border-divider-light px-4 py-2 bg-accent-blue-bg/30">
          <input
            value={mkdirName}
            onChange={(e) => setMkdirName(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleMkdir()}
            placeholder={t('folderName')}
            className="input max-w-xs"
            autoFocus
          />
          <button onClick={handleMkdir} className="btn-primary text-xs">
            {t('common:create')}
          </button>
          <button onClick={() => setShowMkdir(false)} className="btn-ghost text-xs">
            {t('common:cancel')}
          </button>
        </div>
      )}

      {/* File list */}
      <div className="flex-1 overflow-auto">
        {isLoading ? (
          <div className="flex items-center justify-center py-20 text-text-muted">{t('common:loading')}</div>
        ) : sorted.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-20 text-text-muted">
            <Folder size={40} className="mb-3 opacity-30" />
            <p>{t('emptyFolder')}</p>
          </div>
        ) : (
          <table className="w-full text-left">
            <thead className="sticky top-0 bg-card-bg">
              <tr className="border-b border-divider">
                <th className="px-4 py-2 text-xs font-medium text-text-muted">{t('colName')}</th>
                <th className="px-4 py-2 text-xs font-medium text-text-muted w-24">{t('colSize')}</th>
                <th className="px-4 py-2 text-xs font-medium text-text-muted w-40">{t('colModified')}</th>
                <th className="px-4 py-2 text-xs font-medium text-text-muted w-20"></th>
              </tr>
            </thead>
            <tbody>
              {currentPath !== '/sdcard' && currentPath !== '/' && (
                <tr
                  className="border-b border-divider-light hover:bg-hover cursor-pointer"
                  onClick={goUp}
                >
                  <td className="px-4 py-2 text-sm" colSpan={4}>
                    <span className="flex items-center gap-2 text-text-muted">
                      <Folder size={16} /> ..
                    </span>
                  </td>
                </tr>
              )}
              {sorted.map((file) => (
                <tr
                  key={file.path}
                  className="border-b border-divider-light hover:bg-hover transition-colors"
                >
                  <td className="px-4 py-2">
                    {file.is_dir ? (
                      <button
                        onClick={() => navigateTo(file.path)}
                        className="flex items-center gap-2 text-sm text-brand-light hover:text-brand"
                      >
                        <Folder size={16} className="text-amber-500" /> {file.name}
                      </button>
                    ) : (
                      <span className="flex items-center gap-2 text-sm text-text-primary">
                        <FileText size={16} className="text-text-hint" /> {file.name}
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-2 text-xs text-text-muted">
                    {file.is_dir ? '—' : formatSize(file.size)}
                  </td>
                  <td className="px-4 py-2 text-xs text-text-muted">
                    {file.modified > 0
                      ? new Date(file.modified).toLocaleString('zh-CN')
                      : '—'}
                  </td>
                  <td className="px-4 py-2">
                    <div className="flex items-center gap-1">
                      {!file.is_dir && (
                        <button
                          onClick={() => fileApi.download(imei!, file.path)}
                          className="btn-ghost p-1 text-text-hint hover:text-brand"
                          title={t('download')}
                        >
                          <Download size={14} />
                        </button>
                      )}
                      <button
                        onClick={() => handleDelete(file.path, file.name)}
                        className="btn-ghost p-1 text-text-hint hover:text-danger"
                        title={t('common:delete')}
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
