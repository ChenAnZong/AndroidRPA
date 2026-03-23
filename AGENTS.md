安卓平台RPA脚本开发
使用python开发自动化脚本，要求ROOT权限或者SHELL权限, 这是脚本引擎的工作进程yyds-android\app\src\main\java\pyengine\Main.java
包含安卓App、VSCode开发插件、MCP Server、多设备控制台、Python模版工程

## 项目目录结构

```
RPA/
├── AGENTS.md                           # 项目摘要文档
├── yyds-android/                       # 安卓App主工程
│   └── app/src/main/
│       ├── AndroidManifest.xml         # 清单文件（权限、组件声明）
│       ├── jni/                        # Native层
│       │   ├── CMakeLists.txt          # 构建配置（libai.so + yyds_keep + script_crypto + cpython_bridge）
│       │   ├── keeper.cpp              # yyds.keep native守护进程（独立可执行文件）
│       │   ├── cpython_bridge.cpp      # CPython JNI桥接（嵌入式Python调用）
│       │   ├── script_crypto.c         # 脚本加密白盒密钥派生（AES-256-GCM）
│       │   ├── main.cpp / image.cpp    # 图像处理、OCR、YOLO推理
│       │   └── ncnn/                   # NCNN推理库（OCR/YOLO模型）
│       └── java/
│           ├── pyengine/               # Python脚本引擎核心
│           │   ├── Main.java           # 工作进程入口（ROOT/SHELL权限运行）
│           │   ├── PyEngine.kt         # Python引擎管理（初始化、启动/停止项目、pip路径）
│           │   ├── CPythonBridge.kt    # CPython JNI桥接Kotlin封装（替代Chaquopy）
│           │   ├── PyProcess.kt        # 独立子进程运行单个项目（进程隔离）
│           │   ├── EngineClient.kt     # App进程与工作进程通信客户端（HTTP REST + WebSocket日志流）
│           │   ├── EngineProtocol.kt   # RPC方法常量与数据键定义
│           │   ├── WebSocketAsServer.kt# 工作进程HTTP/WebSocket服务（Ktor CIO, 端口61140, 全部REST API）
│           │   ├── WebSocketAsClient.kt# 连接yyds-con控制台的WebSocket客户端（设备端）
│           │   ├── WebRtcDataChannel.kt# WebRTC DataChannel存根（P2P屏幕流，待启用）
│           │   ├── HandleApiServerConnection.kt # 公网服务器WebSocket RPC处理（旧远程控制）
│           │   ├── YyProject.kt        # 脚本项目数据模型与扫描逻辑
│           │   ├── YyProjectUtil.kt    # 项目ZIP解压工具
│           │   ├── ApkPackageHelper.kt # APK打包助手（脚本→独立APK，V1签名）
│           │   ├── ApkV1Signer.java    # APK V1签名实现
│           │   ├── BinaryXmlEditor.java# Android二进制XML编辑（修改resources.arsc）
│           │   ├── ScriptEncryptor.kt  # 脚本加密器（AES-256-GCM + 白盒密钥 + PBKDF2）
│           │   ├── PyOut.kt            # 日志输出管理（Java侧队列，Python通过JNI回调）
│           │   ├── RpcDataModel.kt     # RPC数据模型（JSON序列化）
│           │   ├── ContextUtil.java    # 系统Context工具 + native库路径注册
│           │   ├── ShareReflectUtil.java # 反射工具
│           │   └── ZipUtility.java     # 压缩解压工具
│           ├── com/tencent/yyds/       # App UI层
│           │   ├── App.kt              # Application
│           │   ├── MainActivity.kt     # 主Activity（导航、权限、侧边栏）
│           │   ├── frag/
│           │   │   ├── HomeFragment.kt     # 首页
│           │   │   ├── ScriptFragment.kt   # 脚本项目列表页
│           │   │   └── RemoteFragment.kt   # 远程控制页
│           │   ├── PackageActivity.kt      # APK打包配置页（应用名、图标、包名、运行行为）
│           │   ├── RunnerActivity.kt       # Runner模式Activity（打包后APK的控制中心）
│           │   ├── PipManagerActivity.kt   # Pip包管理器（搜索PyPI、安装/卸载/升级）
│           │   ├── FileBrowserActivity.kt  # 文件浏览器（ROOT权限浏览设备文件系统）
│           │   ├── LogcatActivity.kt       # 运行日志页
│           │   ├── ProjectConfigActivity.kt# 项目配置页
│           │   ├── FloatingWindowService.kt# 悬浮窗服务（脚本控制+UI布局检查器）
│           │   ├── FloatingLogService.kt   # 悬浮日志控制台服务
│           │   ├── ShizukuUtil.kt          # Shizuku免ROOT启动引擎
│           │   ├── YypListAdapter.kt       # 项目列表RecyclerView适配器
│           │   ├── YypListViewHolder.kt    # 项目列表ViewHolder
│           │   ├── inspector/              # UI布局检查器
│           │   │   ├── UiInspectorView.kt  # 控件树覆盖绘制视图（Canvas）
│           │   │   ├── UiNode.kt           # 控件节点数据模型
│           │   │   └── NodeTreeAdapter.kt  # 控件树列表适配器
│           │   └── widget/
│           │       └── AppBanner.kt        # 应用横幅组件
│           ├── common/                 # 公共组件
│           │   ├── BootService.kt      # 前台核心服务（通知控制、项目切换）
│           │   ├── BootReceiver.kt     # 开机广播接收器
│           │   └── BootProvider.kt     # ContentProvider
│           ├── uiautomator/            # 自动化引擎API
│           │   ├── AppProcess.java     # 进程管理（app_process启动命令、native keeper路径）
│           │   ├── ExportApi.java      # 自动化引擎API导出
│           │   ├── ExportHandle.java   # 引擎通信句柄
│           │   ├── ExportHttp.java     # HTTP接口导出（JSON参数转换）
│           │   ├── ExtSystem.java      # 系统Shell工具
│           │   └── tool/               # 截图、显示控制等工具
│           ├── image/                  # 图像处理（OCR、颜色识别）
│           ├── noadb/                  # 屏幕编解码
│           └── scrcpy/                 # 剪贴板、显示辅助
├── CPython-android/                    # CPython交叉编译与嵌入式桥接
│   ├── scripts/build-cpython.sh       # WSL交叉编译CPython脚本（--enable-shared）
│   ├── build.bat                      # Windows一键编译入口
│   ├── python-shims/                  # Python兼容层模块（打包进APK assets）
│   │   ├── pyengine.py               # PyOut回调shim（JNI _yyds_bridge C扩展）
│   │   ├── entry.py                  # 脚本入口（子进程模式：加载并运行用户项目）
│   │   └── _android_bootstrap.py     # Android适配层（SSL/locale/tempfile/HOME等）
│   ├── runtime/                       # 纯Python运行时模块（替代部分Kotlin逻辑的Python实现）
│   │   ├── server.py                 # aiohttp HTTP+WS服务器（替代Ktor，端口61140）
│   │   ├── project_manager.py        # 项目管理（扫描/启动/停止/运行代码片段）
│   │   ├── auto_engine_proxy.py      # yyds.auto自动化引擎HTTP代理
│   │   ├── log_manager.py            # 日志管理（stdout/stderr收集 → WS /log推送）
│   │   ├── screen_capture.py         # 截图模块（screencap命令/yyds.auto代理）
│   │   ├── file_ops.py              # 文件操作（ROOT权限文件系统访问）
│   │   ├── console.py               # 悬浮日志控制台Python API（console.log/warn/error等）
│   │   ├── process_guard.py         # 守护线程（监控yyds.auto和yyds.keep，异常重启）
│   │   ├── agent.py                 # Agent主循环（单VLM调用/步，AutoGLM-Phone兼容）
│   │   ├── prompts.py               # Prompt模板（AutoGLM-Phone + 通用VLM双格式）
│   │   ├── action_parser.py         # 动作解析器（AST安全解析pseudo-code，正则回退）
│   │   ├── observation.py           # 智能观测层（UI XML压缩/OCR格式化/Activity信息注入）
│   │   ├── agent_tools.py           # Agent工具注册表（click/swipe/ocr/shell等原子工具）
│   │   ├── agent_executor.py        # Python沙箱执行器（Agent生成代码安全运行）
│   │   ├── agent_config.py          # Agent配置管理（持久化到/sdcard/Yyds.Auto/agent.json）
│   │   ├── agent_skills.py          # Skills快速路径（关键词匹配/DeepLink/GUI自动化指引）
│   │   ├── vlm_client.py            # VLM客户端（OpenAI兼容，16+服务商预置）
│   │   ├── skills.json              # Skills定义文件（预置技能库）
│   │   └── requirements.txt         # 运行时依赖（aiohttp, requests, pyyaml等）
│   ├── include/                       # [编译生成] CPython 3.13 头文件
│   └── libs/arm64-v8a/               # [编译生成] libpython3.13.so
├── yyds-auto-vscode-extension/        # VSCode开发插件（替代IntelliJ插件）
│   ├── src/
│   │   ├── extension.ts              # 插件入口（激活条件：workspaceContains:project.config）
│   │   ├── statusBar.ts              # 底部状态栏（连接状态、快捷操作）
│   │   ├── engine/
│   │   │   ├── connector.ts          # 通讯连接器（HTTP REST + WS日志流）
│   │   │   ├── implement.ts          # 引擎操作实现（截图/推送/运行/打包等）
│   │   │   ├── protocol.ts           # HTTP REST API路径定义与响应类型
│   │   │   ├── projectServer.ts      # 项目服务（配置读取、文件扫描、引擎调用封装）
│   │   │   ├── adbManager.ts         # ADB管理器（自动探测/按需下载/命令执行）
│   │   │   ├── engineActivator.ts    # 引擎激活器（通过ADB在非ROOT设备启动工作进程）
│   │   │   └── logger.ts             # 调试日志OutputChannel
│   │   ├── commands/
│   │   │   ├── projectCommands.ts    # 项目命令（推送/运行/停止/打包等）
│   │   │   └── initProjectCommand.ts # 初始化项目（创建project.config等模板文件）
│   │   ├── views/
│   │   │   ├── devToolView.ts        # 开发助手面板（截图交互、控件树、区域选择，WebView Canvas）
│   │   │   ├── uiDesignerView.ts     # UI配置设计器（可视化ui.yml编辑，类Qt Designer）
│   │   │   ├── logView.ts            # 日志面板（WebView实时日志流）
│   │   │   └── sidebarViews.ts       # 侧边栏视图（设备信息树、操作列表树）
│   │   ├── language/
│   │   │   ├── yydsApiData.ts        # Yyds.Auto Python API数据（自动补全/悬停/签名）
│   │   │   ├── completionProvider.ts # 代码补全提供者
│   │   │   ├── hoverProvider.ts      # 悬停提示提供者
│   │   │   └── signatureHelpProvider.ts # 函数签名帮助
│   │   └── utils/
│   │       ├── xmlFormatter.ts       # XML格式化工具
│   │       └── zipUtil.ts            # ZIP压缩工具
│   └── package.json                   # 插件配置（命令、视图、激活条件、设置项）
├── yyds-auto-mcp/                     # MCP Server（让LLM直接操控安卓设备）
│   ├── src/
│   │   ├── index.ts              # MCP入口（stdio transport, 注册tools/resources）
│   │   ├── client.ts             # HTTP客户端（连接设备端口61140）
│   │   └── tools/                # ~35个工具，10个分类
│   │       ├── device.ts         # 设备信息（型号、屏幕、IMEI、前台应用）
│   │       ├── touch.ts          # 触控（点击、滑动、长按、按键、文本输入）
│   │       ├── screen.ts         # 截图（返回base64图片供LLM查看）
│   │       ├── ui.ts             # UI自动化（控件树dump、按属性查找控件）
│   │       ├── ocr.ts            # OCR与图像（屏幕OCR、模板匹配、取色）
│   │       ├── shell.ts          # Shell命令（ROOT权限执行）
│   │       ├── app.ts            # 应用管理（启动/停止/列表）
│   │       ├── file.ts           # 文件操作（读写/列目录/删除）
│   │       ├── project.ts        # 脚本项目管理（列表/启停/执行代码）
│   │       └── pip.ts            # Pip包管理（安装/卸载/列表）
│   ├── package.json
│   └── tsconfig.json
├── yyds-con/                          # 多设备Android RPA控制台（支持200+设备公网部署）
│   ├── backend/                       # Rust + Axum（端口8818）
│   │   └── src/
│   │       ├── main.rs               # Axum路由, SPA fallback, CORS, 压缩, 调度器
│   │       ├── config.rs             # 配置常量
│   │       ├── error.rs              # 错误类型
│   │       ├── device/
│   │       │   ├── protocol.rs       # 设备WS协议（DeviceMessage/ServerCommand）
│   │       │   ├── registry.rs       # 设备注册表（DashMap, 广播通道）
│   │       │   ├── connection.rs     # 设备WebSocket连接处理
│   │       │   └── signaling.rs      # WebRTC SDP/ICE信令中继
│   │       ├── api/
│   │       │   ├── devices.rs        # 设备列表/详情API
│   │       │   ├── stream.rs         # 截图流API（WS中继 + WebRTC信令）
│   │       │   ├── control.rs        # 设备控制API（触控/按键/Shell/启停项目）
│   │       │   ├── projects.rs       # 项目管理API
│   │       │   ├── files.rs          # 文件管理API
│   │       │   ├── logs.rs           # 日志流API
│   │       │   ├── rpc.rs            # 通用RPC转发
│   │       │   └── scheduler.rs      # 定时任务CRUD
│   │       └── scheduler/
│   │           └── engine.rs         # Cron调度引擎（30秒tick循环）
│   ├── frontend/                      # React 19 + TS + Tailwind v4 + TanStack Query
│   │   └── src/
│   │       ├── App.tsx               # 路由（7页面）+ QueryClient + Toaster
│   │       ├── components/Layout.tsx  # 侧边栏导航布局
│   │       ├── pages/
│   │       │   ├── Dashboard.tsx     # 仪表盘（统计 + 设备缩略图网格）
│   │       │   ├── DeviceList.tsx    # 设备列表（表格 + 批量操作）
│   │       │   ├── DeviceDetail.tsx  # 设备详情（屏幕查看 + 触控 + Shell）
│   │       │   ├── FileBrowser.tsx   # 文件管理器（目录列表, 面包屑, 创建/删除）
│   │       │   ├── LogViewer.tsx     # 日志查看器（WS实时流, 过滤, 导出）
│   │       │   └── Schedules.tsx     # 定时任务管理（Cron CRUD表格）
│   │       ├── hooks/
│   │       │   ├── useDevices.ts     # 设备列表Hook
│   │       │   └── useStreamConnection.ts # 屏幕流连接（WebRTC P2P → 5s超时 → WS回退）
│   │       ├── services/api.ts       # API客户端
│   │       ├── store.ts              # Zustand状态管理
│   │       └── types.ts              # TypeScript类型定义
│   └── README.md
├── yyds-auto-py/                      # PyPI包（PC端Python控制Android设备，类似uiautomator2）
│   ├── pyproject.toml                # 包配置（PEP 621, hatchling构建）
│   ├── README.md                     # 使用文档
│   ├── LICENSE                       # MIT协议
│   └── yyds_auto/                    # 包源码
│       ├── __init__.py               # 公开API: connect(), connect_usb(), connect_wifi(), discover()
│       ├── __main__.py               # CLI: python -m yyds_auto version/init/doctor/discover/shell
│       ├── version.py                # __version__
│       ├── device.py                 # Device核心类（组合所有Mixin）
│       ├── adb.py                    # ADB管理（查找/设备列表/端口转发/shell执行）
│       ├── client.py                 # HTTP REST客户端（requests封装，对接端口61140）
│       ├── setup.py                  # 设备初始化（APK安装检测、引擎三级启动策略）
│       ├── discover.py               # 局域网设备自动扫描（并发TCP探测+信息采集）
│       ├── selector.py               # UiSelector + UiObject元素选择器
│       ├── exceptions.py             # 自定义异常
│       ├── types.py                  # 数据模型（DeviceInfo, ShellResponse, OcrResult等）
│       ├── mixins/                   # 功能Mixin模块
│       │   ├── touch.py              # 触控: click, swipe, long_press, drag
│       │   ├── input.py              # 输入: send_keys, press, clipboard
│       │   ├── screen.py             # 截图: screenshot, dump_hierarchy, window_size
│       │   ├── app.py                # 应用: app_start, app_stop, app_list, app_current
│       │   ├── shell.py              # Shell: shell(), adb_shell()
│       │   ├── file.py               # 文件: push, pull, list_files, read_file, write_file
│       │   ├── ocr.py                # OCR: ocr, ocr_find, ocr_click, find_image
│       │   └── project.py            # 项目: list_projects, start_project, run_code
│       └── assets/                   # 内置APK（可选）
├── yyds.-auto_-py-projcet/            # Python脚本模版工程（用户项目模板）
│   ├── main.py                       # 脚本入口
│   ├── project.config                # 项目配置文件
│   └── test/
│       └── run_on_device.py          # 设备运行测试
└── yyds-doc-website/                  # 文档站点（待建设）
```

