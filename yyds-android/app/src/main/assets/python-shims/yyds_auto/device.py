"""Device 核心类 — 组合所有 Mixin，提供统一设备操作 API"""

from __future__ import annotations

import logging
from typing import Any, Dict, Optional, Tuple

from .client import HttpClient
from .exceptions import ConnectError
from .selector import Selector, UiObject
from .types import DeviceInfo

from .mixins.touch import TouchMixin
from .mixins.input import InputMixin
from .mixins.screen import ScreenMixin
from .mixins.app import AppMixin
from .mixins.shell import ShellMixin
from .mixins.file import FileMixin
from .mixins.ocr import OcrMixin
from .mixins.project import ProjectMixin

logger = logging.getLogger("yyds_auto")


class _Settings(dict):
    """设备设置（可像 dict 一样读写）"""

    _DEFAULTS = {
        "wait_timeout": 10,       # 元素等待默认超时
        "click_after_wait": 0.1,  # 点击后等待
        "screenshot_quality": 80, # 截图质量
    }

    def __init__(self):
        super().__init__(self._DEFAULTS)


class Device(TouchMixin, InputMixin, ScreenMixin, AppMixin,
             ShellMixin, FileMixin, OcrMixin, ProjectMixin):
    """Android 设备操作对象

    通过 yyds_auto.connect() 创建，不要直接实例化。

    Example:
        >>> import yyds_auto
        >>> d = yyds_auto.connect()
        >>> d.screenshot("screen.png")
        >>> d.click(500, 800)
        >>> d(text="Settings").click()
    """

    def __init__(
        self,
        host: str = "127.0.0.1",
        port: int = 61140,
        serial: Optional[str] = None,
        adb=None,
    ):
        self._host = host
        self._port = port
        self._serial = serial or ""
        self._adb = adb
        self._client = HttpClient(host=host, port=port)
        self._settings = _Settings()
        self._device_info_cache: Optional[DeviceInfo] = None

    def __repr__(self) -> str:
        if self._serial:
            return f"Device(serial={self._serial!r}, endpoint={self._client.base_url})"
        return f"Device(endpoint={self._client.base_url})"

    def __enter__(self) -> "Device":
        return self

    def __exit__(self, *args) -> None:
        self.disconnect()

    # ---------- 元素选择器 ----------

    def __call__(self, **kwargs) -> UiObject:
        """创建 UI 元素选择器

        Example:
            >>> d(text="Settings").click()
            >>> d(resourceId="com.example:id/btn", clickable=True).wait(timeout=10)
            >>> d(className="android.widget.EditText").set_text("hello")
        """
        return UiObject(self, Selector(**kwargs))

    # ---------- 设备信息 ----------

    @property
    def serial(self) -> str:
        """设备序列号"""
        return self._serial

    @property
    def info(self) -> Dict[str, Any]:
        """基础设备信息"""
        try:
            return self._client.get_json("/")
        except Exception:
            return {}

    @property
    def device_info(self) -> DeviceInfo:
        """详细硬件信息"""
        if self._device_info_cache:
            return self._device_info_cache
        try:
            data = self._client.get_json("/")
            self._device_info_cache = DeviceInfo.from_dict(data if isinstance(data, dict) else {})
        except Exception:
            self._device_info_cache = DeviceInfo()
        return self._device_info_cache

    @property
    def wlan_ip(self) -> str:
        """设备 WLAN IP 地址"""
        return self.device_info.wlan_ip

    @property
    def settings(self) -> _Settings:
        """设备设置

        Example:
            >>> d.settings["wait_timeout"] = 20
        """
        return self._settings

    # ---------- 连接管理 ----------

    def alive(self) -> bool:
        """检测引擎是否可达"""
        return self._client.ping()

    def disconnect(self) -> None:
        """断开连接（清理端口转发等资源）"""
        if self._adb and self._serial:
            try:
                self._adb.forward_remove(self._serial, self._port)
            except Exception:
                pass

    def healthcheck(self) -> Dict[str, Any]:
        """健康检查，返回引擎状态"""
        return {
            "alive": self.alive(),
            "serial": self._serial,
            "endpoint": self._client.base_url,
        }

    def reboot_engine(self) -> None:
        """重启引擎"""
        try:
            self._client.post_text("/engine/reboot")
        except Exception:
            pass
