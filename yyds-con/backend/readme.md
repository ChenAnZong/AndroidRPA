# yyds-con 后端

多设备 Android RPA 控制台服务端，支持 200+ 设备公网部署的集中管理平台。

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Rust | Edition 2021 |
| Web 框架 | Axum | 0.8 |
| 异步运行时 | Tokio | 1.x (full features) |
| 数据库 | SQLite (rusqlite bundled) | 0.31 |
| 认证 | JWT (jsonwebtoken) + bcrypt | 9 / 0.15 |
| 序列化 | serde + serde_json | 1.x |
| 并发容器 | DashMap | 6 |
| 定时任务 | cron 表达式解析 | 0.15 |
| 日志 | tracing + tracing-subscriber | 0.1 / 0.3 |
| HTTP 中间件 | tower-http (CORS, gzip, 静态文件) | 0.6 |
| 错误处理 | thiserror + anyhow | 2 / 1 |
| 时间 | chrono | 0.4 |
| UUID | uuid v4 | 1 |
| WebSocket | axum 内置 ws + futures-util | — |

## 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                      Axum HTTP Server (:8818)                │
├──────────┬──────────┬───────────┬───────────┬───────────────┤
│ JWT Auth │  CORS    │   Gzip    │ SPA       │  Tracing      │
│Middleware│  Layer   │Compression│ Fallback  │  Logger       │
├──────────┴──────────┴───────────┴───────────┴───────────────┤
│                                                              │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────────┐  │
│  │ Auth Routes  │  │ Device WS    │  │ API Routes (JWT)   │  │
│  │ (public)     │  │ /ws/device   │  │ /api/devices/...   │  │
│  │ login/register│ │              │  │ /api/batch/...     │  │
│  └─────────────┘  └──────┬───────┘  └────────┬───────────┘  │
│                          │                    │              │
│  ┌─────────────┐         │          ┌─────────┴──────────┐  │
│  │ Admin Routes │         │          │ Scheduler Routes   │  │
│  │ (JWT+admin)  │         │          │ /api/schedules     │  │
│  └─────────────┘         │          └────────────────────┘  │
│                          │                                   │
│                          │          ┌────────────────────┐  │
│                          │          │ IDE Routes         │  │
│                          │          │ run-code/screenshot│  │
│                          │          │ ui-dump/pip/package│  │
│                          │          └────────────────────┘  │
├──────────────────────────┴──────────────────────────────────┤
│                     AppState                                 │
│  ┌──────────────────────┐  ┌─────────┐  ┌───────────────┐  │
│  │ DeviceRegistry       │  │   Db    │  │ ScheduleStore │  │
│  │ (DashMap<IMEI,State>)│  │(SQLite) │  │ (JSON file)   │  │
│  └──────────────────────┘  └─────────┘  └───────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 模块结构

```
src/
├── main.rs              # 入口：路由注册、中间件组装、服务启动
├── config.rs            # 配置常量（端口、超时、JWT 过期时间等）
├── error.rs             # 统一错误类型 AppError → HTTP 状态码映射
├── auth/                # 认证模块
│   ├── db.rs            # SQLite 初始化（用户表、设备绑定表）
│   ├── jwt.rs           # JWT 签发与验证（用户 Token + 设备 Token）
│   └── middleware.rs    # JWT 认证中间件（保护 /api/* 路由）
├── device/              # 设备管理核心
│   ├── protocol.rs      # WS 协议定义（DeviceMessage / ServerCommand / WebRTC 信令）
│   ├── registry.rs      # 设备注册表（DashMap 并发安全，含命令/帧/日志通道）
│   ├── connection.rs    # 设备 WebSocket 连接处理（心跳、消息分发、断线清理）
│   └── signaling.rs     # WebRTC SDP/ICE 信令中继
├── api/                 # REST API 处理器
│   ├── auth.rs          # 登录/注册/个人信息/设备绑定
│   ├── admin.rs         # 管理员：用户 CRUD、统计、绑定管理
│   ├── devices.rs       # 设备列表/详情/缩略图
│   ├── control.rs       # 设备控制（触控/滑动/按键/文本/Shell/重启引擎/批量操作）
│   ├── stream.rs        # 屏幕流（WebRTC P2P 优先 → WS 二进制帧回退）
│   ├── projects.rs      # 项目管理（列表/启动/停止/状态/批量操作）
│   ├── files.rs         # 文件管理（列目录/上传/下载/创建目录/删除）
│   ├── logs.rs          # 实时日志流（WebSocket）
│   ├── logcat.rs        # Android Logcat（dump/clear/buffers）
│   ├── ide.rs           # IDE 集成（执行代码/截图/UI dump/文件操作/Pip/APK 打包）
│   ├── rpc.rs           # 通用 RPC 转发
│   └── scheduler.rs     # 定时任务 CRUD（Cron 表达式）
└── scheduler/
    └── engine.rs        # Cron 调度引擎（30 秒 tick 循环，自动触发设备命令）
```

