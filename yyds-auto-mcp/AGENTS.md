# yyds-auto-mcp — MCP Server for Yyds.Auto Android RPA

让 LLM 通过 MCP 协议（stdio）直接操控安卓设备。TypeScript 实现，通过 HTTP REST 与设备端 yyds.py 引擎（端口 61140）通信。

## 技术栈

- **语言**: TypeScript (ES2022, Node16 模块)
- **MCP SDK**: `@modelcontextprotocol/sdk` ^1.12.1
- **Schema**: `zod` ^3.24.2
- **传输**: stdio（stdout 是 MCP 协议通道，日志必须输出到 stderr）
- **后端通信**: 纯 `node:http`（无 axios/fetch），JSON 格式
- **构建**: `tsc` → `dist/`，ESM (`"type": "module"`)

## 项目结构

```
yyds-auto-mcp/
├── src/
│   ├── index.ts              # MCP Server 入口：注册工具+资源、autoSetup(ADB转发+引擎启动)、stdio transport
│   ├── client.ts             # HTTP 客户端：httpGetText/httpGetJson/httpGetBuffer/httpPostJson/autoApi/directAutoApi
│   │                         #   内置 ADB 断线自动重连（withReconnect 包装器，5s冷却去重）
│   ├── adb.ts                # ADB 工具：findAdb(多路径搜索) / getDevices / adbForward / adbShell / checkAndForward
│   ├── engine-bootstrap.ts   # 引擎启动检测：pingEngine → am start App → fallback keeper 启动（SO提取+启动守护进程）
│   └── tools/                # 工具注册模块（每个文件导出 registerXxxTools(server) 函数）
│       ├── device.ts         # 4 tools: device_info, get_foreground_app, get_screen_size, is_network_online
│       ├── touch.ts          # 8 tools: tap, swipe, long_press, drag, input_text, set_clipboard, get_clipboard, press_key
│       ├── screen.ts         # 2 tools: take_screenshot(返回base64图片), save_screenshot
│       ├── ui.ts             # 5 tools: dump_ui_hierarchy, find_ui_elements, get_element_relation, wait_for_element, scroll_to_find
│       ├── ocr.ts            # 8 tools: screen_ocr, tap_text, image_ocr, find_image_on_screen, get_pixel_color, compare_images, set_ocr_version, wait_for_screen_change
│       ├── shell.ts          # 1 tool:  run_shell (ROOT/SHELL权限)
│       ├── app.ts            # 8 tools: launch_app, stop_app, list_installed_apps, is_app_running, open_url, show_toast, install_apk, uninstall_app
│       ├── file.ts           # 7 tools: list_files, read_file, write_file, file_exists, delete_file, create_directory, rename_file
│       ├── project.ts        # 5 tools: list_projects, project_status, start_project, stop_project, run_python_code
│       ├── pip.ts            # 4 tools: pip_list, pip_install, pip_uninstall, pip_show
│       └── agent.ts          # 8 tools: agent_get_config, agent_set_config, agent_get_providers, agent_get_models, agent_run, agent_stop, agent_status, agent_test_connection
├── package.json              # name=yyds-auto-mcp, bin=dist/index.js, type=module
├── tsconfig.json             # strict, ES2022, Node16, declaration+sourceMap
└── dist/                     # 编译输出（git ignored）
```

## 架构与数据流

```
LLM (Claude/GPT/Gemini/...)
  ↓ MCP Protocol (stdio, JSON-RPC)
yyds-auto-mcp (Node.js, 本项目)
  ↓ HTTP REST (JSON, port 61140)
yyds.py 引擎 (Android, aiohttp server)
  ↓ IPC (HTTP, port 61100)
yyds.auto 引擎 (Android, UI自动化/截图/OCR)
```

## 关键设计模式

### 1. 两层 API 代理

设备端有两个引擎进程：
- **yyds.py**（端口 61140）：Python 运行时，提供项目管理、文件操作、pip 管理等
- **yyds.auto**（端口 61100）：Java/Kotlin 进程，提供 UI 自动化、触控、截图、OCR 等

MCP Server 只连接 yyds.py，需要调用 yyds.auto 的功能时通过两种方式代理：
- **`autoApi(uri, params)`** → `POST /engine/auto` → yyds.py 转发到 yyds.auto，返回 `{ok, data}` 二层 JSON
- **`directAutoApi(api, params)`** → `POST /api/{api}` → 直接代理到 yyds.auto

### 2. HTTP 客户端与自动重连

`client.ts` 提供 4 个公共 HTTP 方法（均带自动重连）：
- `httpGetText(path)` / `httpGetJson<T>(path)` / `httpGetBuffer(path)` / `httpPostJson<T>(path, data)`
- 连接失败时（ECONNREFUSED/ECONNRESET/EPIPE），自动重建 ADB forward 并重试一次
- 5 秒冷却 + Promise 去重，防止并发请求同时触发重连
- 默认超时 15 秒

