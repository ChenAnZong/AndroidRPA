"""触控操作 Mixin — click, swipe, long_press, drag"""

from __future__ import annotations

import time
from typing import TYPE_CHECKING, Optional, Tuple, Union

if TYPE_CHECKING:
    from ..device import Device


class TouchMixin:
    """触控手势"""

    # 由 Device 注入
    _client: ...

    def click(self, x: int, y: int) -> None:
        """点击指定坐标"""
        self._client.auto_api("/touch", {"x": str(x), "y": str(y)})

    def double_click(self, x: int, y: int, interval: float = 0.1) -> None:
        """双击指定坐标"""
        self.click(x, y)
        time.sleep(interval)
        self.click(x, y)

    def long_click(self, x: int, y: int, duration: float = 0.5) -> None:
        """长按指定坐标"""
        ms = int(duration * 1000)
        self._client.auto_api("/touch_down", {"x": str(x), "y": str(y)})
        time.sleep(duration)
        self._client.auto_api("/touch_up", {"x": str(x), "y": str(y)})

    def swipe(self, fx: int, fy: int, tx: int, ty: int, duration: float = 0.5) -> None:
        """从 (fx, fy) 滑动到 (tx, ty)

        Args:
            fx, fy: 起点坐标
            tx, ty: 终点坐标
            duration: 滑动时长（秒）
        """
        ms = int(duration * 1000)
        self._client.auto_api("/swipe", {
            "x1": str(fx), "y1": str(fy),
            "x2": str(tx), "y2": str(ty),
            "duration": str(ms),
        })

    def swipe_ext(self, direction: str, scale: float = 0.8) -> None:
        """扩展滑动 — 按方向滑动屏幕

        Args:
            direction: "up", "down", "left", "right"
            scale: 滑动距离占屏幕比例 (0~1)
        """
        w, h = self.window_size()  # type: ignore
        cx, cy = w // 2, h // 2
        dist_x = int(w * scale / 2)
        dist_y = int(h * scale / 2)

        mapping = {
            "up": (cx, cy + dist_y, cx, cy - dist_y),
            "down": (cx, cy - dist_y, cx, cy + dist_y),
            "left": (cx + dist_x, cy, cx - dist_x, cy),
            "right": (cx - dist_x, cy, cx + dist_x, cy),
        }
        if direction not in mapping:
            raise ValueError(f"direction 必须是 up/down/left/right，收到: {direction}")
        self.swipe(*mapping[direction])

    def drag(self, fx: int, fy: int, tx: int, ty: int, duration: float = 0.5) -> None:
        """拖拽（长按后滑动）"""
        ms = int(duration * 1000)
        self._client.auto_api("/swipe", {
            "x1": str(fx), "y1": str(fy),
            "x2": str(tx), "y2": str(ty),
            "duration": str(ms),
        })

    @property
    def touch(self) -> "_TouchAction":
        """底层触控操作链: d.touch.down(x,y).move(x,y).up()"""
        return _TouchAction(self._client)


class _TouchAction:
    """底层触控操作链"""

    def __init__(self, client):
        self._client = client

    def down(self, x: int, y: int) -> "_TouchAction":
        self._client.auto_api("/touch_down", {"x": str(x), "y": str(y)})
        return self

    def move(self, x: int, y: int) -> "_TouchAction":
        self._client.auto_api("/touch_move", {"x": str(x), "y": str(y)})
        return self

    def up(self, x: int = 0, y: int = 0) -> "_TouchAction":
        self._client.auto_api("/touch_up", {"x": str(x), "y": str(y)})
        return self

    def sleep(self, seconds: float) -> "_TouchAction":
        time.sleep(seconds)
        return self
