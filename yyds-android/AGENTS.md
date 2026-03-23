# yyds-android — Android RPA 引擎

Android RPA 平台，为 Python 脚本提供设备自动化能力。包含两个独立进程引擎（`yyds.auto` 自动化引擎 + `yyds.py` Python引擎）、一个 Native 守护进程（`yyds.keep`）和 App UI 层。

---

## 目录结构

```
yyds-android/
└── app/src/main/
    ├── AndroidManifest.xml         # 权限 + 组件声明
    ├── jni/                        # Native 层 (C/C++)
    │   ├── CMakeLists.txt          # 构建配置
    │   ├── keeper.cpp              # yyds.keep 守护进程 (独立可执行文件)
    │   ├── cpython_bridge.cpp      # CPython JNI 桥接
    │   ├── script_crypto.c         # 脚本加密白盒密钥派生 (AES-256-GCM)
    │   ├── main.cpp / image.cpp    # 图像处理 (主要图像算法)
    │   ├── ncnn_paddle_ocr.cpp     # PaddleOCR v5 NCNN 推理
    │   ├── ncnn_yolov8.cpp         # YOLOv8 NCNN 推理
    │   └── ncnn/                   # NCNN 库头文件
    └── java/
        ├── pyengine/               # Python 引擎核心 (yyds.py 进程)
        ├── uiautomator/            # 自动化引擎 (yyds.auto 进程)
        ├── com/tencent/yyds/       # App UI 层
        ├── common/                 # 公共 Service 组件
        ├── image/                  # 图像颜色处理
        └── scrcpy/                 # 剪贴板辅助
```

---

## 进程架构（核心概念）

系统由 **3 个独立进程**组成，理解这一点是修改代码的前提：

```
yyds.keep  (native C 二进制，/data/local/tmp/cache/lib/<abi>/libyyds_keep.so)
    │  启动 + 监控 (10s/15s 心跳)
    ├─→ yyds.auto  (app_process, uiautomator.ExportApi.main)
    │       提供触控/截图/OCR/UI等自动化能力，HTTP 端口 49009/61100
    └─→ yyds.py   (app_process, pyengine.Main.main)
            运行 Python 脚本，HTTP+WS 服务端口 61140
            内置 CPython 3.13 解释器

App (com.yyds.auto, uid > 10000)
    通过 HTTP REST 与 yyds.py 通信 (EngineClient)
    通过 libsu (ROOT) 启动 yyds.keep
```

### 关键规则
- `yyds.py` 和 `yyds.auto` 以 **SHELL/ROOT** 权限运行（`uid=0` 或 `uid=2000`）
- App 进程（`uid>10000`）**无法直接操作** `/data/local/tmp/`，需通过 `EngineClient` HTTP 调用
- 判断当前代码在哪个进程：`ExtSystem.uid() > 10000` → App 进程，否则 → 工作进程

---

## 启动流程

### App → 引擎启动
```
MainActivity → PyEngine.startAllEngines()
    → App进程(uid>10000): PyEngine.startAllEnginesFromApp()
        Phase1 (libsu ROOT): mkdir、cp SO、unzip APK lib/*.so、chmod +x
        Phase2 (libsu ROOT): $keeperPath $apkPath </dev/null >/dev/null 2>&1 &
    → 工作进程: startAllEnginesFromWorker() 直接 shell 执行
```

> **关键陷阱**: libsu 的 shell 是 pipe-based（非 terminal），`nohup` 无效。后台进程必须显式 `</dev/null >/dev/null 2>&1 &` 防止 SIGPIPE 杀死进程。

### yyds.keep 启动引擎
```
keeper.cpp::main()
    → 启动 yyds.auto: app_process /system/bin uiautomator.ExportApi
    → 启动 yyds.py:   app_process /system/bin pyengine.Main
        + LD_LIBRARY_PATH 包含 libpython3.13.so 所在目录
    → 每10s 检查 yyds.auto，每15s 检查 yyds.py，死亡则重启
    → 检测 APK mtime 变化，更新时重启所有子进程
```

