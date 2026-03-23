# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a comprehensive Android RPA (Robotic Process Automation) platform consisting of multiple interconnected components:

- **yyds-android**: Android app with embedded CPython 3.13 engine for running automation scripts
- **CPython-android**: Cross-compiled CPython 3.13 for Android with JNI bridge
- **yyds-auto-vscode-extension**: VSCode extension for script development
- **yyds-auto-mcp**: MCP Server enabling LLMs to control Android devices
- **yyds-con**: Multi-device control console (Rust backend + React frontend)
- **yyds-auto-py**: Python package for PC-side device control (similar to uiautomator2)

## Architecture

### Multi-Process Architecture

The Android app runs three interconnected processes with mutual monitoring:

1. **yyds.keep** (native daemon): Native C++ process, starts first, monitors other processes
2. **yyds.auto** (automation engine): Java process via app_process, provides UI automation APIs
3. **yyds.py** (Python engine): Java process via app_process with embedded CPython, runs user scripts

All three processes run with ROOT/SHELL privileges and form a triangular watchdog system for high availability.

### Communication

- **HTTP REST API**: Port 61140, all control commands use JSON over HTTP
- **WebSocket /log**: Real-time log streaming
- **WebSocket /shot/{quality}/{count}/{interval}**: Screenshot streaming
- Scripts run in isolated subprocess (not threads) for deterministic termination

## Key Technologies

- **Android**: Kotlin + Java, JNI for native integration
- **Native**: C++ (keeper daemon, CPython bridge, image processing with NCNN)
- **Python**: CPython 3.13 embedded via JNI, replaces Chaquopy
- **Backend**: Rust + Axum (yyds-con), Ktor (Android engine server)
- **Frontend**: React 19 + TypeScript + Tailwind v4
- **Build**: Gradle (Android), Cargo (Rust), npm (TypeScript)

## Development Commands

### Android App (yyds-android)

```bash
# Build APK
cd yyds-android
./gradlew assembleDebug

# Install to device
./gradlew installDebug

# Build release APK
./gradlew assembleRelease
```

### CPython Cross-Compilation (CPython-android)

```bash
# Cross-compile CPython 3.13 for Android (requires WSL2)
cd CPython-android
build.bat arm64 3.13.2

# Deploy to device
adb push build/aarch64/install /data/local/tmp/python3
adb push python-shims /data/local/tmp/cache/python-shims
adb shell chmod -R 755 /data/local/tmp/python3
```

### Multi-Device Console (yyds-con)

```bash
# Build frontend
cd yyds-con/frontend
npm install
npm run build

# Run backend (serves frontend)
cd ../backend
cargo run

# Development mode (hot reload)
# Terminal 1:
cd frontend && npm run dev
# Terminal 2:
cd backend && cargo run
```

### VSCode Extension (yyds-auto-vscode-extension)

```bash
cd yyds-auto-vscode-extension
npm install
npm run compile

# Package extension
npm run package  # Creates .vsix file
```

### MCP Server (yyds-auto-mcp)

```bash
cd yyds-auto-mcp
npm install
npm run build

# Test locally
npm start
```

### Python Package (yyds-auto-py)

```bash
cd yyds-auto-py
pip install -e .  # Development install

# Build distribution
pip install build
python -m build

# Publish to PyPI
pip install twine
twine upload dist/*
```

## Important File Locations

### Android App Structure

- `yyds-android/app/src/main/java/pyengine/`: Python engine core
  - `Main.java`: Worker process entry point
  - `PyEngine.kt`: Engine management
  - `CPythonBridge.kt`: JNI wrapper for CPython
  - `WebSocketAsServer.kt`: HTTP/WS server (port 61140)
  - `WebSocketAsClient.kt`: Connects to yyds-con console
- `yyds-android/app/src/main/java/com/tencent/yyds/`: UI layer
- `yyds-android/app/src/main/java/uiautomator/`: Automation engine API
- `yyds-android/app/src/main/jni/`: Native code
  - `keeper.cpp`: Native daemon process
  - `cpython_bridge.cpp`: CPython JNI bridge
  - `CMakeLists.txt`: Native build configuration

### CPython Integration

- `CPython-android/scripts/build-cpython.sh`: Cross-compilation script
- `CPython-android/python-shims/`: Python compatibility layer
  - `entry.py`: Script entry point
  - `pyengine.py`: PyOut callback shim
  - `_android_bootstrap.py`: Android adaptation layer
- `CPython-android/runtime/`: Pure Python runtime modules
  - `server.py`: Alternative aiohttp server
  - `agent.py`: VLM-based automation agent

### Console Backend

- `yyds-con/backend/src/main.rs`: Axum server entry
- `yyds-con/backend/src/device/`: Device registry and WebSocket handling
- `yyds-con/backend/src/api/`: REST API endpoints
- `yyds-con/backend/src/scheduler/`: Cron job scheduler

### Console Frontend

- `yyds-con/frontend/src/App.tsx`: Main app with routing
- `yyds-con/frontend/src/pages/`: Page components
- `yyds-con/frontend/src/hooks/`: React hooks for device management

## Code Conventions

### Android/Kotlin

- Use Kotlin for new Android code, Java for legacy compatibility
- Follow Android Architecture Components patterns
- Use coroutines for async operations
- JNI functions prefixed with `native`

### Python

- Target CPython 3.13 compatibility
- User scripts import from `yyds` module
- Use `pyengine.PyOut` for logging to Android
- Scripts run in isolated subprocesses via `PyProcess`

### TypeScript

- Strict mode enabled
- Use async/await for promises
- React functional components with hooks
- TanStack Query for server state management

### Rust

- Use tokio async runtime
- Axum for HTTP server
- DashMap for concurrent device registry
- Error handling with custom error types

## Testing

### Android

```bash
cd yyds-android
./gradlew test  # Unit tests
./gradlew connectedAndroidTest  # Instrumented tests
```

### Rust

```bash
cd yyds-con/backend
cargo test
cargo clippy  # Linting
```

### TypeScript

```bash
# VSCode extension
cd yyds-auto-vscode-extension
npm test

# MCP server
cd yyds-auto-mcp
npm test

# Console frontend
cd yyds-con/frontend
npm test
```

## Key Ports

- **61140**: Python engine HTTP/WS server (on Android device)
- **61100**: yyds.auto automation engine (internal)
- **8818**: yyds-con multi-device console server

## Critical Notes

- **Process Isolation**: Each script project runs in a separate subprocess, not a thread. Use `Runtime.exec()` to start, `proc.destroy()` to stop.
- **ROOT/SHELL Required**: All worker processes (yyds.keep, yyds.auto, yyds.py) require ROOT or SHELL privileges.
- **CPython Deployment**: libpython3.13.so and standard library must be deployed to `/data/local/tmp/python3` on device.
- **LD_LIBRARY_PATH**: Must be set in both `AppProcess.java` and `keeper.cpp` to load libpython3.13.so.
- **Script Encryption**: Optional AES-256-GCM encryption with white-box key derivation for packaged APKs.
- **WebSocket Migration**: Early CBOR-based WebSocket RPC has been fully migrated to JSON + HTTP REST. WebSocket only used for log/screenshot streaming.

## Device Connection Methods

1. **USB**: ADB connection with automatic port forwarding
2. **WiFi**: Direct IP connection to port 61140
3. **Console**: Device connects to yyds-con server via WebSocket
4. **MCP**: LLM connects via stdio transport to MCP server

## Documentation

Refer to `AGENTS.md` in the root directory for comprehensive architecture documentation in Chinese.
