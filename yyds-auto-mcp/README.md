# yyds-auto-mcp

MCP Server for **Yyds.Auto** Android RPA — 让大语言模型通过 MCP 协议直接操控安卓设备。

## 功能概览

| 类别 | 工具数 | 能力 |
|------|--------|------|
| 设备信息 | 4 | 设备型号、屏幕尺寸、前台应用、网络状态 |
| 触控输入 | 7 | 点击、滑动、长按、文本输入、剪贴板、按键 |
| 截图 | 2 | 截图返回图片(LLM可直接看)、保存到设备 |
| UI自动化 | 3 | UI控件树dump、按属性查找控件、控件关系 |
| OCR/图像 | 5 | 屏幕OCR、图片OCR、模板匹配、取色、图片对比 |
| Shell | 1 | ROOT权限执行任意命令 |
| 应用管理 | 6 | 启动/停止应用、已安装列表、运行检测、打开URL、Toast |
| 文件操作 | 7 | 列目录、读写文件、删除、重命名、创建目录 |
| 脚本项目 | 5 | 项目列表、状态、启动/停止项目、执行Python代码 |
| Pip管理 | 4 | 列表、安装、卸载、查看包信息 |

## 前置条件

1. 安卓设备已安装 Yyds.Auto 并启动引擎（yyds.py 进程运行中，端口 61140）
2. 设备与电脑连通：
   - **USB**: `adb forward tcp:61140 tcp:61140`
   - **WiFi**: 设备与电脑在同一局域网

## 安装

```bash
cd yyds-auto-mcp
npm install
npm run build
```

## 配置

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `YYDS_DEVICE_HOST` | `127.0.0.1` | 设备 IP 地址 |
| `YYDS_DEVICE_PORT` | `61140` | 引擎端口号 |

### Claude Desktop 配置

在 `claude_desktop_config.json` 中添加：

```json
{
  "mcpServers": {
    "yyds-auto": {
      "command": "node",
      "args": ["D:/Project/RPA/yyds-auto-mcp/dist/index.js"],
      "env": {
        "YYDS_DEVICE_HOST": "127.0.0.1",
        "YYDS_DEVICE_PORT": "61140"
      }
    }
  }
}
```

### Windsurf / Cursor 配置

在 MCP 设置中添加同样的配置。

## 开发

```bash
npm run watch   # 监视编译
npm run dev     # 编译并运行
```

## 架构

```
LLM (Claude/GPT/etc.)
  ↓ MCP Protocol (stdio)
yyds-auto-mcp (Node.js)
  ↓ HTTP REST (JSON)
yyds.py engine (Android, port 61140)
  ↓ IPC
yyds.auto engine (Android, UI automation)
```
