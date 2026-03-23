"""yyds-auto 数据模型"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple


@dataclass
class DeviceInfo:
    """设备详细信息"""
    model: str = ""
    brand: str = ""
    android_version: str = ""
    sdk_version: int = 0
    display_width: int = 0
    display_height: int = 0
    density: int = 0
    imei: str = ""
    serial: str = ""
    wlan_ip: str = ""

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "DeviceInfo":
        return cls(
            model=d.get("model", ""),
            brand=d.get("brand", ""),
            android_version=d.get("androidVersion", d.get("android_version", "")),
            sdk_version=int(d.get("sdkVersion", d.get("sdk_version", 0))),
            display_width=int(d.get("displayWidth", d.get("display_width", 0))),
            display_height=int(d.get("displayHeight", d.get("display_height", 0))),
            density=int(d.get("density", 0)),
            imei=d.get("imei", ""),
            serial=d.get("serial", ""),
            wlan_ip=d.get("wlanIp", d.get("wlan_ip", "")),
        )


@dataclass
class ShellResponse:
    """Shell 命令执行结果"""
    output: str = ""
    exit_code: int = 0

    def __str__(self) -> str:
        return self.output

    def __bool__(self) -> bool:
        return self.exit_code == 0


@dataclass
class OcrResult:
    """OCR 识别结果"""
    text: str = ""
    confidence: float = 0.0
    bounds: Tuple[int, int, int, int] = (0, 0, 0, 0)  # left, top, right, bottom

    @property
    def center(self) -> Tuple[int, int]:
        return ((self.bounds[0] + self.bounds[2]) // 2,
                (self.bounds[1] + self.bounds[3]) // 2)


@dataclass
class AppInfo:
    """应用信息"""
    package_name: str = ""
    activity: str = ""
    version_name: str = ""
    version_code: int = 0


@dataclass
class FileInfo:
    """文件信息"""
    name: str = ""
    path: str = ""
    is_dir: bool = False
    size: int = 0
    modified: str = ""


@dataclass
class ProjectInfo:
    """脚本项目信息"""
    name: str = ""
    path: str = ""
    running: bool = False


@dataclass
class DiscoveredDevice:
    """局域网扫描发现的设备"""
    ip: str = ""
    port: int = 61140
    model: str = ""
    brand: str = ""
    android_version: str = ""
    engine_version: str = ""

    def connect(self) -> Any:
        """连接此设备，返回 Device 对象"""
        from . import connect_wifi
        return connect_wifi(self.ip, self.port)

    def __repr__(self) -> str:
        label = self.model or self.ip
        return f"DiscoveredDevice(ip={self.ip!r}, model={self.model!r}, android={self.android_version!r})"