### 核心设计

**设备连接模型**：每台设备通过 WebSocket 长连接到服务端，服务端为每台设备维护一个 `DeviceState`，包含：
- `cmd_tx` — 命令发送通道（mpsc），API 层通过此通道向设备下发指令
- `frame_tx` — 截图帧广播通道（broadcast），支持多个浏览器同时观看
- `log_tx` — 日志广播通道（broadcast），实时推送脚本日志
- `pending_responses` — 请求-响应映射（DashMap + oneshot），实现异步 RPC
- `rtc_signal_txs` — WebRTC 信令通道，支持 P2P 屏幕流

**请求-响应模式**：浏览器 API 请求 → 生成 UUID → 通过 `cmd_tx` 发送到设备 → 注册 `oneshot` 等待 → 设备返回 `Response{id}` → 匹配并回复浏览器。

**屏幕流策略**：WebRTC DataChannel P2P 优先（低延迟），5 秒超时自动回退到 WebSocket 二进制帧中继。

## 数据库

SQLite（WAL 模式），数据文件 `data/yyds.db`，包含以下表：

| 表名 | 用途 |
|------|------|
| `users` | 用户账号（username/password/role/created_at/last_login） |
| `device_bindings` | 用户-设备绑定关系（user_id/imei/alias） |
| `device_tokens` | 设备认证令牌（长期有效，365 天） |

定时任务存储在 `data/schedules.json` 文件中（JSON 持久化）。

## API 路由

### 公开路由（无需认证）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 用户登录，返回 JWT |
| POST | `/api/auth/register` | 用户注册 |
| GET | `/ws/device?imei=&model=&ver=&sw=&sh=&token=` | 设备 WebSocket 接入 |

### 用户路由（需 JWT）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/auth/me` | 当前用户信息 |
| POST | `/api/auth/change-password` | 修改密码 |
| GET | `/api/auth/devices` | 我绑定的设备列表 |
| POST | `/api/auth/bind-device` | 绑定设备 |
| POST | `/api/auth/unbind-device` | 解绑设备 |

### 设备操作路由（需 JWT）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/devices` | 设备列表 |
| GET | `/api/devices/{imei}` | 设备详情 |
| GET | `/api/devices/{imei}/thumbnail` | 设备缩略图 |
| GET | `/api/devices/{imei}/stream` | 屏幕流 WebSocket |
| POST | `/api/devices/{imei}/touch` | 点击 |
| POST | `/api/devices/{imei}/swipe` | 滑动 |
| POST | `/api/devices/{imei}/key` | 按键 |
| POST | `/api/devices/{imei}/text` | 文本输入 |
| POST | `/api/devices/{imei}/shell` | 执行 Shell 命令 |
| POST | `/api/devices/{imei}/reboot-engine` | 重启引擎 |
| POST | `/api/batch/touch` | 批量点击 |
| POST | `/api/batch/shell` | 批量 Shell |

