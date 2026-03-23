import type {
  Device, DeviceListResponse, ApiResponse, Schedule,
  AuthResponse, DeviceBinding, BindDeviceResponse,
  AdminUser, AdminBinding, AdminStats, UserInfo,
  TaskRun, TaskRunStats,
} from '@/types';
import { useAuthStore } from '@/store';

const BASE = '';

/** Public endpoints that should NOT trigger auto-logout on 401 */
const PUBLIC_ENDPOINTS = ['/api/auth/login', '/api/auth/register'];

async function fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
  const token = useAuthStore.getState().token;
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init?.headers as Record<string, string>),
  };
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const res = await fetch(`${BASE}${url}`, { ...init, headers });

  if (res.status === 401 && !PUBLIC_ENDPOINTS.some((p) => url.startsWith(p))) {
    // Token expired or invalid on a protected route — logout and redirect
    useAuthStore.getState().logout();
    window.location.href = '/login';
    throw new Error('Session expired, please login again');
  }

  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }));
    throw new Error(body.error || res.statusText);
  }
  return res.json();
}

// ── Auth API ──

export const authApi = {
  login: (username: string, password: string) =>
    fetchJson<AuthResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),

  register: (username: string, password: string) =>
    fetchJson<AuthResponse>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),

  me: () => fetchJson<UserInfo>('/api/auth/me'),

  changePassword: (old_password: string, new_password: string) =>
    fetchJson<ApiResponse>('/api/auth/change-password', {
      method: 'POST',
      body: JSON.stringify({ old_password, new_password }),
    }),

  myDevices: () =>
    fetchJson<{ devices: DeviceBinding[] }>('/api/auth/devices'),

  bindDevice: (imei: string, alias?: string) =>
    fetchJson<BindDeviceResponse>('/api/auth/bind-device', {
      method: 'POST',
      body: JSON.stringify({ imei, alias: alias || '' }),
    }),

  unbindDevice: (imei: string) =>
    fetchJson<ApiResponse>('/api/auth/unbind-device', {
      method: 'POST',
      body: JSON.stringify({ imei }),
    }),
};

// ── Admin API ──

