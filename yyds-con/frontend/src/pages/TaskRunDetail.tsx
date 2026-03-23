import { useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import {
  ArrowLeft, CheckCircle2, XCircle, WifiOff, Clock, Loader2,
  ChevronDown, ChevronRight, BarChart3, Zap, Timer,
} from 'lucide-react';
import { taskRunApi } from '@/services/api';
import type { DeviceResult, TaskRun } from '@/types';

function StatusBadge({ status }: { status: string }) {
  const { t } = useTranslation('schedule');
  const cfg: Record<string, { label: string; cls: string }> = {
    done:         { label: t('statusDone'),        cls: 'bg-success/10 text-success border border-success/20' },
    partial_fail: { label: t('statusPartialFail'), cls: 'bg-warning/10 text-warning border border-warning/20' },
    all_fail:     { label: t('statusAllFail'),     cls: 'bg-danger/10 text-danger border border-danger/20' },
    running:      { label: t('statusRunning'),     cls: 'bg-brand/10 text-brand border border-brand/20' },
  };
  const { label, cls } = cfg[status] ?? { label: status, cls: 'bg-hover text-text-muted border border-divider' };
  return <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium ${cls}`}>{label}</span>;
}

function DeviceStatusIcon({ status }: { status: string }) {
  if (status === 'success') return <CheckCircle2 size={14} className="text-success shrink-0" />;
  if (status === 'offline') return <WifiOff size={14} className="text-text-hint shrink-0" />;
  if (status === 'timeout') return <Clock size={14} className="text-warning shrink-0" />;
  return <XCircle size={14} className="text-danger shrink-0" />;  // failed
}

function DeviceRow({ result }: { result: DeviceResult }) {
  const [expanded, setExpanded] = useState(false);
  const hasOutput = result.output && result.output.trim().length > 0;

  const statusCls: Record<string, string> = {
    success: 'border-success/10 bg-success/5',
    offline: 'border-divider-light bg-hover',
    timeout: 'border-warning/10 bg-warning/5',
    failed:  'border-danger/10 bg-danger/5',
  };

  return (
    <div className={`rounded border ${statusCls[result.status] ?? 'border-divider bg-hover'} overflow-hidden`}>
      <button
        className="w-full flex items-center gap-3 px-3 py-2.5 text-left hover:opacity-80 transition-opacity"
        onClick={() => hasOutput && setExpanded(!expanded)}
        disabled={!hasOutput}
      >
        <DeviceStatusIcon status={result.status} />
        <span className="flex-1 text-sm font-medium text-text-primary truncate">
          {result.model || result.imei}
        </span>
        <span className="text-[10px] text-text-hint font-mono">
          {result.imei.slice(0, 8)}…
        </span>
        <span className="text-xs text-text-muted ml-2">{result.duration_ms}ms</span>
        {hasOutput && (
          expanded ? <ChevronDown size={12} className="text-text-hint shrink-0" /> : <ChevronRight size={12} className="text-text-hint shrink-0" />
        )}
      </button>
      {expanded && hasOutput && (
        <div className="px-3 pb-3">
          <pre className="text-xs text-text-muted bg-background rounded p-2 overflow-auto max-h-40 whitespace-pre-wrap break-all">
            {result.output}
          </pre>
        </div>
      )}
    </div>
  );
}

function SummaryBar({ run }: { run: TaskRun }) {
  const { t } = useTranslation('schedule');
  const pct = run.summary.total > 0
    ? Math.round((run.summary.success / run.summary.total) * 100)
    : 0;

  return (
    <div className="card space-y-4">
      {/* Header stats row */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <div className="flex flex-col gap-0.5">
          <span className="text-xs text-text-muted">{t('successRate')}</span>
          <span className="text-2xl font-bold text-text-primary">{pct}%</span>
        </div>
        <div className="flex flex-col gap-0.5">
          <span className="text-xs text-text-muted">{t('devices')}</span>
          <span className="text-2xl font-bold text-text-primary">{run.summary.total}</span>
        </div>
        <div className="flex flex-col gap-0.5">
          <span className="text-xs text-text-muted">{t('filterSuccess')}</span>
          <span className="text-2xl font-bold text-success">{run.summary.success}</span>
        </div>
        <div className="flex flex-col gap-0.5">
          <span className="text-xs text-text-muted">{t('filterFailed')} + {t('filterOffline')}</span>
          <span className="text-2xl font-bold text-danger">
            {run.summary.failed + run.summary.offline + run.summary.timeout}
          </span>
        </div>
      </div>

      {/* Progress bar */}
      <div className="h-2 rounded-full bg-hover overflow-hidden">
        <div
          className="h-full rounded-full bg-success transition-all duration-500"
          style={{ width: `${pct}%` }}
        />
      </div>

      {/* Meta row */}
      <div className="flex flex-wrap items-center gap-4 text-xs text-text-muted">
        <span className="flex items-center gap-1">
          <Zap size={12} />
          {run.trigger_type === 'manual' ? t('triggerManual') : t('triggerCron')}
        </span>
        <span className="flex items-center gap-1">
          <Timer size={12} />
          {run.duration_ms}ms
        </span>
        <span>{new Date(run.triggered_at).toLocaleString()}</span>
        <StatusBadge status={run.status} />
      </div>
    </div>
  );
}

export default function TaskRunDetail() {
  const { runId } = useParams<{ runId: string }>();
  const navigate = useNavigate();
  const { t } = useTranslation('schedule');
  const { t: tc } = useTranslation('common');
  const [filter, setFilter] = useState<string>('all');

  const { data, isLoading, error } = useQuery({
    queryKey: ['task-run', runId],
    queryFn: () => taskRunApi.get(runId!),
    refetchInterval: (query) => {
      const run = query.state.data?.run;
      return run?.status === 'running' ? 2000 : false;
    },
  });

  const run = data?.run;

  const filteredResults = (run?.device_results ?? []).filter((r) => {
    if (filter === 'all') return true;
    return r.status === filter;
  });

  const filterTabs = [
    { key: 'all',     label: t('filterAll') },
    { key: 'success', label: t('filterSuccess') },
    { key: 'failed',  label: t('filterFailed') },
    { key: 'offline', label: t('filterOffline') },
    { key: 'timeout', label: t('filterTimeout') },
  ];

  return (
    <div className="p-6 space-y-4 animate-in max-w-4xl mx-auto">
      {/* Header */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate(-1)}
          className="btn-ghost p-1.5 text-text-muted hover:text-text-primary"
        >
          <ArrowLeft size={18} />
        </button>
        <div>
          <h1 className="text-xl font-semibold text-text-primary">{t('runDetailTitle')}</h1>
          {run && (
            <p className="text-sm text-text-muted mt-0.5">
              <Link to="/schedules" className="hover:text-brand transition-colors">
                {run.schedule_name}
              </Link>
              <span className="mx-1 text-text-hint">/</span>
              <span className="font-mono text-xs">{run.id.slice(0, 8)}</span>
            </p>
          )}
        </div>
      </div>

      {isLoading && (
        <div className="flex items-center justify-center py-20 text-text-muted gap-2">
          <Loader2 size={18} className="animate-spin" />
          <span className="text-sm">{tc('loading')}</span>
        </div>
      )}

      {error && (
        <div className="card text-danger text-sm">
          {(error as Error).message}
        </div>
      )}

      {run && (
        <>
          {/* Summary */}
          <SummaryBar run={run} />

          {/* Filter tabs + device list */}
          <div className="card space-y-3">
            <div className="flex items-center gap-1 flex-wrap">
              <BarChart3 size={14} className="text-text-muted mr-1" />
              {filterTabs.map((tab) => {
                const count = tab.key === 'all'
                  ? run.device_results.length
                  : run.device_results.filter((r) => r.status === tab.key).length;
                return (
                  <button
                    key={tab.key}
                    onClick={() => setFilter(tab.key)}
                    className={`px-2.5 py-1 rounded text-xs font-medium transition-colors ${
                      filter === tab.key
                        ? 'bg-accent-blue-bg text-brand border border-brand/30'
                        : 'bg-hover text-text-muted border border-divider hover:border-brand/30'
                    }`}
                  >
                    {tab.label} {count > 0 && <span className="ml-0.5 opacity-70">({count})</span>}
                  </button>
                );
              })}
            </div>

            {filteredResults.length === 0 ? (
              <div className="text-sm text-text-hint text-center py-8">{t(filter === 'all' ? 'noRuns' : 'noResults')}</div>
            ) : (
              <div className="space-y-1.5">
                {filteredResults.map((r, idx) => (
                  <DeviceRow key={`${r.imei}-${idx}`} result={r} />
                ))}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}
