# Yyds Console — 多设备 Android RPA 控制台前端

支持 200+ 设备公网部署的集中管理平台，内置 Web IDE、可视化流程编辑器、UI 设计器、实时屏幕镜像与触控转发。

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 框架 | React | 19 |
| 语言 | TypeScript | 5.9 |
| 构建 | Vite | 7.3 |
| 样式 | Tailwind CSS (Vite 插件) | 4 |
| 路由 | react-router-dom | 7 |
| 状态管理 | Zustand | 5 |
| 数据请求 | TanStack Query | 5 |
| 代码编辑器 | Monaco Editor | 4.7 |
| 图标 | lucide-react | — |
| 通知 | sonner | — |

## 项目结构

```
src/
├── main.tsx                    # 应用入口
├── App.tsx                     # 路由定义 + Auth Guard + QueryClient
├── index.css                   # Tailwind 全局样式
├── store.ts                    # Zustand 状态（Auth持久化 + App状态）
├── types.ts                    # 全局 TypeScript 类型定义
├── services/
│   └── api.ts                  # API 客户端（8个命名空间，自动鉴权+401登出）
├── hooks/
│   ├── useDevices.ts           # 设备列表/详情轮询 Hook
│   └── useStreamConnection.ts  # 屏幕流连接（WebRTC P2P → WS 回退）
├── components/
│   ├── Layout.tsx              # 侧边栏导航 + Outlet 布局
│   ├── LogcatPanel.tsx         # 日志面板组件
│   └── ide/                    # Web IDE 子系统
│       ├── RunToolbar.tsx      # 运行工具栏（项目选择/运行/停止/推送）
│       ├── FileTreePanel.tsx   # 文件树面板
│       ├── EditorPanel.tsx     # Monaco 代码编辑器（Python 智能提示）
│       ├── LogPanel.tsx        # 实时日志流（WebSocket）
│       ├── DevToolPanel.tsx    # 开发助手（截图交互/控件树/坐标拾取）
│       ├── PipManagerPanel.tsx # Pip 包管理器
│       ├── ApkBuilderPanel.tsx # APK 打包向导
│       ├── TerminalPanel.tsx   # 终端面板
│       ├── ProjectInitDialog.tsx # 新建项目对话框
│       ├── monacoSetup.ts      # Monaco Python 语言配置
│       ├── yydsApiData.ts      # Yyds.Auto API 补全数据
│       ├── flow/               # 可视化流程编辑器
│       │   ├── FlowEditorPanel.tsx   # SVG 画布（平移/缩放/连线）
│       │   ├── FlowNodeRenderer.tsx  # 节点渲染
│       │   ├── FlowNodePalette.tsx   # 节点面板（拖拽）
│       │   ├── FlowNodeConfig.tsx    # 节点属性编辑
│       │   ├── flowTypes.ts          # 流程图类型定义
│       │   └── flowCodeGen.ts        # 拓扑排序 → Python 代码生成
│       └── designer/           # UI 配置设计器
│           ├── UiDesignerPanel.tsx    # 设计器主面板
│           ├── UiPhoneMock.tsx        # 手机模拟预览
│           ├── UiPropertyEditor.tsx   # 属性编辑器
│           └── uiYamlCodec.ts        # ui.yml 解析/序列化
└── pages/
    ├── Dashboard.tsx           # 仪表盘（统计卡片 + 设备缩略图网格）
    ├── DeviceList.tsx          # 设备列表（表格 + 批量操作）
    ├── DeviceDetail.tsx        # 设备详情（屏幕镜像 + 触控 + Shell）
    ├── FileBrowser.tsx         # 文件管理器（面包屑/目录浏览/上传下载）
    ├── LogViewer.tsx           # 日志查看器（WS 实时流/过滤/导出）
    ├── Schedules.tsx           # 定时任务管理（Cron CRUD）
    ├── Login.tsx               # 登录/注册
    ├── MyDevices.tsx           # 我的设备（绑定/解绑）
    ├── AdminPanel.tsx          # 管理后台（用户管理/设备绑定管理）
    ├── TemplateMarket.tsx      # 模板市场（浏览/发布/下载脚本模板）
    └── IdeWorkbench.tsx        # Web IDE 工作台（多面板可调布局）
```

## 架构设计

### 路由与鉴权

采用 react-router-dom v7 嵌套路由，两层 Guard 保护：

