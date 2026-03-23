import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import {
  Plus, Trash2, Calendar, ToggleLeft, ToggleRight, Info,
  Play, ChevronDown, ChevronRight, CheckCircle2, XCircle,
  Loader2, Settings2, Bell, Pencil, X,
} from 'lucide-react';
import { scheduleApi, deviceApi } from '@/services/api';
import { status } from '@/store';
import CronBuilder from '@/components/CronBuilder';
import type { Schedule, Device, TaskRun, AlertConfig } from '@/types';

const ACTION_LABELS: Record<string, string> = {
  start_project: 'startProject',
  stop_project:  'stopProject',
  shell:         'executeShell',
  reboot_engine: 'rebootEngine',
};

function RunStatusBadge({ s }: { s: string | undefined }) {
  const { t } = useTranslation('schedule');
  if (!s) return null;
  const cfg: Record<string, string> = {
    done:         'text-success',
    partial_fail: 'text-warning',
    all_fail:     'text-danger',
    running:      'text-brand',
  };
  const labels: Record<string, string> = {
    done:         t('statusDone'),
    partial_fail: t('statusPartialFail'),
    all_fail:     t('statusAllFail'),
    running:      t('statusRunning'),
  };
  const Icon = s === 'done' ? CheckCircle2 : s === 'running' ? Loader2 : XCircle;
  return (
    <span className={`inline-flex items-center gap-1 text-[10px] font-medium ${cfg[s] ?? 'text-text-muted'}`}>
      <Icon size={10} className={s === 'running' ? 'animate-spin' : ''} />
      {labels[s] ?? s}
    </span>
  );
}

function RunHistoryPanel({
  scheduleId,
  scheduleName,
}: {
  scheduleId: string;
  scheduleName: string;
}) {
  const { t } = useTranslation(['schedule', 'common']);
  const navigate = useNavigate();

  const { data, isLoading } = useQuery({
    queryKey: ['schedule-runs', scheduleId],
    queryFn: () => scheduleApi.runs(scheduleId, 8),
    refetchInterval: (query) => {
      const runs = (query.state.data as { runs?: { status: string }[] } | undefined)?.runs ?? [];
      return runs.some(r => r.status === 'running') ? 2000 : false;
    },
  });

  const runs: TaskRun[] = data?.runs ?? [];

  const statusIcon = (s: string) => {
    if (s === 'done')    return <CheckCircle2 size={12} className="text-success shrink-0" />;
    if (s === 'running') return <Loader2 size={12} className="text-brand animate-spin shrink-0" />;
    if (s === 'all_fail') return <XCircle size={12} className="text-danger shrink-0" />;
    return <XCircle size={12} className="text-warning shrink-0" />;
  };

  return (
    <tr>
      <td colSpan={8} className="px-4 pb-3 pt-0 bg-hover/40">
        <div className="rounded border border-divider-light bg-background p-3 space-y-2">
          <p className="text-[10px] text-text-hint font-medium uppercase tracking-wide">
            {t('viewRuns')} — {scheduleName}
          </p>
          {isLoading ? (
            <div className="flex items-center gap-2 text-xs text-text-muted py-2">
              <Loader2 size={12} className="animate-spin" /> {t('common:loading')}
            </div>
          ) : runs.length === 0 ? (
            <p className="text-xs text-text-hint py-2">{t('noRuns')}</p>
          ) : (
            <div className="space-y-1">
              {runs.map((r) => (
                <button
                  key={r.id}
                  onClick={() => navigate(`/task-runs/${r.id}`)}
                  className="w-full flex items-center gap-3 px-2 py-1.5 rounded hover:bg-hover transition-colors text-left group"
                >
                  {statusIcon(r.status)}
                  <span className="text-xs text-text-muted w-32 shrink-0">
                    {new Date(r.triggered_at).toLocaleString()}
                  </span>
                  <span className={`text-[10px] px-1.5 py-0.5 rounded border ${r.trigger_type === 'manual' ? 'border-brand/30 text-brand bg-brand/5' : 'border-divider text-text-hint bg-hover'}`}>
                    {r.trigger_type === 'manual' ? t('triggerManual') : t('triggerCron')}
                  </span>
                  <span className="flex-1 text-xs text-text-muted">
                    ✓{r.summary.success} ✗{r.summary.failed + r.summary.timeout} ⊘{r.summary.offline}
                  </span>
                  <span className="text-[10px] text-text-hint">{r.duration_ms}ms</span>
                  <ChevronRight size={10} className="text-text-hint opacity-0 group-hover:opacity-100 shrink-0" />
                </button>
              ))}
            </div>
          )}
        </div>
      </td>
    </tr>
  );
}

