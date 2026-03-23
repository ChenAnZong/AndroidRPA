"""文本输入与按键 Mixin"""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from ..device import Device

# 按键名 → Android KeyCode 映射
_KEY_MAP = {
    "home": "3", "back": "4", "call": "5", "end_call": "6",
    "0": "7", "1": "8", "2": "9", "3": "10", "4": "11",
    "5": "12", "6": "13", "7": "14", "8": "15", "9": "16",
    "star": "17", "pound": "18", "dpad_up": "19", "dpad_down": "20",
    "dpad_left": "21", "dpad_right": "22", "dpad_center": "23",
    "volume_up": "24", "volume_down": "25", "power": "26",
    "camera": "27", "clear": "28", "enter": "66", "delete": "67",
    "backspace": "67", "tab": "61", "space": "62", "menu": "82",
    "search": "84", "recent": "187", "app_switch": "187",
    "notification": "83", "settings": "176",
}


class InputMixin:
    """文本输入与按键"""

    _client: ...

    def send_keys(self, text: str, clear: bool = False) -> None:
        """输入文本

        Args:
            text: 要输入的文本
            clear: 是否先清空输入框
        """
        if clear:
            self.clear_text()
        self._client.auto_api("/inject_text", {"text": text})

    def clear_text(self) -> None:
        """清空当前输入框"""
        # 全选 + 删除
        self._client.auto_api("/key_code", {"code": "29", "meta": "28672"})  # Ctrl+A
        self._client.auto_api("/key_code", {"code": "67"})  # Delete

    def press(self, key: str) -> None:
        """按下按键

        Args:
            key: 按键名称（如 "home", "back", "enter", "volume_up"）
                 或 Android KeyCode 数字字符串
        """
        code = _KEY_MAP.get(key.lower(), key)
        self._client.auto_api("/key_code", {"code": code})

    def set_clipboard(self, text: str) -> None:
        """设置剪贴板内容"""
        self._client.auto_api("/set_clipboard", {"text": text})

    @property
    def clipboard(self) -> str:
        """获取剪贴板内容"""
        result = self._client.auto_api("/get_clipboard")
        return str(result) if result else ""