export const adminApi = {
  stats: () => fetchJson<AdminStats>('/api/admin/stats'),

  listUsers: () => fetchJson<{ users: AdminUser[]; total: number }>('/api/admin/users'),

  getUser: (id: number) =>
    fetchJson<{ user: AdminUser; devices: DeviceBinding[] }>(`/api/admin/users/${id}`),

  deleteUser: (id: number) =>
    fetchJson<ApiResponse>(`/api/admin/users/${id}`, { method: 'DELETE' }),

  updateUser: (id: number, data: { role?: string; password?: string; enabled?: boolean; max_devices?: number }) =>
    fetchJson<ApiResponse>(`/api/admin/users/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),

  listBindings: () =>
    fetchJson<{ bindings: AdminBinding[]; total: number }>('/api/admin/bindings'),
};

// ── Device API ──

export const deviceApi = {
  list: () => fetchJson<DeviceListResponse>('/api/devices'),

  get: (imei: string) => fetchJson<Device>(`/api/devices/${imei}`),

  thumbnailUrl: (imei: string, cacheBust?: number) => {
    const token = useAuthStore.getState().token;
    const params = new URLSearchParams();
    if (token) params.set('token', token);
    if (cacheBust != null) params.set('t', String(cacheBust));
    const qs = params.toString();
    return `/api/devices/${imei}/thumbnail${qs ? `?${qs}` : ''}`;
  },

  touch: (imei: string, x: number, y: number) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/touch`, {
      method: 'POST',
      body: JSON.stringify({ x, y }),
    }),

  swipe: (imei: string, x1: number, y1: number, x2: number, y2: number, duration = 300) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/swipe`, {
      method: 'POST',
      body: JSON.stringify({ x1, y1, x2, y2, duration }),
    }),

  shell: (imei: string, command: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/shell`, {
      method: 'POST',
      body: JSON.stringify({ command }),
    }),

  key: (imei: string, keycode: number) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/key`, {
      method: 'POST',
      body: JSON.stringify({ keycode }),
    }),

  text: (imei: string, text: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/text`, {
      method: 'POST',
      body: JSON.stringify({ text }),
    }),

  rebootEngine: (imei: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/reboot-engine`, { method: 'POST' }),

  batchTouch: (deviceIds: string[], x: number, y: number) =>
    fetchJson<ApiResponse>('/api/batch/touch', {
      method: 'POST',
      body: JSON.stringify({ device_ids: deviceIds, x, y }),
    }),

  batchShell: (deviceIds: string[], command: string) =>
    fetchJson<ApiResponse>('/api/batch/shell', {
      method: 'POST',
      body: JSON.stringify({ device_ids: deviceIds, command }),
    }),

  batchText: (deviceIds: string[], text: string) =>
    fetchJson<ApiResponse>('/api/batch/text', {
      method: 'POST',
      body: JSON.stringify({ device_ids: deviceIds, text }),
    }),

  installApk: (imei: string, path: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/install-apk`, {
      method: 'POST',
      body: JSON.stringify({ path }),
    }),

  batchInstallApk: (deviceIds: string[], path: string) =>
    fetchJson<ApiResponse>('/api/batch/install-apk', {
      method: 'POST',
      body: JSON.stringify({ device_ids: deviceIds, path }),
    }),

  batchUploadFile: (deviceIds: string[], file: File, path: string, onProgress?: (pct: number) => void): Promise<ApiResponse> => {
    return new Promise((resolve, reject) => {
      const token = useAuthStore.getState().token;
      const form = new FormData();
      form.append('file', file);
      form.append('path', path);
      form.append('device_ids', JSON.stringify(deviceIds));

      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${BASE}/api/batch/files/upload`);
      if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`);

      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable && onProgress) onProgress(Math.round((e.loaded / e.total) * 100));
      };

      xhr.onload = () => {
        if (xhr.status === 401) {
          useAuthStore.getState().logout();
          window.location.href = '/login';
          reject(new Error('Session expired, please login again'));
          return;
        }
        let body: Record<string, unknown>;
        try { body = JSON.parse(xhr.responseText); } catch { body = { error: xhr.statusText }; }
        if (xhr.status >= 400) {
          reject(new Error((body.error as string) || xhr.statusText));
        } else {
          resolve(body as unknown as ApiResponse);
        }
      };

      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(form);
    });
  },

  batchRebootEngine: (deviceIds: string[]) =>
    fetchJson<ApiResponse>('/api/batch/reboot-engine', {
      method: 'POST',
      body: JSON.stringify({ device_ids: deviceIds }),
    }),

  // Paste
  paste: (imei: string, text: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/paste`, {
      method: 'POST',
      body: JSON.stringify({ text }),
    }),

  batchPaste: (deviceIds: string[], text: string) =>
    fetchJson<ApiResponse>('/api/batch/paste', {
      method: 'POST',
      body: JSON.stringify({ device_ids: deviceIds, text }),
    }),

  // IME
  imeGet: (imei: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/ime`),

  imeList: (imei: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/ime/list`),

  imeSet: (imei: string, ime_id: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/ime`, {
      method: 'POST',
      body: JSON.stringify({ ime_id }),
    }),

  batchImeSet: (deviceIds: string[], ime_id: string) =>
    fetchJson<ApiResponse>('/api/batch/ime', {
      method: 'POST',
      body: JSON.stringify({ device_ids: deviceIds, ime_id }),
    }),
};

export const projectApi = {
  list: (imei: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/projects`),

  start: (imei: string, name: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/projects/start`, {
      method: 'POST',
      body: JSON.stringify({ name }),
    }),

  stop: (imei: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/projects/stop`, { method: 'POST' }),

  status: (imei: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/projects/status`),

  batchStart: (deviceIds: string[], name: string) =>
    fetchJson<ApiResponse>('/api/batch/projects/start', {
      method: 'POST',
      body: JSON.stringify({ device_ids: deviceIds, name }),
    }),

  batchStop: (deviceIds: string[]) =>
    fetchJson<ApiResponse>('/api/batch/projects/stop', {
      method: 'POST',
      body: JSON.stringify({ device_ids: deviceIds }),
    }),
};