### yyds.py 初始化
```
pyengine.Main.main()
    → ensureBusyboxInit()               # 解压 busybox 工具集
    → PyEngine.initPythonParser(ctx)
        → ContextUtil.InstallLoader     # 注册 native lib 搜索路径
        → extractPythonShims()          # 释放 python-shims/ 到设备
        → ensurePythonHome()            # 从 APK assets/python3-stdlib.zip 解压标准库
        → CPythonBridge.initialize()    # 初始化 CPython 3.13
        → CPythonBridge.importModule("entry")  # 加载入口模块
    → startGuardThread()                # 每30s 守护 yyds.auto + yyds.keep
    → WebSocketAsClient.keepConnected() # 连接 yyds-con 服务器
    → PyEngine.startWsSocket()          # 启动 Ktor HTTP+WS 服务 (端口 61140)
```

---

## Python 引擎层 (`pyengine/`)

| 文件 | 职责 |
|------|------|
| `Main.java` | `yyds.py` 工作进程入口，守护线程，HTTP 服务 |
| `PyEngine.kt` | Kotlin 单例，继承 `WebSocketAsServer`，管理引擎全生命周期 |
| `WebSocketAsServer.kt` | Ktor CIO HTTP+WS 服务器，全部 REST API 路由定义（端口 61140） |
| `PyProcess.kt` | 每个脚本项目的子进程入口（`app_process pyengine.PyProcess`） |
| `CPythonBridge.kt` | CPython JNI 桥接 Kotlin 封装（替代 Chaquopy） |
| `EngineClient.kt` | App 进程与工作进程通信客户端（HTTP REST + WS 日志流） |
| `EngineProtocol.kt` | RPC 方法常量与数据键定义 |
| `WebSocketAsClient.kt` | 连接 yyds-con 控制台服务器的 WS 客户端 |
| `ApkPackageHelper.kt` | APK 打包助手（脚本 → 独立 APK，V1 签名） |
| `ScriptEncryptor.kt` | 脚本加密器（AES-256-GCM + 白盒密钥 + PBKDF2） |
| `PyOut.kt` | 日志输出管理（线程安全队列，供 /log WS 端点消费） |
| `YyProject.kt` | 脚本项目数据模型与文件扫描 |
| `HandleApiServerConnection.kt` | 旧版公网服务器 WS 处理（遗留，不维护） |

### 脚本项目运行（进程隔离）

每个项目运行在独立 `app_process` 子进程，**100% 确定性终止**：

```
PyEngine.startProject(name)
    → Runtime.exec("sh -c YYDS_SUBPROCESS=1 LD_LIBRARY_PATH=... exec app_process /system/bin pyengine.PyProcess 'name'")
    → 3 个后台线程: stdout → PyOut, stderr → PyOut.err, waitFor → 更新状态
PyEngine.abortProject()
    → proc.destroy()         # SIGTERM，500ms 超时
    → proc.destroyForcibly() # SIGKILL，100% 终止
```

`YYDS_SUBPROCESS=1` 环境变量告知 `cpython_bridge.cpp` 跳过 fd 重定向（子进程 stdout 直接走管道到父进程）。

### CPython 嵌入
- **版本**: CPython 3.13.2 交叉编译，`arm64-v8a`
- **PYTHONHOME**: `/data/local/tmp/python3`（从 APK `assets/python3-stdlib.zip` 自动释放，版本戳防重复解压）
- **python-shims 路径**: `/data/local/tmp/cache/python-shims/`（`entry.py`, `pyengine.py` 等）
- **PYTHONPATH**: python-shims + `/data/local/tmp/pylib/lib/<abi>`
- **pyengine.py**: 由 `writePyengineShim()` 内联写入（AAPT2 会过滤与 Java 包名同名的 asset 文件）
- **GIL 管理**: `nativeInit()` 末尾调用 `PyEval_SaveThread()` 释放 GIL，防止 Ktor 协程死锁

---

## HTTP REST API（端口 61140，yyds.py 进程提供）

