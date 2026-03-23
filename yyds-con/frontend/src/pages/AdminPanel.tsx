import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { status } from '@/store';
import {
  Users, Smartphone, Link2, Trash2, Shield, ShieldCheck,
  Search, RefreshCw, Key,
  BarChart3, Ban, CheckCircle, Settings2,
} from 'lucide-react';
import { adminApi } from '@/services/api';
import type { AdminUser } from '@/types';

export default function AdminPanel() {
  const { t } = useTranslation(['admin', 'common']);
  const [tab, setTab] = useState<'stats' | 'users' | 'bindings'>('stats');

  return (
    <div className="animate-in p-6 space-y-6">
      <div className="flex items-center gap-3">
        <div
          className="flex h-10 w-10 items-center justify-center rounded-xl text-white"
          style={{ background: 'linear-gradient(135deg, #2C5F8A, #1A3D5C)' }}
        >
          <ShieldCheck size={20} />
        </div>
        <div>
          <h1 className="text-xl font-bold text-text-primary">{t('title')}</h1>
          <p className="text-sm text-text-muted">{t('subtitle')}</p>
        </div>
      </div>

      {/* Tab bar */}
      <div className="flex gap-1 border-b border-divider">
        {([
          { key: 'stats', label: t('tabOverview'), icon: BarChart3 },
          { key: 'users', label: t('tabUsers'), icon: Users },
          { key: 'bindings', label: t('tabBindings'), icon: Link2 },
        ] as const).map(({ key, label, icon: Icon }) => (
          <button
            key={key}
            onClick={() => setTab(key)}
            className={`flex items-center gap-2 px-4 py-2 text-sm font-medium transition-all ${
              tab === key
                ? 'border-b-2 border-brand text-brand pb-2.5'
                : 'border-b-2 border-transparent text-text-muted hover:text-text-primary pb-2.5'
            }`}
          >
            <Icon size={16} />
            {label}
          </button>
        ))}
      </div>

      {tab === 'stats' && <StatsPanel />}
      {tab === 'users' && <UsersPanel />}
      {tab === 'bindings' && <BindingsPanel />}
    </div>
  );
}

function StatsPanel() {
  const { t } = useTranslation(['admin', 'common']);
  const { data } = useQuery({
    queryKey: ['admin', 'stats'],
    queryFn: adminApi.stats,
    refetchInterval: 5000,
  });

  if (!data) return <div className="text-text-muted text-sm">{t('common:loading')}</div>;

  const cards = [
    { label: t('registeredUsers'), value: data.users, color: 'bg-accent-blue-bg text-brand', icon: Users },
    { label: t('deviceBindings'), value: data.device_bindings, color: 'bg-accent-purple-bg text-purple-600', icon: Link2 },
    { label: t('totalDevices'), value: data.devices_total, color: 'bg-accent-amber-bg text-amber-600', icon: Smartphone },
    { label: t('onlineDevices'), value: data.devices_online, color: 'bg-success-bg text-success', icon: Smartphone },
  ];

  return (
    <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
      {cards.map(({ label, value, color, icon: Icon }) => (
        <div key={label} className="card flex items-center gap-4">
          <div className={`flex h-12 w-12 items-center justify-center rounded-xl ${color}`}>
            <Icon size={22} />
          </div>
          <div>
            <p className="text-2xl font-bold text-text-primary">{value}</p>
            <p className="text-sm text-text-muted">{label}</p>
          </div>
        </div>
      ))}
    </div>
  );
}

