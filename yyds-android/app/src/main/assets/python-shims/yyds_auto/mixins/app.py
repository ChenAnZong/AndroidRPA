"""应用管理 Mixin"""

from __future__ import annotations

import time
from typing import TYPE_CHECKING, Dict, List, Optional

from ..types import AppInfo

if TYPE_CHECKING:
    from ..device import Device


class AppMixin:
    """应用管理"""

    _client: ...

    def app_start(self, package: str, activity: Optional[str] = None, stop: bool = False) -> None:
        """启动应用

        Args:
            package: 包名
            activity: Activity 名称，为 None 则启动默认 Activity
            stop: 是否先停止应用再启动
        """
        if stop:
            self.app_stop(package)
            time.sleep(0.5)

        if activity:
            self._client.auto_api("/open_app", {"packageName": package, "activity": activity})
        else:
            self._client.auto_api("/open_app", {"packageName": package})

    def app_stop(self, package: str) -> None:
        """停止应用"""
        self.shell(f"am force-stop {package}")  # type: ignore

    def app_install(self, apk_path: str) -> None:
        """安装 APK（设备本地路径）"""
        self.shell(f"pm install -r {apk_path}")  # type: ignore

    def app_uninstall(self, package: str) -> None:
        """卸载应用"""
        self.shell(f"pm uninstall {package}")  # type: ignore

    def app_list(self, filter: Optional[str] = None) -> List[str]:
        """列出已安装应用包名

        Args:
            filter: 过滤关键字
        """
        result = self._client.get_json("/package/installed-apps")
        packages = result if isinstance(result, list) else []
        if filter:
            packages = [p for p in packages if filter.lower() in str(p).lower()]
        return packages

    def app_current(self) -> Dict[str, str]:
        """获取当前前台应用信息

        Returns:
            {"package": "com.xxx", "activity": ".MainActivity"}
        """
        result = self._client.auto_api("/foreground")
        if isinstance(result, dict):
            return result
        return {"package": "", "activity": ""}

    def app_wait(self, package: str, timeout: float = 20) -> bool:
        """等待应用出现在前台

        Args:
            package: 包名
            timeout: 超时秒数

        Returns:
            是否在超时前出现
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            current = self.app_current()
            if current.get("package") == package:
                return True
            time.sleep(0.5)
        return False

    def app_info(self, package: str) -> Dict:
        """获取应用详细信息"""
        resp = self.shell(f"dumpsys package {package}")  # type: ignore
        return {"package": package, "raw": str(resp)}
