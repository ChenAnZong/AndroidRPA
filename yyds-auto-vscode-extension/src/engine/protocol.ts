/**
 * 协议定义 — JSON + HTTP REST 架构
 * 定义了与安卓设备 Python 引擎通信的 HTTP API 路径和响应类型
 */

// ============================================================
// HTTP REST API 路径
// ============================================================
export const API = {
    // 项目管理
    PROJECT_LIST: '/project/list',
    PROJECT_STATUS: '/project/status',
    PROJECT_START: '/project/start',       // ?name=xxx
    PROJECT_STOP: '/project/stop',

    // 引擎控制
    ENGINE_RUN_CODE: '/engine/run-code',   // POST {code: "..."}
    ENGINE_REBOOT: '/engine/reboot',       // POST
    ENGINE_SHELL: '/engine/shell',         // POST {command: "..."}
    ENGINE_CLICK: '/engine/click',         // POST {x, y}
    ENGINE_AUTO: '/engine/auto',           // POST {uri, ...params}
    ENGINE_FOREGROUND: '/engine/foreground',

    // 截图与UI
    SCREENSHOT: '/screenshot',
    SCREEN: '/screen',                     // /{quality}
    UI_DUMP: '/uia_dump',

    // OCR 模型切换
    SET_OCR_VERSION: '/set-ocr-version',   // GET ?version=v2|v5_mobile|v5_server&target_size=640

    // 文件传输
    PULL_FILE: '/pull_file',               // ?path=xxx
    POST_FILE: '/post_file',               // POST multipart
    PUSH_PROJECT: '/push_project',         // POST multipart {name, file}

    // APK打包
    PACKAGE_BUILD: '/package/build',       // POST {appName, projectName, version, packageName, ...}
    PACKAGE_LIST: '/package/list',         // GET
    PACKAGE_DOWNLOAD: '/package/download', // GET ?path=xxx
    PACKAGE_INSTALLED_APPS: '/package/installed-apps', // GET
    PACKAGE_APP_ICON: '/package/app-icon', // GET ?pkg=xxx

    // WebSocket（保留）
    WS_LOG: '/log',

    // AI Agent
    AGENT_CONFIG: '/agent/config',
    AGENT_RUN: '/agent/run',
    AGENT_STOP: '/agent/stop',
    AGENT_STATUS: '/agent/status',
    AGENT_TEST: '/agent/test-connection',
} as const;

// ============================================================
// HTTP JSON 响应类型
// ============================================================

/** 通用 JSON 响应 */
export interface ApiResponse {
    success?: boolean;
    error?: string;
    result?: string;
    [key: string]: any;
}

/** 项目运行状态 */
export interface ProjectStatus {
    running: boolean;
    project: string | null;
}

/** 项目列表项 */
export interface ProjectInfo {
    name: string;
    [key: string]: any;
}

/** APK打包结果 */
export interface PackageBuildResult {
    success: boolean;
    outputPath?: string;
    fileSize?: number;
    error?: string;
    durationMs?: number;
}

/** APK打包配置 */
export interface PackageBuildConfig {
    appName: string;
    projectName: string;
    version?: string;
    packageName?: string;
    iconPath?: string;
    // 运行行为
    autoStart?: boolean;
    autoRunOnOpen?: boolean;
    keepScreenOn?: boolean;
    showLog?: boolean;
    exitOnScriptStop?: boolean;
    encryptScripts?: boolean;
}

/** 已安装应用信息 */
export interface InstalledAppInfo {
    packageName: string;
    appName: string;
}

// ============================================================
// AI Agent 类型
// ============================================================

/** Agent 配置 */
export interface AgentConfig {
    provider: 'autoglm' | 'doubao' | 'qwen' | 'openai' | 'custom';
    api_key: string;
    base_url?: string;
    model?: string;
    max_steps?: number;
    use_ui_dump?: boolean;
    temperature?: number;
    max_tokens?: number;
    is_configured?: boolean;
}

/** Agent 状态 */
export interface AgentStatus {
    running: boolean;
    instruction?: string;
    current_step?: number;
    max_steps?: number;
    message?: string;
}

/** Agent 日志条目 */
export interface AgentLogEntry {
    step: number;
    type: string;
    title: string;
    detail: string;
    timestamp: number;
}
