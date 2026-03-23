# CPython for Android - 嵌入式方案

替代 Chaquopy，通过 JNI 嵌入交叉编译的 CPython 3.13 共享库到 Android 应用中。

## 架构概览

```
┌─────────────────────────────────────────────────┐
│  yyds.py 工作进程 (app_process, ROOT/SHELL)     │
│                                                  │
│  ┌──────────────┐    ┌────────────────────────┐ │
│  │ Ktor HTTP/WS │    │  CPython 嵌入式引擎    │ │
│  │ Server       │    │                        │ │
│  │ (保留不变)    │◄──►│ CPythonBridge.kt       │ │
│  │ port:61140   │    │   ↕ JNI                │ │
│  └──────────────┘    │ cpython_bridge.cpp     │ │
│                      │   ↕ C API              │ │
│                      │ libpython3.13.so       │ │
│                      │   ↕                    │ │
│                      │ entry.py → 用户脚本     │ │
│                      └────────────────────────┘ │
└─────────────────────────────────────────────────┘
```

**核心思路**: CPython 作为共享库嵌入 JVM 进程，Ktor 服务器和所有 Android API 访问保持不变，仅替换 Python 执行层。

## 目录结构

```
CPython-android/
├── scripts/
│   └── build-cpython.sh       # WSL 交叉编译脚本
├── python-shims/
│   └── pyengine.py            # Python 兼容层 (PyOut 回调)
├── include/                   # [编译生成] CPython 头文件 (Python.h)
├── libs/
│   └── arm64-v8a/             # [编译生成] libpython3.13.so
├── build/                     # [编译生成] 中间文件
├── dist/                      # [编译生成] 打包输出
├── build.bat                  # Windows 一键编译
├── README.md                  # 本文件
└── .gitignore

yyds-android/ (修改的文件)
├── app/src/main/
│   ├── jni/
│   │   ├── cpython_bridge.cpp    # [新增] JNI Bridge
│   │   └── CMakeLists.txt        # [修改] 添加 cpython_bridge 目标
│   ├── java/pyengine/
│   │   ├── CPythonBridge.kt      # [新增] Kotlin 封装
│   │   └── PyEngine.kt           # [修改] 使用 CPythonBridge
│   └── java/com/tencent/yyds/
│       ├── MainActivity.kt       # [修改] 移除 Chaquopy 引用
│       └── frag/HomeFragment.kt  # [修改] 移除 Chaquopy 引用
├── app/build.gradle              # [修改] 注释 Chaquopy 插件
└── build.gradle                  # [修改] 注释 Chaquopy 插件声明
```

## 编译步骤

### 前置要求

- Windows 10/11 + WSL2 (Ubuntu 22.04)
- Android Studio (用于构建 APK)

### 1. 交叉编译 CPython

```bat
cd CPython-android
build.bat arm64 3.13.2
```

脚本自动在 WSL 中完成：
1. 安装系统依赖 (gcc, make, etc.)
2. 下载 Android NDK r27c (Linux 版, ~1.5GB)
3. 下载 CPython 3.13.2 源码
4. 构建宿主 Python
5. 交叉编译 CPython for Android (arm64-v8a)
6. 复制 headers → `include/`, library → `libs/arm64-v8a/`
7. 打包 → `dist/cpython-3.13.2-android-arm64-v8a-api24.tar.gz`

### 2. 构建 APK

在 Android Studio 中正常构建。CMakeLists.txt 会自动检测 `CPython-android/include/Python.h`，若存在则编译 `libcpython_bridge.so`。

### 3. 部署到设备

```bash
# 推送 CPython 运行时到设备
adb push CPython-android/build/aarch64/install /data/local/tmp/python3
adb shell chmod -R 755 /data/local/tmp/python3

# 推送 Python shim 模块
adb push CPython-android/python-shims /data/local/tmp/cache/python-shims

# 首次安装 pip 和基础包
adb shell sh /data/local/tmp/python3/setup-pip.sh

# 验证
adb shell /data/local/tmp/python3/bin/python3 -c "print('Hello Android!')"
```

## API 对应关系

