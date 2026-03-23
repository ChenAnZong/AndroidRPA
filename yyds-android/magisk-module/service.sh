#!/system/bin/sh
# Yyds.Auto 开机自启服务脚本
# 由 Magisk/KernelSU/APatch 在系统启动完成后执行
# 等待系统就绪后启动 yyds.keep 守护进程，由其派生 yyds.auto 和 yyds.py

MODDIR=${0%/*}
LOG_FILE="/data/local/tmp/yyds_autostart.log"
PKG="com.yyds.auto"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

log "=== 开机自启模块触发 ==="

# 等待系统引导完成（最多等待 120 秒）
WAIT=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $WAIT -lt 120 ]; do
    sleep 2
    WAIT=$((WAIT + 2))
done
log "系统引导完成，等待了 ${WAIT}s"

# 再等几秒让 PackageManager 完全就绪
sleep 5

# 获取 APK 路径
APK_RAW=$(pm path "$PKG" 2>/dev/null)
if [ -z "$APK_RAW" ]; then
    log "错误: 未找到 $PKG，模块退出"
    exit 1
fi
APK_PATH="${APK_RAW#*:}"
log "APK 路径: $APK_PATH"

# 确定 ABI
ABI=$(getprop ro.product.cpu.abi)
[ -z "$ABI" ] && ABI="arm64-v8a"

CACHE_DIR="/data/local/tmp/cache"
LIB_DIR="$CACHE_DIR/lib/$ABI"
KEEPER_PATH="$LIB_DIR/libyyds_keep.so"

# 释放 SO 文件（从 APK 中解压 native 库）
mkdir -p "$LIB_DIR"
unzip -o "$APK_PATH" "lib/$ABI/*.so" -d "$CACHE_DIR" >/dev/null 2>&1
chown shell:shell -R "$CACHE_DIR"
chmod +x "$KEEPER_PATH" 2>/dev/null
log "SO 文件释放完成: $LIB_DIR"

# 检查 keeper 是否已经运行
if pidof yyds.keep >/dev/null 2>&1; then
    log "yyds.keep 已在运行，跳过启动"
    exit 0
fi

# 启动守护进程
if [ -x "$KEEPER_PATH" ]; then
    log "使用 native keeper 启动: $KEEPER_PATH"
    "$KEEPER_PATH" "$APK_PATH" </dev/null >/dev/null 2>&1 &
else
    log "native keeper 不可用，使用 app_process 回退"
    cd /data/local/tmp
    CLASSPATH="$APK_PATH" app_process /system/bin uiautomator.ExportApi --keep </dev/null >/dev/null 2>&1 &
fi

sleep 3

# 验证启动结果
if pidof yyds.keep >/dev/null 2>&1; then
    log "yyds.keep 启动成功 (PID: $(pidof yyds.keep))"
else
    log "警告: yyds.keep 启动可能失败，尝试 app_process 回退"
    cd /data/local/tmp
    CLASSPATH="$APK_PATH" app_process /system/bin uiautomator.ExportApi --keep </dev/null >/dev/null 2>&1 &
    sleep 3
    if pidof yyds.keep >/dev/null 2>&1; then
        log "app_process 回退启动成功"
    else
        log "错误: 所有启动方式均失败"
    fi
fi

log "=== 开机自启模块执行完毕 ==="
