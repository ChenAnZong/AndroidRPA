import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Code2,
  Wifi,
  WifiOff,
  Smartphone,
  Search,
  ArrowRight,
} from 'lucide-react';
import { useDevices } from '@/hooks/useDevices';

export default function DevTools() {
  const { t } = useTranslation(['ide', 'common']);
  const { data, isLoading } = useDevices();
  const [search, setSearch] = useState('');

  const devices = data?.devices ?? [];
  const filtered = devices.filter(
    (d) =>
      d.model.toLowerCase().includes(search.toLowerCase()) ||
      d.imei.includes(search),
  );

  // Sort: online first, then by model name
  const sorted = [...filtered].sort((a, b) => {
    if (a.online !== b.online) return a.online ? -1 : 1;
    return a.model.localeCompare(b.model);
  });

  return (
    <div className="p-6 space-y-6 max-w-5xl mx-auto animate-in">
      {/* Header */}
      <div>
        <h1 className="text-lg font-semibold text-text-primary flex items-center gap-2">
          <Code2 size={20} className="text-brand" />
          {t('title')}
        </h1>
        <p className="text-sm text-text-muted mt-1">
          {t('subtitle')}
        </p>
      </div>

      {/* Search */}
      <div className="relative max-w-sm">
        <Search
          size={16}
          className="absolute left-3 top-1/2 -translate-y-1/2 text-text-hint"
        />
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder={t('searchPlaceholder')}
          className="input pl-9 w-full"
        />
      </div>

      {/* Device grid */}
      {isLoading ? (
        <div className="text-sm text-text-muted py-12 text-center">
          {t('loadingDevices')}
        </div>
      ) : sorted.length === 0 ? (
        <div className="text-sm text-text-muted py-12 text-center">
          {search ? t('noMatchingDevices') : t('noDevices')}
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          {sorted.map((device) => (
            <button
              key={device.imei}
              onClick={() => window.open(`/dev-tools/${device.imei}/ide`, '_blank')}
              disabled={!device.online}
              className={`group relative flex items-center gap-3 rounded-[3px] border p-4 text-left transition-all ${
                device.online
                  ? 'border-divider bg-card-bg hover:border-brand/30 cursor-pointer'
                  : 'border-divider bg-hover opacity-60 cursor-not-allowed'
              }`}
            >
              <div
                className={`flex h-10 w-10 items-center justify-center rounded-[3px] shrink-0 ${
                  device.online
                    ? 'bg-accent-blue-bg text-brand'
                    : 'bg-hover text-text-hint'
                }`}
              >
                <Smartphone size={20} />
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-text-primary truncate">
                  {device.model}
                </p>
                <p className="text-[11px] text-text-hint font-mono truncate">
                  {device.imei}
                </p>
                <div className="flex items-center gap-2 mt-1">
                  {device.online ? (
                    <span className="flex items-center gap-1 text-[11px] text-green-600">
                      <Wifi size={10} /> {t('common:online')}
                    </span>
                  ) : (
                    <span className="flex items-center gap-1 text-[11px] text-text-hint">
                      <WifiOff size={10} /> {t('common:offline')}
                    </span>
                  )}
                  {device.running_project && (
                    <span className="text-[11px] text-text-muted truncate">
                      {t('common:running', { name: device.running_project })}
                    </span>
                  )}
                </div>
              </div>
              {device.online && (
                <ArrowRight
                  size={16}
                  className="text-text-hint group-hover:text-brand transition-colors shrink-0"
                />
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
