@echo off
REM ============================================================
REM CPython for Android - Windows一键编译脚本
REM 通过 WSL(Ubuntu-22.04) 调用交叉编译脚本
REM ============================================================

setlocal

set WSL_DISTRO=Ubuntu-22.04
set SCRIPT_DIR=%~dp0

REM 将 Windows 路径转为 WSL 路径
set WIN_PATH=%SCRIPT_DIR%
set WIN_PATH=%WIN_PATH:\=/%
set WIN_PATH=%WIN_PATH:D:=/mnt/d%
set WIN_PATH=%WIN_PATH:d:=/mnt/d%
set WIN_PATH=%WIN_PATH:C:=/mnt/c%
set WIN_PATH=%WIN_PATH:c:=/mnt/c%

echo ============================================================
echo  CPython for Android 交叉编译
echo  WSL Distro: %WSL_DISTRO%
echo  Project: %WIN_PATH%
echo ============================================================

REM 参数传递: build.bat [arm64|arm|x86_64|x86] [3.13.2]
set ABI=%1
set PY_VERSION=%2
if "%ABI%"=="" set ABI=arm64
if "%PY_VERSION%"=="" set PY_VERSION=3.13.2

echo.
echo  Target ABI: %ABI%
echo  Python Version: %PY_VERSION%
echo ============================================================

wsl -d %WSL_DISTRO% -- bash -c "cd '%WIN_PATH%' && chmod +x scripts/*.sh && bash scripts/build-cpython.sh %ABI% %PY_VERSION%"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] 编译失败! 请检查WSL环境和错误日志。
    pause
    exit /b 1
)

echo.
echo [SUCCESS] 编译完成！输出在 dist/ 目录
echo.
pause
