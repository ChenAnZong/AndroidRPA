import { useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { status } from '@/store';
import {
  X, ChevronLeft, ChevronRight, Package, Upload, Smartphone,
  Image, Settings, Loader2, Check, FileDown, Eye, Power,
  MonitorOff, Lock, Search,
} from 'lucide-react';
import { ideApi } from '@/services/api';
import type { PackageBuildConfig, InstalledApp, BuiltApk } from '@/types';

interface Props {
  imei: string;
  projectName: string;
  onClose: () => void;
}

type IconMode = 'default' | 'upload' | 'clone';

const STEPS = ['apk.stepBasicInfo', 'apk.stepIcon', 'apk.stepBehavior'] as const;

function parseInstalledApps(data: string | undefined): InstalledApp[] {
  if (!data) return [];
  try { return JSON.parse(data); } catch { return []; }
}
function parseBuiltApks(data: string | undefined): BuiltApk[] {
  if (!data) return [];
  try { return JSON.parse(data); } catch { return []; }
}

export default function ApkBuilderPanel({ imei, projectName, onClose }: Props) {
  const { t } = useTranslation(['apk', 'common']);
  const qc = useQueryClient();
  const [step, setStep] = useState(0);

  // Step 1: Basic info
  const [appName, setAppName] = useState(projectName);
  const [version, setVersion] = useState('1.0');
  const [packageName, setPackageName] = useState('');

  // Step 2: Icon
  const [iconMode, setIconMode] = useState<IconMode>('default');
  const [iconBase64, setIconBase64] = useState('');
  const [clonePkg, setClonePkg] = useState('');
  const [appSearch, setAppSearch] = useState('');

  // Step 3: Behavior
  const [autoRunOnOpen, setAutoRunOnOpen] = useState(false);
  const [keepScreenOn, setKeepScreenOn] = useState(true);
  const [showLog, setShowLog] = useState(true);
  const [exitOnScriptStop, setExitOnScriptStop] = useState(false);
  const [encryptScripts, setEncryptScripts] = useState(false);

  // Build state
  const [buildStatus, setBuildStatus] = useState('');

  // Queries
  const { data: appsRes, isLoading: appsLoading } = useQuery({
    queryKey: ['installed-apps', imei],
    queryFn: () => ideApi.packageInstalledApps(imei),
    enabled: iconMode === 'clone',
  });
  const installedApps = parseInstalledApps(appsRes?.data as string | undefined);
  const filteredApps = appSearch
    ? installedApps.filter((a) => a.appName.toLowerCase().includes(appSearch.toLowerCase()) || a.packageName.includes(appSearch.toLowerCase()))
    : installedApps;

  const { data: listRes } = useQuery({
    queryKey: ['package-list', imei],
    queryFn: () => ideApi.packageList(imei),
  });
  const builtApks = parseBuiltApks(listRes?.data as string | undefined);

  // Clone icon
  const loadCloneIcon = useCallback(async (pkg: string) => {
    setClonePkg(pkg);
    try {
      const res = await ideApi.packageAppIcon(imei, pkg);
      if (res.data) setIconBase64(res.data as string);
    } catch { status.error(t('getIconFailed')); }
  }, [imei]);

  // Upload icon
  const handleUpload = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result as string;
      setIconBase64(result.includes(',') ? result.split(',')[1] : result);
    };
    reader.readAsDataURL(file);
  }, []);

  // Build mutation
  const buildMut = useMutation({
    mutationFn: (config: PackageBuildConfig) => {
      setBuildStatus(t('building'));
      return ideApi.packageBuild(imei, config);
    },
    onSuccess: () => {
      setBuildStatus('');
      status.success(t('buildSuccess'));
      qc.invalidateQueries({ queryKey: ['package-list', imei] });
    },
    onError: (e: Error) => {
      setBuildStatus('');
      status.error(t('buildFailed', { msg: e.message }));
    },
  });

  const handleBuild = () => {
    const config: PackageBuildConfig = {
      appName: appName || projectName,
      projectName,
      version,
      packageName: packageName || undefined,
      iconPath: iconBase64 || undefined,
      autoRunOnOpen,
      keepScreenOn,
      showLog,
      exitOnScriptStop,
      encryptScripts,
    };
    buildMut.mutate(config);
  };

  const canNext = step < 2;
  const canBack = step > 0;
  const canBuild = step === 2 && appName.trim().length > 0;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
      <div className="bg-[#252526] rounded-lg border border-[#3c3c3c] w-[560px] max-h-[90vh] flex flex-col shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b border-[#3c3c3c]">
          <div className="flex items-center gap-2">
            <Package size={16} className="text-[#007acc]" />
            <span className="text-[13px] font-medium text-white">{t('title')}</span>
            <span className="text-[11px] text-gray-500">— {projectName}</span>
          </div>
          <button onClick={onClose} className="p-1 hover:bg-[#3c3c3c] rounded"><X size={14} /></button>
        </div>

        {/* Step indicator */}
        <div className="flex items-center justify-center gap-0 py-3 px-8">
          {STEPS.map((label, i) => (
            <div key={i} className="flex items-center">
              <div className={`w-7 h-7 rounded-full flex items-center justify-center text-[11px] font-medium border-2 transition-colors ${
                i < step ? 'bg-[#007acc] border-[#007acc] text-white'
                : i === step ? 'border-[#007acc] text-[#007acc]'
                : 'border-[#3c3c3c] text-gray-500'
              }`}>
                {i < step ? <Check size={12} /> : i + 1}
              </div>
              <span className={`ml-1.5 text-[11px] ${i === step ? 'text-white' : 'text-gray-500'}`}>{label}</span>
              {i < STEPS.length - 1 && <div className={`w-12 h-px mx-2 ${i < step ? 'bg-[#007acc]' : 'bg-[#3c3c3c]'}`} />}
            </div>
          ))}
        </div>

        {/* Step content */}
        <div className="flex-1 overflow-auto px-5 py-3 min-h-[280px]">
          {step === 0 && (
            <StepBasicInfo
              appName={appName} setAppName={setAppName}
              version={version} setVersion={setVersion}
              packageName={packageName} setPackageName={setPackageName}
            />
          )}
          {step === 1 && (
            <StepIcon
              iconMode={iconMode} setIconMode={setIconMode}
              iconBase64={iconBase64}
              onUpload={handleUpload}
              apps={filteredApps} appsLoading={appsLoading}
              appSearch={appSearch} setAppSearch={setAppSearch}
              clonePkg={clonePkg} onClone={loadCloneIcon}
            />
          )}
          {step === 2 && (
            <StepBehavior
              autoRunOnOpen={autoRunOnOpen} setAutoRunOnOpen={setAutoRunOnOpen}
              keepScreenOn={keepScreenOn} setKeepScreenOn={setKeepScreenOn}
              showLog={showLog} setShowLog={setShowLog}
              exitOnScriptStop={exitOnScriptStop} setExitOnScriptStop={setExitOnScriptStop}
              encryptScripts={encryptScripts} setEncryptScripts={setEncryptScripts}
            />
          )}
        </div>

        {/* Build status */}
        {(buildMut.isPending || buildStatus) && (
          <div className="px-5 py-2 flex items-center gap-2 text-[11px] text-[#007acc] border-t border-[#3c3c3c]">
            <Loader2 size={12} className="animate-spin" />
            {buildStatus || t('processing')}
          </div>
        )}

        {/* Footer nav */}
        <div className="flex items-center justify-between px-5 py-3 border-t border-[#3c3c3c]">
          <button
            onClick={() => setStep((s) => s - 1)}
            disabled={!canBack}
            className="flex items-center gap-1 px-3 py-1.5 rounded text-[12px] bg-[#3c3c3c] hover:bg-[#4c4c4c] disabled:opacity-30"
          >
            <ChevronLeft size={12} />{t('prevStep')}
          </button>
          <div className="flex gap-2">
            {canNext ? (
              <button
                onClick={() => setStep((s) => s + 1)}
                className="flex items-center gap-1 px-3 py-1.5 rounded text-[12px] bg-[#007acc] hover:bg-[#005f9e]"
              >
                {t('nextStep')}<ChevronRight size={12} />
              </button>
            ) : (
              <button
                onClick={handleBuild}
                disabled={!canBuild || buildMut.isPending}
                className="flex items-center gap-1 px-4 py-1.5 rounded text-[12px] bg-[#007acc] hover:bg-[#005f9e] disabled:opacity-50 font-medium"
              >
                {buildMut.isPending ? <Loader2 size={12} className="animate-spin" /> : <Package size={12} />}
                {t('build')}
              </button>
            )}
          </div>
        </div>

        {/* Built APK history */}
        {builtApks.length > 0 && (
          <div className="border-t border-[#3c3c3c] px-5 py-2 max-h-[120px] overflow-auto">
            <div className="text-[10px] text-gray-500 mb-1">{t('history')}</div>
            {builtApks.map((apk) => (
              <div key={apk.path} className="flex items-center justify-between py-0.5 text-[11px]">
                <span className="text-gray-300 truncate flex-1">{apk.name}</span>
                <span className="text-gray-500 ml-2 shrink-0">{(apk.size / 1024 / 1024).toFixed(1)}MB</span>
                <FileDown size={11} className="ml-2 text-gray-400 hover:text-white cursor-pointer shrink-0" />
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

// ── Step 1: Basic Info ──

function StepBasicInfo({ appName, setAppName, version, setVersion, packageName, setPackageName }: {
  appName: string; setAppName: (v: string) => void;
  version: string; setVersion: (v: string) => void;
  packageName: string; setPackageName: (v: string) => void;
}) {
  const { t } = useTranslation('apk');
  return (
    <div className="space-y-4">
      <Field label={t('appName')} required>
        <input
          className="w-full bg-[#3c3c3c] text-white text-[12px] px-3 py-1.5 rounded border border-[#555] focus:border-[#007acc] outline-none"
          value={appName} onChange={(e) => setAppName(e.target.value)}
          placeholder={t('appNamePlaceholder')}
        />
      </Field>
      <Field label={t('version')}>
        <input
          className="w-full bg-[#3c3c3c] text-white text-[12px] px-3 py-1.5 rounded border border-[#555] focus:border-[#007acc] outline-none"
          value={version} onChange={(e) => setVersion(e.target.value)}
          placeholder="1.0"
        />
      </Field>
      <Field label={t('packageName')} hint={t('packageNameHint')}>
        <input
          className="w-full bg-[#3c3c3c] text-white text-[12px] px-3 py-1.5 rounded border border-[#555] focus:border-[#007acc] outline-none"
          value={packageName} onChange={(e) => setPackageName(e.target.value)}
          placeholder="com.yyds.auto"
        />
      </Field>
    </div>
  );
}

// ── Step 2: Icon ──

function StepIcon({ iconMode, setIconMode, iconBase64, onUpload, apps, appsLoading, appSearch, setAppSearch, clonePkg, onClone }: {
  iconMode: IconMode; setIconMode: (v: IconMode) => void;
  iconBase64: string;
  onUpload: (e: React.ChangeEvent<HTMLInputElement>) => void;
  apps: InstalledApp[]; appsLoading: boolean;
  appSearch: string; setAppSearch: (v: string) => void;
  clonePkg: string; onClone: (pkg: string) => void;
}) {
  const { t } = useTranslation('apk');
  return (
    <div className="space-y-3">
      {/* Mode selector */}
      <div className="flex gap-2">
        {([['default', t('defaultIcon'), Image], ['upload', t('uploadImage'), Upload], ['clone', t('cloneApp'), Smartphone]] as const).map(([mode, label, Icon]) => (
          <button
            key={mode}
            onClick={() => setIconMode(mode as IconMode)}
            className={`flex-1 flex items-center justify-center gap-1.5 py-2 rounded text-[11px] border transition-colors ${
              iconMode === mode ? 'border-[#007acc] bg-[#007acc]/20 text-[#007acc]' : 'border-[#3c3c3c] text-gray-400 hover:border-gray-500'
            }`}
          >
            <Icon size={13} />{label}
          </button>
        ))}
      </div>

      {/* Preview */}
      {iconBase64 && (iconMode === 'upload' || iconMode === 'clone') && (
        <div className="flex justify-center">
          <img src={`data:image/png;base64,${iconBase64}`} className="w-16 h-16 rounded-xl border border-[#3c3c3c]" />
        </div>
      )}
      {iconMode === 'default' && (
        <div className="flex justify-center">
          <div className="w-16 h-16 rounded-xl bg-[#007acc] flex items-center justify-center">
            <Package size={28} className="text-white" />
          </div>
        </div>
      )}

      {/* Upload */}
      {iconMode === 'upload' && (
        <label className="block text-center cursor-pointer">
          <div className="border-2 border-dashed border-[#3c3c3c] rounded-lg py-4 hover:border-[#007acc] transition-colors">
            <Upload size={20} className="mx-auto text-gray-500 mb-1" />
            <span className="text-[11px] text-gray-400">{t('selectImage')}</span>
          </div>
          <input type="file" accept="image/*" className="hidden" onChange={onUpload} />
        </label>
      )}

      {/* Clone from app */}
      {iconMode === 'clone' && (
        <div>
          <div className="flex items-center bg-[#3c3c3c] rounded px-2 mb-2">
            <Search size={11} className="text-gray-500" />
            <input
              className="bg-transparent text-white text-[11px] px-1.5 py-1 outline-none w-full"
              placeholder={t('searchApp')}
              value={appSearch} onChange={(e) => setAppSearch(e.target.value)}
            />
          </div>
          <div className="grid grid-cols-4 gap-1.5 max-h-[160px] overflow-auto">
            {appsLoading ? (
              <div className="col-span-4 text-center py-4 text-gray-500"><Loader2 size={14} className="animate-spin mx-auto" /></div>
            ) : apps.slice(0, 40).map((app) => (
              <button
                key={app.packageName}
                onClick={() => onClone(app.packageName)}
                className={`flex flex-col items-center p-1.5 rounded text-[10px] truncate transition-colors ${
                  clonePkg === app.packageName ? 'bg-[#007acc]/30 border border-[#007acc]' : 'hover:bg-[#3c3c3c] border border-transparent'
                }`}
                title={app.packageName}
              >
                <Smartphone size={18} className="text-gray-400 mb-0.5" />
                <span className="truncate w-full text-center text-gray-300">{app.appName}</span>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

// ── Step 3: Behavior ──

function StepBehavior({ autoRunOnOpen, setAutoRunOnOpen, keepScreenOn, setKeepScreenOn, showLog, setShowLog, exitOnScriptStop, setExitOnScriptStop, encryptScripts, setEncryptScripts }: {
  autoRunOnOpen: boolean; setAutoRunOnOpen: (v: boolean) => void;
  keepScreenOn: boolean; setKeepScreenOn: (v: boolean) => void;
  showLog: boolean; setShowLog: (v: boolean) => void;
  exitOnScriptStop: boolean; setExitOnScriptStop: (v: boolean) => void;
  encryptScripts: boolean; setEncryptScripts: (v: boolean) => void;
}) {
  const { t } = useTranslation('apk');
  const toggles: [string, string, string, boolean, (v: boolean) => void, React.ReactNode][] = [
    ['autoRunOnOpen', t('autoRunOnOpen'), t('autoRunOnOpenDesc'), autoRunOnOpen, setAutoRunOnOpen, <Power size={13} />],
    ['keepScreenOn', t('keepScreenOn'), t('keepScreenOnDesc'), keepScreenOn, setKeepScreenOn, <MonitorOff size={13} />],
    ['showLog', t('showLog'), t('showLogDesc'), showLog, setShowLog, <Eye size={13} />],
    ['exitOnStop', t('exitOnStop'), t('exitOnStopDesc'), exitOnScriptStop, setExitOnScriptStop, <Settings size={13} />],
    ['encrypt', t('encrypt'), t('encryptDesc'), encryptScripts, setEncryptScripts, <Lock size={13} />],
  ];

  return (
    <div className="space-y-1">
      {toggles.map(([id, label, desc, value, setter, icon]) => (
        <div key={id} className="flex items-center justify-between py-2.5 px-2 rounded hover:bg-[#2d2d2d]">
          <div className="flex items-center gap-2.5">
            <span className="text-gray-400">{icon}</span>
            <div>
              <div className="text-[12px] text-white">{label}</div>
              <div className="text-[10px] text-gray-500">{desc}</div>
            </div>
          </div>
          <button
            onClick={() => setter(!value)}
            className={`w-9 h-5 rounded-full transition-colors relative ${value ? 'bg-[#007acc]' : 'bg-[#3c3c3c]'}`}
          >
            <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-transform ${value ? 'left-[18px]' : 'left-0.5'}`} />
          </button>
        </div>
      ))}
    </div>
  );
}

// ── Shared ──

function Field({ label, hint, required, children }: { label: string; hint?: string; required?: boolean; children: React.ReactNode }) {
  return (
    <div>
      <label className="block text-[11px] text-gray-400 mb-1">
        {label}{required && <span className="text-red-400 ml-0.5">*</span>}
        {hint && <span className="text-gray-600 ml-1.5">({hint})</span>}
      </label>
      {children}
    </div>
  );
}