function UsersPanel() {
  const { t } = useTranslation(['admin', 'common']);
  const queryClient = useQueryClient();
  const [search, setSearch] = useState('');
  const [resetPwUser, setResetPwUser] = useState<number | null>(null);
  const [newPassword, setNewPassword] = useState('');

  const { data, isLoading, refetch } = useQuery({
    queryKey: ['admin', 'users'],
    queryFn: adminApi.listUsers,
  });

  const deleteMut = useMutation({
    mutationFn: adminApi.deleteUser,
    onSuccess: () => {
      status.success(t('userDeleted'));
      queryClient.invalidateQueries({ queryKey: ['admin'] });
    },
    onError: (e: Error) => status.error(e.message),
  });

  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: number; data: { role?: string; password?: string; enabled?: boolean; max_devices?: number } }) =>
      adminApi.updateUser(id, data),
    onSuccess: () => {
      status.success(t('updateSuccess'));
      queryClient.invalidateQueries({ queryKey: ['admin'] });
      setResetPwUser(null);
      setNewPassword('');
    },
    onError: (e: Error) => status.error(e.message),
  });

  const users = (data?.users || []).filter(
    (u) => !search || u.username.toLowerCase().includes(search.toLowerCase())
  );

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <div className="relative flex-1 max-w-sm">
          <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-text-hint" />
          <input
            className="input pl-9"
            placeholder={t('searchUsers')}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        <button onClick={() => refetch()} className="btn-ghost">
          <RefreshCw size={16} />
        </button>
      </div>

      {isLoading ? (
        <p className="text-sm text-text-muted">{t('common:loading')}</p>
      ) : (
        <div className="card overflow-hidden p-0">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b bg-hover text-left text-text-muted">
                <th className="px-4 py-3 font-medium">ID</th>
                <th className="px-4 py-3 font-medium">{t('colUsername')}</th>
                <th className="px-4 py-3 font-medium">{t('colRole')}</th>
                <th className="px-4 py-3 font-medium">{t('colStatus')}</th>
                <th className="px-4 py-3 font-medium">{t('colMaxDevices')}</th>
                <th className="px-4 py-3 font-medium">{t('colRegisterTime')}</th>
                <th className="px-4 py-3 font-medium">{t('colLastLogin')}</th>
                <th className="px-4 py-3 font-medium text-right">{t('colActions')}</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => (
                <UserRow
                  key={u.id}
                  user={u}
                  onDelete={() => {
                    if (confirm(t('confirmDeleteUser', { name: u.username }))) deleteMut.mutate(u.id);
                  }}
                  onToggleRole={() =>
                    updateMut.mutate({
                      id: u.id,
                      data: { role: u.role === 'admin' ? 'user' : 'admin' },
                    })
                  }
                  onToggleEnabled={() =>
                    updateMut.mutate({
                      id: u.id,
                      data: { enabled: !u.enabled },
                    })
                  }
                  onSetMaxDevices={(max_devices: number) =>
                    updateMut.mutate({
                      id: u.id,
                      data: { max_devices },
                    })
                  }
                  resetPwOpen={resetPwUser === u.id}
                  onResetPwToggle={() => {
                    setResetPwUser(resetPwUser === u.id ? null : u.id);
                    setNewPassword('');
                  }}
                  newPassword={newPassword}
                  onNewPasswordChange={setNewPassword}
                  onResetPw={() =>
                    updateMut.mutate({ id: u.id, data: { password: newPassword } })
                  }
                />
              ))}
            </tbody>
          </table>
          {users.length === 0 && (
            <p className="py-8 text-center text-sm text-text-hint">{t('noMatchingUsers')}</p>
          )}
        </div>
      )}
    </div>
  );
}