`WebSocketAsServer.kt` 中定义，供 App、VSCode 插件、MCP Server 调用：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/` | GET | 引擎版本信息 |
| `/ping` | GET | 探活（转发 yyds.auto /ping） |
| `/project/list` | GET | 获取项目列表 |
| `/project/status` | GET | 当前运行状态 |
| `/project/start?name=` | GET | 启动项目 |
| `/project/stop` | GET | 停止项目 |
| `/engine/run-code` | POST | 执行代码片段（在 yyds.py 主进程内） |
| `/engine/reboot` | POST | 重启引擎 |
| `/engine/shell` | POST | 执行 shell 命令（ROOT） |
| `/engine/foreground` | GET | 获取前台 Activity |
| `/screenshot` | GET | 截图（JPEG bytes） |
| `/screen/{quality}` | GET | 指定质量截图 |
| `/ui-dump` | GET | UI 层次 XML |
| `/pull-file?path=` | GET | 下载文件 |
| `/post-file` | POST | 上传文件（multipart） |
| `/push-project` | POST | 推送项目 ZIP |
| `/agent/run` | POST | 启动 AI Agent |
| `/agent/stop` | GET | 停止 AI Agent |
| `/agent/status` | GET | Agent 状态 |
| `/agent/config` | GET/POST | Agent 配置 |
| `/package/build` | POST | 打包 APK |
| `/log` | **WS** | 日志流（仅 WS） |
| `/shot/{q}/{c}/{i}` | **WS** | 截图流（仅 WS） |

yyds.auto 的 HTTP 服务端口为 **49009**（`ExportHttp.PORT`），供 yyds.py 代理自动化 API。

---

## 自动化引擎层 (`uiautomator/`)

`yyds.auto` 进程入口是 `ExportApi.main()`。

| 文件/目录 | 职责 |
|-----------|------|
| `ExportApi.java` | 所有自动化 API 实现（触控、截图、OCR、UI、Shell 等） |
| `ExportHandle.java` | 引擎通信句柄（自动选择进程内/HTTP 调用） |
| `ExportHttp.kt` | Ktor HTTP 服务（端口 49009），供 yyds.py 调用 |
| `AppProcess.java` | 进程启动命令、路径常量、进程检测 |
| `ExtSystem.java` | Shell 执行、日志输出、UID/PID 工具 |
| `Const.java` | 全局常量（超时、包名、签名 Hash 等） |
| `input/RootAutomator` | ROOT 级触控注入（`/dev/input/eventX` 或 uinput 虚拟设备） |
| `u2/HierarchyDumper` | UI 层次 XML 转储（AccessibilityService） |
| `tool/ScreenCapture` | 截图实现（多种后端） |
| `tool/Foreground` | 前台 Activity 检测 |
| `util/InternalApi` | 系统内部 API 反射调用 |

触控注入模式由 `touchMode` 控制：`"uinput"` → `"kernel"` → `"java"` 自动回退（anti-detection 首选 uinput）。

---

## Native 层 (`jni/`)

### 构建目标（`CMakeLists.txt`）

| 目标 | 类型 | 说明 |
|------|------|------|
| `libai.so` | shared library | 图像处理 + OCR + YOLO（`main.cpp`, `image.cpp`, `ncnn_paddle_ocr.cpp`, `ncnn_yolov8.cpp`） |
| `libyyds_keep.so` | **executable** | Native 守护进程（`keeper.cpp`），伪装为 `.so` 以便 APK 打包 |
| `libscript_crypto.so` | shared library | 白盒密钥派生（`script_crypto.c`） |
| `libcpython_bridge.so` | shared library | CPython JNI 桥接（`cpython_bridge.cpp`） |
| `libpython3.13.so` | shared library | CPython 3.13，从 `CPython-android/libs/<abi>/` 复制 |

> `libyyds_keep.so` 是 ELF 可执行文件，Android 15+ 要求 16KB 页面对齐（已配置 `-Wl,-z,max-page-size=16384`）。

### 路径常量（`AppProcess.java`）

```java
unzipTo          = "/data/local/tmp/cache"
libPath          = "/data/local/tmp/cache/lib/<abi>"
nativeKeeperPath = "/data/local/tmp/cache/lib/<abi>/libyyds_keep.so"
pyUnzipTo        = "/data/local/tmp/pylib"
PYTHON_HOME      = "/data/local/tmp/python3"
PYTHON_SHIMS     = "/data/local/tmp/cache/python-shims"
```

### 图像能力（`libai.so`）
- **OCR**: PaddleOCR v5（`ncnn_paddle_ocr.cpp`），NCNN 推理，字典 `ppocrv5_dict.h`
- **YOLO**: YOLOv8（`ncnn_yolov8.cpp`），用户可自定义模型目录 `/data/local/tmp/yyds_yolo`
- **图像**: 截图、颜色识别、图像匹配（`image.cpp`, OpenCV Mobile 4.5.4）

---

## App UI 层 (`com/tencent/yyds/`)

| 文件 | 职责 |
|------|------|
| `App.kt` | Application 入口，ShizukuUtil 初始化 |
| `MainActivity.kt` | 主 Activity（导航栏，权限检查） |
| `frag/HomeFragment.kt` | 首页（引擎状态，启动/停止） |
| `frag/ScriptFragment.kt` | 脚本列表页 |
| `frag/RemoteFragment.kt` | 远程控制页 |
| `frag/AgentFragment.kt` | AI Agent 控制页 |
| `PackageActivity.kt` | APK 打包配置（应用名、图标、包名、加密开关） |
| `RunnerActivity.kt` | Runner 模式控制中心（打包后 APK 的 UI） |
| `PipManagerActivity.kt` | pip 包管理器（搜索 PyPI、安装/卸载/升级） |
| `FileBrowserActivity.kt` | 文件浏览器（ROOT 权限） |
| `ProjectConfigActivity.kt` | 项目配置页 |
| `LogcatActivity.kt` | 运行日志页 |
| `AgentSettingsActivity.kt` | AI Agent 配置（Provider、模型、API Key，16+ 服务商） |
| `AgentOverlayService.kt` | AI Agent 悬浮窗（思考过程可视化，协议前缀 `##YYDS_AGENT_OVERLAY##`） |
| `FloatingWindowService.kt` | 悬浮控制面板（脚本启停 + UI 控件树检查器） |
| `FloatingLogService.kt` | 悬浮日志控制台（协议前缀 `##YYDS_CONSOLE##`） |
| `ShizukuUtil.kt` | Shizuku 免 ROOT 启动引擎（状态机：NOT_INSTALLED → READY → ENGINE_STARTED） |
| `inspector/` | UI 布局检查器（Canvas 绘制控件树，`UiInspectorView`, `UiNode`, `NodeTreeAdapter`） |

