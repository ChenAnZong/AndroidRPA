"""OCR 与图像识别 Mixin"""

from __future__ import annotations

import time
from typing import TYPE_CHECKING, List, Optional, Tuple

from ..types import OcrResult

if TYPE_CHECKING:
    from ..device import Device


class OcrMixin:
    """OCR 与图像识别"""

    _client: ...

    def ocr(self, region: Optional[Tuple[int, int, int, int]] = None) -> List[OcrResult]:
        """屏幕 OCR 识别

        Args:
            region: 识别区域 (left, top, right, bottom)，None 为全屏

        Returns:
            OCR 结果列表
        """
        params = {}
        if region:
            params["region"] = f"{region[0]},{region[1]},{region[2]},{region[3]}"

        result = self._client.auto_api("/screen_ocr", params)
        items: List[OcrResult] = []
        if isinstance(result, list):
            for item in result:
                if isinstance(item, dict):
                    bounds = item.get("bounds", item.get("rect", {}))
                    if isinstance(bounds, dict):
                        b = (
                            int(bounds.get("left", bounds.get("x", 0))),
                            int(bounds.get("top", bounds.get("y", 0))),
                            int(bounds.get("right", bounds.get("x", 0) + bounds.get("width", 0))),
                            int(bounds.get("bottom", bounds.get("y", 0) + bounds.get("height", 0))),
                        )
                    elif isinstance(bounds, (list, tuple)) and len(bounds) == 4:
                        b = tuple(int(x) for x in bounds)  # type: ignore
                    else:
                        b = (0, 0, 0, 0)

                    items.append(OcrResult(
                        text=item.get("text", ""),
                        confidence=float(item.get("confidence", item.get("score", 0))),
                        bounds=b,  # type: ignore
                    ))
        return items

    def ocr_find(self, text: str, region: Optional[Tuple[int, int, int, int]] = None) -> Optional[OcrResult]:
        """查找屏幕上的文字

        Args:
            text: 要查找的文字（子串匹配）
            region: 搜索区域

        Returns:
            找到的第一个匹配结果，未找到返回 None
        """
        results = self.ocr(region=region)
        for r in results:
            if text in r.text:
                return r
        return None

    def ocr_click(self, text: str, timeout: float = 10, region: Optional[Tuple[int, int, int, int]] = None) -> bool:
        """OCR 查找文字并点击

        Args:
            text: 要查找并点击的文字
            timeout: 超时秒数
            region: 搜索区域

        Returns:
            是否成功找到并点击
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            result = self.ocr_find(text, region=region)
            if result:
                cx, cy = result.center
                self.click(cx, cy)  # type: ignore
                return True
            time.sleep(0.5)
        return False

    def ocr_wait(self, text: str, timeout: float = 10, region: Optional[Tuple[int, int, int, int]] = None) -> bool:
        """等待屏幕上出现指定文字

        Args:
            text: 要等待的文字
            timeout: 超时秒数
            region: 搜索区域

        Returns:
            是否在超时前出现
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.ocr_find(text, region=region):
                return True
            time.sleep(0.5)
        return False

    def find_image(self, template: str, threshold: float = 0.8) -> Optional[Tuple[int, int]]:
        """模板匹配 — 在屏幕上查找图片

        Args:
            template: 模板图片路径（设备本地路径或 base64）
            threshold: 匹配阈值 (0~1)

        Returns:
            匹配中心坐标 (x, y)，未找到返回 None
        """
        result = self._client.auto_api("/find_image", {
            "template": template,
            "threshold": str(threshold),
        })
        if isinstance(result, dict):
            x = result.get("x")
            y = result.get("y")
            if x is not None and y is not None:
                return (int(x), int(y))
        return None

    def pixel_color(self, x: int, y: int) -> str:
        """获取指定坐标的像素颜色

        Returns:
            颜色值字符串，如 "#FF0000"
        """
        result = self._client.auto_api("/get_color", {"x": str(x), "y": str(y)})
        return str(result) if result else ""