function ScheduleRow({
  schedule,
  onDelete,
  onToggle,
  onTrigger,
  onEdit,
  triggeringId,
}: {
  schedule: Schedule;
  onDelete: (id: string) => void;
  onToggle: (id: string, enabled: boolean) => void;
  onTrigger: (id: string) => void;
  onEdit: (schedule: Schedule) => void;
  triggeringId: string | null;
}) {
  const { t } = useTranslation(['schedule', 'common']);
  const [showHistory, setShowHistory] = useState(false);
  const isTriggering = triggeringId === schedule.id;

  return (
    <>
      <tr className="border-b border-divider-light hover:bg-hover/50 transition-colors duration-150">
        {/* Name + last-run status */}
        <td className="px-4 py-3">
          <p className="text-sm font-medium text-text-primary">{schedule.name}</p>
          <RunStatusBadge s={schedule.last_run_status} />
        </td>
        {/* Cron */}
        <td className="px-4 py-3 text-xs text-text-muted font-mono">{schedule.cron_expr}</td>
        {/* Action */}
        <td className="px-4 py-3 text-xs text-text-muted">
          {t(ACTION_LABELS[schedule.action] ?? schedule.action)}
          {schedule.params && Object.keys(schedule.params).length > 0 && (
            <span className="ml-1 text-text-hint">
              ({Object.entries(schedule.params).map(([k, v]) => `${k}=${v}`).join(', ')})
            </span>
          )}
        </td>
        {/* Devices */}
        <td className="px-4 py-3 text-xs text-text-muted">{t('devicesCount', { count: schedule.device_ids.length })}</td>
        {/* Enabled toggle */}
        <td className="px-4 py-3">
          <button
            onClick={() => onToggle(schedule.id, !schedule.enabled)}
            className="text-text-muted hover:text-text-primary transition-colors"
          >
            {schedule.enabled ? (
              <ToggleRight size={20} className="text-success" />
            ) : (
              <ToggleLeft size={20} />
            )}
          </button>
        </td>
        {/* Last run */}
        <td className="px-4 py-3 text-xs text-text-muted">
          {schedule.last_run ? new Date(schedule.last_run).toLocaleString() : t('common:never')}
        </td>
        {/* Actions: trigger + history + edit + delete */}
        <td className="px-4 py-3">
          <div className="flex items-center gap-1">
            {/* Trigger — disabled while this row is running to prevent double-trigger */}
            <button
              onClick={() => !isTriggering && onTrigger(schedule.id)}
              disabled={isTriggering}
              title={t('triggerNow')}
              className={`btn-ghost p-1 transition-colors ${
                isTriggering
                  ? 'text-brand cursor-not-allowed opacity-60'
                  : 'text-text-hint hover:text-success'
              }`}
            >
              {isTriggering
                ? <Loader2 size={13} className="animate-spin" />
                : <Play size={13} />}
            </button>
            <button
              onClick={() => setShowHistory(!showHistory)}
              title={showHistory ? t('hideRuns') : t('viewRuns')}
              className={`btn-ghost p-1 transition-colors ${showHistory ? 'text-brand' : 'text-text-hint hover:text-brand'}`}
            >
              {showHistory ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
            </button>
            <button
              onClick={() => onEdit(schedule)}
              title={t('common:edit')}
              className="btn-ghost p-1 text-text-hint hover:text-brand"
            >
              <Pencil size={13} />
            </button>
            <button
              onClick={() => {
                if (confirm(t('confirmDelete', { name: schedule.name }))) onDelete(schedule.id);
              }}
              className="btn-ghost p-1 text-text-hint hover:text-danger"
            >
              <Trash2 size={13} />
            </button>
          </div>
        </td>
      </tr>
      {showHistory && (
        <RunHistoryPanel scheduleId={schedule.id} scheduleName={schedule.name} />
      )}
    </>
  );
}

export default function Schedules() {
  const { t } = useTranslation(['schedule', 'common']);
  const queryClient = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [formName, setFormName] = useState('');
  const [formCron, setFormCron] = useState('');
  const [formAction, setFormAction] = useState('start_project');
  const [formParam, setFormParam] = useState('');
  const [selectedDeviceIds, setSelectedDeviceIds] = useState<Set<string>>(new Set());
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [showAlertConfig, setShowAlertConfig] = useState(false);
  // Advanced fields
  const [batchSize, setBatchSize] = useState('');
  const [batchDelay, setBatchDelay] = useState('');
  const [timeoutSecs, setTimeoutSecs] = useState('');
  const [retryCount, setRetryCount] = useState('');
  // Alert fields
  const [alertOnAnyFail, setAlertOnAnyFail] = useState(false);
  const [alertOnOffline, setAlertOnOffline] = useState(false);
  const [alertWebhook, setAlertWebhook] = useState('');
  const [alertFailRate, setAlertFailRate] = useState('');
  // Edit state — single object keeps all mutable fields together
  const [editingSchedule, setEditingSchedule] = useState<Schedule | null>(null);
  const [editName, setEditName] = useState('');
  const [editCron, setEditCron] = useState('');
  const [editEnabled, setEditEnabled] = useState(true);
  const [editAction, setEditAction] = useState('start_project');
  const [editParam, setEditParam] = useState('');
  const [editDeviceIds, setEditDeviceIds] = useState<Set<string>>(new Set());
  const [editShowAdvanced, setEditShowAdvanced] = useState(false);
  const [editShowAlert, setEditShowAlert] = useState(false);
  const [editBatchSize, setEditBatchSize] = useState('');
  const [editBatchDelay, setEditBatchDelay] = useState('');
  const [editTimeoutSecs, setEditTimeoutSecs] = useState('');
  const [editRetryCount, setEditRetryCount] = useState('');
  const [editAlertOnAnyFail, setEditAlertOnAnyFail] = useState(false);
  const [editAlertOnOffline, setEditAlertOnOffline] = useState(false);
  const [editAlertWebhook, setEditAlertWebhook] = useState('');
  const [editAlertFailRate, setEditAlertFailRate] = useState('');
  // Trigger loading state — tracks which schedule is being triggered
  const [triggeringId, setTriggeringId] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['schedules'],
    queryFn: scheduleApi.list,
    refetchInterval: 10000,
  });

  const { data: devicesData } = useQuery({
    queryKey: ['devices'],
    queryFn: deviceApi.list,
    refetchInterval: 5000,
  });
  const devices: Device[] = devicesData?.devices ?? [];

  const resetForm = () => {
    setShowForm(false);
    setFormName(''); setFormCron(''); setFormAction('start_project');
    setFormParam(''); setSelectedDeviceIds(new Set());
    setShowAdvanced(false); setShowAlertConfig(false);
    setBatchSize(''); setBatchDelay(''); setTimeoutSecs(''); setRetryCount('');
    setAlertOnAnyFail(false); setAlertOnOffline(false);
    setAlertWebhook(''); setAlertFailRate('');
  };

  const createMut = useMutation({
    mutationFn: () => {
      const params: Record<string, unknown> = {};
      if (formAction === 'start_project' && formParam) params.name = formParam;
      if (formAction === 'shell' && formParam) params.command = formParam;

      const alert: AlertConfig | undefined =
        (alertOnAnyFail || alertOnOffline || alertWebhook) ? {
          on_any_fail: alertOnAnyFail,
          on_device_offline: alertOnOffline,
          webhook_url: alertWebhook || undefined,
          fail_rate_threshold: alertFailRate ? parseFloat(alertFailRate) : undefined,
        } : undefined;

      return scheduleApi.create({
        name: formName,
        cron_expr: formCron,
        action: formAction,
        params,
        device_ids: [...selectedDeviceIds],
        enabled: true,
        alert,
        batch_size:    batchSize   ? parseInt(batchSize)   : undefined,
        batch_delay_ms: batchDelay ? parseInt(batchDelay)  : undefined,
        timeout_secs:  timeoutSecs ? parseInt(timeoutSecs) : undefined,
        retry_count:   retryCount  ? parseInt(retryCount)  : undefined,
      });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['schedules'] });
      status.success(t('taskCreated'));
      resetForm();
    },
    onError: (e: Error) => status.error(e.message),
  });

  const deleteMut = useMutation({
    mutationFn: (id: string) => scheduleApi.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['schedules'] });
      status.success(t('deleted'));
    },
  });

  const toggleMut = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      scheduleApi.update(id, { enabled }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['schedules'] }),
  });

  const triggerMut = useMutation({
    mutationFn: (id: string) => scheduleApi.trigger(id),
    onSuccess: (_, id) => {
      setTriggeringId(null);
      queryClient.invalidateQueries({ queryKey: ['schedules'] });
      queryClient.invalidateQueries({ queryKey: ['schedule-runs', id] });
      status.success(t('triggered'));
    },
    onError: (e: Error) => {
      setTriggeringId(null);
      status.error(e.message);
    },
  });

  const editMut = useMutation({
    mutationFn: ({ id, updates }: { id: string; updates: Partial<Schedule> }) =>
      scheduleApi.update(id, updates),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['schedules'] });
      setEditingSchedule(null);
      status.success(t('common:saved'));
    },
    onError: (e: Error) => status.error(e.message),
  });

  const handleEditSave = () => {
    if (!editingSchedule) return;
    const params: Record<string, unknown> = {};
    if (editAction === 'start_project' && editParam) params.name = editParam;
    if (editAction === 'shell' && editParam) params.command = editParam;
    const alert: AlertConfig | undefined =
      (editAlertOnAnyFail || editAlertOnOffline || editAlertWebhook)
        ? {
            on_any_fail: editAlertOnAnyFail,
            on_device_offline: editAlertOnOffline,
            webhook_url: editAlertWebhook || undefined,
            fail_rate_threshold: editAlertFailRate ? parseFloat(editAlertFailRate) : undefined,
          }
        : undefined;
    editMut.mutate({
      id: editingSchedule.id,
      updates: {
        name: editName,
        cron_expr: editCron,
        enabled: editEnabled,
        action: editAction,
        params,
        device_ids: [...editDeviceIds],
        alert,
        batch_size:     editBatchSize   ? parseInt(editBatchSize)   : undefined,
        batch_delay_ms: editBatchDelay  ? parseInt(editBatchDelay)  : undefined,
        timeout_secs:   editTimeoutSecs ? parseInt(editTimeoutSecs) : undefined,
        retry_count:    editRetryCount  ? parseInt(editRetryCount)  : undefined,
      },
    });
  };

  const openEdit = (s: Schedule) => {
    setEditingSchedule(s);
    setEditName(s.name);
    setEditCron(s.cron_expr);
    setEditEnabled(s.enabled);
    setEditAction(s.action);
    // reconstruct param from stored params object
    const p = s.params as Record<string, unknown>;
    setEditParam(
      s.action === 'start_project' ? String(p?.name ?? '') :
      s.action === 'shell'         ? String(p?.command ?? '') : ''
    );
    setEditDeviceIds(new Set(s.device_ids));
    setEditShowAdvanced(false);
    setEditShowAlert(false);
    setEditBatchSize(s.batch_size != null ? String(s.batch_size) : '');
    setEditBatchDelay(s.batch_delay_ms != null ? String(s.batch_delay_ms) : '');
    setEditTimeoutSecs(s.timeout_secs != null ? String(s.timeout_secs) : '');
    setEditRetryCount(s.retry_count != null ? String(s.retry_count) : '');
    setEditAlertOnAnyFail(s.alert?.on_any_fail ?? false);
    setEditAlertOnOffline(s.alert?.on_device_offline ?? false);
    setEditAlertWebhook(s.alert?.webhook_url ?? '');
    setEditAlertFailRate(s.alert?.fail_rate_threshold != null ? String(s.alert.fail_rate_threshold) : '');
  };

  const handleTrigger = (id: string) => {
    if (triggeringId) return; // prevent concurrent triggers
    setTriggeringId(id);
    triggerMut.mutate(id);
  };

  const schedules = data?.schedules ?? [];
  const needsParam = formAction === 'start_project' || formAction === 'shell';
  const paramPlaceholder = formAction === 'start_project' ? t('projectName') : formAction === 'shell' ? t('shellCommand') : '';
  const canCreate = formName.trim() && formCron.trim() && selectedDeviceIds.size > 0
    && (!needsParam || formParam.trim());

  const onlineDevices = devices.filter(d => d.online);
  const offlineSelected = [...selectedDeviceIds].filter(id => devices.find(d => d.imei === id && !d.online)).length;

  return (
    <div className="p-6 space-y-4 animate-in">
      {/* Edit modal — full parity with create form */}
      {editingSchedule && (() => {
        const editNeedsParam = editAction === 'start_project' || editAction === 'shell';
        const editParamPlaceholder = editAction === 'start_project' ? t('projectName') : editAction === 'shell' ? t('shellCommand') : '';
        const editOnlineDevices = devices.filter(d => d.online);
        const editOfflineSelected = [...editDeviceIds].filter(id => devices.find(d => d.imei === id && !d.online)).length;
        const canSave = editName.trim() && editCron.trim() && editDeviceIds.size > 0
          && (!editNeedsParam || editParam.trim()) && !editMut.isPending;
        return (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-4">
            <div className="card w-full max-w-lg max-h-[90vh] overflow-y-auto space-y-4 shadow-xl">
              <div className="flex items-center justify-between sticky top-0 bg-surface z-10 pb-2 border-b border-divider">
                <h3 className="text-sm font-semibold text-text-primary">{t('editTask')} — {editingSchedule.name}</h3>
                <button onClick={() => setEditingSchedule(null)} className="btn-ghost p-1 text-text-muted hover:text-text-primary">
                  <X size={16} />
                </button>
              </div>

              {/* Name */}
              <div>
                <label className="text-[10px] text-text-hint block mb-1">{t('taskName')}</label>
                <input value={editName} onChange={e => setEditName(e.target.value)} className="input" />
              </div>

              {/* Cron */}
              <div>
                <label className="text-[10px] text-text-hint block mb-1">Cron</label>
                <CronBuilder value={editCron} onChange={setEditCron} />
              </div>

              {/* Action + param */}
              <div className="grid grid-cols-2 gap-3">
                <select value={editAction} onChange={e => { setEditAction(e.target.value); setEditParam(''); }} className="input">
                  <option value="start_project">{t('startProject')}</option>
                  <option value="stop_project">{t('stopProject')}</option>
                  <option value="shell">{t('executeShell')}</option>
                  <option value="reboot_engine">{t('rebootEngine')}</option>
                </select>
                {editNeedsParam && (
                  <input value={editParam} onChange={e => setEditParam(e.target.value)}
                    placeholder={editParamPlaceholder} className="input font-mono" />
                )}
              </div>

              {/* Enabled */}
              <label className="flex items-center gap-2 text-xs text-text-muted cursor-pointer">
                <input type="checkbox" checked={editEnabled} onChange={e => setEditEnabled(e.target.checked)} className="h-3 w-3 accent-brand" />
                {t('colEnabled')}
              </label>

              {/* Device selector */}
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs text-text-muted">
                    {t('targetDevices')}
                    <span className="text-text-hint ml-1">({t('devicesSelected', { count: editDeviceIds.size })})</span>
                    {editOfflineSelected > 0 && (
                      <span className="ml-2 text-warning text-[10px]">⚠ {editOfflineSelected} {t('common:offline')}</span>
                    )}
                  </span>
                  <div className="flex gap-2">
                    <button type="button"
                      onClick={() => setEditDeviceIds(new Set(editOnlineDevices.map(d => d.imei)))}
                      className="text-[10px] text-success hover:underline">
                      {t('common:selectAll')} ({t('common:online')})
                    </button>
                    <button type="button"
                      onClick={() => setEditDeviceIds(new Set())}
                      className="text-[10px] text-text-hint hover:text-text-muted">
                      {t('common:deselectAll')}
                    </button>
                  </div>
                </div>
                {devices.length === 0 ? (
                  <div className="flex items-center gap-2 text-xs text-text-hint py-2">
                    <Info size={14} /> {t('noOnlineDevices')}
                  </div>
                ) : (
                  <div className="grid grid-cols-3 sm:grid-cols-4 gap-1.5 max-h-32 overflow-auto">
                    {[...editOnlineDevices, ...devices.filter(d => !d.online)].map((d) => (
                      <label key={d.imei}
                        className={`flex items-center gap-2 rounded-[3px] px-2 py-1.5 text-xs cursor-pointer transition-colors ${
                          !d.online ? 'opacity-50' : ''} ${
                          editDeviceIds.has(d.imei)
                            ? 'bg-accent-blue-bg text-brand border border-brand/30'
                            : 'bg-hover text-text-muted border border-divider hover:border-brand/30'}`}>
                        <input type="checkbox" checked={editDeviceIds.has(d.imei)}
                          onChange={() => {
                            const next = new Set(editDeviceIds);
                            if (next.has(d.imei)) next.delete(d.imei); else next.add(d.imei);
                            setEditDeviceIds(next);
                          }}
                          className="h-3 w-3 rounded accent-brand" />
                        <span className="truncate">{d.model || d.imei.slice(0, 8)}</span>
                        {!d.online && <span className="text-[9px] text-danger">✕</span>}
                      </label>
                    ))}
                  </div>
                )}
              </div>

              {/* Advanced settings */}
              <div className="space-y-2">
                <button type="button"
                  onClick={() => setEditShowAdvanced(!editShowAdvanced)}
                  className="flex items-center gap-1.5 text-xs text-text-muted hover:text-text-primary transition-colors">
                  <Settings2 size={12} />
                  {t('advancedSettings')}
                  {editShowAdvanced ? <ChevronDown size={10} /> : <ChevronRight size={10} />}
                </button>
                {editShowAdvanced && (
                  <div className="grid grid-cols-2 gap-3 pl-4 border-l border-divider">
                    <div>
                      <label className="text-[10px] text-text-hint block mb-1">{t('batchSize')}</label>
                      <input value={editBatchSize} onChange={e => setEditBatchSize(e.target.value)}
                        type="number" min="1" placeholder="—" className="input text-xs" />
                    </div>
                    <div>
                      <label className="text-[10px] text-text-hint block mb-1">{t('batchDelay')}</label>
                      <input value={editBatchDelay} onChange={e => setEditBatchDelay(e.target.value)}
                        type="number" min="0" placeholder="—" className="input text-xs" />
                    </div>
                    <div>
                      <label className="text-[10px] text-text-hint block mb-1">{t('timeoutSecs')}</label>
                      <input value={editTimeoutSecs} onChange={e => setEditTimeoutSecs(e.target.value)}
                        type="number" min="1" placeholder="60" className="input text-xs" />
                    </div>
                    <div>
                      <label className="text-[10px] text-text-hint block mb-1">{t('retryCount')}</label>
                      <input value={editRetryCount} onChange={e => setEditRetryCount(e.target.value)}
                        type="number" min="0" max="3" placeholder="0" className="input text-xs" />
                    </div>
                  </div>
                )}
              </div>

              {/* Alert config */}
              <div className="space-y-2">
                <button type="button"
                  onClick={() => setEditShowAlert(!editShowAlert)}
                  className="flex items-center gap-1.5 text-xs text-text-muted hover:text-text-primary transition-colors">
                  <Bell size={12} />
                  {t('alertConfig')}
                  {editShowAlert ? <ChevronDown size={10} /> : <ChevronRight size={10} />}
                </button>
                {editShowAlert && (
                  <div className="space-y-3 pl-4 border-l border-divider">
                    <label className="flex items-center gap-2 text-xs text-text-muted cursor-pointer">
                      <input type="checkbox" checked={editAlertOnAnyFail}
                        onChange={e => setEditAlertOnAnyFail(e.target.checked)}
                        className="h-3 w-3 accent-brand" />
                      {t('alertOnAnyFail')}
                    </label>
                    <label className="flex items-center gap-2 text-xs text-text-muted cursor-pointer">
                      <input type="checkbox" checked={editAlertOnOffline}
                        onChange={e => setEditAlertOnOffline(e.target.checked)}
                        className="h-3 w-3 accent-brand" />
                      {t('alertOnOffline')}
                    </label>
                    <div>
                      <label className="text-[10px] text-text-hint block mb-1">
                        {t('alertFailRate')} <span className="text-text-hint">({t('alertFailRateHint')})</span>
                      </label>
                      <input value={editAlertFailRate} onChange={e => setEditAlertFailRate(e.target.value)}
                        type="number" step="0.1" min="0" max="1" placeholder="0.5" className="input text-xs" />
                    </div>
                    <div>
                      <label className="text-[10px] text-text-hint block mb-1">
                        {t('alertWebhook')} <span className="text-text-hint">({t('alertWebhookHint')})</span>
                      </label>
                      <input value={editAlertWebhook} onChange={e => setEditAlertWebhook(e.target.value)}
                        type="url" placeholder="https://…" className="input text-xs" />
                    </div>
                  </div>
                )}
              </div>

              <div className="flex gap-2 pt-1">
                <button disabled={!canSave} onClick={handleEditSave} className="btn-primary">
                  {editMut.isPending ? <Loader2 size={14} className="animate-spin" /> : t('common:save')}
                </button>
                <button onClick={() => setEditingSchedule(null)} className="btn-secondary">{t('common:cancel')}</button>
              </div>
            </div>
          </div>
        );
      })()}

      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold text-text-primary">{t('title')}</h1>
        <button onClick={() => setShowForm(true)} className="btn-primary">
          <Plus size={16} /> {t('newTask')}
        </button>
      </div>

      {/* Create form */}
      {showForm && (
        <div className="card space-y-4">
          <h3 className="text-sm font-medium text-text-primary">{t('createTask')}</h3>

          <input
            value={formName}
            onChange={(e) => setFormName(e.target.value)}
            placeholder={t('taskName')}
            className="input"
          />

          <CronBuilder value={formCron} onChange={setFormCron} />

          <div className="grid grid-cols-2 gap-3">
            <select
              value={formAction}
              onChange={(e) => { setFormAction(e.target.value); setFormParam(''); }}
              className="input"
            >
              <option value="start_project">{t('startProject')}</option>
              <option value="stop_project">{t('stopProject')}</option>
              <option value="shell">{t('executeShell')}</option>
              <option value="reboot_engine">{t('rebootEngine')}</option>
            </select>
            {needsParam && (
              <input
                value={formParam}
                onChange={(e) => setFormParam(e.target.value)}
                placeholder={paramPlaceholder}
                className="input font-mono"
              />
            )}
          </div>

          {/* Device selector — online devices first, offline dimmed */}
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-xs text-text-muted">
                {t('targetDevices')}
                <span className="text-text-hint ml-1">({t('devicesSelected', { count: selectedDeviceIds.size })})</span>
                {offlineSelected > 0 && (
                  <span className="ml-2 text-warning text-[10px]">
                    ⚠ {offlineSelected} {t('common:offline')}
                  </span>
                )}
              </span>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => setSelectedDeviceIds(new Set(onlineDevices.map(d => d.imei)))}
                  className="text-[10px] text-success hover:underline"
                >
                  {t('common:selectAll')} ({t('common:online')})
                </button>
                <button
                  type="button"
                  onClick={() => setSelectedDeviceIds(new Set())}
                  className="text-[10px] text-text-hint hover:text-text-muted"
                >
                  {t('common:deselectAll')}
                </button>
              </div>
            </div>
            {devices.length === 0 ? (
              <div className="flex items-center gap-2 text-xs text-text-hint py-2">
                <Info size={14} /> {t('noOnlineDevices')}
              </div>
            ) : (
              <div className="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-6 gap-1.5 max-h-40 overflow-auto">
                {/* Online devices first */}
                {[...onlineDevices, ...devices.filter(d => !d.online)].map((d) => (
                  <label
                    key={d.imei}
                    className={`flex items-center gap-2 rounded-[3px] px-2 py-1.5 text-xs cursor-pointer transition-colors duration-150 ${
                      !d.online ? 'opacity-50' : ''
                    } ${
                      selectedDeviceIds.has(d.imei)
                        ? 'bg-accent-blue-bg text-brand border border-brand/30'
                        : 'bg-hover text-text-muted border border-divider hover:border-brand/30'
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={selectedDeviceIds.has(d.imei)}
                      onChange={() => {
                        const next = new Set(selectedDeviceIds);
                        if (next.has(d.imei)) next.delete(d.imei); else next.add(d.imei);
                        setSelectedDeviceIds(next);
                      }}
                      className="h-3 w-3 rounded accent-brand"
                    />
                    <span className="truncate">{d.model || d.imei.slice(0, 8)}</span>
                    {!d.online && <span className="text-[9px] text-danger">✕</span>}
                  </label>
                ))}
              </div>
            )}
          </div>

          {/* Advanced settings toggle */}
          <div className="space-y-2">
            <button
              type="button"
              onClick={() => setShowAdvanced(!showAdvanced)}
              className="flex items-center gap-1.5 text-xs text-text-muted hover:text-text-primary transition-colors"
            >
              <Settings2 size={12} />
              {t('advancedSettings')}
              {showAdvanced ? <ChevronDown size={10} /> : <ChevronRight size={10} />}
            </button>
            {showAdvanced && (
              <div className="grid grid-cols-2 gap-3 pl-4 border-l border-divider">
                <div>
                  <label className="text-[10px] text-text-hint block mb-1">{t('batchSize')}</label>
                  <input value={batchSize} onChange={e => setBatchSize(e.target.value)}
                    type="number" min="1" placeholder="—" className="input text-xs" />
                </div>
                <div>
                  <label className="text-[10px] text-text-hint block mb-1">{t('batchDelay')}</label>
                  <input value={batchDelay} onChange={e => setBatchDelay(e.target.value)}
                    type="number" min="0" placeholder="—" className="input text-xs" />
                </div>
                <div>
                  <label className="text-[10px] text-text-hint block mb-1">{t('timeoutSecs')}</label>
                  <input value={timeoutSecs} onChange={e => setTimeoutSecs(e.target.value)}
                    type="number" min="1" placeholder="60" className="input text-xs" />
                </div>
                <div>
                  <label className="text-[10px] text-text-hint block mb-1">{t('retryCount')}</label>
                  <input value={retryCount} onChange={e => setRetryCount(e.target.value)}
                    type="number" min="0" max="3" placeholder="0" className="input text-xs" />
                </div>
              </div>
            )}
          </div>

          {/* Alert config toggle */}
          <div className="space-y-2">
            <button
              type="button"
              onClick={() => setShowAlertConfig(!showAlertConfig)}
              className="flex items-center gap-1.5 text-xs text-text-muted hover:text-text-primary transition-colors"
            >
              <Bell size={12} />
              {t('alertConfig')}
              {showAlertConfig ? <ChevronDown size={10} /> : <ChevronRight size={10} />}
            </button>
            {showAlertConfig && (
              <div className="space-y-3 pl-4 border-l border-divider">
                <label className="flex items-center gap-2 text-xs text-text-muted cursor-pointer">
                  <input type="checkbox" checked={alertOnAnyFail}
                    onChange={e => setAlertOnAnyFail(e.target.checked)}
                    className="h-3 w-3 accent-brand" />
                  {t('alertOnAnyFail')}
                </label>
                <label className="flex items-center gap-2 text-xs text-text-muted cursor-pointer">
                  <input type="checkbox" checked={alertOnOffline}
                    onChange={e => setAlertOnOffline(e.target.checked)}
                    className="h-3 w-3 accent-brand" />
                  {t('alertOnOffline')}
                </label>
                <div>
                  <label className="text-[10px] text-text-hint block mb-1">
                    {t('alertFailRate')} <span className="text-text-hint">({t('alertFailRateHint')})</span>
                  </label>
                  <input value={alertFailRate} onChange={e => setAlertFailRate(e.target.value)}
                    type="number" step="0.1" min="0" max="1" placeholder="0.5" className="input text-xs" />
                </div>
                <div>
                  <label className="text-[10px] text-text-hint block mb-1">
                    {t('alertWebhook')} <span className="text-text-hint">({t('alertWebhookHint')})</span>
                  </label>
                  <input value={alertWebhook} onChange={e => setAlertWebhook(e.target.value)}
                    type="url" placeholder="https://…" className="input text-xs" />
                </div>
              </div>
            )}
          </div>

          <div className="flex gap-2">
            <button
              onClick={() => createMut.mutate()}
              disabled={!canCreate || createMut.isPending}
              className="btn-primary"
            >
              {createMut.isPending ? t('common:creating') : t('common:create')}
            </button>
            <button onClick={resetForm} className="btn-secondary">
              {t('common:cancel')}
            </button>
            {!canCreate && formName && (
              <span className="text-[10px] text-warning self-center">
                {!formCron ? t('fillCron') : selectedDeviceIds.size === 0 ? t('selectDevice')
                  : needsParam && !formParam.trim() ? t('fillParam', { param: paramPlaceholder }) : ''}
              </span>
            )}
          </div>
        </div>
      )}

      {/* Table */}
      <div className="card overflow-hidden p-0">
        {isLoading ? (
          <div className="flex items-center justify-center py-20 text-text-muted">{t('common:loading')}</div>
        ) : schedules.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-20 text-text-muted">
            <Calendar size={40} className="mb-3 opacity-30" />
            <p>{t('noTasks')}</p>
            <p className="mt-1 text-xs text-text-hint">{t('noTasksHint')}</p>
          </div>
        ) : (
          <table className="w-full text-left">
            <thead>
              <tr className="border-b border-divider bg-hover">
                <th className="px-4 py-3 text-xs font-medium text-text-muted">{t('colName')}</th>
                <th className="px-4 py-3 text-xs font-medium text-text-muted">Cron</th>
                <th className="px-4 py-3 text-xs font-medium text-text-muted">{t('colAction')}</th>
                <th className="px-4 py-3 text-xs font-medium text-text-muted">{t('colDevices')}</th>
                <th className="px-4 py-3 text-xs font-medium text-text-muted">{t('colEnabled')}</th>
                <th className="px-4 py-3 text-xs font-medium text-text-muted">{t('colLastRun')}</th>
                <th className="px-4 py-3 text-xs font-medium text-text-muted w-24"></th>
              </tr>
            </thead>
            <tbody>
              {schedules.map((s: Schedule) => (
                <ScheduleRow
                  key={s.id}
                  schedule={s}
                  onDelete={(id) => deleteMut.mutate(id)}
                  onToggle={(id, enabled) => toggleMut.mutate({ id, enabled })}
                  onTrigger={handleTrigger}
                  onEdit={openEdit}
                  triggeringId={triggeringId}
                />
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