## 架构说明

### 多进程架构
- **App进程** (uid > 10000): UI界面进程，运行Activity/Fragment/Service，无ROOT权限
- **自动化引擎** (uiautomator.ExportApi): ROOT/SHELL权限运行
  - 启动命令: `AppProcess.getActiveEngineCmd()`
  - 进程名: `yyds.auto`
  - 提供: 截图、点击、UI控件识别、图像识别等自动化API
  - 由 `yyds.keep` native守护进程派生启动
- **Python脚本引擎** (pyengine.Main): ROOT/SHELL权限运行
  - 启动命令: `AppProcess.getActivePyEngineCmd()`
  - 进程名: `yyds.py`
  - 端口: `61140`（HTTP + WebSocket服务，Ktor CIO）
  - Python引擎: **CPython 3.13 嵌入式**（通过 JNI Bridge 调用 libpython3.13.so）
  - 替代了原来的 Chaquopy 方案，提供完整 pip 支持
  - 内置守护线程监控 `yyds.auto` 和 `yyds.keep`
  - 内置 `WebSocketAsClient` 连接 yyds-con 控制台服务器
- **守护进程** (jni/keeper.cpp → libyyds_keep.so): **Native进程**，ROOT/SHELL权限运行
  - 启动命令: `AppProcess.getActiveEngineKeeperCmd()`
  - 进程名: `yyds.keep`
  - **独立native二进制，不依赖zygote/app_process**，减少系统对脚本进程的干扰
  - 默认最先启动，负责派生启动 `yyds.auto` 和 `yyds.py`
  - 每10秒检查 `yyds.auto`，每15秒检查 `yyds.py`（pidof + HTTP探测）
  - 发现进程异常则自动重启
  - 检测APK更新（文件修改时间），更新后重启所有工作进程