---

## 公共组件 (`common/`)

| 文件 | 职责 |
|------|------|
| `BootService.kt` | 前台 Service（通知栏控制，脚本切换/启停，监听熄屏广播自动停止） |
| `BootReceiver.kt` | 开机广播接收器（开机自启） |
| `BootProvider.kt` | ContentProvider（用于 am 命令跨进程调用） |

---

## 脚本加密 (`ScriptEncryptor.kt` + `script_crypto.c`)

**7 层安全**：

1. Native 白盒密钥（`script_crypto.c`）：16轮 ChaCha20 风格混淆，密钥从不存储
2. 反调试：TracerPid 检查 + 时序侧信道
3. Python 源码混淆：zlib + base64 + `exec(compile())`
4. AES-256-GCM 加密，随机 12 字节 IV
5. Per-build 随机 16 字节 salt（存于 `pack_config.json`）
6. PBKDF2-HMAC-SHA256，100,000 次迭代
7. 运行时解密到 `chmod 700` 目录，退出钩子清理

`.pye` 文件格式：`YENC`(4) | `0x02`(1) | IV(12) | Ciphertext+GCM-Tag(N)

---

## 多设备控制台连接 (`WebSocketAsClient.kt`)

`yyds.py` 进程主动连接 `yyds-con` 服务器（端口 8818）：
- 配置文件：`/sdcard/Yyds.Auto/server.conf` → `{"host":"...", "port":8818, "token":"jwt"}`
- 每 3s 发送缩略图（质量 25，WS 二进制帧）
- 每 10s 发送设备状态报告
- 断开后 5s 自动重连
- 4线程池处理服务端命令（防止爆线程）