### 项目管理路由

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/devices/{imei}/projects` | 项目列表 |
| POST | `/api/devices/{imei}/projects/start` | 启动项目 |
| POST | `/api/devices/{imei}/projects/stop` | 停止项目 |
| GET | `/api/devices/{imei}/projects/status` | 项目状态 |
| POST | `/api/batch/projects/start` | 批量启动 |
| POST | `/api/batch/projects/stop` | 批量停止 |

### 文件与日志路由

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/devices/{imei}/files` | 列目录 |
| GET | `/api/devices/{imei}/files/download` | 下载文件 |
| POST | `/api/devices/{imei}/files/upload` | 上传文件 |
| POST | `/api/devices/{imei}/files/mkdir` | 创建目录 |
| DELETE | `/api/devices/{imei}/files` | 删除文件 |
| GET | `/api/devices/{imei}/log` | 实时日志 WebSocket |
| GET | `/api/devices/{imei}/logcat/dump` | Logcat 导出 |
| POST | `/api/devices/{imei}/logcat/clear` | 清空 Logcat |
| GET | `/api/devices/{imei}/logcat/buffers` | Logcat 缓冲区列表 |

### IDE 集成路由

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/devices/{imei}/run-code` | 执行代码片段 |
| GET | `/api/devices/{imei}/screenshot` | 截图 |
| GET | `/api/devices/{imei}/ui-dump` | UI 控件树 |
| GET | `/api/devices/{imei}/foreground` | 前台应用 |
| POST | `/api/devices/{imei}/click` | 点击坐标 |
| GET | `/api/devices/{imei}/file/read` | 读取文件 |
| POST | `/api/devices/{imei}/file/write` | 写入文件 |
| GET | `/api/devices/{imei}/file/exists` | 文件是否存在 |
| POST | `/api/devices/{imei}/file/rename` | 重命名文件 |
| GET | `/api/devices/{imei}/pip/list` | Pip 包列表 |
| POST | `/api/devices/{imei}/pip/install` | 安装包 |
| POST | `/api/devices/{imei}/pip/uninstall` | 卸载包 |
| GET | `/api/devices/{imei}/pip/outdated` | 可更新包 |
| GET | `/api/devices/{imei}/pip/show` | 包详情 |
| POST | `/api/devices/{imei}/pip/upgrade` | 升级包 |
| GET | `/api/devices/{imei}/pip/search` | 搜索包 |
| POST | `/api/devices/{imei}/package/build` | APK 打包 |
| GET | `/api/devices/{imei}/package/list` | 已打包列表 |
| GET | `/api/devices/{imei}/package/installed-apps` | 已安装应用 |
| GET | `/api/devices/{imei}/package/app-icon` | 应用图标 |

### 定时任务路由

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/schedules` | 任务列表 |
| POST | `/api/schedules` | 创建任务 |
| PUT | `/api/schedules/{id}` | 更新任务 |
| DELETE | `/api/schedules/{id}` | 删除任务 |

### 管理员路由（需 JWT + admin 角色）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/stats` | 系统统计 |
| GET | `/api/admin/users` | 用户列表 |
| GET | `/api/admin/users/{id}` | 用户详情 |
| PUT | `/api/admin/users/{id}` | 更新用户 |
| DELETE | `/api/admin/users/{id}` | 删除用户 |
| GET | `/api/admin/bindings` | 所有设备绑定 |

## 配置

通过环境变量配置：

| 环境变量 | 说明 | 默认值 |
|----------|------|--------|
| `YYDS_JWT_SECRET` | JWT 签名密钥 | `yyds-con-default-secret-change-me` |
| `YYDS_API_KEY` | API Key（未设置则禁用额外认证，开发模式） | 无 |
| `RUST_LOG` | 日志级别 | `yyds_con_backend=info,tower_http=info` |

内置常量（`config.rs`）：

| 常量 | 值 | 说明 |
|------|----|------|
| `SERVER_PORT` | 8818 | 监听端口 |
| `HEARTBEAT_INTERVAL_SECS` | 30 | 心跳间隔 |
| `HEARTBEAT_TIMEOUT_SECS` | 60 | 心跳超时断连 |
| `THUMBNAIL_INTERVAL_SECS` | 3 | 缩略图刷新间隔 |
| `JWT_EXPIRATION_SECS` | 7 天 | 用户 Token 有效期 |
| `DEVICE_TOKEN_EXPIRATION_SECS` | 365 天 | 设备 Token 有效期 |
| `P2P_TIMEOUT_SECS` | 5 | WebRTC P2P 超时 |

## 用法

### 编译

```bash
cd yyds-con/backend
cargo build --release
```

产物位于 `target/release/yyds-con-backend`。

### 运行

