export interface Device {
  imei: string;
  model: string;
  screen_width: number;
  screen_height: number;
  version: number;
  online: boolean;
  connected_at: number;
  last_seen: number;
  running_project: string;
  foreground_app: string;
  stream_viewers: number;
}

export interface DeviceListResponse {
  devices: Device[];
  total: number;
  online: number;
}

export interface ApiResponse<T = unknown> {
  success: boolean;
  data?: T;
  error?: string;
}

export interface AlertConfig {
  on_any_fail: boolean;
  fail_rate_threshold?: number;
  on_device_offline: boolean;
  webhook_url?: string;
}

export interface Schedule {
  id: string;
  name: string;
  cron_expr: string;
  action: string;
  params: Record<string, unknown>;
  device_ids: string[];
  enabled: boolean;
  last_run: number | null;
  created_at: number;
  alert?: AlertConfig;
  batch_size?: number;
  batch_delay_ms?: number;
  timeout_secs?: number;
  retry_count?: number;
  last_run_status?: string;
}

export interface DeviceResult {
  imei: string;
  model: string;
  /** "success" | "failed" | "offline" | "timeout" */
  status: string;
  output: string;
  duration_ms: number;
}

export interface RunSummary {
  total: number;
  success: number;
  failed: number;
  offline: number;
  timeout: number;
}

export interface TaskRun {
  id: string;
  schedule_id: string;
  schedule_name: string;
  triggered_at: number;
  /** "cron" | "manual" */
  trigger_type: string;
  /** "running" | "done" | "partial_fail" | "all_fail" */
  status: string;
  device_results: DeviceResult[];
  summary: RunSummary;
  duration_ms: number;
}

export interface TaskRunStats {
  total_today: number;
  success_runs: number;
  failed_runs: number;
  total_devices: number;
  success_devices: number;
  success_rate: number;
}

export interface FileEntry {
  name: string;
  path: string;
  is_dir: boolean;
  size: number;
  modified: number;
}

export interface ProjectInfo {
  name: string;
  path: string;
  running: boolean;
}

export type ConnectionMode = 'p2p' | 'ws' | 'relay' | 'connecting' | 'disconnected';

// ── Auth types ──

export interface UserInfo {
  id: number;
  username: string;
  role: 'admin' | 'user';
  created_at: number;
}

export interface AuthResponse {
  token: string;
  user: UserInfo;
}

export interface DeviceBinding {
  imei: string;
  alias: string;
  bound_at: number;
}

export interface BindDeviceResponse {
  device_token: string;
  imei: string;
}

// ── Admin types ──

export interface AdminUser {
  id: number;
  username: string;
  role: string;
  enabled: boolean;
  max_devices: number;
  created_at: number;
  last_login: number | null;
}

export interface AdminBinding {
  id: number;
  user_id: number;
  username: string;
  imei: string;
  alias: string;
  bound_at: number;
}

export interface AdminStats {
  users: number;
  device_bindings: number;
  devices_total: number;
  devices_online: number;
}

// ── IDE types ──

export interface IdeFileNode {
  name: string;
  path: string;
  is_dir: boolean;
  size: number;
  modified: number;
  children?: IdeFileNode[];
  expanded?: boolean;
}

export interface IdeTab {
  path: string;
  name: string;
  content: string;
  dirty: boolean;
  language: string;
}

export interface UiNode {
  index: number;
  text: string;
  resourceId: string;
  className: string;
  packageName: string;
  contentDesc: string;
  checkable: boolean;
  checked: boolean;
  clickable: boolean;
  enabled: boolean;
  focusable: boolean;
  focused: boolean;
  scrollable: boolean;
  longClickable: boolean;
  password: boolean;
  selected: boolean;
  visible: boolean;
  bounds: { x1: number; y1: number; x2: number; y2: number };
  children: UiNode[];
  depth: number;
}

// ── Pip types ──

export interface PipPackage {
  name: string;
  version: string;
}

export interface PipOutdatedPackage {
  name: string;
  version: string;
  latest_version: string;
}

export interface PipSearchResult {
  found: boolean;
  name: string;
  latest_version: string;
  versions: string[];
  installed_version?: string;
}

// ── APK Package types ──

export interface PackageBuildConfig {
  appName: string;
  projectName: string;
  version: string;
  packageName?: string;
  iconPath?: string;
  autoRunOnOpen: boolean;
  keepScreenOn: boolean;
  showLog: boolean;
  exitOnScriptStop: boolean;
  encryptScripts: boolean;
}

export interface BuiltApk {
  name: string;
  path: string;
  size: number;
  lastModified: number;
}

export interface InstalledApp {
  packageName: string;
  appName: string;
}

// ── Flow Editor types ──

export type FlowNodeType =
  | 'start' | 'end'
  | 'click' | 'swipe' | 'input_text'
  | 'ocr_find' | 'ocr_click' | 'find_image'
  | 'wait' | 'sleep'
  | 'condition' | 'loop'
  | 'shell' | 'open_app' | 'screenshot' | 'log'
  | 'sub_flow';

export interface FlowNodePort {
  id: string;
  label?: string;
}

export interface FlowNode {
  id: string;
  type: FlowNodeType;
  label: string;
  x: number;
  y: number;
  data: Record<string, unknown>;
  inputs?: FlowNodePort[];
  outputs?: FlowNodePort[];
  width?: number;
  height?: number;
}

export interface FlowEdge {
  id: string;
  source: string;
  sourcePort: string;
  target: string;
  targetPort: string;
}

export interface FlowViewport {
  zoom: number;
  x: number;
  y: number;
}

export interface FlowGraph {
  nodes: FlowNode[];
  edges: FlowEdge[];
  viewport: FlowViewport;
}

// ── UI Designer types ──

export type UiWidgetType = 'text' | 'div' | 'space' | 'check' | 'select' | 'edit';

export interface UiWidget {
  type: UiWidgetType;
  name: string;
  props: Record<string, unknown>;
}