function UserRow({
  user: u,
  onDelete,
  onToggleRole,
  onToggleEnabled,
  onSetMaxDevices,
  resetPwOpen,
  onResetPwToggle,
  newPassword,
  onNewPasswordChange,
  onResetPw,
}: {
  user: AdminUser;
  onDelete: () => void;
  onToggleRole: () => void;
  onToggleEnabled: () => void;
  onSetMaxDevices: (v: number) => void;
  resetPwOpen: boolean;
  onResetPwToggle: () => void;
  newPassword: string;
  onNewPasswordChange: (v: string) => void;
  onResetPw: () => void;
}) {
  const { t } = useTranslation(['admin', 'common']);
  const [editingMaxDevices, setEditingMaxDevices] = useState(false);
  const [maxDevicesVal, setMaxDevicesVal] = useState(String(u.max_devices));
  const fmtTime = (ts: number | null) =>
    ts ? new Date(ts * 1000).toLocaleString('zh-CN') : '-';

  return (
    <>
      <tr className={`border-b hover:bg-hover transition-colors ${!u.enabled ? 'opacity-50' : ''}`}>
        <td className="px-4 py-3 text-text-muted">{u.id}</td>
        <td className="px-4 py-3 font-medium">{u.username}</td>
        <td className="px-4 py-3">
          <span
            className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${
              u.role === 'admin'
                ? 'bg-accent-purple-bg text-purple-600'
                : 'bg-accent-blue-bg text-brand'
            }`}
          >
            {u.role === 'admin' ? <ShieldCheck size={12} /> : <Shield size={12} />}
            {u.role === 'admin' ? t('common:admin') : t('common:user')}
          </span>
        </td>
        <td className="px-4 py-3">
          <span
            className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${
              u.enabled
                ? 'bg-success-bg text-success'
                : 'bg-danger-bg text-danger'
            }`}
          >
            {u.enabled ? <CheckCircle size={12} /> : <Ban size={12} />}
            {u.enabled ? t('enabled') : t('disabled')}
          </span>
        </td>
        <td className="px-4 py-3">
          {editingMaxDevices ? (
            <div className="flex items-center gap-1">
              <input
                className="input w-20 text-xs"
                type="number"
                min={1}
                max={9999}
                value={maxDevicesVal}
                onChange={(e) => setMaxDevicesVal(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    const v = parseInt(maxDevicesVal);
                    if (v > 0) { onSetMaxDevices(v); setEditingMaxDevices(false); }
                  }
                  if (e.key === 'Escape') setEditingMaxDevices(false);
                }}
                autoFocus
              />
              <button
                className="btn-ghost text-xs text-success"
                onClick={() => {
                  const v = parseInt(maxDevicesVal);
                  if (v > 0) { onSetMaxDevices(v); setEditingMaxDevices(false); }
                }}
              >
                <CheckCircle size={14} />
              </button>
            </div>
          ) : (
            <button
              className="inline-flex items-center gap-1 text-text-secondary hover:text-brand text-xs"
              onClick={() => { setMaxDevicesVal(String(u.max_devices)); setEditingMaxDevices(true); }}
            >
              <Smartphone size={12} />
              {u.max_devices}
              <Settings2 size={10} className="text-text-hint" />
            </button>
          )}
        </td>
        <td className="px-4 py-3 text-text-muted">{fmtTime(u.created_at)}</td>
        <td className="px-4 py-3 text-text-muted">{fmtTime(u.last_login)}</td>
        <td className="px-4 py-3 text-right">
          <div className="flex items-center justify-end gap-1">
            <button
              onClick={onToggleEnabled}
              className={`btn-ghost text-xs ${u.enabled ? 'text-warning hover:text-amber-600' : 'text-success hover:text-green-600'}`}
              title={u.enabled ? t('disableUser') : t('enableUser')}
            >
              {u.enabled ? <Ban size={14} /> : <CheckCircle size={14} />}
            </button>
            <button onClick={onToggleRole} className="btn-ghost text-xs" title={t('switchRole')}>
              <Shield size={14} />
            </button>
            <button onClick={onResetPwToggle} className="btn-ghost text-xs" title={t('resetPassword')}>
              <Key size={14} />
            </button>
            <button
              onClick={onDelete}
              className="btn-ghost text-xs text-danger hover:text-red-600"
              title={t('common:delete')}
            >
              <Trash2 size={14} />
            </button>
          </div>
        </td>
      </tr>
      {resetPwOpen && (
        <tr className="border-b bg-hover">
          <td colSpan={8} className="px-4 py-3">
            <div className="flex items-center gap-2 max-w-md">
              <input
                className="input flex-1"
                placeholder={t('enterNewPassword')}
                type="password"
                value={newPassword}
                onChange={(e) => onNewPasswordChange(e.target.value)}
              />
              <button
                onClick={onResetPw}
                disabled={newPassword.length < 6}
                className="btn-primary text-xs"
              >
                {t('confirmReset')}
              </button>
              <button onClick={onResetPwToggle} className="btn-secondary text-xs">
                {t('common:cancel')}
              </button>
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

function BindingsPanel() {
  const { t } = useTranslation(['admin', 'common']);
  const [search, setSearch] = useState('');
  const { data, isLoading, refetch } = useQuery({
    queryKey: ['admin', 'bindings'],
    queryFn: adminApi.listBindings,
  });

  const bindings = (data?.bindings || []).filter(
    (b) =>
      !search ||
      b.username.toLowerCase().includes(search.toLowerCase()) ||
      b.imei.includes(search) ||
      b.alias.toLowerCase().includes(search.toLowerCase())
  );

  const fmtTime = (ts: number) => new Date(ts * 1000).toLocaleString('zh-CN');

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <div className="relative flex-1 max-w-sm">
          <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-text-hint" />
          <input
            className="input pl-9"
            placeholder={t('searchBindings')}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        <button onClick={() => refetch()} className="btn-ghost">
          <RefreshCw size={16} />
        </button>
      </div>

      {isLoading ? (
        <p className="text-sm text-text-muted">{t('common:loading')}</p>
      ) : (
        <div className="card overflow-hidden p-0">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b bg-hover text-left text-text-muted">
                <th className="px-4 py-3 font-medium">{t('colUser')}</th>
                <th className="px-4 py-3 font-medium">IMEI</th>
                <th className="px-4 py-3 font-medium">{t('colAlias')}</th>
                <th className="px-4 py-3 font-medium">{t('colBindTime')}</th>
              </tr>
            </thead>
            <tbody>
              {bindings.map((b) => (
                <tr key={b.id} className="border-b hover:bg-hover transition-colors">
                  <td className="px-4 py-3 font-medium">{b.username}</td>
                  <td className="px-4 py-3 font-mono text-xs text-text-muted">{b.imei}</td>
                  <td className="px-4 py-3 text-text-secondary">{b.alias || '-'}</td>
                  <td className="px-4 py-3 text-text-muted">{fmtTime(b.bound_at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {bindings.length === 0 && (
            <p className="py-8 text-center text-sm text-text-hint">{t('noBindings')}</p>
          )}
        </div>
      )}
    </div>
  );
}
