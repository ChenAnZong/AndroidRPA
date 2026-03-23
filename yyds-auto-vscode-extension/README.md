# Yyds.Auto Android Dev Plugin for VS Code

[![Version](https://img.shields.io/visual-studio-marketplace/v/ChenAnzong.yyds-auto-dev-plugin)](https://marketplace.visualstudio.com/items?itemName=ChenAnzong.yyds-auto-dev-plugin)
[![Installs](https://img.shields.io/visual-studio-marketplace/i/ChenAnzong.yyds-auto-dev-plugin)](https://marketplace.visualstudio.com/items?itemName=ChenAnzong.yyds-auto-dev-plugin)
[![Rating](https://img.shields.io/visual-studio-marketplace/r/ChenAnzong.yyds-auto-dev-plugin)](https://marketplace.visualstudio.com/items?itemName=ChenAnzong.yyds-auto-dev-plugin)

**专业的安卓 RPA 自动化脚本开发插件** — 提供智能提示、项目管理、截图交互、控件解析、实时日志

> 让 Android 自动化脚本开发像写普通 Python 代码一样简单！

## ✨ 核心特性

- 🎯 **智能代码补全** — Yyds.Auto SDK 完整 API 提示
- 📱 **实时设备交互** — 截图、控件树、点击测试一键完成
- 🚀 **一键推送运行** — 本地编辑，设备执行，秒级反馈
- 📊 **实时日志查看** — WebSocket 实时日志流，支持颜色分类
- 🎨 **可视化 UI 设计器** — 拖拽式 UI 配置文件编辑
- 🔧 **完整开发工具链** — 从项目创建到 APK 打包全流程支持

## 功能

### 智能开发体验
- **API 自动补全** — 输入 yyds SDK 函数名时提供智能补全 (CompletionProvider)
- **悬停文档** — 鼠标悬停在 API 函数上显示完整文档、参数说明、返回值 (HoverProvider)
- **参数签名提示** — 输入函数参数时显示参数名称和类型 (SignatureHelpProvider)
- **新建脚本工程** — 一键初始化标准工程脚手架 (main.py, project.config, yyds SDK, ui.yml)

### 项目管理
- **推送并运行** (`Ctrl+Alt+1`) — 将项目打包发送到设备并启动运行
- **运行工程** — 在设备上启动当前项目
- **停止工程** (`Ctrl+Alt+2`) — 停止正在运行的项目
- **推送工程** — 将项目文件发送到设备（不启动）
- **打包工程** — 将项目打包为 `.yyp.zip` 文件

### 代码执行
- **运行选中代码 / 当前行** (`Ctrl+Enter`) — 选中代码后发送到设备执行；未选中时自动运行光标所在行
- **运行当前文件** (`Ctrl+Shift+Enter`) — 将当前编辑器中的整个文件发送到设备运行

### 开发助手
- **截图载入** — 从设备获取实时截图，支持缩放显示
- **控件树** — 获取设备 UI 控件层级，树形展示，支持搜索过滤
- **区域选择** — 在截图上拖拽选择区域，显示坐标和尺寸信息
- **点击交互** — 双击截图可在设备上执行点击操作
- **控件高亮** — 点击截图自动定位对应控件，高亮显示边界

### 日志输出
- **实时日志** — WebSocket 连接显示设备端日志
- **颜色分类** — 错误（红色）、输出（白色）、插件调试（蓝色）
- **日志管理** — 清空、复制、自动滚动

### 连接管理
- **自动连接** — 打开项目时自动连接设备
- **断线重连** — 连接断开后自动尝试重连
- **状态栏** — 底部状态栏显示连接状态

## 📦 安装

### 从 VS Code 市场安装（推荐）

1. 打开 VS Code
2. 按 `Ctrl+Shift+X` 打开扩展面板
3. 搜索 "Yyds.Auto"
4. 点击 "安装"

### 从 VSIX 文件安装

下载 `.vsix` 文件后：
```bash
code --install-extension yyds-auto-dev-plugin-1.0.0.vsix
```

## 🚀 快速开始

### 1. 安装依赖（开发者）

```bash
cd yyds-auto-vscode-extension
npm install
```

### 2. 编译

```bash
npm run compile
```

### 3. 构建 VSIX 插件包

```bash
npm run vsix
```

生成 `yyds-auto-dev-plugin-1.0.0.vsix` 文件。

### 4. 构建并安装到 VS Code

```bash
npm run install
```

自动打包 VSIX 并安装到本机 VS Code，安装后重启 VS Code 生效。

### 5. 调试运行

按 `F5` 启动 VS Code 调试实例。

### 6. 项目配置

在 Python 脚本项目根目录创建 `project.config` 文件：

```properties
PROJECT_NAME=我的项目
PROJECT_VERSION=1.0
DEBUG_DEVICE_IP=192.168.1.2
PACK_KEY_WITH_VERSION=false
```

## VS Code 配置项

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `yyds.deviceIp` | string | `""` | 调试设备IP（留空则从 project.config 读取） |
| `yyds.port` | number | `61140` | 设备端口号 |
| `yyds.autoConnect` | boolean | `true` | 打开项目时自动连接设备 |

## 快捷键

| 快捷键 | 功能 |
|--------|------|
| `Ctrl+Alt+1` | 推送并运行 |
| `Ctrl+Alt+2` | 停止工程 |
| `Ctrl+Enter` | 运行选中代码 / 当前行 |
| `Ctrl+Shift+Enter` | 运行当前文件 |

## 右键菜单

在 Python 文件编辑器中右键可以看到：
- **推送并运行** — 推送整个工程到设备并启动
- **运行选中代码 / 当前行** — 选中代码或光标所在行发送到设备执行
- **运行当前文件** — 将整个文件发送到设备执行

## 通信协议

插件通过 HTTP REST + WebSocket 与设备端 Python 引擎（端口 `61140`）通信：
- **HTTP REST** — 所有控制命令（启动/停止/截图/文件传输等），JSON 数据交换
- **日志通道** (`ws://设备IP:61140/log`) — 实时日志流（WebSocket）

## 技术栈

- **语言**: TypeScript
- **通信**: HTTP REST (JSON) + WebSocket (`ws`)
- **UI**: VS Code WebView API + HTML Canvas
- **压缩**: `archiver`

## 📋 系统要求

- VS Code 1.80.0 或更高版本
- Android 设备（ROOT 或通过 ADB 激活引擎）
- 设备需安装 Yyds.Auto 应用

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件

## 🔗 相关链接

- [Yyds.Auto 项目主页](https://github.com/yourusername/RPA)
- [问题反馈](https://github.com/yourusername/yyds-auto-vscode-extension/issues)
- [更新日志](CHANGELOG.md)

---

**享受自动化开发的乐趣！** 🎉