# yyds-con Frontend — AGENTS.md

## 项目概览

React 19 + TypeScript 前端，为 yyds-con 多设备 Android RPA 控制台提供完整 Web UI。
构建产物由后端 Axum 静态服务（SPA fallback），无独立服务器。

**开发命令**
```bash
npm run dev        # Vite 开发服务器（HMR）
npm run build      # tsc + vite build → dist/
npm run test       # vitest --run（单次）
npm run test:watch # vitest 监听模式
npm run lint       # eslint
```

**端口**：开发时 `http://localhost:5173`，生产由后端 `http://localhost:8818` 提供

---

## 技术栈

| 依赖 | 版本 | 用途 |
|------|------|------|
| React | 19 | UI 框架 |
| TypeScript | ~5.9 | 类型系统 |
| Vite | 7 | 构建/HMR |
| TailwindCSS | v4 | 样式（@tailwindcss/vite 插件） |
| react-router-dom | 7 | 路由 |
| @tanstack/react-query | 5 | 服务端状态管理 |
| zustand | 5 | 客户端状态管理 |
| @monaco-editor/react | 4 | 代码编辑器 |
| i18next + react-i18next | 25/16 | 国际化 |
| lucide-react | 0.564 | 图标 |
| framer-motion | 12 | 动画 |
| vitest + @testing-library | 4 | 单元测试 |

---

## 目录结构

```
frontend/src/
├── App.tsx                  # 路由配置 + QueryClient + 守卫组件
├── main.tsx                 # React 入口，挂载 <App/>
├── index.css                # TailwindCSS base + 全局样式
├── types.ts                 # 所有 TypeScript 接口定义（Device, Schedule, IDE类型等）
├── store.ts                 # Zustand stores（Auth / App / Status / Theme）
├── services/
│   └── api.ts               # 全部 HTTP 请求封装（fetchJson + 各域 API 对象）
├── hooks/
│   ├── useStreamConnection.ts  # 屏幕流 WebSocket 连接 + 自适应画质控制
│   └── useLogStream.ts         # 设备日志 WebSocket 流
├── components/
│   ├── Layout.tsx           # 侧边栏导航（含折叠），<Outlet/> 容器
│   ├── CronBuilder.tsx      # Cron 表达式可视化构建器
│   ├── LanguageSwitcher.tsx # 语言切换组件（i18n）
│   ├── LogcatPanel.tsx      # Android Logcat 面板组件
│   └── ide/                 # IDE 工作台子组件（见下文）
├── pages/
│   ├── LandingPage.tsx      # 公开首页（未登录）
│   ├── Login.tsx            # 登录页
│   ├── Dashboard.tsx        # 主控台（设备网格 + 统计 + 批量操作）
│   ├── DeviceDetail.tsx     # 设备详情（屏幕流 + 触控 + Shell + 项目管理）
│   ├── FileBrowser.tsx      # 设备文件浏览器（列表 + 上传 + 下载 + 删除）
│   ├── LogViewer.tsx        # 脚本运行日志查看器（WebSocket 实时流）
│   ├── Schedules.tsx        # 定时任务管理（CRUD + Cron 构建器）
│   ├── IdeWorkbench.tsx     # 全屏 IDE 工作台（全功能，独立于 Layout）
│   ├── DevTools.tsx         # 开发工具入口页（快捷跳转 IDE）
│   ├── MyDevices.tsx        # 我的设备（绑定 / 解绑 / 获取 device token）
│   ├── AdminPanel.tsx       # 管理员面板（用户 CRUD / 绑定关系 / 统计）
│   ├── AgentHistory.tsx     # Agent 执行历史（步骤列表 + 截图回放）
│   └── Documentation.tsx   # 内嵌 API 文档页
└── i18n/
    └── [zh, en, ...].json   # 各语言翻译文件（通过 i18next-browser-languagedetector 自动检测）
```

---

## 路由设计

```
/                        → LandingPage（公开）
/login                   → Login（公开）
/dashboard               → Dashboard（需登录）
/devices/:imei           → DeviceDetail
/devices/:imei/files     → FileBrowser
/devices/:imei/logs      → LogViewer
/devices/:imei/ide       → IdeWorkbench（全屏，无 Layout）
/devices/:imei/agent-history → AgentHistory
/schedules               → Schedules
/dev-tools               → DevTools
/dev-tools/:imei/ide     → IdeWorkbench（backTo="/dev-tools"）
/docs                    → Documentation
/my-devices              → MyDevices
/admin                   → AdminPanel（需 admin 角色）
*                        → 重定向 /dashboard
```

