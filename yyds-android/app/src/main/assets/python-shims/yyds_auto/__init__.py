"""yyds-auto — Android RPA 自动化库

像 uiautomator2 一样，从 PC 端 Python 控制 Android 设备。

Quick Start:
    >>> import yyds_auto
    >>> d = yyds_auto.connect()           # USB 自动连接
    >>> d = yyds_auto.connect("serial")   # 指定设备
    >>> d = yyds_auto.connect("192.168.1.5")  # WiFi 直连
    >>> d.screenshot("screen.png")
    >>> d.click(500, 800)
    >>> d(text="Settings").click()
"""

from __future__ import annotations

import logging
import re
from typing import List, Optional, Union

from .version import __version__
from .device import Device
from .types import DiscoveredDevice
from .exceptions import (
    YydsError,
    AdbError,
    AdbNotFoundError,
    DeviceNotFoundError,
    EngineNotRunningError,
    ConnectError,
    UiObjectNotFoundError,
)

__all__ = [
    "__version__",
    "connect",
    "connect_usb",
    "connect_wifi",
    "discover",
    "Device",
    "YydsError",
    "AdbError",
    "DeviceNotFoundError",
    "EngineNotRunningError",
    "ConnectError",
    "UiObjectNotFoundError",
]

logger = logging.getLogger("yyds_auto")

_IP_PATTERN = re.compile(r"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?$")


def connect(addr: Optional[str] = None, auto_start: bool = True) -> Device:
    """连接 Android 设备（自动识别 USB / WiFi）

    Args:
        addr: 设备地址，支持以下格式:
            - None: 自动连接 USB 设备（仅一台时）
            - "SERIAL": USB 设备序列号
            - "192.168.1.5": WiFi IP 地址
            - "192.168.1.5:61140": WiFi IP + 端口
        auto_start: 引擎未运行时是否自动启动

    Returns:
        Device 对象

    Example:
        >>> d = yyds_auto.connect()
        >>> d = yyds_auto.connect("HJR5T19A28007194")
        >>> d = yyds_auto.connect("192.168.1.5")
    """
    if addr and _IP_PATTERN.match(addr):
        # WiFi 模式
        parts = addr.split(":")
        host = parts[0]
        port = int(parts[1]) if len(parts) > 1 else 61140
        return connect_wifi(host, port, auto_start=auto_start)
    else:
        # USB 模式
        return connect_usb(serial=addr, auto_start=auto_start)


def connect_usb(serial: Optional[str] = None, port: int = 61140, auto_start: bool = True) -> Device:
    """通过 USB (ADB) 连接设备

    Args:
        serial: 设备序列号，None 则自动选择唯一设备
        port: 本地转发端口
        auto_start: 引擎未运行时是否自动启动

    Returns:
        Device 对象
    """
    from .adb import AdbClient
    from .client import HttpClient
    from .setup import ensure_engine

    adb = AdbClient()
    dev = adb.get_device(serial)
    logger.info("USB 连接设备: %s", dev.serial)

    # 端口转发
    adb.forward(dev.serial, port, 61140)
    logger.debug("端口转发: localhost:%d → device:61140", port)

    http = HttpClient(host="127.0.0.1", port=port)

    # 确保引擎运行
    ensure_engine(adb, dev.serial, http, auto_start=auto_start)

    return Device(host="127.0.0.1", port=port, serial=dev.serial, adb=adb)


def connect_wifi(host: str, port: int = 61140, auto_start: bool = True) -> Device:
    """通过 WiFi 直连设备

    Args:
        host: 设备 IP 地址
        port: 引擎端口（默认 61140）
        auto_start: 引擎未运行时是否尝试启动（WiFi 模式下需设备已启动引擎）

    Returns:
        Device 对象
    """
    from .client import HttpClient
    from .setup import ensure_engine

    logger.info("WiFi 连接设备: %s:%d", host, port)
    http = HttpClient(host=host, port=port)

    # WiFi 模式下无 ADB 通道，无法自动启动
    ensure_engine(None, None, http, auto_start=auto_start)

    return Device(host=host, port=port)


def discover(
    subnet: Union[str, List[str], None] = None,
    port: int = 61140,
    timeout: float = 0.3,
    max_workers: int = 128,
) -> List[DiscoveredDevice]:
    """扫描局域网中的 yyds.auto 设备

    Args:
        subnet: 子网范围（CIDR），如 "192.168.1.0/24"，None 自动检测
        port: 引擎端口
        timeout: 单 IP 探测超时（秒）
        max_workers: 并发线程数

    Returns:
        发现的设备列表

    Example:
        >>> devices = yyds_auto.discover()
        >>> for dev in devices:
        ...     print(f"{dev.ip} - {dev.model}")
        >>> d = devices[0].connect()
    """
    from .discover import discover as _discover
    return _discover(subnet=subnet, port=port, timeout=timeout, max_workers=max_workers)
