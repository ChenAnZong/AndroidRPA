import { useState, useEffect, useRef, useCallback } from 'react';
import { useParams, Link } from 'react-router-dom';
import {
  agentApi,
  type AgentRunSummary,
  type AgentRunDetail,
  type AgentStatus,
  type AgentConfig,
  type AgentProvider,
} from '@/services/api';
import {
  Play, Square, UserCheck, RotateCcw, Settings,
  Loader2, CheckCircle2, XCircle,
  Eye, EyeOff, FlaskConical, Save, X, Bot,
  ChevronDown,
} from 'lucide-react';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatTime(iso: string | null) {
  if (!iso) return '-';
  return new Date(iso).toLocaleString();
}

function formatDuration(ms: number) {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60000).toFixed(1)}min`;
}

// ─── Small badges ─────────────────────────────────────────────────────────────

function TokenBadge({ usage }: { usage?: { prompt_tokens: number; completion_tokens: number; total_tokens: number } }) {
  if (!usage || !usage.total_tokens) return null;
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs bg-violet-100 text-violet-700 dark:bg-violet-900/30 dark:text-violet-300">
      🪙 {usage.total_tokens.toLocaleString()}
    </span>
  );
}

function RunStatusBadge({ success }: { success: boolean | null }) {
  if (success === null)
    return <span className="px-2 py-0.5 rounded-full text-xs bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-300">运行中</span>;
  if (success)
    return <span className="px-2 py-0.5 rounded-full text-xs bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300">成功</span>;
  return <span className="px-2 py-0.5 rounded-full text-xs bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300">失败</span>;
}

function LogTypeBadge({ type }: { type: string }) {
  const colors: Record<string, string> = {
    thinking: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300',
    action: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300',
    success: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300',
    error: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300',
    tool_result: 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300',
    screenshot: 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300',
  };
  return (
    <span className={`px-1.5 py-0.5 rounded text-xs font-mono ${colors[type] || 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400'}`}>
      {type}
    </span>
  );
}

function AgentStatusBadge({ s }: { s: AgentStatus | null }) {
  if (!s) return null;
  if (s.takeover)
    return (
      <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-300">
        <UserCheck size={11} /> 人工接管中
      </span>
    );
  if (s.running)
    return (
      <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300">
        <Loader2 size={11} className="animate-spin" /> 运行中 · Step {s.current_step}/{s.max_steps}
      </span>
    );
  return (
    <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-gray-100 text-gray-500 dark:bg-gray-800 dark:text-gray-400">
      <Bot size={11} /> 空闲
    </span>
  );
}

// ─── Toggle switch (standalone, not nested inside <label>) ───────────────────