| Chaquopy | CPythonBridge | 说明 |
|----------|--------------|------|
| `Python.start(platform)` | `CPythonBridge.initialize(home, paths)` | 初始化解释器 |
| `Python.isStarted()` | `CPythonBridge.isInitialized()` | 检查状态 |
| `Python.getInstance().getModule(name)` | `CPythonBridge.importModule(name)` | 导入模块 |
| `pyObj.callAttr(method, args)` | `handle.callAttr(method, args)` | 调用方法 |
| `pyObj.toBoolean()` | `handle.callAttrBool(method)` | 布尔返回 |
| `pyObj.close()` | `handle.close()` | 释放引用 |
| `PyException` | `RuntimeException` | 异常类型 |

## Python 侧兼容

用户脚本 `from pyengine import PyOut` 通过 `python-shims/pyengine.py` 兼容层实现，内部调用 `_yyds_bridge` C 扩展模块回调 Java 侧 `PyOut.out()`/`PyOut.err()`。

用户脚本 `from yyds import *` 无需任何修改。

## Android 适配层

裸 CPython 在 Android 上运行时缺少许多 Linux 标准环境。我们实现了双层适配，替代 Chaquopy 的内部平台层：

### C 层适配 (cpython_bridge.cpp)

| 适配项 | 问题 | 方案 |
|--------|------|------|
| **stdout/stderr fd 重定向** | native C 扩展 (numpy, OpenCV) 直接写 fd 1/2，输出丢失 | `pipe()` + `dup2()` + 后台线程转发到 logcat + PyOut |
| **TMPDIR** | Android 没有 /tmp，tempfile 崩溃 | 设为 `$PYTHONHOME/tmp` 并创建目录 |
| **HOME** | 未设置，pip/pathlib 等依赖此变量 | 设为 `$PYTHONHOME` |
| **LANG/LC_ALL** | Android 无 locale 数据库 | 设为 `C.UTF-8` |
| **TZ** | Android 时区存在系统属性中 | 读取 `persist.sys.timezone` |
| **PYTHONDONTWRITEBYTECODE** | 只读目录写 .pyc 报错 | 设为 1 |

### Python 层适配 (_android_bootstrap.py)

解释器初始化后自动加载，处理 Python 标准库级别的适配：

| 适配项 | 问题 | 方案 |
|--------|------|------|
| **SSL/TLS 证书** | Android 无 /etc/ssl/certs，HTTPS 全部失败 | certifi 优先 → Android 系统 CA 合并 → ssl.create_default_context patch |
| **tempfile** | 缓存的 tempdir 可能无效 | 重置 tempfile.tempdir |
| **multiprocessing** | fork() 在 Android 上不可靠 | 默认启动方式改为 spawn |
| **locale** | getpreferredencoding() 可能返回 None | patch 为安全的 UTF-8 fallback |
| **os.getlogin()** | 无终端时 OSError | patch 为读取 USER 环境变量 |
| **文件系统编码** | 可能非 UTF-8 | 强制 PYTHONUTF8=1 |

## pip 包管理

设备端安装（需 ROOT/SHELL 权限）：

```bash
# 首次安装 pip + 基础包（含 certifi 用于 SSL）
sh /data/local/tmp/python3/setup-pip.sh

# 安装纯 Python 包
/data/local/tmp/python3/bin/python3 -m pip install requests pyyaml
```

### Native 扩展包 (numpy, pillow 等)

含 C 代码的包需要预编译的 Android wheel。三种获取方式：

**方式1: Termux 预编译 wheel（推荐）**
```bash
# 从 Termux 的包仓库获取已编译的 .so
# https://packages.termux.dev/apt/termux-main/
# 提取 data.tar.xz 中的 Python 包文件
```

**方式2: crossenv 交叉编译**
```bash
# 在 WSL 中使用 crossenv 创建交叉编译虚拟环境
pip install crossenv
python -m crossenv /path/to/android-python3 cross_venv
source cross_venv/bin/activate
pip install numpy  # 交叉编译
```

**方式3: cibuildwheel + Android NDK**
```bash
# 使用 cibuildwheel 构建 Android wheel
# 需要自定义 build 配置指向 NDK 工具链
```

## 不变的部分

- **Ktor HTTP/WebSocket 服务器** — 所有 REST API 端点保持不变
- **keeper.cpp** — native 守护进程（仅增加 LD_LIBRARY_PATH）
- **AppProcess.java** — 启动命令（增加 LD_LIBRARY_PATH）
- **entry.py** — 用户脚本入口保持兼容
- **WebSocketAsServer.kt** — 全部 HTTP 端点保持不变