### 进程互相守护机制
三个工作进程形成三角守护，确保系统高可用：
```
  yyds.keep (native守护进程，最先启动)
   ↙ 派生+10s检查  ↘ 派生+15s检查
yyds.auto          yyds.py
(app_process)   (app_process, 30s检查auto+keep)
```
- `yyds.keep`（native进程）最先启动，派生并监控 `yyds.auto`（每10秒）和 `yyds.py`（每15秒），异常时自动重启
- `yyds.py` 内置守护线程监控 `yyds.auto`（每30秒）和 `yyds.keep`（每30秒），异常时自动重启
- 任一进程挂掉，其他进程可检测并重启，实现无单点故障

### 通信机制（已迁移为 JSON + HTTP REST）
**核心变更**: 早期使用CBOR序列化的WebSocket RPC (`/api`)，现已全面迁移为 **JSON + HTTP REST**，WebSocket仅保留日志流和截图流。

- **HTTP REST API** (`http://127.0.0.1:61140/`): **所有控制命令**均通过HTTP REST
  - 项目管理: `GET /project/list`, `/project/status`, `/project/start?name=`, `/project/stop`
  - 引擎控制: `POST /engine/run-code`, `/engine/reboot`, `/engine/shell`, `/engine/click`, `/engine/auto`
  - 截图与UI: `GET /screenshot`, `/screen/{quality}`, `/ui-dump`
  - 文件操作: `GET /file/list?path=`, `/file/read-text?path=`, `/file/exists?path=`, `POST /file/write-text`, `/file/rename`, `/file/delete`, `/file/mkdir`
  - 文件传输: `GET /pull-file?path=`, `POST /post-file` (multipart), `POST /push-project` (multipart)
  - Pip管理: `GET /pip/list`, `/pip/outdated`, `/pip/show?name=`, `/pip/search?name=`, `POST /pip/install`, `/pip/uninstall`, `/pip/upgrade`
  - APK打包: `POST /package/build`, `GET /package/list`, `/package/download?path=`, `/package/installed-apps`, `/package/app-icon?pkg=`, `POST /package/extract`
  - 悬浮控制台: `POST /console/show`, `/console/hide`, `/console/close`, `/console/clear`, `/console/log`, `/console/set-alpha`, `/console/set-size`, `/console/set-position`, `/console/set-title`
  - 自动化代理: `POST /api/{api}` (透传到yyds.auto)