function Toggle({ checked, onChange }: { checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={() => onChange(!checked)}
      className={`relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/50 ${
        checked ? 'bg-brand' : 'bg-gray-300 dark:bg-gray-600'
      }`}
    >
      <span
        className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white shadow transition-transform ${
          checked ? 'translate-x-4' : 'translate-x-1'
        }`}
      />
    </button>
  );
}

// ─── Config Drawer ────────────────────────────────────────────────────────────

function ConfigDrawer({ imei, onClose }: { imei: string; onClose: () => void }) {
  const [config, setConfig] = useState<AgentConfig | null>(null);
  const [providers, setProviders] = useState<AgentProvider[]>([]);
  const [models, setModels] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; msg: string } | null>(null);
  const [showKey, setShowKey] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;

    async function loadAll() {
      setLoading(true);
      setError('');
      try {
        const [cfgRes, provRes] = await Promise.all([
          agentApi.getConfig(imei),
          agentApi.providers(imei).catch(() => ({ providers: [] as AgentProvider[] })),
        ]);
        if (cancelled) return;
        setConfig(cfgRes);
        const provList = provRes.providers || [];
        setProviders(provList);
        if (cfgRes.provider) {
          const mRes = await agentApi.models(imei, cfgRes.provider).catch(() => ({ models: [] as string[] }));
          if (!cancelled) setModels(mRes.models || []);
        }
      } catch (e: any) {
        if (!cancelled) setError(e.message);
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    loadAll();
    return () => { cancelled = true; };
  }, [imei]);

  const loadModels = useCallback(async (provider: string) => {
    setModels([]);
    try {
      const res = await agentApi.models(imei, provider);
      setModels(res.models || []);
    } catch {
      setModels([]);
    }
  }, [imei]);

  async function handleSave() {
    if (!config) return;
    setSaving(true);
    setError('');
    try {
      await agentApi.setConfig(imei, config);
      onClose();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setSaving(false);
    }
  }

  async function handleTestConnection() {
    if (!config) return;
    setTesting(true);
    setTestResult(null);
    try {
      const res = await agentApi.testConnection(imei, config);
      setTestResult({ ok: !!res.success, msg: (res.error as string) || '连接成功' });
    } catch (e: any) {
      setTestResult({ ok: false, msg: e.message });
    } finally {
      setTesting(false);
    }
  }

  function update(patch: Partial<AgentConfig>) {
    setConfig((c) => (c ? { ...c, ...patch } : c));
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-40 text-text-muted gap-2">
        <Loader2 size={18} className="animate-spin" /> 加载配置...
      </div>
    );
  }
  if (!config) {
    return (
      <div className="p-4">
        <div className="p-3 rounded bg-danger-bg text-danger text-sm">{error || '无法加载配置'}</div>
      </div>
    );
  }

  const providerInfo = providers.find((p) => p.id === config.provider);

  return (
    <div className="space-y-4 p-4">
      {error && (
        <div className="p-2 rounded bg-danger-bg text-danger text-sm">{error}</div>
      )}

      {/* Provider */}
      <div>
        <label className="block text-xs font-medium text-text-muted mb-1">AI 服务商</label>
        <div className="relative">
          <select
            value={config.provider}
            onChange={(e) => {
              update({ provider: e.target.value, model: '' });
              loadModels(e.target.value);
            }}
            className="w-full appearance-none input text-sm pr-8"
          >
            {providers.length > 0
              ? providers.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)
              : <option value={config.provider}>{config.provider}</option>
            }
          </select>
          <ChevronDown size={14} className="absolute right-2 top-1/2 -translate-y-1/2 text-text-hint pointer-events-none" />
        </div>
      </div>

      {/* Model */}
      <div>
        <label className="block text-xs font-medium text-text-muted mb-1">模型</label>
        {models.length > 0 ? (
          <div className="relative">
            <select
              value={config.model || providerInfo?.default_model || ''}
              onChange={(e) => update({ model: e.target.value })}
              className="w-full appearance-none input text-sm pr-8"
            >
              {models.map((m) => <option key={m} value={m}>{m}</option>)}
            </select>
            <ChevronDown size={14} className="absolute right-2 top-1/2 -translate-y-1/2 text-text-hint pointer-events-none" />
          </div>
        ) : (
          <input
            value={config.model}
            onChange={(e) => update({ model: e.target.value })}
            placeholder={providerInfo?.default_model || '输入模型名称'}
            className="input text-sm w-full"
          />
        )}
      </div>

      {/* API Key */}
      <div>
        <label className="block text-xs font-medium text-text-muted mb-1">API Key</label>
        <div className="relative">
          <input
            type={showKey ? 'text' : 'password'}
            value={config.api_key}
            onChange={(e) => update({ api_key: e.target.value })}
            placeholder="sk-..."
            className="input text-sm w-full pr-9 font-mono"
          />
          <button
            type="button"
            onClick={() => setShowKey((v) => !v)}
            className="absolute right-2 top-1/2 -translate-y-1/2 text-text-hint hover:text-text-primary transition-colors"
          >
            {showKey ? <EyeOff size={14} /> : <Eye size={14} />}
          </button>
        </div>
      </div>

      {/* Custom base_url */}
      {config.provider === 'custom' && (
        <div>
          <label className="block text-xs font-medium text-text-muted mb-1">Base URL</label>
          <input
            value={config.base_url}
            onChange={(e) => update({ base_url: e.target.value })}
            placeholder="https://api.example.com/v1"
            className="input text-sm w-full"
          />
        </div>
      )}

      {/* Test connection */}
      <div className="flex items-center gap-2 flex-wrap">
        <button
          type="button"
          onClick={handleTestConnection}
          disabled={testing || !config.api_key}
          className="btn-secondary text-xs py-1.5 flex items-center gap-1.5"
        >
          {testing ? <Loader2 size={12} className="animate-spin" /> : <FlaskConical size={12} />}
          测试连接
        </button>
        {testResult && (
          <span className={`text-xs flex items-center gap-1 ${testResult.ok ? 'text-success' : 'text-danger'}`}>
            {testResult.ok ? <CheckCircle2 size={12} /> : <XCircle size={12} />}
            {testResult.msg}
          </span>
        )}
      </div>

      <hr className="border-divider" />

      {/* Numeric params */}
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="block text-xs font-medium text-text-muted mb-1">最大步数</label>
          <input
            type="number" min={1} max={100}
            value={config.max_steps}
            onChange={(e) => update({ max_steps: Number(e.target.value) })}
            className="input text-sm w-full"
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-text-muted mb-1">Temperature</label>
          <input
            type="number" min={0} max={2} step={0.05}
            value={config.temperature}
            onChange={(e) => update({ temperature: Number(e.target.value) })}
            className="input text-sm w-full"
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-text-muted mb-1">Max Tokens</label>
          <input
            type="number" min={256} max={8192}
            value={config.max_tokens}
            onChange={(e) => update({ max_tokens: Number(e.target.value) })}
            className="input text-sm w-full"
          />
        </div>
      </div>

      {/* Boolean toggles — standalone, NOT inside <label> to avoid double-activation */}
      <div className="space-y-3">
        {(
          [
            { key: 'use_ui_dump' as const, label: '包含 UI 控件树' },
            { key: 'show_floating_window' as const, label: '悬浮窗显示执行过程' },
          ]
        ).map(({ key, label }) => (
          <div key={key} className="flex items-center justify-between">
            <span className="text-sm text-text-primary">{label}</span>
            <Toggle
              checked={!!config[key]}
              onChange={(v) => update({ [key]: v })}
            />
          </div>
        ))}
      </div>

      {/* Save */}
      <button
        type="button"
        onClick={handleSave}
        disabled={saving}
        className="btn-primary w-full text-sm flex items-center justify-center gap-2"
      >
        {saving ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
        保存配置
      </button>
    </div>
  );
}

// ─── Control Panel ─────────────────────────────────────────────────────────────

function ControlPanel({
  agentStatus,
  instruction,
  setInstruction,
  sending,
  isRunning,
  isTakeover,
  controlError,
  controlMsg,
  onRun,
  onStop,
  onTakeover,
  onResume,
}: {
  agentStatus: AgentStatus | null;
  instruction: string;
  setInstruction: (v: string) => void;
  sending: boolean;
  isRunning: boolean;
  isTakeover: boolean;
  controlError: string;
  controlMsg: string;
  onRun: () => void;
  onStop: () => void;
  onTakeover: () => void;
  onResume: () => void;
}) {
  return (
    <div className="p-4 max-w-3xl mx-auto space-y-4">
      {/* Status card */}
      <div className="card p-4 space-y-3">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-text-primary">当前状态</span>
          <AgentStatusBadge s={agentStatus} />
        </div>
        {agentStatus?.running && agentStatus.instruction && (
          <div className="text-sm">
            <span className="text-text-hint">执行指令：</span>
            <span className="text-text-primary">{agentStatus.instruction}</span>
          </div>
        )}
        {agentStatus?.running && (
          <div className="w-full bg-gray-200 dark:bg-gray-700 rounded-full h-1.5">
            <div
              className="bg-brand h-1.5 rounded-full transition-all duration-500"
              style={{ width: `${Math.min(100, (agentStatus.current_step / Math.max(1, agentStatus.max_steps)) * 100)}%` }}
            />
          </div>
        )}
        {agentStatus?.message && (
          <p className="text-xs text-text-muted">{agentStatus.message}</p>
        )}
      </div>

      {/* Feedback messages — mutually exclusive display */}
      {controlError ? (
        <div className="p-3 rounded-lg bg-danger-bg text-danger text-sm flex items-center gap-2">
          <XCircle size={14} /> {controlError}
        </div>
      ) : controlMsg ? (
        <div className="p-3 rounded-lg bg-success-bg text-success text-sm flex items-center gap-2">
          <CheckCircle2 size={14} /> {controlMsg}
        </div>
      ) : null}

      {/* Instruction input */}
      <div className="card p-4 space-y-3">
        <label htmlFor="agent-instruction" className="text-sm font-medium text-text-primary">
          发送 Agent 任务
        </label>
        <textarea
          id="agent-instruction"
          value={instruction}
          onChange={(e) => setInstruction(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) onRun();
          }}
          placeholder="描述你要完成的任务，例如：打开微信，搜索张三，发送消息"
          rows={3}
          disabled={isRunning}
          className="w-full input text-sm resize-none disabled:opacity-50"
        />
        <div className="flex gap-2 flex-wrap items-center">
          <button
            type="button"
            onClick={onRun}
            disabled={isRunning || sending || !instruction.trim()}
            className="btn-primary text-sm flex items-center gap-1.5"
          >
            {sending ? <Loader2 size={13} className="animate-spin" /> : <Play size={13} />}
            启动 Agent
          </button>
          {isRunning && !isTakeover && (
            <button type="button" onClick={onTakeover} className="btn-secondary text-sm flex items-center gap-1.5">
              <UserCheck size={13} /> 人工接管
            </button>
          )}
          {isTakeover && (
            <button type="button" onClick={onResume} className="btn-secondary text-sm flex items-center gap-1.5">
              <RotateCcw size={13} /> 恢复自动
            </button>
          )}
          {isRunning && (
            <button
              type="button"
              onClick={onStop}
              className="btn-danger text-sm flex items-center gap-1.5"
            >
              <Square size={13} /> 停止
            </button>
          )}
        </div>
        <p className="text-xs text-text-hint">提示：Ctrl+Enter 快速发送</p>
      </div>

      {/* Quick examples */}
      <div className="card p-4 space-y-2">
        <p className="text-xs font-medium text-text-muted">快捷示例</p>
        <div className="flex flex-wrap gap-2">
          {[
            '截屏并保存到 /sdcard/screenshot.png',
            '打开设置，查看当前 Wi-Fi 连接信息',
            '打开浏览器，搜索今日天气',
            '打开微信，查看最新消息',
          ].map((ex) => (
            <button
              key={ex}
              type="button"
              onClick={() => setInstruction(ex)}
              disabled={isRunning}
              className="text-xs px-2.5 py-1 rounded-full border border-divider text-text-muted hover:border-brand hover:text-brand transition-colors disabled:opacity-40"
            >
              {ex}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

// ─── History Panel ─────────────────────────────────────────────────────────────

function HistoryPanel({
  runs,
  selectedRun,
  loading,
  detailLoading,
  error,
  onSelectRun,
  onRefresh,
  onClear,
}: {
  runs: AgentRunSummary[];
  selectedRun: AgentRunDetail | null;
  loading: boolean;
  detailLoading: boolean;
  error: string;
  onSelectRun: (runId: string) => void;
  onRefresh: () => void;
  onClear: () => void;
}) {
  return (
    // Use absolute positioning to fill parent that has overflow-auto
    <div className="absolute inset-0 flex overflow-hidden">
      {/* Run list */}
      <div className="w-72 shrink-0 border-r border-divider flex flex-col overflow-hidden">
        <div className="flex items-center gap-2 px-3 py-2 border-b border-divider shrink-0">
          <span className="text-xs font-medium text-text-muted flex-1">运行记录 ({runs.length})</span>
          <button
            type="button"
            onClick={onRefresh}
            className="p-1 text-text-muted hover:text-text-primary rounded transition-colors"
            title="刷新"
          >
            <RotateCcw size={13} />
          </button>
          {runs.length > 0 && (
            <button
              type="button"
              onClick={onClear}
              className="p-1 text-red-400 hover:text-red-500 rounded transition-colors"
              title="清空历史"
            >
              <XCircle size={13} />
            </button>
          )}
        </div>
        <div className="flex-1 overflow-y-auto p-2 space-y-1">
          {error && <div className="text-xs text-danger p-2">{error}</div>}
          {loading ? (
            <div className="text-center py-8 text-text-hint">
              <Loader2 size={16} className="animate-spin inline-block" />
            </div>
          ) : runs.length === 0 ? (
            <div className="text-center py-8 text-text-hint text-sm">暂无运行记录</div>
          ) : (
            runs.map((run) => (
              <button
                key={run.run_id}
                type="button"
                onClick={() => onSelectRun(run.run_id)}
                className={`w-full text-left p-2.5 rounded-lg border transition-colors ${
                  selectedRun?.run_id === run.run_id
                    ? 'border-brand/50 bg-brand/5'
                    : 'border-divider hover:bg-hover'
                }`}
              >
                <div className="flex items-center justify-between mb-1">
                  <RunStatusBadge success={run.success} />
                  <span className="text-[10px] text-text-hint">{formatTime(run.started_at)}</span>
                </div>
                <p className="text-xs text-text-primary truncate">{run.instruction}</p>
                <div className="flex items-center gap-1.5 mt-1 text-[10px] text-text-muted">
                  <span>{run.total_steps} 步</span>
                  <span>·</span>
                  <span>{formatDuration(run.elapsed_ms)}</span>
                  <TokenBadge usage={run.token_usage} />
                </div>
              </button>
            ))
          )}
        </div>
      </div>

      {/* Detail pane */}
      <div className="flex-1 overflow-y-auto">
        {detailLoading ? (
          <div className="flex items-center justify-center h-40 text-text-hint">
            <Loader2 size={20} className="animate-spin" />
          </div>
        ) : selectedRun ? (
          <RunDetailPanel run={selectedRun} />
        ) : (
          <div className="flex flex-col items-center justify-center h-40 text-text-hint gap-2">
            <Bot size={28} className="opacity-30" />
            <span className="text-sm">选择一条运行记录查看详情</span>
          </div>
        )}
      </div>
    </div>
  );
}

// ─── Run Detail Panel ─────────────────────────────────────────────────────────

function RunDetailPanel({ run }: { run: AgentRunDetail }) {
  const [tab, setTab] = useState<'logs' | 'steps'>('logs');

  return (
    <div>
      <div className="px-4 py-3 border-b border-divider bg-hover/30">
        <div className="flex items-center gap-2 mb-1.5 flex-wrap">
          <RunStatusBadge success={run.success} />
          <span className="text-sm font-medium text-text-primary">{run.instruction}</span>
        </div>
        <div className="flex flex-wrap gap-3 text-xs text-text-muted">
          <span>开始: {formatTime(run.started_at)}</span>
          <span>结束: {formatTime(run.finished_at)}</span>
          <span>{run.total_steps} 步 · {formatDuration(run.elapsed_ms)}</span>
          {(run.token_usage?.total_tokens ?? 0) > 0 && (
            <span>
              🪙 {run.token_usage.prompt_tokens.toLocaleString()} in
              / {run.token_usage.completion_tokens.toLocaleString()} out
              / {run.token_usage.total_tokens.toLocaleString()} total
            </span>
          )}
        </div>
      </div>

      <div className="flex border-b border-divider">
        {(['logs', 'steps'] as const).map((t) => (
          <button
            key={t}
            type="button"
            onClick={() => setTab(t)}
            className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
              tab === t ? 'border-brand text-brand' : 'border-transparent text-text-muted hover:text-text-primary'
            }`}
          >
            {t === 'logs' ? `日志 (${run.logs?.length ?? 0})` : `步骤 (${run.steps?.length ?? 0})`}
          </button>
        ))}
      </div>

      {tab === 'logs' ? <LogsTab logs={run.logs ?? []} /> : <StepsTab steps={run.steps ?? []} />}
    </div>
  );
}