---

## Runner 模式（APK 打包）

脚本可被打包为**独立 APK**（`ApkPackageHelper.kt`）：
- `ApkPackageHelper.isRunnerMode(ctx)` → 检测 APK `assets/pack_config.json`
- `Main.java` 启动时自动提取 + 解密（加密模式）内嵌项目到 `/storage/emulated/0/Yyds.Py/<name>/`
- 解密文件权限 `chmod 700`，退出时自动清理
- APK 签名：`ApkV1Signer.java`（V1 签名）

---

## 关键常量 & 端口

| 常量 | 值 | 说明 |
|------|----|------|
| `PyEngine.enginePort` | `61140` | yyds.py HTTP+WS 服务端口 |
| `ExportHttp.PORT` | `49009` | yyds.auto HTTP 服务端口 |
| `WebSocketAsClient.serverPort` | `8818` | yyds-con 控制台端口 |
| `AppProcess.defaultABI` | `Build.SUPPORTED_ABIS[0]` | 主 ABI |
| `BuildConfig.APPLICATION_ID` | `com.yyds.auto` | 包名 |
| 脚本目录 | `/storage/emulated/0/Yyds.Py/` | 用户脚本存放位置 |
| PYTHONHOME | `/data/local/tmp/python3` | CPython 标准库根目录 |
| lib 缓存 | `/data/local/tmp/cache/lib/<abi>/` | SO 文件解压目录 |

---

## 进程间通信

```
App ──HTTP REST──→ yyds.py (61140) ──HTTP──→ yyds.auto (49009)
App ←──WS /log──  yyds.py                 自动化 API 调用

yyds.py ──WS──→ yyds-con (8818)   多设备控制台
MCP Server ──HTTP──→ yyds.py (61140)  LLM 控制设备

App ──libsu ROOT──→ yyds.keep 启动/管理进程
yyds.keep ──fork──→ yyds.auto, yyds.py
```

### 特殊协议前缀（日志流内嵌命令）

Python 脚本无法直接启动 Android Service，通过日志流传递指令：

- `##YYDS_CONSOLE##show:<msg>` → `EngineClient` 触发 `FloatingLogService`
- `##YYDS_AGENT_OVERLAY##show:<text>` → 触发 `AgentOverlayService` 显示 AI 思考悬浮窗
- `##YYDS_AGENT_OVERLAY##think:<text>` → 更新思考内容
- `##YYDS_AGENT_OVERLAY##hide` → 隐藏悬浮窗（截图前必须隐藏）
- `##YYDS_AGENT_OVERLAY##done:<text>` → 完成提示，3.5s 后淡出

---

## 常见陷阱 & 注意事项

1. **libsu SIGPIPE**: libsu shell 是 pipe-based，后台进程必须 `</dev/null >/dev/null 2>&1 &`
2. **AAPT2 过滤**: assets 中不能有与 Java 包名同名的文件（如 `pyengine.py`），用 `writePyengineShim()` 内联写入
3. **HOME 环境变量**: ROOT 进程 `HOME=/`（只读），必须用 `setenv(..., 1)` 强制覆写
4. **GIL 死锁**: `Py_InitializeFromConfig()` 完成后调用线程持有 GIL，必须 `PyEval_SaveThread()` 释放
5. **libyyds_keep.so 不在 nativeLibraryDir**: 它是 executable 非 shared lib，App 进程必须从 APK unzip 提取
6. **进程权限**: App 进程（uid>10000）无法写 `/data/local/tmp/`，所有文件操作必须通过 ROOT shell 或 yyds.py HTTP
7. **CPython 加载顺序**: `libpython3.13.so` 必须在 `libcpython_bridge.so` 之前加载
8. **Android 15+ 页面对齐**: 所有 ELF 目标已配置 `-Wl,-z,max-page-size=16384`