### 3. 自动启动流程（localhost 场景）

`index.ts → autoSetup()`:
1. `adb.ts:checkAndForward()` — 搜索 ADB → 获取设备列表 → 执行 `adb forward tcp:61140 tcp:61140`
2. `engine-bootstrap.ts:ensureEngine()` — ping 引擎 → am start App（等 30s）→ fallback: 提取 SO + 启动 keeper（等 20s）
3. 全部 best-effort，失败不阻塞 MCP 启动

### 4. 工具注册模式

每个 `tools/*.ts` 文件导出一个 `registerXxxTools(server: McpServer)` 函数：
```typescript
export function registerXxxTools(server: McpServer) {
  server.tool("tool_name", "description", { /* zod schema */ }, async (params) => {
    // 调用 httpGetText/httpPostJson/autoApi
    return { content: [{ type: "text", text: result }] };
  });
}
```

返回值格式：
- **文本**: `{ content: [{ type: "text", text: "..." }] }`
- **图片**: `{ content: [{ type: "image", data: base64, mimeType: "image/jpeg" }] }`

### 5. 日志约定

stdout 是 MCP 协议通道，**所有日志必须用 `console.error()`**：
```typescript
console.error(`[yyds-mcp:模块名] 消息`);
```

## MCP Resources（3 个）

| 资源 URI | MIME | 说明 |
|----------|------|------|
| `device://screenshot` | image/jpeg | GET `/screen/80` 截图 |
| `device://ui-hierarchy` | text/xml | GET `/ui-dump` UI 控件树 |
| `device://info` | application/json | GET `/` 引擎版本信息 |

## 全部 MCP Tools（60 个）

### Device（4）
| 工具 | 参数 | 后端 API | 说明 |
|------|------|----------|------|
| `device_info` | — | `GET /` + `autoApi /screen_size /imei /foreground` | 综合设备信息 |
| `get_foreground_app` | — | `autoApi /foreground` | 当前前台 App+Activity |
| `get_screen_size` | — | `autoApi /screen_size` | 屏幕分辨率 |
| `is_network_online` | — | `autoApi /is_net_online` | 网络连通性 |

### Touch & Input（8）
| 工具 | 参数 | 后端 API | 说明 |
|------|------|----------|------|
| `tap` | x, y, count?, interval? | `autoApi /touch` | 点击（支持多次） |
| `swipe` | x1, y1, x2, y2, duration? | `autoApi /swipe` | 滑动手势 |
| `long_press` | x, y, duration? | `autoApi /touch_down` + sleep + `/touch_up` | 长按 |
| `drag` | x1, y1, x2, y2, duration? | `autoApi /swipe` | 拖拽 |
| `input_text` | text | `autoApi /inject_text` | 输入文本到焦点控件 |
| `set_clipboard` | text | `autoApi /set_clipboard` | 设置剪贴板 |
| `get_clipboard` | — | `autoApi /get_clipboard` | 获取剪贴板 |
| `press_key` | key (名称或keycode) | `autoApi /key_code` | 按键（home/back/enter 等） |

### Screen（2）
| 工具 | 参数 | 后端 API | 说明 |
|------|------|----------|------|
| `take_screenshot` | quality?(1-100) | `GET /screen/{q}` | 截图返回 base64 图片，LLM 可直接看 |
| `save_screenshot` | path? | `autoApi /screenshot` | 保存截图到设备文件 |

### UI Automation（5）
| 工具 | 参数 | 后端 API | 说明 |
|------|------|----------|------|
| `dump_ui_hierarchy` | — | `GET /uia_dump` | 完整 UI 控件树（>15KB 自动精简） |
| `find_ui_elements` | text?, textContains?, resourceId?, className?, contentDesc?, clickable?, scrollable?, limit? | `autoApi /uia_match` | 按属性查找控件 |
| `get_element_relation` | hashcode, type?(parent/children/sibling) | `autoApi /uia_relation` | 获取控件关系 |
| `wait_for_element` | text?, textContains?, resourceId?, timeout?, interval? | 轮询 `autoApi /uia_match` | 等待控件出现 |
| `scroll_to_find` | text?, textContains?, resourceId?, maxScrolls?, direction? | 滚动 + `autoApi /uia_match` | 滚动查找控件 |