- **WebSocket /log**: 实时日志流（唯一保留的WS通道之一）
- **WebSocket /shot/{quality}/{count}/{interval}**: 截图流
- **Binder/HTTP**: App进程通过 `ExportHandle` 与自动化引擎通信（系统服务注册或HTTP回退）
- **Shell命令**: 通过 `ExtSystem.shell()` 执行系统命令（需ROOT/SHELL权限）

### 脚本项目子进程隔离
**核心变更**: 每个脚本项目在独立的 `app_process` 子进程中运行，而非在 yyds.py 主进程内线程运行。

- **启动**: `Runtime.exec("sh -c ... exec app_process pyengine.PyProcess '<name>'")`
- **停止**: `proc.destroy()` (SIGTERM) → 500ms → `proc.destroyForcibly()` (SIGKILL) → **100%确定性终止**
- **状态**: `proc.isAlive`
- **日志**: 子进程stdout/stderr通过管道 → 父进程PyOut → WebSocket /log
- **环境变量**: `YYDS_SUBPROCESS=1` 告知 cpython_bridge.cpp 跳过fd重定向（子进程stdout走管道到父进程）
- **代码片段**: `runCodeSnippet()` 仍在主进程CPython中执行（轻量级、交互式）

### 项目扫描流程
- 脚本项目存放在 `/storage/emulated/0/Yyds.Py/` 目录下
- 项目扫描（`YyProject.scanProject()`）仅在**工作进程**中执行，因其拥有ROOT/SHELL权限可直接访问文件系统
- App进程通过 `EngineClient.getProjectList()` 调用工作进程HTTP接口 `/project/list` 获取项目列表
- App进程通过 `EngineClient.ensureEngineRunning()` 确保所有引擎已启动，未启动则自动用ROOT权限启动
- 这样App无需申请"所有文件访问权限"（MANAGE_EXTERNAL_STORAGE），降低了用户操作门槛