**路由守卫**
- `RequireAuth`：检查 `useAuthStore.token`，未登录跳 `/login`
- `RequireAdmin`：检查 `user.role === 'admin'`，非管理员跳 `/dashboard`

---

## 状态管理（store.ts）

所有 store 使用 **Zustand**，持久化 store 用 `zustand/middleware persist`：

| Store | 持久化 | 内容 |
|-------|--------|------|
| `useAuthStore` | ✅ `yyds-auth` | `token`, `user`（UserInfo），`setAuth`, `logout`, `isAdmin()` |
| `useAppStore` | ❌ | `selectedDevices`（批量操作集合），`sidebarCollapsed`，`connectionModes`（imei→ConnectionMode） |
| `useStatusStore` | ❌ | 全局状态栏消息（替代 toast），5 秒自动消失 |
| `useThemeStore` | ✅ `yyds-theme` | `'light' \| 'dark'`，切换时同步 `document.documentElement.classList` |

**`status` 快捷方法**（在任意组件外直接调用）：
```ts
status.success('操作成功');
status.error('失败原因');
status.loading('处理中...');
status.dismiss();
```

---

## API 层（services/api.ts）

所有请求通过 `fetchJson<T>(url, init?)` 统一发出：
- 自动附加 `Authorization: Bearer <token>`
- 401 响应（非公开路由）→ 自动 `logout()` + 跳 `/login`
- `BASE = ''`（相对路径，开发时 Vite proxy 转发，生产直连）

**API 对象分组**：

| 对象 | 路径前缀 | 主要方法 |
|------|----------|---------|
| `authApi` | `/api/auth/` | `login`, `register`, `me`, `changePassword`, `myDevices`, `bindDevice`, `unbindDevice` |
| `adminApi` | `/api/admin/` | `stats`, `listUsers`, `getUser`, `createUser`, `updateUser`, `deleteUser`, `listAllBindings` |
| `deviceApi` | `/api/devices/` | `list`, `get`, `thumbnail` |
| `scheduleApi` | `/api/schedules` | `list`, `create`, `update`, `delete` |

IDE / 设备操作类 API 通常在各 page/component 内直接 `fetch` 调用（避免过度封装）。

---

## 屏幕流（useStreamConnection.ts）

**流协议**（二进制帧，与 Android `ScreenCapture.java` 约定）：
- `0x01` FRAME_KEY：完整 WebP 关键帧
- `0x02` FRAME_SKIP：跳帧（维持 FPS 计数）
- `0x03` FRAME_DELTA：差分帧，格式：`header(1) + regionCount(2) + [x(2)+y(2)+w(2)+h(2)+dataLen(4)+WebP...]×N`

**关键机制**：
- `OffscreenCanvas`（优先）/ `HTMLCanvasElement`（Safari fallback）用于差分帧合成
- 自适应画质：5 档 `QUALITY_TIERS`（ultra/high/medium/low/min），每 3 秒检测 FPS ratio
  - `fpsRatio < 0.6` → 降档（立即）
  - `fpsRatio > 0.9` → 升档（稳定 8 秒后）
- 向 WebSocket 发 `{type: "adjust_stream", quality, interval, max_height}` 控制设备端推流参数
- 帧处理队列：`processingFrame` flag + `pendingFrame` 保持最新，防止积压
- 指数退避重连：初始 1s，最大 16s，`ev.code === 1000` 正常关闭不重连

**返回值**：`{ frame, mode, browserId, error, fps, tier, bandwidth }`

---

## IDE 工作台（IdeWorkbench + components/ide/）

**IdeWorkbench.tsx**：全屏三栏布局，自身管理所有 IDE 状态（不依赖全局 store）