### OCR & Image（8）
| 工具 | 参数 | 后端 API | 说明 |
|------|------|----------|------|
| `screen_ocr` | x?, y?, w?, h? | `autoApi /screen_ocr` | 屏幕文字识别（可指定区域） |
| `tap_text` | text, index? | `autoApi /screen_ocr` + `/touch` | OCR 找文字并点击 |
| `image_ocr` | path | `autoApi /image_ocr` | 图片文件 OCR |
| `find_image_on_screen` | templates, threshold? | `autoApi /find_image` | 模板匹配找图 |
| `get_pixel_color` | x, y | `autoApi /get_color` | 取像素颜色 |
| `compare_images` | image1, image2 | `autoApi /image_similarity` | 图片相似度对比 |
| `set_ocr_version` | version, target_size? | `autoApi /update_language` | 切换 OCR 模型 |
| `wait_for_screen_change` | timeout?, interval?, threshold? | 轮询 `GET /screen/50` | 等待画面变化 |

### Shell（1）
| 工具 | 参数 | 后端 API | 说明 |
|------|------|----------|------|
| `run_shell` | command | `POST /engine/shell` | ROOT/SHELL 权限执行命令 |

### App Management（8）
| 工具 | 参数 | 后端 API | 说明 |
|------|------|----------|------|
| `launch_app` | packageName | `autoApi /open_app` | 启动应用 |
| `stop_app` | packageName | `POST /engine/shell` (am force-stop) | 强制停止应用 |
| `list_installed_apps` | — | `GET /package/installed-apps` | 已安装非系统应用列表 |
| `is_app_running` | packageName | `autoApi /is_app_running` | 检查应用是否运行 |
| `open_url` | url | `autoApi /open_url` | 浏览器打开 URL |
| `show_toast` | message | `autoApi /toast` | 显示 Toast 消息 |
| `install_apk` | path | `POST /engine/shell` (pm install) | 安装 APK |
| `uninstall_app` | packageName | `POST /engine/shell` (pm uninstall) | 卸载应用 |

### File Operations（7）
| 工具 | 参数 | 后端 API | 说明 |
|------|------|----------|------|
| `list_files` | path | `GET /file/list?path=` | 列出目录内容 |
| `read_file` | path | `GET /file/read-text?path=` | 读取文本文件 |
| `write_file` | path, content | `POST /file/write-text` | 写入文本文件 |
| `file_exists` | path | `GET /file/exists?path=` | 检查文件是否存在 |
| `delete_file` | path | `GET /file/delete?path=` | 删除文件/目录 |
| `create_directory` | path | `GET /file/mkdir?path=` | 创建目录 |
| `rename_file` | path, newName | `POST /file/rename` | 重命名 |

### Script Projects（5）
| 工具 | 参数 | 后端 API | 说明 |
|------|------|----------|------|
| `list_projects` | — | `GET /project/list` | 设备上的脚本项目列表 |
| `project_status` | — | `GET /project/status` | 当前项目运行状态 |
| `start_project` | name | `GET /project/start?name=` | 启动项目 |
| `stop_project` | — | `GET /project/stop` | 停止当前项目 |
| `run_python_code` | code | `POST /engine/run-code` | 在主进程执行 Python 代码片段 |

### Pip Management（4）
| 工具 | 参数 | 后端 API | 说明 |
|------|------|----------|------|
| `pip_list` | — | `GET /pip/list` | 已安装包列表 |
| `pip_install` | name, mirror? | `POST /pip/install` | 安装包 |
| `pip_uninstall` | name | `POST /pip/uninstall` | 卸载包 |
| `pip_show` | name | `GET /pip/show?name=` | 包详情 |

### AI Agent（8）
| 工具 | 参数 | 后端 API | 说明 |
|------|------|----------|------|
| `agent_get_config` | — | `GET /agent/config` | 获取 Agent 配置 |
| `agent_set_config` | provider, api_key, base_url?, model?, max_steps?, use_ui_dump? | `POST /agent/config` | 设置 Agent 配置 |
| `agent_get_providers` | — | `GET /agent/providers` | 可用 AI 提供商列表 |
| `agent_get_models` | provider | `GET /agent/models?provider=` | 提供商的可用模型 |
| `agent_run` | instruction | `POST /agent/run` | 启动 Agent 执行自然语言任务 |
| `agent_stop` | — | `GET /agent/stop` | 停止 Agent |
| `agent_status` | — | `GET /agent/status` | Agent 运行状态 |
| `agent_test_connection` | provider, api_key, base_url?, model? | `POST /agent/test-connection` | 测试模型连接 |

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `YYDS_DEVICE_HOST` | `127.0.0.1` | 设备 IP |
| `YYDS_DEVICE_PORT` | `61140` | 引擎端口 |
| `YYDS_DEVICE_SERIAL` | (首个设备) | 指定 ADB 设备序列号 |
| `YYDS_ADB_PATH` | (自动搜索) | 手动指定 ADB 路径 |

## 开发约定

