import { useState, useCallback, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { status } from '@/store';
import {
  Search, RefreshCw, ChevronDown, ChevronRight, Package,
  Download, Trash2, ArrowUpCircle, Loader2, ToggleLeft, ToggleRight,
} from 'lucide-react';
import { ideApi } from '@/services/api';
import type { PipPackage, PipOutdatedPackage, PipSearchResult } from '@/types';

interface Props {
  imei: string;
}

const TSINGHUA_MIRROR = 'https://pypi.tuna.tsinghua.edu.cn/simple';
const LS_MIRROR_KEY = 'pip-tsinghua-mirror';

function parsePipList(data: string | undefined): PipPackage[] {
  if (!data) return [];
  try { return JSON.parse(data); } catch { return []; }
}
function parsePipOutdated(data: string | undefined): PipOutdatedPackage[] {
  if (!data) return [];
  try { return JSON.parse(data); } catch { return []; }
}
function parsePipSearch(data: string | undefined): PipSearchResult | null {
  if (!data) return null;
  try { return JSON.parse(data); } catch { return null; }
}

export default function PipManagerPanel({ imei }: Props) {
  const { t } = useTranslation(['pip', 'common']);
  const qc = useQueryClient();
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [showDetail, setShowDetail] = useState<string | null>(null);
  const [detailText, setDetailText] = useState('');
  const [useMirror, setUseMirror] = useState(() => localStorage.getItem(LS_MIRROR_KEY) === '1');
  const debounceRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  // Debounce search
  useEffect(() => {
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => setDebouncedSearch(search), 400);
    return () => clearTimeout(debounceRef.current);
  }, [search]);

  // Toggle mirror persistence
  useEffect(() => {
    localStorage.setItem(LS_MIRROR_KEY, useMirror ? '1' : '0');
  }, [useMirror]);

  const withMirror = useCallback((name: string) => {
    return useMirror ? `${name} --index-url ${TSINGHUA_MIRROR}` : name;
  }, [useMirror]);

  // ── Queries ──
  const { data: listRes, isLoading: listLoading } = useQuery({
    queryKey: ['pip-list', imei],
    queryFn: () => ideApi.pipList(imei),
    refetchInterval: false,
  });
  const packages = parsePipList(listRes?.data as string | undefined);

  const { data: outdatedRes, isLoading: outdatedLoading } = useQuery({
    queryKey: ['pip-outdated', imei],
    queryFn: () => ideApi.pipOutdated(imei),
    refetchInterval: false,
  });
  const outdated = parsePipOutdated(outdatedRes?.data as string | undefined);
  const outdatedMap = new Map(outdated.map((o) => [o.name, o]));

  const { data: searchRes, isFetching: searchLoading } = useQuery({
    queryKey: ['pip-search', imei, debouncedSearch],
    queryFn: () => ideApi.pipSearch(imei, debouncedSearch),
    enabled: debouncedSearch.length >= 2,
  });
  const searchResult = parsePipSearch(searchRes?.data as string | undefined);

  // ── Mutations ──
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['pip-list', imei] });
    qc.invalidateQueries({ queryKey: ['pip-outdated', imei] });
  };

  const installMut = useMutation({
    mutationFn: (name: string) => ideApi.pipInstall(imei, withMirror(name)),
    onSuccess: (_, name) => { status.success(t('installSuccess', { name })); invalidate(); },
    onError: (e: Error) => status.error(t('installFailed', { msg: e.message })),
  });

  const uninstallMut = useMutation({
    mutationFn: (name: string) => ideApi.pipUninstall(imei, name),
    onSuccess: (_, name) => { status.success(t('uninstallSuccess', { name })); invalidate(); },
    onError: (e: Error) => status.error(t('uninstallFailed', { msg: e.message })),
  });

  const upgradeMut = useMutation({
    mutationFn: (name: string) => ideApi.pipUpgrade(imei, withMirror(name)),
    onSuccess: (_, name) => { status.success(t('upgradeSuccess', { name })); invalidate(); },
    onError: (e: Error) => status.error(t('upgradeFailed', { msg: e.message })),
  });

  const [upgradingAll, setUpgradingAll] = useState(false);
  const upgradeAll = useCallback(async () => {
    if (outdated.length === 0) return;
    setUpgradingAll(true);
    try {
      for (const pkg of outdated) {
        await ideApi.pipUpgrade(imei, withMirror(pkg.name));
      }
      status.success(t('upgradeAllSuccess', { count: outdated.length }));
      invalidate();
    } catch (e: any) {
      status.error(t('upgradeAllFailed', { msg: e.message }));
    } finally {
      setUpgradingAll(false);
    }
  }, [outdated, imei, withMirror]);

  // ── Show detail ──
  const loadDetail = useCallback(async (name: string) => {
    if (showDetail === name) { setShowDetail(null); return; }
    setShowDetail(name);
    setDetailText(t('common:loading'));
    try {
      const res = await ideApi.pipShow(imei, name);
      setDetailText((res.data as string) || t('noInfo'));
    } catch {
      setDetailText(t('loadFailed'));
    }
  }, [imei, showDetail]);

  // ── Filter ──
  const filtered = search
    ? packages.filter((p) => p.name.toLowerCase().includes(search.toLowerCase()))
    : packages;

  const loading = listLoading || outdatedLoading;

  // ── RENDER ──
  return (
    <div className="flex flex-col h-full text-[12px] bg-[#1e1e1e]">
      {/* Toolbar */}
      <div className="flex items-center gap-1.5 h-8 px-2 bg-[#252526] border-b border-[#3c3c3c] shrink-0">
        <Package size={13} className="text-[#007acc] shrink-0" />
        <span className="text-[11px] text-[#cccccc] font-medium mr-1">Pip</span>

        <div className="flex items-center bg-[#3c3c3c] rounded px-1.5 flex-1 max-w-[240px]">
          <Search size={11} className="text-gray-500 shrink-0" />
          <input
            className="bg-transparent text-white text-[11px] px-1 py-0.5 outline-none w-full"
            placeholder={t('searchPlaceholder')}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          {searchLoading && <Loader2 size={11} className="animate-spin text-gray-400 shrink-0" />}
        </div>

        <div className="flex-1" />

        {/* Mirror toggle */}
        <button
          onClick={() => setUseMirror(!useMirror)}
          className="flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] hover:bg-[#3c3c3c]"
          title={useMirror ? t('mirrorOn') : t('mirrorOff')}
        >
          {useMirror
            ? <ToggleRight size={14} className="text-[#007acc]" />
            : <ToggleLeft size={14} className="text-gray-500" />}
          <span className={useMirror ? 'text-[#007acc]' : 'text-gray-500'}>{t('mirror')}</span>
        </button>

        {outdated.length > 0 && (
          <button
            onClick={upgradeAll}
            disabled={upgradingAll}
            className="flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] bg-[#007acc] hover:bg-[#005f9e] disabled:opacity-50"
          >
            {upgradingAll ? <Loader2 size={11} className="animate-spin" /> : <ArrowUpCircle size={11} />}
            {t('upgradeAll', { count: outdated.length })}
          </button>
        )}

        <button
          onClick={() => invalidate()}
          className="p-0.5 hover:bg-[#3c3c3c] rounded"
          title={t('refresh')}
        >
          <RefreshCw size={12} className={loading ? 'animate-spin' : ''} />
        </button>
      </div>

      {/* PyPI search result */}
      {debouncedSearch.length >= 2 && searchResult && (
        <div className="px-2 py-1.5 bg-[#2d2d2d] border-b border-[#3c3c3c]">
          {searchResult.found ? (
            <div className="flex items-center justify-between">
              <div>
                <span className="text-white font-medium">{searchResult.name}</span>
                <span className="text-gray-400 ml-2">v{searchResult.latest_version}</span>
                {searchResult.installed_version && (
                  <span className="text-green-400 ml-2 text-[10px]">{t('installed', { version: searchResult.installed_version })}</span>
                )}
              </div>
              {!searchResult.installed_version && (
                <button
                  onClick={() => installMut.mutate(searchResult.name)}
                  disabled={installMut.isPending}
                  className="flex items-center gap-0.5 px-2 py-0.5 rounded text-[10px] bg-[#007acc] hover:bg-[#005f9e] disabled:opacity-50"
                >
                  {installMut.isPending ? <Loader2 size={10} className="animate-spin" /> : <Download size={10} />}
                  {t('install')}
                </button>
              )}
            </div>
          ) : (
            <span className="text-gray-500">{t('notFound', { name: debouncedSearch })}</span>
          )}
        </div>
      )}

      {/* Package table */}
      <div className="flex-1 overflow-auto">
        {loading ? (
          <div className="flex items-center justify-center py-8 text-gray-500">
            <Loader2 size={16} className="animate-spin mr-2" />{t('common:loading')}
          </div>
        ) : filtered.length === 0 ? (
          <div className="text-gray-600 text-center py-8">
            {search ? t('noMatch') : t('noPackages')}
          </div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="text-[10px] text-gray-500 uppercase bg-[#252526] sticky top-0">
                <th className="text-left px-2 py-1 font-medium w-5"></th>
                <th className="text-left px-2 py-1 font-medium">{t('colName')}</th>
                <th className="text-left px-2 py-1 font-medium w-24">{t('colVersion')}</th>
                <th className="text-right px-2 py-1 font-medium w-28">{t('colActions')}</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((pkg) => {
                const od = outdatedMap.get(pkg.name);
                const isExpanded = showDetail === pkg.name;
                return (
                  <PkgRow
                    key={pkg.name}
                    pkg={pkg}
                    outdated={od}
                    isExpanded={isExpanded}
                    detailText={isExpanded ? detailText : ''}
                    onToggleDetail={() => loadDetail(pkg.name)}
                    onUninstall={() => uninstallMut.mutate(pkg.name)}
                    onUpgrade={() => upgradeMut.mutate(pkg.name)}
                    uninstalling={uninstallMut.isPending && uninstallMut.variables === pkg.name}
                    upgrading={upgradeMut.isPending && upgradeMut.variables === pkg.name}
                  />
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      {/* Footer */}
      <div className="h-6 px-2 flex items-center bg-[#252526] border-t border-[#3c3c3c] text-[10px] text-gray-500 shrink-0">
        {t('packageCount', { count: packages.length })}
        {outdated.length > 0 && <span className="ml-2 text-yellow-500">{t('upgradableCount', { count: outdated.length })}</span>}
      </div>
    </div>
  );
}

// ── Row component ──

interface PkgRowProps {
  pkg: PipPackage;
  outdated?: PipOutdatedPackage;
  isExpanded: boolean;
  detailText: string;
  onToggleDetail: () => void;
  onUninstall: () => void;
  onUpgrade: () => void;
  uninstalling: boolean;
  upgrading: boolean;
}

function PkgRow({ pkg, outdated, isExpanded, detailText, onToggleDetail, onUninstall, onUpgrade, uninstalling, upgrading }: PkgRowProps) {
  const { t } = useTranslation(['pip', 'common']);
  return (
    <>
      <tr className="hover:bg-[#2d2d2d] border-b border-[#3c3c3c]/50 group">
        <td className="px-2 py-1">
          <button onClick={onToggleDetail} className="p-0.5 hover:bg-[#3c3c3c] rounded">
            {isExpanded ? <ChevronDown size={11} /> : <ChevronRight size={11} />}
          </button>
        </td>
        <td className="px-2 py-1">
          <span className="text-[#cccccc]">{pkg.name}</span>
        </td>
        <td className="px-2 py-1">
          <span className="text-gray-400">{pkg.version}</span>
          {outdated && (
            <span className="ml-1.5 px-1 py-0 rounded text-[9px] bg-yellow-600/30 text-yellow-400">
              → {outdated.latest_version}
            </span>
          )}
        </td>
        <td className="px-2 py-1 text-right">
          <div className="flex items-center justify-end gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
            {outdated && (
              <button
                onClick={onUpgrade}
                disabled={upgrading}
                className="flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] bg-yellow-600/30 hover:bg-yellow-600/50 text-yellow-400 disabled:opacity-50"
              >
                {upgrading ? <Loader2 size={9} className="animate-spin" /> : <ArrowUpCircle size={9} />}
                {t('upgrade')}
              </button>
            )}
            <button
              onClick={onUninstall}
              disabled={uninstalling}
              className="flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] bg-red-600/20 hover:bg-red-600/40 text-red-400 disabled:opacity-50"
            >
              {uninstalling ? <Loader2 size={9} className="animate-spin" /> : <Trash2 size={9} />}
              {t('uninstall')}
            </button>
          </div>
        </td>
      </tr>
      {isExpanded && (
        <tr>
          <td colSpan={4} className="bg-[#1a1a2e] px-4 py-2">
            <pre className="text-[11px] text-gray-300 whitespace-pre-wrap font-mono leading-relaxed">{detailText}</pre>
          </td>
        </tr>
      )}
    </>
  );
}