function LogsTab({ logs }: { logs: AgentRunDetail['logs'] }) {
  if (!logs.length) return <div className="p-8 text-center text-text-hint text-sm">无日志</div>;
  return (
    <div className="divide-y divide-divider">
      {logs.map((log, i) => (
        <div key={i} className="px-4 py-2.5 hover:bg-hover/40">
          <div className="flex items-center gap-2 mb-0.5 flex-wrap">
            {log.step > 0 && <span className="text-[10px] text-text-hint font-mono">#{log.step}</span>}
            <LogTypeBadge type={log.type} />
            <span className="text-sm text-text-primary">{log.title}</span>
            <TokenBadge usage={log.token_usage} />
            <span className="ml-auto text-[10px] text-text-hint shrink-0">{formatTime(log.timestamp)}</span>
          </div>
          {log.detail && (
            <pre className="mt-1 text-xs text-text-muted whitespace-pre-wrap break-all max-h-32 overflow-y-auto">
              {log.detail}
            </pre>
          )}
        </div>
      ))}
    </div>
  );
}

function StepsTab({ steps }: { steps: AgentRunDetail['steps'] }) {
  if (!steps.length) return <div className="p-8 text-center text-text-hint text-sm">无步骤记录</div>;
  return (
    <div className="divide-y divide-divider">
      {steps.map((step, i) => (
        <div key={i} className="px-4 py-3 hover:bg-hover/40">
          <div className="flex items-center gap-2 mb-1 flex-wrap">
            <span className="text-[10px] font-mono text-text-hint">Step {step.step}</span>
            <span className={`px-1.5 py-0.5 rounded text-[10px] ${
              step.success
                ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300'
                : 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
            }`}>
              {step.success ? '✓' : '✗'}
            </span>
            <span className="text-sm font-medium text-text-primary">{step.action_desc}</span>
          </div>
          {step.thought && (
            <p className="text-xs text-blue-500 dark:text-blue-400 mb-1">💭 {step.thought}</p>
          )}
          {step.description && (
            <p className="text-xs text-text-muted">{step.description}</p>
          )}
        </div>
      ))}
    </div>
  );
}

// ─── Main Page ────────────────────────────────────────────────────────────────

export default function AgentControl() {
  const { imei } = useParams<{ imei: string }>();

  // Agent status
  const [agentStatus, setAgentStatus] = useState<AgentStatus | null>(null);

  // Control state
  const [instruction, setInstruction] = useState('');
  const [sending, setSending] = useState(false);
  const [controlError, setControlError] = useState('');
  const [controlMsg, setControlMsg] = useState('');

  // Config drawer
  const [configOpen, setConfigOpen] = useState(false);

  // History state
  const [runs, setRuns] = useState<AgentRunSummary[]>([]);
  const [historyLoaded, setHistoryLoaded] = useState(false);   // loaded at least once
  const [selectedRun, setSelectedRun] = useState<AgentRunDetail | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [historyError, setHistoryError] = useState('');

  // Tab selection
  const [mainTab, setMainTab] = useState<'control' | 'history'>('control');

  // ── Polling — single interval, managed with useCallback + ref ──────────────
  // We use a ref to always capture the latest imei/setAgentStatus without
  // stale closures, and a single effect that rebuilds the interval when
  // the desired frequency changes.
  const imeiRef = useRef(imei);
  imeiRef.current = imei;

  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const isRunningRef = useRef(false);

  const pollStatus = useCallback(async () => {
    const id = imeiRef.current;
    if (!id) return;
    try {
      const s = await agentApi.status(id);
      isRunningRef.current = s.running;
      setAgentStatus(s);
    } catch {
      // silently ignore transient network errors
    }
  }, []); // stable — no captured state, uses refs

  // Single effect: rebuilds interval whenever desired frequency changes.
  // Frequency is driven by a local state that mirrors running status.
  const [pollFast, setPollFast] = useState(false);

  useEffect(() => {
    // sync fast-poll state when agentStatus changes
    setPollFast(agentStatus?.running ?? false);
  }, [agentStatus?.running]);

  useEffect(() => {
    if (!imei) return;
    // Immediate first poll
    pollStatus();
    const interval = pollFast ? 1000 : 3000;
    if (pollRef.current) clearInterval(pollRef.current);
    pollRef.current = setInterval(pollStatus, interval);
    return () => {
      if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null; }
    };
  }, [imei, pollFast, pollStatus]);

  // ── History loading (lazy: only on first tab open or explicit refresh) ──────
  const loadHistory = useCallback(async () => {
    const id = imeiRef.current;
    if (!id) return;
    setHistoryLoading(true);
    setHistoryError('');
    try {
      const res = await agentApi.history(id);
      setRuns(res.runs || []);
      setHistoryLoaded(true);
    } catch (e: any) {
      setHistoryError(e.message);
    } finally {
      setHistoryLoading(false);
    }
  }, []); // stable

  const loadDetail = useCallback(async (runId: string) => {
    const id = imeiRef.current;
    if (!id) return;
    setDetailLoading(true);
    try {
      const res = await agentApi.detail(id, runId);
      setSelectedRun(res);
    } catch (e: any) {
      setHistoryError(e.message);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  // ── msg auto-clear ──────────────────────────────────────────────────────────
  const msgTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  function showMsg(msg: string) {
    setControlError('');
    setControlMsg(msg);
    if (msgTimerRef.current) clearTimeout(msgTimerRef.current);
    msgTimerRef.current = setTimeout(() => setControlMsg(''), 4000);
  }
  function showErr(err: string) {
    setControlMsg('');
    setControlError(err);
    if (msgTimerRef.current) clearTimeout(msgTimerRef.current);
    msgTimerRef.current = setTimeout(() => setControlError(''), 6000);
  }
  useEffect(() => () => { if (msgTimerRef.current) clearTimeout(msgTimerRef.current); }, []);

  // ── Control actions ─────────────────────────────────────────────────────────
  async function handleRun() {
    const id = imeiRef.current;
    if (!instruction.trim() || !id) return;
    setSending(true);
    try {
      await agentApi.run(id, instruction.trim());
      showMsg('Agent 已启动');
      setInstruction('');
      pollStatus();
    } catch (e: any) {
      showErr(e.message);
    } finally {
      setSending(false);
    }
  }

  async function handleStop() {
    const id = imeiRef.current;
    if (!id) return;
    try {
      await agentApi.stop(id);
      showMsg('已发送停止指令');
      pollStatus();
    } catch (e: any) {
      showErr(e.message);
    }
  }

  async function handleTakeover() {
    const id = imeiRef.current;
    if (!id) return;
    try {
      await agentApi.takeover(id);
      showMsg('已切换为人工接管模式');
      pollStatus();
    } catch (e: any) {
      showErr(e.message);
    }
  }

  async function handleResume() {
    const id = imeiRef.current;
    if (!id) return;
    try {
      await agentApi.resume(id);
      showMsg('Agent 已恢复自动执行');
      pollStatus();
    } catch (e: any) {
      showErr(e.message);
    }
  }

  async function handleClearHistory() {
    const id = imeiRef.current;
    if (!id || !confirm('确定清空所有 Agent 运行历史？')) return;
    try {
      await agentApi.clearHistory(id);
      setRuns([]);
      setSelectedRun(null);
    } catch (e: any) {
      setHistoryError(e.message);
    }
  }

  // Switch to history tab: lazy-load on first visit only
  function handleHistoryTabClick() {
    setMainTab('history');
    if (!historyLoaded) loadHistory();
  }

  const isRunning = agentStatus?.running ?? false;
  const isTakeover = agentStatus?.takeover ?? false;

  return (
    <div className="flex h-full overflow-hidden">
      {/* ── Main ── */}
      <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
        {/* Header */}
        <div className="flex items-center gap-3 px-4 py-3 border-b border-divider shrink-0 bg-card-bg">
          <Link to={`/devices/${imei}`} className="text-brand hover:opacity-80 text-sm shrink-0">← 返回</Link>
          <Bot size={16} className="text-brand shrink-0" />
          <h1 className="text-base font-semibold text-text-primary truncate">Agent 控制台</h1>
          <span className="text-xs text-text-hint font-mono truncate hidden sm:block">{imei}</span>
          <div className="ml-auto flex items-center gap-2 shrink-0">
            <AgentStatusBadge s={agentStatus} />
            <button
              type="button"
              onClick={() => setConfigOpen((v) => !v)}
              className={`p-1.5 rounded transition-colors ${configOpen ? 'bg-brand/15 text-brand' : 'text-text-muted hover:text-text-primary hover:bg-hover'}`}
              title="Agent 配置"
            >
              <Settings size={15} />
            </button>
          </div>
        </div>

        {/* Tab bar */}
        <div className="flex border-b border-divider shrink-0 bg-card-bg">
          <button
            type="button"
            onClick={() => setMainTab('control')}
            className={`px-5 py-2 text-sm font-medium border-b-2 transition-colors ${mainTab === 'control' ? 'border-brand text-brand' : 'border-transparent text-text-muted hover:text-text-primary'}`}
          >
            控制面板
          </button>
          <button
            type="button"
            onClick={handleHistoryTabClick}
            className={`px-5 py-2 text-sm font-medium border-b-2 transition-colors ${mainTab === 'history' ? 'border-brand text-brand' : 'border-transparent text-text-muted hover:text-text-primary'}`}
          >
            运行历史{runs.length > 0 && <span className="ml-1 text-xs text-text-hint">({runs.length})</span>}
          </button>
        </div>

        {/* Content — relative so HistoryPanel can use absolute inset-0 */}
        <div className="flex-1 overflow-auto relative">
          {mainTab === 'control' ? (
            <ControlPanel
              agentStatus={agentStatus}
              instruction={instruction}
              setInstruction={setInstruction}
              sending={sending}
              isRunning={isRunning}
              isTakeover={isTakeover}
              controlError={controlError}
              controlMsg={controlMsg}
              onRun={handleRun}
              onStop={handleStop}
              onTakeover={handleTakeover}
              onResume={handleResume}
            />
          ) : (
            <HistoryPanel
              runs={runs}
              selectedRun={selectedRun}
              loading={historyLoading}
              detailLoading={detailLoading}
              error={historyError}
              onSelectRun={loadDetail}
              onRefresh={loadHistory}
              onClear={handleClearHistory}
            />
          )}
        </div>
      </div>

      {/* ── Config Drawer ── */}
      {configOpen && (
        <div className="w-80 shrink-0 border-l border-divider bg-card-bg flex flex-col overflow-hidden">
          <div className="flex items-center justify-between px-4 py-3 border-b border-divider shrink-0">
            <span className="text-sm font-semibold text-text-primary flex items-center gap-2">
              <Settings size={14} /> Agent 配置
            </span>
            <button
              type="button"
              onClick={() => setConfigOpen(false)}
              className="text-text-muted hover:text-text-primary transition-colors p-1 rounded hover:bg-hover"
            >
              <X size={15} />
            </button>
          </div>
          <div className="flex-1 overflow-y-auto">
            <ConfigDrawer imei={imei!} onClose={() => setConfigOpen(false)} />
          </div>
        </div>
      )}
    </div>
  );
}