### 添加新工具

1. 在 `src/tools/` 下新建或修改对应分类文件
2. 用 `server.tool(name, description, zodSchema, handler)` 注册
3. 在 `src/index.ts` 中导入并调用 `registerXxxTools(server)`
4. 参数用 zod 定义，每个参数必须有 `.describe()` 说明
5. 调用设备 API 使用 `client.ts` 导出的函数（自带重连）
6. 返回 `{ content: [{ type: "text"|"image", ... }] }`

### autoApi vs httpPostJson

- **需要 yyds.auto 引擎功能**（触控、截图、OCR、UI）→ 用 `autoApi(uri, params)`
- **需要 yyds.py 自身功能**（项目、文件、pip、shell）→ 用 `httpGetText/httpPostJson` 直接调用

### 构建与运行

```bash
npm install          # 安装依赖
npm run build        # tsc 编译
npm run watch        # 监视编译
npm run dev          # 编译并运行
npm start            # 运行 dist/index.js
```

### UI 控件树精简 (`ui.ts:slimXml`)

当 XML 超过 15KB 时自动精简，只保留有 text/resource-id/content-desc 或 clickable/scrollable 的元素，减少 LLM token 消耗。

### 截图重试与降级 (`screen.ts:captureScreen`)

截图失败自动重试 2 次，最后一次降低质量到 30 以提高成功率。

### ADB 路径搜索优先级 (`adb.ts:findAdb`)

`YYDS_ADB_PATH` → `PATH` → `ANDROID_HOME/platform-tools` → 平台常见路径（支持 Windows/macOS/Linux）

## 设备端 API 端点速查（yyds.py 端口 61140）

### yyds.py 直接提供
```
GET  /                          # 引擎版本信息
GET  /ping                      # 健康检查
GET  /screen/{quality}          # JPEG 截图
GET  /uia_dump                  # UI XML dump (从 yyds.auto 获取后缓存)
GET  /ui-dump                   # 同上
GET  /project/list              # 项目列表
GET  /project/status            # 运行状态
GET  /project/start?name=       # 启动项目
GET  /project/stop              # 停止项目
POST /engine/run-code           # {code} 执行 Python
POST /engine/shell              # {command} 执行 Shell
POST /engine/auto               # {uri, ...params} 代理到 yyds.auto
POST /api/{api}                 # 直接代理到 yyds.auto
GET  /file/list?path=           # 列目录
GET  /file/read-text?path=      # 读文件
POST /file/write-text           # {path, content} 写文件
GET  /file/exists?path=         # 文件存在检查
GET  /file/delete?path=         # 删除
GET  /file/mkdir?path=          # 创建目录
POST /file/rename               # {oldPath, newName}
GET  /pip/list                  # pip 包列表
POST /pip/install               # {name, mirror?}
POST /pip/uninstall             # {name}
GET  /pip/show?name=            # pip 包详情
GET  /package/installed-apps    # 已安装应用列表
GET  /agent/config              # Agent 配置
POST /agent/config              # 设置 Agent 配置
GET  /agent/providers           # AI 提供商列表
GET  /agent/models?provider=    # 可用模型
POST /agent/run                 # {instruction} 启动 Agent
GET  /agent/stop                # 停止 Agent
GET  /agent/status              # Agent 状态
POST /agent/test-connection     # 测试模型连接
```

### yyds.auto 代理端点（通过 autoApi 调用）
```
/touch          {x, y, time, interval}     # 点击
/swipe          {x1, y1, x2, y2, duration} # 滑动
/touch_down     {x, y}                     # 按下
/touch_up       {x, y}                     # 抬起
/inject_text    {text}                     # 输入文本
/key_code       {code}                     # 按键
/set_clipboard  {text}                     # 设置剪贴板
/get_clipboard  —                          # 获取剪贴板
/foreground     —                          # 前台 App
/screen_size    —                          # 屏幕尺寸
/imei           —                          # 设备 IMEI
/is_net_online  —                          # 网络状态
/open_app       {pkg}                      # 启动 App
/is_app_running {pkg}                      # 检查 App 运行
/open_url       {url}                      # 打开 URL
/toast          {content}                  # Toast
/screenshot     {path}                     # 保存截图
/screen_ocr     {x?, y?, w?, h?}           # 屏幕 OCR
/image_ocr      {path}                     # 图片 OCR
/find_image     {templates, threshold}     # 模板匹配
/get_color      {x, y}                     # 取色
/image_similarity {image1, image2}         # 图片对比
/update_language {code, target_size?}      # OCR 模型切换
/uia_match      {text?, resource-id?, class?, content-desc?, clickable?, scrollable?, limit?} # 控件查找
/uia_relation   {hashcode, type?}          # 控件关系
```