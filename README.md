# 🤖 AndroidRPA (Yyds.Auto)

<div align="center">

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Python Version](https://img.shields.io/badge/python-3.8+-blue.svg)](https://www.python.org/downloads/)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://www.android.com/)
[![React](https://img.shields.io/badge/Console-React_19-61dafb.svg)](https://react.dev/)
[![Rust](https://img.shields.io/badge/Backend-Rust-black.svg)](https://www.rust-lang.org/)

**强大的下一代安卓平台 RPA（机器人流程自动化）框架与生态**  
*集 CPython 全栈环境、VSCode 深度集成、MCP 协议模型原生控制于一体的工业级方案*

[快速开始](#-快速开始) • [项目特性](#-核心特性) • [架构解析](#-系统架构) • [组件生态](#-组件生态圈)

</div>

<br/>

## 💡 简介 (Introduction)

**AndroidRPA** (客户端名称 `Yyds.Auto`) 是一个面向安卓全系设备的强大自动化开发与调度工作流引擎。不同于传统的 `uiautomator` 或按键精灵，它首开先河地在 Android 设备底层原生嵌入了 **完整的交叉编译版 CPython 3.13 解释器**，并支持无损多进程守护。

不仅如此，本项目构建了一个极具生产力的开发生态：涵盖了从设备端核心底层能力，到云端设备的调度集群管理控制台，再到 PC 端极度友好的 VS Code 开发插件（自带代码补全和 UI 视觉检查器）。最近，我们甚至引入了先进的 **MCP (Model Context Protocol) Server**，让现代大型语言模型（Claude/VLM）可以通过文字和视觉理解，无缝将人类意图转化为原生的触控底层指令操控安卓！

---

## ✨ 核心特性

- 🚀 **真·设备端 Python 执行**：内置原生 CPython 3.13 跨平台运行时，Android 环境下支持完整的 `pip` 第三方生态包拉取，各脚本任务多进程沙盒物理隔离。
- 👁️ **全栈化视觉与自动化能力**：内置系统级 UI 控件树 XML 解析引擎、NCNN 加持的高效端侧 OCR 识别引擎、YOLO 目标检测和 OpenCV 高频图像取色/模板匹配。
- 💻 **顶级的开发者体验**：专为开发者设计的 `VS Code` 扩展支持。拥有创建工程、实时推包调试、控件树 Dump 可视化审查的丝滑体验。
- 🤖 **AI Agent / LLM 原生支持**：提供兼容 [MCP 标准](https://modelcontextprotocol.io/) 的服务器，暴露多达 35 个以上的系统原子 Tools，让 Agent 直接感知并操控手机屏幕。
- 🌐 **高可用企业级群控台**：基于极致性能 `Rust + React 19` 开发的分布式群控控制台 `yyds-con`。长连管理上百台设备，支持 WebRTC 实时低延迟屏幕流。
- 🛡️ **底层进程高可用守护**：`yyds.keep` 纯 Native 守护进程 + System Server 自动化引擎 + Python 工作区，自实现“三点三角互相唤醒”防杀机制，7x24 小时挂机无忧。

---

## 🧩 组件生态圈 (Monorepo)

AndroidRPA 整体作为一个大型的 Mono 仓库维护，拆分了不同技术栈的模块独立进化：

| 模块名 | 语言技术栈 | 功能定位 |
| :--- | :--- | :--- |
| 📱 **`yyds-android`** | Kotlin / C++ / JNI | **安卓 App 主工程** (入口UI + Root自动化导出 + CPython 解释器 JNI 桥接守护) |
| 🐍 **`CPython-android`** | Python / Shell | **核心 Python 运行时栈** (WSL 下交叉编译的 ARM64 动态库及 Python Shims 标准底座) |
| 🧑‍💻 **`yyds-auto-vscode-extension`**| TypeScript / VSIX | **VS Code 生产力开发插件**（项目管理、可视化打包、UI 控件一站式检索探测） |
| 🧠 **`yyds-auto-mcp`** | TypeScript / MCP | **LLM智能控制协议服务**（赋予各类大语言模型理解、探查和指挥控制安卓的能力） |
| 🌐 **`yyds-con`** | Rust / Axum / React | **云平台多机控制台**（端口 `8818`，公私网映射 200台+ 设备的实时运行、日志、监控等） |
| 💻 **`yyds-auto-py`** | Python / PyPI | **Python PC 调用端 SDK** (使用 `pip install yyds-auto` 进行电脑端远程执行和调试) |
| 🛠️ **`yyds.-auto_-py-projcet`** | Python | **官方开发模板库** (新建自动化逻辑项目时的结构骨架与 Demo 示例) |

---

## 🏗 系统架构

AndroidRPA 抛弃了传统方案的单点故障风险，实现了极具代表性的**三角自愈式进程架构**：

1. **`yyds.keep` (Native 守护进程)** —— 最先启动，利用 Unix `pidof` 持续 10~15 秒探测，直接派生两大引擎引擎的进程！
2. **`yyds.auto` (自动化引擎)** —— 作为基于系统 `app_process` 的特权引擎，掌控最高的 ROOT 或 Shell 权限，接管底层截图、ADB 与触摸分发。
3. **`yyds.py` (Python 执行引擎)** —— 在端口 `61140` 提供基于轻量 HTTP/WebSocket 的 REST API，负责给每个被调用的外部 Python 脚本拉起独立子进程 (`exec app_process`)。

如果任意一个进程被安卓内存杀手（OOM Killer）回收或出现崩溃异常，剩下的子进程会在最多 30 秒内检测到它并将其满血复活起立！

---

## 🚀 快速开始

### 模式一：极轻量从电脑端运行控制手机
通过我们提供的 PyPI 包，你可以像使用 `uiautomator2` 一样极其简单地接入（完全兼容 WiFi / USB 直连）：

1. 手机端安装预编译的 `yyds.auto` APK，赋予相关权限后启动引擎。
2. 电脑端安装包：
   ```bash
   pip install yyds-auto
   ```
3. 在电脑写出你的第一个脚本：
   ```python
   import yyds_auto
   
   # USB自动连接 或 WiFi 局域网直连探测 (例如 "192.168.1.5")
   d = yyds_auto.connect()
   
   # 查看当前系统桌面是否在最前
   print(d.info)
   
   # 内置级联 OCR 识别直接触控
   d.ocr_click("确认登录", timeout=10)
   ```

### 模式二：脱机运行的高性能打包方案（VS Code）
如果你需要将 RPA 逻辑变成可以发布给其它用户的独立 App 或实现无脑后台挂机运行：

1. 打开 VS Code，在插件市场或者导入 `yyds-auto-vscode-extension` 生成的 VSIX 插件。
2. 在插件的操作面板里点击 **新建脚本工程**。
3. VS Code 自动化在本地拉取了包含了完整 Python 环境脚手架的项目，你的代码中同时兼容 PC 与 手机运行。
4. 开发测试完成后，右键直接点击 **打包独立APK** 即可脱离电脑。

---

## 🤖 让大模型代理控制你的手机 (MCP 支持)

借助仓库内的 `yyds-auto-mcp`，你可以立刻赋予 Claude、Cursor、或是任何兼容 MCP 协议的大模型调度设备的执行力。

在你的 MCP 客户端里（如 Claude Desktop）配置如下：
```json
{
  "mcpServers": {
    "yyds-android": {
      "command": "node",
      "args": ["/绝对路径/AndroidRPA/yyds-auto-mcp/dist/index.js"]
    }
  }
}
```
配置成功后，你可以直接给 AI 说：**“打开设置，帮我看看电池健康是多少？”** 或 **“往下翻一下淘宝，提取屏幕里的商品价格。”**。大模型会自行通过 Server 解析屏幕图片，分析控件 UI Tree，通过 ADB 发达出滑动和点击命令，完全代理你的枯燥操作！

---

## 🤝 参与项目 (Contributing)

AndroidRPA 由多个组件组成，在不同的路径下均有自驱动的构建生态：
- **Python 运行时**：需 WSL 或 Linux 下执行 `CPython-android/scripts/build-cpython.sh` 跨平台打包 `.so` 动态库。
- **VS Code 插件**：在前端目录下 `npm install && npm run compile`。
- **Android App**：标准 AS (Android Studio) 项目开发。

我们欢迎任何关于代码优化、功能插件和文档完善的 Pull Request 或者 Issue！

## 📝 许可 (License)

本项目代码遵循 [MIT License](LICENSE) 协议开源。
