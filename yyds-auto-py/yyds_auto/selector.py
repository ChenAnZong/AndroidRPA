"""UiSelector + UiObject — 元素选择器"""

from __future__ import annotations

import time
from typing import TYPE_CHECKING, Any, Dict, List, Optional, Tuple

from .exceptions import UiObjectNotFoundError

if TYPE_CHECKING:
    from .device import Device


class Selector(dict):
    """UI 选择器参数构建，映射到 Android UiSelector

    支持的属性:
        text, textContains, textStartsWith, textMatches,
        resourceId, resourceIdMatches,
        className, classNameMatches,
        description, descriptionContains, descriptionStartsWith, descriptionMatches,
        clickable, scrollable, checkable, checked, enabled, focusable, focused, selected,
        packageName, index, instance
    """

    # 属性名 → autoApi 参数名映射
    _FIELD_MAP = {
        "text": "text",
        "textContains": "textContains",
        "textStartsWith": "textStartsWith",
        "textMatches": "textMatches",
        "resourceId": "resourceId",
        "resourceIdMatches": "resourceIdMatches",
        "className": "className",
        "classNameMatches": "classNameMatches",
        "description": "description",
        "descriptionContains": "descriptionContains",
        "descriptionStartsWith": "descriptionStartsWith",
        "descriptionMatches": "descriptionMatches",
        "clickable": "clickable",
        "scrollable": "scrollable",
        "checkable": "checkable",
        "checked": "checked",
        "enabled": "enabled",
        "focusable": "focusable",
        "focused": "focused",
        "selected": "selected",
        "packageName": "packageName",
        "index": "index",
        "instance": "instance",
    }

    def __init__(self, **kwargs):
        super().__init__()
        for k, v in kwargs.items():
            if k in self._FIELD_MAP:
                self[self._FIELD_MAP[k]] = v
            else:
                self[k] = v

    def __repr__(self) -> str:
        items = ", ".join(f"{k}={v!r}" for k, v in self.items())
        return f"Selector({items})"


class UiObject:
    """UI 元素操作对象

    通过 Device.__call__ 创建: d(text="Settings")
    """

    def __init__(self, device: "Device", selector: Selector):
        self._device = device
        self._selector = selector
        self._hashcode: Optional[str] = None

    def __repr__(self) -> str:
        return f"UiObject({self._selector})"

    # ---------- 元素查找 ----------

    def _find(self) -> Optional[Dict]:
        """查找元素，返回元素信息 dict"""
        params = dict(self._selector)
        # 将 bool 值转为字符串
        for k, v in params.items():
            if isinstance(v, bool):
                params[k] = str(v).lower()
            else:
                params[k] = str(v)

        result = self._device._client.auto_api("/uia_match", params)
        if isinstance(result, list) and len(result) > 0:
            node = result[0]
            if isinstance(node, dict):
                self._hashcode = str(node.get("hashCode", node.get("hashcode", "")))
                return node
        elif isinstance(result, dict):
            self._hashcode = str(result.get("hashCode", result.get("hashcode", "")))
            return result
        return None

    def _find_or_raise(self) -> Dict:
        """查找元素，未找到则抛异常"""
        node = self._find()
        if node is None:
            raise UiObjectNotFoundError(self._selector)
        return node

    def _get_bounds(self, node: Dict) -> Tuple[int, int, int, int]:
        """从节点信息提取边界"""
        bounds = node.get("bounds", node.get("rect", {}))
        if isinstance(bounds, dict):
            return (
                int(bounds.get("left", bounds.get("x", 0))),
                int(bounds.get("top", bounds.get("y", 0))),
                int(bounds.get("right", bounds.get("x", 0) + bounds.get("width", 0))),
                int(bounds.get("bottom", bounds.get("y", 0) + bounds.get("height", 0))),
            )
        if isinstance(bounds, str) and bounds.startswith("["):
            # "[left,top][right,bottom]" 格式
            import re
            nums = re.findall(r"\d+", bounds)
            if len(nums) == 4:
                return tuple(int(n) for n in nums)  # type: ignore
        return (0, 0, 0, 0)

    def _get_center(self, node: Dict) -> Tuple[int, int]:
        """获取元素中心坐标"""
        b = self._get_bounds(node)
        return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)

    # ---------- 属性 ----------

    @property
    def exists(self) -> bool:
        """元素是否存在"""
        return self._find() is not None

    @property
    def info(self) -> Dict:
        """元素详细信息"""
        return self._find_or_raise()

    @property
    def bounds(self) -> Tuple[int, int, int, int]:
        """元素边界 (left, top, right, bottom)"""
        return self._get_bounds(self._find_or_raise())

    @property
    def center(self) -> Tuple[int, int]:
        """元素中心坐标 (x, y)"""
        return self._get_center(self._find_or_raise())

    @property
    def text(self) -> str:
        """获取元素文本"""
        node = self._find_or_raise()
        return node.get("text", "")

    # ---------- 等待 ----------

    def wait(self, timeout: float = 10) -> bool:
        """等待元素出现

        Args:
            timeout: 超时秒数

        Returns:
            是否在超时前出现
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self._find() is not None:
                return True
            time.sleep(0.5)
        return False

    def wait_gone(self, timeout: float = 10) -> bool:
        """等待元素消失

        Args:
            timeout: 超时秒数

        Returns:
            是否在超时前消失
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self._find() is None:
                return True
            time.sleep(0.5)
        return False

    # ---------- 操作 ----------

    def click(self, timeout: Optional[float] = None) -> None:
        """点击元素"""
        if timeout:
            if not self.wait(timeout):
                raise UiObjectNotFoundError(self._selector)
        node = self._find_or_raise()
        cx, cy = self._get_center(node)
        self._device.click(cx, cy)

    def long_click(self, duration: float = 0.5) -> None:
        """长按元素"""
        node = self._find_or_raise()
        cx, cy = self._get_center(node)
        self._device.long_click(cx, cy, duration=duration)

    def set_text(self, text: str) -> None:
        """设置文本（先点击获取焦点，再输入）"""
        self.click()
        time.sleep(0.2)
        self._device.send_keys(text, clear=True)

    def get_text(self) -> str:
        """获取元素文本"""
        return self.text

    def clear_text(self) -> None:
        """清空文本"""
        self.click()
        time.sleep(0.2)
        self._device.clear_text()

    # ---------- 链式选择 ----------

    def child(self, **kwargs) -> "UiObject":
        """选择子元素"""
        merged = dict(self._selector)
        merged["_child"] = dict(Selector(**kwargs))
        return UiObject(self._device, Selector(**merged))

    def sibling(self, **kwargs) -> "UiObject":
        """选择兄弟元素"""
        merged = dict(self._selector)
        merged["_sibling"] = dict(Selector(**kwargs))
        return UiObject(self._device, Selector(**merged))

    def scroll_to(self, **kwargs) -> bool:
        """滚动查找元素

        Args:
            **kwargs: 目标元素选择器参数

        Returns:
            是否找到
        """
        target = Selector(**kwargs)
        for _ in range(10):
            # 检查目标是否已可见
            test = UiObject(self._device, target)
            if test.exists:
                return True
            # 向下滑动
            self._device.swipe_ext("up", scale=0.5)
            time.sleep(0.5)
        return False
