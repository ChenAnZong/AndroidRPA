"""截图与 UI 树 Mixin"""

from __future__ import annotations

import io
from typing import TYPE_CHECKING, Optional, Tuple, Union

if TYPE_CHECKING:
    from PIL import Image as PILImage
    from ..device import Device


class ScreenMixin:
    """截图与屏幕操作"""

    _client: ...

    def screenshot(self, filename: Optional[str] = None, quality: int = 80) -> "PILImage.Image":
        """截图

        Args:
            filename: 保存路径，为 None 则仅返回 Image 对象
            quality: JPEG 质量 (1-100)

        Returns:
            PIL.Image.Image 对象
        """
        from PIL import Image

        data = self._client.get_bytes(f"/screen/{quality}")
        img = Image.open(io.BytesIO(data))
        if filename:
            img.save(filename)
        return img

    def dump_hierarchy(self, compressed: bool = False) -> str:
        """获取 UI 控件树 XML

        Args:
            compressed: 是否压缩（去除空白节点）

        Returns:
            XML 字符串
        """
        return self._client.get_text("/uia_dump")

    def window_size(self) -> Tuple[int, int]:
        """获取屏幕尺寸 (width, height)"""
        result = self._client.auto_api("/screen_size")
        if isinstance(result, dict):
            return (int(result.get("width", 0)), int(result.get("height", 0)))
        if isinstance(result, str) and "x" in result:
            parts = result.split("x")
            return (int(parts[0].strip()), int(parts[1].strip()))
        return (0, 0)

    @property
    def orientation(self) -> int:
        """获取屏幕方向 (0=竖屏, 1=横屏左, 2=倒屏, 3=横屏右)"""
        result = self._client.auto_api("/screen_rotation")
        return int(result) if result else 0

    def screen_on(self) -> None:
        """点亮屏幕"""
        self._client.auto_api("/wake_up")

    def screen_off(self) -> None:
        """熄灭屏幕"""
        self._client.auto_api("/sleep")

    def freeze_rotation(self, freeze: bool = True) -> None:
        """冻结/解冻屏幕旋转"""
        self._client.auto_api("/freeze_rotation", {"freeze": str(freeze).lower()})