### 引擎启动流程
- `EngineClient.ensureEngineRunning()` -> `PyEngine.startAllEngines()`:
  1. 先释放SO文件（从APK解压到 `/data/local/tmp/cache/lib/<abi>/`）
  2. 启动 `yyds.keep`（native守护进程，路径: `/data/local/tmp/cache/lib/<abi>/libyyds_keep.so`）
  3. `yyds.keep` 自动派生启动 `yyds.auto`（自动化引擎）
  4. `yyds.keep` 等待后派生启动 `yyds.py`（Python脚本引擎）
  5. 等待端口 `61140` 可连接，确认启动成功
- **启动命令注意**: 必须使用 `</dev/null >/dev/null 2>&1 &` 而非 `nohup`（libsu管道环境下nohup无效，会导致SIGPIPE）

### 脚本启动流程
1. 用户在 `ScriptFragment` 选择项目 -> 调用 `YyProject.start()`
2. `start()` 先调用 `EngineClient.ensureEngineRunning()` 确保所有引擎运行
3. 然后调用 `PyEngine.startProject()` -> HTTP `GET /project/start?name=` 到工作进程
4. 工作进程启动子进程 `PyProcess` -> CPython初始化 -> `entry.run_project()` 执行用户脚本

### CPython嵌入式运行时
- **Python版本**: CPython 3.13.2（交叉编译，`--enable-shared`）
- **PYTHONHOME**: `/data/local/tmp/python3`（需通过adb push部署标准库）
- **LD_LIBRARY_PATH**: 在 `AppProcess` 和 `keeper.cpp` 中均设置，确保能加载 `libpython3.13.so`
- **初始化流程**: `nativeInit()` → `Py_InitializeFromConfig()` → `PyEval_SaveThread()`（释放GIL避免死锁）
- **Python shims**: 从APK assets提取到 `/data/local/tmp/cache/python-shims/`
- **pyengine.py shim**: 运行时由 `PyEngine.writePyengineShim()` 内联生成（避免AAPT2同名包过滤）

