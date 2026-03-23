# 更新日志

## [1.0.0] - 2026-03-12

### 首次发布 🎉

#### 新增功能
- ✨ 智能代码补全 - Yyds.Auto SDK 完整 API 提示
- 📱 实时设备交互 - 截图、控件树、点击测试
- 🚀 一键推送运行 - 本地编辑，设备执行
- 📊 实时日志查看 - WebSocket 实时日志流
- 🎨 可视化 UI 设计器 - 拖拽式 UI 配置
- 🔧 完整开发工具链 - 项目创建到 APK 打包

#### 核心特性
- API 自动补全（CompletionProvider）
- 悬停文档提示（HoverProvider）
- 参数签名提示（SignatureHelpProvider）
- 项目脚手架初始化
- 代码片段执行（Ctrl+Enter）
- 文件推送与运行
- 开发助手面板（截图、控件树、区域选择）
- 日志输出面板（颜色分类、自动滚动）
- ADB 管理器（自动探测、按需下载）
- 无线配对支持（Android 11+）
- APK 打包功能

#### 技术实现
- HTTP REST + WebSocket 通信协议
- TypeScript + esbuild 构建
- WebView 面板交互
- 多设备连接支持