```bash
# 开发模式（默认 JWT 密钥，无 API Key 认证）
cargo run

# 生产模式
YYDS_JWT_SECRET="your-secret-key" ./target/release/yyds-con-backend
```

启动后输出：

```
yyds-con server starting on 0.0.0.0:8818
  Device WS: ws://0.0.0.0:8818/ws/device
  Browser:   http://0.0.0.0:8818/
  API:       http://0.0.0.0:8818/api/
  Default admin: admin / admin123
```

### 前端

服务端自动托管 `../frontend/dist` 目录下的前端静态文件，支持 SPA 路由回退。需先构建前端：

```bash
cd ../frontend
npm install && npm run build
```

### 设备接入

设备端通过 WebSocket 连接服务器：

```
ws://HOST:8818/ws/device?imei=DEVICE_IMEI&model=MODEL&ver=1&sw=1080&sh=2400&token=DEVICE_JWT
```

设备配置文件位于 `/sdcard/Yyds.Auto/server.conf`：

```json
{"host": "服务器IP", "port": 8818}
```

### 默认账号

首次启动自动创建管理员账号：`admin` / `admin123`，请及时修改密码。

## 部署规划与服务器选型

### 资源消耗分析

#### 每台设备的服务端开销

| 资源 | 空闲连接 | 仪表盘浏览 | 屏幕流观看（WS 回退） | 屏幕流观看（WebRTC P2P） |
|------|----------|------------|----------------------|------------------------|
| 内存 | ~50 KB | ~80 KB | ~200 KB | ~80 KB |
| CPU | 极低 | 极低 | 中（帧中继） | 无（P2P 直连） |
| 上行带宽 | ~0.1 KB/s | ~3 KB/s | 500 KB~1 MB/s 每观看者 | ~0.5 KB/s（仅信令） |
| 下行带宽 | ~0.1 KB/s | ~3 KB/s | 500 KB~1 MB/s 来自设备 | ~0.5 KB/s（仅信令） |

说明：
- **空闲连接**：仅心跳 ping/pong（每 30 秒一次），几乎无开销
- **仪表盘浏览**：设备周期性上报缩略图（quality=25，每 3 秒一帧，约 5~15 KB/帧）
- **WS 回退流**：quality=70、80ms 间隔（~12.5 fps），每帧 40~80 KB（1080p JPEG），服务端需中继全部帧数据
- **WebRTC P2P**：浏览器与设备直连，服务端仅转发 SDP/ICE 信令，带宽开销可忽略

#### 服务端进程本身

| 项目 | 估算 |
|------|------|
| 二进制体积 | ~10 MB（release 编译） |
| 启动内存 | ~15 MB（无设备连接时） |
| SQLite 数据库 | < 10 MB（千级用户规模） |
| 磁盘占用 | < 50 MB（含日志和调度数据） |
| 文件描述符 | 每设备 1 个 WS fd + 每浏览器观看者 1 个 fd |

### 带宽估算模型

> 核心公式：**总带宽 = 设备数 × 缩略图带宽 + 同时观看流数 × 每流带宽**

典型场景带宽估算（假设 1080p 设备）：

| 场景 | 设备数 | 同时观看流 | 预估带宽（上+下） |
|------|--------|-----------|-------------------|
| 日常待机（无人查看） | 200 | 0 | ~40 KB/s ≈ 0.3 Mbps |
| 仪表盘巡检（缩略图刷新） | 200 | 0（缩略图） | ~600 KB/s ≈ 5 Mbps |
| 轻度运维（5 路屏幕流） | 200 | 5（WS 回退） | ~5 MB/s ≈ 40 Mbps |
| 重度运维（20 路屏幕流） | 200 | 20（WS 回退） | ~20 MB/s ≈ 160 Mbps |
| P2P 模式（20 路屏幕流） | 200 | 20（P2P） | ~600 KB/s ≈ 5 Mbps |

> WebRTC P2P 成功率取决于网络环境（NAT 类型）。公网服务器 + 4G/WiFi 设备场景下，P2P 成功率约 60~80%，剩余回退到 WS 中继。

### 服务器选型推荐

#### 小规模（≤50 台设备）

适用：个人开发者、小团队测试

