import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';

import {
  Smartphone, Link2, Link2Off, Plus, User, Key, LogOut, ChevronRight,
} from 'lucide-react';
import { authApi, deviceApi } from '@/services/api';
import { useAuthStore, status } from '@/store';
import type { DeviceBinding, Device } from '@/types';

export default function MyDevices() {
  const { t } = useTranslation(['device', 'common', 'auth']);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const [showBind, setShowBind] = useState(false);
  const [bindImei, setBindImei] = useState('');
  const [bindAlias, setBindAlias] = useState('');
  const [showChangePw, setShowChangePw] = useState(false);
  const [oldPw, setOldPw] = useState('');
  const [newPw, setNewPw] = useState('');

  const handleLogout = () => {
    logout();
    queryClient.clear();
    navigate('/login', { replace: true });
  };

  // My bound devices
  const { data: bindingsData } = useQuery({
    queryKey: ['my-devices'],
    queryFn: authApi.myDevices,
  });

  // All online devices (to show status)
  const { data: allDevices } = useQuery({
    queryKey: ['devices'],
    queryFn: deviceApi.list,
    refetchInterval: 5000,
  });

  const bindMut = useMutation({
    mutationFn: () => authApi.bindDevice(bindImei.trim(), bindAlias.trim()),
    onSuccess: (res) => {
      status.success(t('device:deviceBound', { token: res.device_token.slice(0, 20) }));
      queryClient.invalidateQueries({ queryKey: ['my-devices'] });
      setShowBind(false);
      setBindImei('');
      setBindAlias('');
      // Copy token to clipboard
      navigator.clipboard?.writeText(res.device_token).then(() => {
        status.info(t('device:tokenCopied'));
      });
    },
    onError: (e: Error) => status.error(e.message),
  });

  const unbindMut = useMutation({
    mutationFn: (imei: string) => authApi.unbindDevice(imei),
    onSuccess: () => {
      status.success(t('device:deviceUnbound'));
      queryClient.invalidateQueries({ queryKey: ['my-devices'] });
    },
    onError: (e: Error) => status.error(e.message),
  });

  const changePwMut = useMutation({
    mutationFn: () => authApi.changePassword(oldPw, newPw),
    onSuccess: () => {
      status.success(t('auth:passwordChanged'));
      setShowChangePw(false);
      setOldPw('');
      setNewPw('');
    },
    onError: (e: Error) => status.error(e.message),
  });

  const bindings: DeviceBinding[] = bindingsData?.devices || [];
  const deviceMap = new Map<string, Device>();
  (allDevices?.devices || []).forEach((d) => deviceMap.set(d.imei, d));

  return (
    <div className="p-6 space-y-6 animate-in">
      {/* User info header */}
      <div className="card flex items-center justify-between">
        <div className="flex items-center gap-4">
          <div className="flex h-12 w-12 items-center justify-center rounded-full bg-accent-blue-bg text-brand">
            <User size={24} />
          </div>
          <div>
            <h2 className="text-lg font-bold text-text-primary">{user?.username}</h2>
            <span
              className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${
                user?.role === 'admin'
                  ? 'bg-accent-purple-bg text-purple-600'
                  : 'bg-accent-blue-bg text-brand'
              }`}
            >
              {user?.role === 'admin' ? t('common:admin') : t('common:normalUser')}
            </span>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={() => setShowChangePw(!showChangePw)} className="btn-secondary text-sm">
            <Key size={14} />
            {t('auth:changePassword')}
          </button>
          <button
            onClick={handleLogout}
            className="btn-ghost text-sm text-danger"
          >
            <LogOut size={14} />
            {t('common:logoutShort')}
          </button>
        </div>
      </div>

      {/* Change password */}
      {showChangePw && (
        <div className="card space-y-3">
          <h3 className="text-sm font-medium text-text-secondary">{t('auth:changePassword')}</h3>
          <div className="flex items-center gap-3 max-w-lg">
            <input
              className="input flex-1"
              type="password"
              placeholder={t('auth:oldPassword')}
              value={oldPw}
              onChange={(e) => setOldPw(e.target.value)}
            />
            <input
              className="input flex-1"
              type="password"
              placeholder={t('auth:newPassword')}
              value={newPw}
              onChange={(e) => setNewPw(e.target.value)}
            />
            <button
              onClick={() => changePwMut.mutate()}
              disabled={!oldPw || newPw.length < 6}
              className="btn-primary text-sm"
            >
              {t('common:confirm')}
            </button>
            <button onClick={() => setShowChangePw(false)} className="btn-secondary text-sm">
              {t('common:cancel')}
            </button>
          </div>
        </div>
      )}

      {/* My devices */}
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold text-text-primary flex items-center gap-2">
            <Smartphone size={20} />
            {t('device:nav.myDevices')}
            <span className="text-sm font-normal text-text-muted">({bindings.length})</span>
          </h2>
          <button onClick={() => setShowBind(!showBind)} className="btn-primary text-sm">
            <Plus size={14} />
            {t('device:bindDevice')}
          </button>
        </div>

        {/* Bind form */}
        {showBind && (
          <div className="card space-y-3">
            <h3 className="text-sm font-medium text-text-secondary">{t('device:bindNewDevice')}</h3>
            <div className="flex items-end gap-3 max-w-2xl">
              <div className="flex-1">
                <label className="mb-1 block text-xs text-text-muted">{t('device:deviceImei')}</label>
                <input
                  className="input"
                  placeholder={t('device:imeiPlaceholder')}
                  value={bindImei}
                  onChange={(e) => setBindImei(e.target.value)}
                />
              </div>
              <div className="flex-1">
                <label className="mb-1 block text-xs text-text-muted">{t('device:alias')}</label>
                <input
                  className="input"
                  placeholder={t('device:aliasPlaceholder')}
                  value={bindAlias}
                  onChange={(e) => setBindAlias(e.target.value)}
                />
              </div>
              <button
                onClick={() => bindMut.mutate()}
                disabled={!bindImei.trim() || bindMut.isPending}
                className="btn-primary text-sm"
              >
                <Link2 size={14} />
                {t('device:bind')}
              </button>
            </div>
            <p className="text-xs text-text-hint">
              {t('device:bindHint')}
            </p>
          </div>
        )}

        {/* Device grid */}
        {bindings.length === 0 ? (
          <div className="card py-12 text-center">
            <Smartphone size={40} className="mx-auto text-text-hint" />
            <p className="mt-3 text-sm text-text-muted">{t('device:noBindings')}</p>
            <p className="text-xs text-text-hint">{t('device:noBindingsHint')}</p>
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {bindings.map((b) => {
              const device = deviceMap.get(b.imei);
              const online = device?.online ?? false;
              return (
                <div
                  key={b.imei}
                  className="card cursor-pointer hover:border-brand/30 hover:scale-[1.02] transition-all duration-200"
                  onClick={() => {
                    if (device) navigate(`/devices/${b.imei}`);
                  }}
                >
                  <div className="flex items-start justify-between">
                    <div className="flex items-center gap-3">
                      <div
                        className={`flex h-10 w-10 items-center justify-center rounded-[3px] ${
                          online ? 'bg-success-bg text-success' : 'bg-hover text-text-hint'
                        }`}
                      >
                        <Smartphone size={20} />
                      </div>
                      <div>
                        <p className="font-medium text-text-primary">
                          {b.alias || device?.model || b.imei}
                        </p>
                        <p className="text-xs text-text-muted font-mono">{b.imei}</p>
                      </div>
                    </div>
                    <span className={online ? 'badge-online' : 'badge-offline'}>
                      <span className={`h-1.5 w-1.5 rounded-full ${online ? 'bg-success' : 'bg-text-hint'}`} />
                      {online ? t('common:online') : t('common:offline')}
                    </span>
                  </div>

                  {device && (
                    <div className="mt-3 flex items-center gap-4 text-xs text-text-muted">
                      <span>{device.model}</span>
                      <span>{device.screen_width}×{device.screen_height}</span>
                      {device.running_project && (
                        <span className="text-success">{t('common:running', { name: device.running_project })}</span>
                      )}
                    </div>
                  )}

                  <div className="mt-3 flex items-center justify-between">
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        if (confirm(t('device:confirmUnbind', { name: b.alias || b.imei })))
                          unbindMut.mutate(b.imei);
                      }}
                      className="btn-ghost text-xs text-danger"
                    >
                      <Link2Off size={12} />
                      {t('device:unbind')}
                    </button>
                    {device && (
                      <ChevronRight size={16} className="text-text-hint" />
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