| 子组件 | 职责 |
|--------|------|
| `FileTreePanel.tsx` | 设备文件树（展开/折叠/新建/删除），双击打开文件到 Tab |
| `EditorPanel.tsx` | Monaco Editor 多 Tab（dirty 标记/保存），调用 file/write API |
| `RunToolbar.tsx` | 项目运行/停止/状态轮询，连接 WebSocket 日志流 |
| `LogPanel.tsx` | 运行日志实时展示（ANSI 颜色解析），滚动锁定 |
| `DevToolPanel.tsx` | 开发工具：屏幕截图（Canvas 交互）/ UI 树检查 / OCR / 图色 / 坐标点击 |
| `PipManagerPanel.tsx` | pip 包管理（列表/搜索/安装/卸载/升级/outdated） |
| `ApkBuilderPanel.tsx` | APK 打包配置与构建（appName/包名/图标/加密选项） |
| `ProjectInitDialog.tsx` | 新建项目向导（生成 project.config + main.py 模板） |
| `monacoSetup.ts` | Monaco 语言注册（Python 高亮 + Yyds.Auto API 自动补全） |
| `yydsApiData.ts` | Yyds.Auto Python API 数据（函数签名/文档，供 Monaco 补全） |
| `flow/FlowEditorPanel.tsx` | 可视化流程编辑器（节点图，Canvas 渲染） |
| `flow/FlowNodePalette.tsx` | 节点类型选择面板 |
| `flow/FlowNodeConfig.tsx` | 节点属性配置面板 |
| `flow/flowCodeGen.ts` | 流程图 → Python 代码生成 |
| `designer/` | UI 设计器（ui.yml 可视化编辑，预览 Android 浮窗 UI） |

---

## 类型定义（types.ts）

所有接口集中在 `src/types.ts`，主要类型：

- **`Device`**：`imei, model, screen_width, screen_height, version, online, connected_at, last_seen, running_project, foreground_app, stream_viewers`
- **`UserInfo`**：`id, username, role('admin'|'user'), created_at`
- **`Schedule`**：`id, name, cron_expr, action, params, device_ids, enabled, last_run`
- **`IdeTab`**：`path, name, content, dirty, language`
- **`UiNode`**：完整 Android AccessibilityNodeInfo 镜像（text/resourceId/bounds/children/...）
- **`FlowNode`/`FlowEdge`/`FlowGraph`**：流程编辑器数据模型
- **`PackageBuildConfig`**：APK 打包参数（appName/版本/包名/图标/加密等）
- **`PipPackage`/`PipOutdatedPackage`/`PipSearchResult`**：pip 管理相关

---

## i18n

- 使用 `i18next` + `react-i18next` + `i18next-browser-languagedetector`
- 翻译文件在 `src/i18n/` 目录下，按语言代码分文件
- 在 `src/App.tsx` 顶部 `import '@/i18n'` 初始化
- 组件内使用 `const { t } = useTranslation()`

---

## 测试

测试文件在 `src/__tests__/`，使用 `vitest` + `@testing-library/react` + `jsdom`。

```bash
npm run test        # 单次运行所有测试
npm run test:watch  # 监听模式（开发时）
```

---

## 关键约定与注意事项

1. **路径别名**：`@/` → `src/`（Vite alias，`tsconfig.json` 已配置）
2. **样式**：仅使用 TailwindCSS utility class，不写独立 CSS 文件（`index.css` 除外）
3. **组件规范**：
   - 页面级组件在 `pages/`，可复用组件在 `components/`
   - IDE 子组件全部放 `components/ide/`，由 `IdeWorkbench` 统一管理状态
4. **API 调用**：通过 `services/api.ts` 中的封装函数调用，或在组件内直接 `fetch`（附带 `Authorization` 头）
5. **错误处理**：API 错误通过 `status.error(msg)` 展示到全局状态栏，不用 alert/toast
6. **批量操作**：`useAppStore.selectedDevices` 存选中设备集合，`Dashboard.tsx` 提供批量 shell/start/stop/IME/安装 APK
7. **屏幕流**：`useStreamConnection` 管理所有连接生命周期，组件 unmount 时自动 cleanup（revoke blob URL + 关闭 WS）
8. **暗色模式**：`useThemeStore` 切换 `document.documentElement.classList` 的 `dark` class，TailwindCSS dark 变体响应
9. **新增页面**：在 `App.tsx` 的 `<Routes>` 中注册，需要登录的放 `RequireAuth` 内，管理员专属放 `RequireAdmin` 内
10. **禁止事项**：不引入 Ant Design / MUI 等第三方 UI 库，不使用 CSS Modules，不硬编码 API 路径（统一在 `services/api.ts`）