| 配置项 | 推荐 |
|--------|------|
| CPU | 1 核 |
| 内存 | 1 GB |
| 带宽 | 5 Mbps |
| 磁盘 | 20 GB SSD |
| 系统 | Ubuntu 22.04 / Debian 12 |
| 参考机型 | 阿里云 ecs.t6-c1m1.large / 腾讯云 S5.SMALL2 |
| 月费参考 | ¥40~80 |

#### 中规模（50~200 台设备）

适用：中小企业生产环境

| 配置项 | 推荐 |
|--------|------|
| CPU | 2 核 |
| 内存 | 4 GB |
| 带宽 | 20~50 Mbps（按需弹性） |
| 磁盘 | 40 GB SSD |
| 系统 | Ubuntu 22.04 / Debian 12 |
| 参考机型 | 阿里云 ecs.c7.large / 腾讯云 S5.MEDIUM8 |
| 月费参考 | ¥150~400（带宽为主要成本） |

#### 大规模（200~500+ 台设备）

适用：大型设备农场、商业化部署

| 配置项 | 推荐 |
|--------|------|
| CPU | 4 核 |
| 内存 | 8 GB |
| 带宽 | 100 Mbps+（建议按量计费） |
| 磁盘 | 100 GB SSD |
| 系统 | Ubuntu 22.04 / Debian 12 |
| 参考机型 | 阿里云 ecs.c7.xlarge / 腾讯云 S5.LARGE16 |
| 月费参考 | ¥500~1500（带宽按量计费更经济） |

### 关键瓶颈与优化建议

**带宽是第一瓶颈**，而非 CPU 或内存。Rust + Tokio 异步模型下，单核即可轻松维持数百个 WebSocket 长连接。

| 优化方向 | 措施 | 效果 |
|----------|------|------|
| 优先 P2P | 确保设备和浏览器网络支持 WebRTC | 屏幕流带宽降至接近 0 |
| 降低缩略图频率 | 调大 `THUMBNAIL_INTERVAL_SECS`（如 5~10s） | 缩略图带宽降低 50~70% |
| 降低流画质 | 调低 `WS_FALLBACK_STREAM_QUALITY`（如 50） | 每路流带宽降低 30~50% |
| 降低流帧率 | 调大 `WS_FALLBACK_STREAM_INTERVAL_MS`（如 150ms ≈ 6.7fps） | 每路流带宽降低 ~50% |
| 按量计费带宽 | 云服务商选择按流量计费而非固定带宽 | 空闲时段不浪费费用 |
| 内网部署 | 服务器与设备在同一局域网 | 无公网带宽成本，延迟极低 |

### 系统参数调优（生产环境）

```bash
# /etc/sysctl.conf — 提升并发连接能力
net.core.somaxconn = 65535
net.ipv4.tcp_max_syn_backlog = 65535
net.ipv4.ip_local_port_range = 1024 65535
net.ipv4.tcp_tw_reuse = 1
net.ipv4.tcp_fin_timeout = 15
net.core.netdev_max_backlog = 65535

# /etc/security/limits.conf — 提升文件描述符上限
* soft nofile 65535
* hard nofile 65535
```

```bash
# systemd 服务文件参考 /etc/systemd/system/yyds-con.service
[Unit]
Description=yyds-con RPA Console Server
After=network.target

[Service]
Type=simple
User=yyds
WorkingDirectory=/opt/yyds-con
Environment=YYDS_JWT_SECRET=your-production-secret
Environment=RUST_LOG=yyds_con_backend=info
ExecStart=/opt/yyds-con/yyds-con-backend
Restart=always
RestartSec=3
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
```

### 监控指标建议

| 指标 | 告警阈值 | 说明 |
|------|----------|------|
| 在线设备数 | 低于预期 80% | 可能网络波动或服务异常 |
| WebSocket 连接数 | > 系统 fd 上限 80% | 需扩容或排查泄漏 |
| 带宽使用率 | > 80% | 需升级带宽或优化流参数 |
| 内存使用 | > 80% | 需扩容（通常不会触发） |
| CPU 使用率 | > 70% 持续 5 分钟 | 需扩容（通常不会触发） |
| 进程存活 | 进程退出 | systemd 自动重启，但需排查原因 |