### APK打包功能
将Python脚本项目打包为独立可安装的APK：
- **打包器**: `ApkPackageHelper.kt` — 读取模板APK → 注入脚本到assets/project/ → 注入pack_config.json → 修改应用名(resources.arsc) → 替换图标 → V1签名
- **配置页**: `PackageActivity.kt` — 用户配置应用名、版本号、包名、图标、运行行为开关
- **Runner模式**: `RunnerActivity.kt` — 打包后APK的运行控制中心（自动运行、保持常亮、悬浮控制等）
- **脚本加密**: 可选AES-256-GCM加密（7层安全: Native白盒密钥 + 反调试 + Python混淆 + GCM + 随机IV/Salt + PBKDF2 100K轮 + 运行时权限保护）
- **加密文件格式** (.pye): `Magic("YENC") | Version(0x02) | IV(12B) | Ciphertext+GCM-Tag`
- **API端点**: `POST /package/build`, `GET /package/list`, `/package/download`, `/package/installed-apps`, `/package/app-icon`

### 悬浮日志控制台
Python脚本可通过 `console` 对象在屏幕上显示悬浮日志窗口：
- **Python API** (`CPython-android/runtime/console.py`): `console.show()`, `.log()`, `.warn()`, `.error()`, `.clear()`, `.hide()`, `.close()`, `.time()/.time_end()`, `.count()`, `.trace()`, `.table()`, `.json()`, `.set_alpha()`, `.set_size()`, `.set_position()`, `.set_title()`
- **通信链路**: Python console → HTTP `POST /console/*` → PyOut特殊前缀 `##YYDS_CONSOLE##` → WS /log → `EngineClient` 拦截 → `FloatingLogService` 渲染
- **Android服务**: `FloatingLogService.kt` 悬浮窗 + RecyclerView日志列表，支持拖拽、透明度调节、最小化

