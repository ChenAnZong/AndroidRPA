# yyds-con

多设备 Android RPA 控制台，支持 200+ 设备公网部署。

## 架构

- **后端**: Rust + Axum (端口 8818)
- **前端**: React + TypeScript + Tailwind CSS + TanStack Query
- **通信**: WebSocket (设备↔服务器), WebRTC DataChannel (浏览器↔设备 P2P)
- **Android**: 设备主动连接服务器, 支持截图流/触控/Shell/项目管理

## 快速开始

```bash
# 1. 构建前端
cd frontend
npm install
npm run build

# 2. 启动后端 (自动serve前端静态文件)
cd ../backend
cargo run

# 3. 访问 http://localhost:8818
```

## 开发模式

```bash
# 终端1: 前端热重载 (代理API到后端)
cd frontend && npm run dev

# 终端2: 后端
cd backend && cargo run
```

## 设备连接

Android 设备通过 WebSocket 连接:
```
ws://SERVER_IP:8818/ws/device?imei=XXX&model=YYY&ver=1&sw=1080&sh=1920
```

设备配置文件: `/sdcard/Yyds.Auto/server.conf`
```json
{"host": "SERVER_IP", "port": 8818}
```
