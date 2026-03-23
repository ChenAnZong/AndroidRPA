"""设备初始化 — APK 安装检测、引擎启动"""

from __future__ import annotations

import logging
import time
from typing import Optional

from .adb import AdbClient
from .client import HttpClient
from .exceptions import AppNotInstalledError, EngineNotRunningError, EngineStartError

logger = logging.getLogger("yyds_auto")

PACKAGE_NAME = "com.yyds.auto"
MAIN_ACTIVITY = f"{PACKAGE_NAME}/.MainActivity"
ENGINE_PORT = 61140


def check_app_installed(adb: AdbClient, serial: str) -> bool:
    """检查 yyds.auto APK 是否已安装"""
    return adb.is_installed(serial, PACKAGE_NAME)


def install_app(adb: AdbClient, serial: str, apk_path: Optional[str] = None) -> None:
    """安装 yyds.auto APK

    Args:
        apk_path: APK 文件路径，为 None 时尝试从 assets 目录或网络下载
    """
    if apk_path is None:
        apk_path = _find_builtin_apk()

    if apk_path is None:
        raise AppNotInstalledError(
            "yyds.auto 未安装且未找到 APK 文件\n"
            "请手动安装 APK 或指定 apk_path 参数\n"
            "下载地址: https://github.com/nicekwell/yyds-auto/releases"
        )

    logger.info("正在安装 yyds.auto APK: %s", apk_path)
    adb.install(serial, apk_path)
    logger.info("APK 安装完成")


def _find_builtin_apk() -> Optional[str]:
    """查找内置 APK"""
    import os
    assets_dir = os.path.join(os.path.dirname(__file__), "assets")
    if os.path.isdir(assets_dir):
        for f in os.listdir(assets_dir):
            if f.endswith(".apk"):
                return os.path.join(assets_dir, f)
    return None


def _try_start_via_am(adb: AdbClient, serial: str) -> None:
    """通过 am start 启动 App"""
    logger.info("通过 am start 启动 yyds.auto ...")
    adb.start_activity(serial, MAIN_ACTIVITY)


def _try_start_via_keeper(adb: AdbClient, serial: str) -> bool:
    """通过 yyds.keep 守护进程启动引擎"""
    logger.info("尝试通过 keeper 启动引擎 ...")
    # 检查 keeper 是否存在
    code, out = adb.shell(serial, "ls /data/local/tmp/cache/lib/*/libyyds_keep.so 2>/dev/null")
    if code != 0 or not out.strip():
        return False

    keeper_path = out.strip().splitlines()[0]
    # 启动 keeper（后台运行）
    adb.shell(serial, f"chmod 755 {keeper_path} && {keeper_path} </dev/null >/dev/null 2>&1 &")
    return True


def _try_start_via_app_process(adb: AdbClient, serial: str) -> bool:
    """通过 app_process 直接启动引擎（需要 ROOT/SHELL 权限）"""
    logger.info("尝试通过 app_process 启动引擎 ...")
    # 获取 APK 路径
    code, out = adb.shell(serial, f"pm path {PACKAGE_NAME}")
    if code != 0 or "package:" not in out:
        return False

    apk_path = out.strip().split("package:")[1].strip()

    # 提取 native libs
    code, _ = adb.shell(serial, (
        f"mkdir -p /data/local/tmp/cache/lib && "
        f"unzip -o {apk_path} 'lib/*' -d /data/local/tmp/cache/ 2>/dev/null; "
        f"chmod -R 755 /data/local/tmp/cache/lib/"
    ), timeout=30)

    # 尝试启动 keeper
    return _try_start_via_keeper(adb, serial)


def ensure_engine(
    adb: Optional[AdbClient],
    serial: Optional[str],
    http: HttpClient,
    auto_start: bool = True,
    timeout: float = 30,
) -> None:
    """确保引擎运行中

    1. ping 检测引擎
    2. 未运行 → am start 启动 App
    3. 仍未运行 → keeper / app_process 启动
    4. 等待引擎就绪
    """
    # 已经在运行
    if http.ping(timeout=3):
        logger.debug("引擎已运行")
        return

    if not auto_start:
        raise EngineNotRunningError("引擎未运行，请先在设备上启动 yyds.auto")

    if adb is None or serial is None:
        raise EngineNotRunningError("引擎未运行，WiFi 直连模式下无法自动启动引擎，请先在设备上启动 yyds.auto")

    # 检查 APK 是否安装
    if not check_app_installed(adb, serial):
        raise AppNotInstalledError(
            "yyds.auto 未安装，请先安装 APK\n"
            "使用 d.setup.install() 安装或手动安装"
        )

    # 策略 1: am start
    _try_start_via_am(adb, serial)
    if http.wait_ready(timeout=10, interval=1):
        logger.info("引擎启动成功 (via am start)")
        return

    # 策略 2: keeper
    if _try_start_via_keeper(adb, serial):
        if http.wait_ready(timeout=15, interval=1):
            logger.info("引擎启动成功 (via keeper)")
            return

    # 策略 3: app_process
    if _try_start_via_app_process(adb, serial):
        if http.wait_ready(timeout=20, interval=1):
            logger.info("引擎启动成功 (via app_process)")
            return

    raise EngineStartError(f"引擎启动超时 ({timeout}s)，请检查设备上的 yyds.auto 应用")