### VSCode开发插件
替代原有IntelliJ/PyCharm插件，提供完整的脚本开发体验：
- **激活条件**: 工作区包含 `project.config` 文件
- **设备连接**: 通过IP:端口连接设备（HTTP REST + WS日志流）
- **ADB集成**: 自动探测ADB路径 / 按需下载platform-tools / 通过ADB启动引擎（非ROOT设备）
- **开发助手面板**: 截图交互（Canvas绘图）、控件树解析、区域选择、坐标拾取
- **UI设计器**: 可视化 `ui.yml` 编辑（拖拽组件、属性编辑、YAML源码切换）
- **Python智能提示**: Yyds.Auto API自动补全、悬停文档、函数签名帮助
- **核心命令**: 推送项目、运行/停止脚本、执行选中代码、APK打包、Pip管理

### 多设备控制台 (yyds-con)
支持200+设备公网部署的集中管理平台：
- **后端**: Rust + Axum (端口8818)，serve前端静态文件
- **前端**: React 19 + TypeScript + Tailwind CSS v4 + TanStack Query + Zustand
- **设备连接**: 设备端 `WebSocketAsClient.kt` 主动连接服务器 `ws://HOST:8818/ws/device?imei=&model=&ver=&sw=&sh=`
- **屏幕流**: WebRTC DataChannel P2P优先 → 5秒超时 → WS二进制帧回退
- **设备配置文件**: `/sdcard/Yyds.Auto/server.conf` → `{"host":"IP","port":8818}`
- **功能页面**: 仪表盘、设备列表（批量操作）、设备详情（屏幕+触控+Shell）、文件管理、日志查看、定时任务（Cron调度）