export const fileApi = {
  list: (imei: string, path: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/files?path=${encodeURIComponent(path)}`),

  downloadUrl: (imei: string, path: string) => {
    const token = useAuthStore.getState().token;
    const params = new URLSearchParams({ path });
    if (token) params.set('token', token);
    return `/api/devices/${imei}/files/download?${params.toString()}`;
  },

  download: (imei: string, path: string) => {
    const url = fileApi.downloadUrl(imei, path);
    const a = document.createElement('a');
    a.href = url;
    a.download = path.split('/').pop() || 'download';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  },

  upload: (imei: string, path: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/files/upload`, {
      method: 'POST',
      body: JSON.stringify({ path }),
    }),

  delete: (imei: string, path: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/files?path=${encodeURIComponent(path)}`, {
      method: 'DELETE',
    }),

  mkdir: (imei: string, path: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/files/mkdir`, {
      method: 'POST',
      body: JSON.stringify({ path }),
    }),
};

export interface LogcatDumpResponse {
  success: boolean;
  data: string;
  lineCount: number;
  command: string;
}

export const logcatApi = {
  dump: (imei: string, params: {
    level?: string; pid?: string; tag?: string;
    format?: string; lines?: number; since?: string;
  } = {}) => {
    const q = new URLSearchParams();
    if (params.level) q.set('level', params.level);
    if (params.pid) q.set('pid', params.pid);
    if (params.tag) q.set('tag', params.tag);
    if (params.format) q.set('format', params.format);
    if (params.lines) q.set('lines', String(params.lines));
    if (params.since) q.set('since', params.since);
    const qs = q.toString();
    return fetchJson<LogcatDumpResponse>(`/api/devices/${imei}/logcat/dump${qs ? '?' + qs : ''}`);
  },

  clear: (imei: string, buffer = 'all') =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/logcat/clear`, {
      method: 'POST',
      body: JSON.stringify({ buffer }),
    }),

  buffers: (imei: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/logcat/buffers`),
};

export const scheduleApi = {
  list: () => fetchJson<{ schedules: Schedule[] }>('/api/schedules'),

  create: (schedule: Omit<Schedule, 'id' | 'last_run' | 'created_at' | 'last_run_status'>) =>
    fetchJson<ApiResponse>('/api/schedules', {
      method: 'POST',
      body: JSON.stringify(schedule),
    }),

  update: (id: string, updates: Partial<Schedule>) =>
    fetchJson<ApiResponse>(`/api/schedules/${id}`, {
      method: 'PUT',
      body: JSON.stringify(updates),
    }),

  delete: (id: string) =>
    fetchJson<ApiResponse>(`/api/schedules/${id}`, { method: 'DELETE' }),

  trigger: (id: string) =>
    fetchJson<{ success: boolean; run_id: string; message: string }>(`/api/schedules/${id}/trigger`, {
      method: 'POST',
    }),

  runs: (id: string, limit = 10) =>
    fetchJson<{ runs: TaskRun[]; total: number }>(`/api/schedules/${id}/runs?limit=${limit}`),
};

export const taskRunApi = {
  list: (params?: { schedule_id?: string; limit?: number; offset?: number }) => {
    const q = new URLSearchParams();
    if (params?.schedule_id) q.set('schedule_id', params.schedule_id);
    if (params?.limit != null) q.set('limit', String(params.limit));
    if (params?.offset != null) q.set('offset', String(params.offset));
    const qs = q.toString();
    return fetchJson<{ runs: TaskRun[]; total: number }>(`/api/task-runs${qs ? '?' + qs : ''}`);
  },

  get: (runId: string) =>
    fetchJson<{ run: TaskRun }>(`/api/task-runs/${runId}`),

  stats: () =>
    fetchJson<TaskRunStats>('/api/task-runs/stats'),

  clear: (scheduleId?: string) => {
    const q = scheduleId ? `?schedule_id=${encodeURIComponent(scheduleId)}` : '';
    return fetchJson<{ removed: number }>(`/api/task-runs${q}`, { method: 'DELETE' });
  },
};

// ── IDE API ──