- `RequireAuth` — 检查 Zustand 持久化的 JWT token，未登录重定向 `/login`
- `RequireAdmin` — 检查 `user.role === 'admin'`，非管理员重定向首页

共 11 个页面路由，设备相关路由支持嵌套（文件/日志/IDE 通过 `:imei` 参数关联）。

### 状态管理

两个 Zustand Store 职责分离：

- `useAuthStore` — JWT token + 用户信息，通过 `zustand/persist` 持久化到 localStorage（key: `yyds-auth`）
- `useAppStore` — 侧边栏折叠状态 + 每设备连接模式（WebRTC/WS）映射

### 数据请求

TanStack Query 统一管理服务端状态：

- 设备列表 3 秒轮询，设备详情 5 秒轮询
- 全局配置：`retry: 1`、`staleTime: 5s`、关闭窗口聚焦刷新
- API 客户端自动附加 `Authorization: Bearer` 头，401 响应触发自动登出

### API 客户端

`services/api.ts` 提供 8 个命名空间，所有请求通过统一的 `fetchJson()` 处理：

| 命名空间 | 职责 |
|----------|------|
| `authApi` | 登录/注册/用户信息/改密 |
| `deviceApi` | 设备列表/截图/触控/Shell/项目控制 |
| `projectApi` | 项目列表/状态 |
| `fileApi` | 文件 CRUD/上传下载 |
| `scheduleApi` | 定时任务 CRUD/开关 |
| `adminApi` | 用户管理/设备绑定管理/统计 |
| `ideApi` | IDE 文件操作/Pip 管理/APK 打包 |
| `templateApi` | 模板市场 CRUD |

### 屏幕流连接

`useStreamConnection` 实现双模式屏幕流，确保不同网络环境下的最佳体验：

```
Auto 模式（默认）:
  1. 尝试 WebRTC DataChannel P2P 连接
     ├─ 通过 /ws/stream/:imei/signal 交换 SDP/ICE
     └─ DataChannel "screen" 接收 JPEG 二进制帧
  2. 5 秒超时 → 回退 WebSocket 二进制流
     └─ /ws/stream/:imei?quality=&interval= 接收 Blob → ObjectURL
```

也可手动指定 `webrtc` 或 `websocket` 模式。

## 开发

### 环境要求

- Node.js >= 18
- pnpm / npm / yarn

### 启动开发服务器

```bash
npm install
npm run dev
```

开发服务器运行在 `http://localhost:5173`，自动代理：

| 路径 | 目标 |
|------|------|
| `/api/*` | `http://127.0.0.1:8818`（Rust 后端 HTTP） |
| `/ws/*` | `ws://127.0.0.1:8818`（Rust 后端 WebSocket） |

确保后端服务已在 8818 端口运行。

### 构建生产版本

```bash
npm run build
```

产物输出到 `dist/`，由后端 Axum 作为 SPA 静态文件 serve（带 fallback）。

### 代码检查

```bash
npm run lint
```

### 路径别名

`vite.config.ts` 配置了 `@` → `src/` 的路径别名，所有导入使用 `@/` 前缀。

## Web IDE 子系统

IdeWorkbench 是一个功能完整的浏览器端 IDE，包含可调整大小的多面板布局：

- 代码编辑 — Monaco Editor，内置 Yyds.Auto Python API 的自动补全、悬停文档和签名帮助
- 可视化流程编辑 — SVG 画布上的节点式编程，支持 15+ 节点类型（click、swipe、ocr_find、condition、loop 等），拓扑排序生成 Python 代码
- UI 设计器 — 可视化编辑 `ui.yml`，手机模拟预览，支持 text/div/check/select/edit 等组件
- 开发助手 — 设备截图交互、UI 控件树 dump 与覆盖绘制、坐标拾取插入代码
- Pip 管理 — 搜索 PyPI、安装/卸载/升级包
- APK 打包 — 配置应用名/图标/包名/加密，一键构建独立 APK

## 关键约定

- 所有 WebSocket 连接通过 query param 传递 auth token（`?token=`）
- 设备通过 IMEI 唯一标识，贯穿路由参数和 API 调用
- 后端 API 统一返回 `{ ok: boolean, data?, error? }` 格式
- 生产部署时前端构建产物由 Rust 后端直接 serve，无需独立的静态文件服务器