### MCP Server (yyds-auto-mcp)
让LLM（如Claude、GPT）直接操控安卓设备：
- **协议**: Model Context Protocol（stdio transport）
- **后端**: HTTP REST客户端连接设备端口61140
- **工具数量**: ~35个工具，覆盖设备信息、触控、截图、UI自动化、OCR、Shell、应用管理、文件操作、项目管理、Pip管理
- **资源**: `device://screenshot`（实时截图）、`device://ui-hierarchy`（控件树XML）、`device://info`（设备信息）

### PyPI包 (yyds-auto-py)
PC端Python自动化库，类似uiautomator2，`pip install yyds-auto` 即可使用：
- **连接方式**: USB自动连接 / 指定序列号 / WiFi IP直连 / 局域网自动扫描
- **设备初始化**: 自动检测APK安装 → 未安装提示安装 → 三级引擎启动策略（am start → keeper → app_process）
- **局域网扫描**: 并发TCP端口探测（128线程） → 设备信息采集 → 一键连接
- **API设计**: 对齐uiautomator2风格（`d.click()`, `d(text="X").click()`, `d.screenshot()`, `d.shell()`）
- **Mixin架构**: TouchMixin + InputMixin + ScreenMixin + AppMixin + ShellMixin + FileMixin + OcrMixin + ProjectMixin
- **元素选择器**: `d(text=, resourceId=, className=, clickable=)` → UiObject（click/wait/set_text/scroll_to）
- **CLI工具**: `python -m yyds_auto version/devices/discover/init/doctor/screenshot/shell`
- **依赖**: requests + Pillow + adbutils

### 关键端口
- `61140`: Python引擎工作进程（HTTP REST + WebSocket）
- `8818`: yyds-con多设备控制台服务器
- `61100`: yyds.auto自动化引擎（内部HTTP）

### Native编译产物 (CMakeLists.txt)
- `libai.so` — 主Native库（图像处理、OCR、YOLO推理）
- `libyyds_keep.so` — yyds.keep守护进程（`add_executable`，打包为.so以便Gradle提取）
- `libscript_crypto.so` — 脚本加密白盒密钥派生
- `libcpython_bridge.so` — CPython JNI桥接库（依赖libpython3.13.so）
- `libpython3.13.so` — CPython运行时（从CPython-android/libs/复制）