export const ideApi = {
  readFile: (imei: string, path: string) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/file/read?path=${encodeURIComponent(path)}`),

  writeFile: (imei: string, path: string, content: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/file/write`, {
      method: 'POST',
      body: JSON.stringify({ path, content }),
    }),

  fileExists: (imei: string, path: string) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/file/exists?path=${encodeURIComponent(path)}`),

  renameFile: (imei: string, oldPath: string, newPath: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/file/rename`, {
      method: 'POST',
      body: JSON.stringify({ old_path: oldPath, new_path: newPath }),
    }),

  runCode: (imei: string, code: string) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/run-code`, {
      method: 'POST',
      body: JSON.stringify({ code }),
    }),

  screenshot: (imei: string) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/screenshot`),

  uiDump: (imei: string) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/ui-dump`),

  foreground: (imei: string) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/foreground`),

  click: (imei: string, x: number, y: number) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/click`, {
      method: 'POST',
      body: JSON.stringify({ x, y }),
    }),

  pipList: (imei: string) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/pip/list`),

  pipInstall: (imei: string, name: string) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/pip/install`, {
      method: 'POST',
      body: JSON.stringify({ name }),
    }),

  pipUninstall: (imei: string, name: string) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/pip/uninstall`, {
      method: 'POST',
      body: JSON.stringify({ name }),
    }),

  // Phase 2: Pip extended
  pipOutdated: (imei: string) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/pip/outdated`),

  pipShow: (imei: string, name: string) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/pip/show?name=${encodeURIComponent(name)}`),

  pipUpgrade: (imei: string, name: string) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/pip/upgrade`, {
      method: 'POST',
      body: JSON.stringify({ name }),
    }),

  pipSearch: (imei: string, name: string) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/pip/search?name=${encodeURIComponent(name)}`),

  // Phase 2: APK packaging
  packageBuild: (imei: string, config: import('@/types').PackageBuildConfig) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/package/build`, {
      method: 'POST',
      body: JSON.stringify(config),
    }),

  packageList: (imei: string) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/package/list`),

  packageInstalledApps: (imei: string) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/package/installed-apps`),

  packageAppIcon: (imei: string, pkg: string) =>
    fetchJson<ApiResponse<string>>(`/api/devices/${imei}/package/app-icon?pkg=${encodeURIComponent(pkg)}`),

  // Phase 3: Color/Image tools + OCR API

  /** Get single point color */
  getColor: (imei: string, x: number, y: number) =>
    fetchJson<ApiResponse<{ r: number; g: number; b: number; hex: string }>>(`/api/devices/${imei}/get-color`, {
      method: 'POST',
      body: JSON.stringify({ x, y }),
    }),

  /** Get multi-point colors */
  getColors: (imei: string, points: string) =>
    fetchJson<ApiResponse<unknown>>(`/api/devices/${imei}/get-colors`, {
      method: 'POST',
      body: JSON.stringify({ points }),
    }),

  /** Multi-point color search */
  findColor: (imei: string, rgb: string, points: string, opts?: {
    prob?: number; max_counts?: number; step_x?: number; step_y?: number;
    x?: string; y?: string; w?: string; h?: string;
  }) =>
    fetchJson<ApiResponse<{ x: number; y: number }[]>>(`/api/devices/${imei}/find-color`, {
      method: 'POST',
      body: JSON.stringify({ rgb, points, ...opts }),
    }),

  /** Find image (multi-template) */
  findImage: (imei: string, templates: string, opts?: {
    threshold?: number; x?: string; y?: string; w?: string; h?: string;
  }) =>
    fetchJson<ApiResponse<{ x: number; y: number }[]>>(`/api/devices/${imei}/find-image`, {
      method: 'POST',
      body: JSON.stringify({ templates, ...opts }),
    }),

  /** Template match (single template + probability) */
  matchImage: (imei: string, template: string, opts?: {
    threshold?: number; prob?: number; x?: string; y?: string; w?: string; h?: string;
  }) =>
    fetchJson<ApiResponse<{ x: number; y: number; w: number; h: number; prob: number }[]>>(`/api/devices/${imei}/match-image`, {
      method: 'POST',
      body: JSON.stringify({ template, ...opts }),
    }),

  /** Screen OCR (screenshot and recognize) */
  screenOcr: (imei: string, opts?: { use_gpu?: boolean; threshold?: number }) => {
    const p = new URLSearchParams();
    if (opts?.use_gpu != null) p.set('use_gpu', String(opts.use_gpu));
    if (opts?.threshold != null) p.set('threshold', String(opts.threshold));
    const qs = p.toString();
    return fetchJson<ApiResponse<{ text: string; x: number; y: number; w: number; h: number; prob: number }[]>>(
      `/api/devices/${imei}/screen-ocr${qs ? '?' + qs : ''}`
    );
  },

  /** Image OCR (recognize specified image file) */
  imageOcr: (imei: string, image: string, opts?: { use_gpu?: boolean; threshold?: number }) =>
    fetchJson<ApiResponse<{ text: string; x: number; y: number; w: number; h: number; prob: number }[]>>(`/api/devices/${imei}/image-ocr`, {
      method: 'POST',
      body: JSON.stringify({ image, ...opts }),
    }),
};

// ── Agent API ──

export interface AgentRunSummary {
  run_id: string;
  instruction: string;
  started_at: string;
  finished_at: string | null;
  success: boolean | null;
  total_steps: number;
  elapsed_ms: number;
  token_usage: { prompt_tokens: number; completion_tokens: number; total_tokens: number };
}

export interface AgentRunDetail extends AgentRunSummary {
  logs: Array<{
    step: number;
    type: string;
    title: string;
    detail: string;
    timestamp: string;
    token_usage?: { prompt_tokens: number; completion_tokens: number; total_tokens: number };
  }>;
  steps: Array<{
    step: number;
    thought: string;
    action: Record<string, unknown>;
    action_desc: string;
    success: boolean;
    description: string;
  }>;
}

export interface AgentStatus {
  running: boolean;
  instruction: string;
  current_step: number;
  max_steps: number;
  message: string;
  takeover: boolean;
}

export interface AgentConfig {
  provider: string;
  api_key: string;
  base_url: string;
  model: string;
  max_steps: number;
  use_ui_dump: boolean;
  use_v2: boolean;
  show_floating_window: boolean;
  temperature: number;
  max_tokens: number;
}

export interface AgentProvider {
  id: string;
  name: string;
  default_model: string;
  models: string[];
}

export const agentApi = {
  status: (imei: string) =>
    fetchJson<AgentStatus>(`/api/devices/${imei}/agent/status`),

  run: (imei: string, instruction: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/agent/run`, {
      method: 'POST',
      body: JSON.stringify({ instruction }),
    }),

  stop: (imei: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/agent/stop`, { method: 'POST' }),

  getConfig: (imei: string) =>
    fetchJson<AgentConfig>(`/api/devices/${imei}/agent/config`),

  setConfig: (imei: string, config: Partial<AgentConfig>) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/agent/config`, {
      method: 'PUT',
      body: JSON.stringify(config),
    }),

  providers: (imei: string) =>
    fetchJson<{ providers: AgentProvider[] }>(`/api/devices/${imei}/agent/providers`),

  models: (imei: string, provider: string) =>
    fetchJson<{ models: string[] }>(`/api/devices/${imei}/agent/models?provider=${encodeURIComponent(provider)}`),

  takeover: (imei: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/agent/takeover`, { method: 'POST' }),

  resume: (imei: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/agent/resume`, { method: 'POST' }),

  testConnection: (imei: string, config: Partial<AgentConfig>) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/agent/test-connection`, {
      method: 'POST',
      body: JSON.stringify(config),
    }),

  history: (imei: string) =>
    fetchJson<{ runs: AgentRunSummary[] }>(`/api/devices/${imei}/agent/history`),

  detail: (imei: string, runId: string) =>
    fetchJson<AgentRunDetail>(`/api/devices/${imei}/agent/history/${runId}`),

  clearHistory: (imei: string) =>
    fetchJson<ApiResponse>(`/api/devices/${imei}/agent/history`, { method: 'DELETE' }),
};

