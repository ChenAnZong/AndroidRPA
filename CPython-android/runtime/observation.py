"""
Smart Observation Layer — 智能观测层
根据当前前台应用类型，选择最合适的观测方式:
1. 标准 App → UI 控件树（压缩后）+ 截图
2. 游戏/Flutter/WebView → OCR 文字 + 截图
3. 始终注入前台应用/Activity 信息

UI XML 压缩策略:
- 仅保留可交互或有文字的控件
- 每个控件只保留: text, resource-id, bounds, clickable, class 短名
- 格式化为紧凑列表（非完整 XML）
- 预估 10:1 压缩比
"""

import json
import re
import struct
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple


# 无法获取有效控件树的应用类型/特征
_GAME_ENGINES = {
    "org.cocos2dx", "com.unity3d", "com.epicgames", "com.unrealengine",
}
_FLUTTER_MARKERS = {"io.flutter", "FlutterView", "FlutterSurfaceView"}
_WEBVIEW_CLASSES = {"android.webkit.WebView", "org.chromium", "com.tencent.smtt"}

# 已知无法获取控件树的包名前缀
_NO_UI_TREE_PACKAGES = {
    "com.miHoYo",          # 米哈游游戏
    "com.tencent.tmgp",    # 腾讯游戏
    "com.netease.g",       # 网易游戏
}


@dataclass
class Observation:
    """一次观测结果"""
    screenshot_bytes: Optional[bytes] = None
    foreground_app: str = ""
    foreground_activity: str = ""
    ui_elements: str = ""       # 压缩后的 UI 控件信息
    ocr_texts: str = ""         # OCR 文字信息
    screen_width: int = 0
    screen_height: int = 0
    observation_type: str = ""  # "ui" | "ocr" | "screenshot_only"


@dataclass
class CompactElement:
    """压缩后的 UI 控件"""
    text: str = ""
    resource_id: str = ""
    content_desc: str = ""
    class_short: str = ""
    bounds: str = ""
    clickable: bool = False
    center_x: int = 0        # 像素坐标
    center_y: int = 0
    rel_x: int = 0           # 相对坐标 (0-1000)
    rel_y: int = 0


class ObservationProvider:
    """观测提供者，封装引擎 API 调用"""

    def __init__(self, engine_proxy):
        self._engine = engine_proxy

    def capture(self, use_ui: bool = True, use_ocr_fallback: bool = True) -> Observation:
        """
        获取当前屏幕观测

        Args:
            use_ui: 是否尝试获取 UI 控件树
            use_ocr_fallback: UI 控件树不可用时是否回退到 OCR
        """
        obs = Observation()

        # 1. 截图
        obs.screenshot_bytes = self._engine.screenshot_bytes(quality=70)

        # 2. 屏幕尺寸
        screen_info = self._engine.screen_info()
        if screen_info:
            obs.screen_width = screen_info.get("width", 0) or screen_info.get("w", 0)
            obs.screen_height = screen_info.get("height", 0) or screen_info.get("h", 0)

        # 3. 前台应用信息
        fg = self._engine.foreground()
        if fg:
            try:
                fg_data = json.loads(fg) if isinstance(fg, str) else fg
                if isinstance(fg_data, dict):
                    obs.foreground_app = fg_data.get("pkg", "")
                    obs.foreground_activity = fg_data.get("activity", "")
                else:
                    # 旧格式: "pkg activity pid"
                    parts = str(fg).split()
                    if len(parts) >= 2:
                        obs.foreground_app = parts[0]
                        obs.foreground_activity = parts[1]
            except (json.JSONDecodeError, TypeError):
                obs.foreground_app = str(fg).split()[0] if fg else ""

        # 屏幕尺寸回退: 如果 screen_info 失败，尝试从截图推断
        if (not obs.screen_width or not obs.screen_height) and obs.screenshot_bytes:
            w, h = _guess_screen_size_from_image(obs.screenshot_bytes)
            if w and h:
                obs.screen_width = w
                obs.screen_height = h

        # 4. 决定观测方式
        should_use_ui = use_ui and not _should_skip_ui_tree(obs.foreground_app, obs.foreground_activity)

        if should_use_ui:
            ui_xml = self._engine.ui_dump_text()
            if ui_xml and len(ui_xml) > 100:
                elements = compress_ui_xml(ui_xml, obs.screen_width, obs.screen_height)
                if elements:
                    obs.ui_elements = format_elements_for_prompt(elements)
                    obs.observation_type = "ui"

        # 5. UI 不可用时回退到 OCR
        if not obs.ui_elements and use_ocr_fallback:
            ocr_raw = self._engine.ocr()
            if ocr_raw:
                obs.ocr_texts = format_ocr_for_prompt(
                    ocr_raw, obs.screen_width, obs.screen_height
                )
                obs.observation_type = "ocr"

        if not obs.observation_type:
            obs.observation_type = "screenshot_only"

        return obs


# ============================================================
# UI XML 压缩
# ============================================================

def compress_ui_xml(
    xml_text: str,
    screen_w: int = 0,
    screen_h: int = 0,
    max_elements: int = 40,
) -> List[CompactElement]:
    """
    将 UI dump XML 压缩为紧凑控件列表

    只保留:
    - clickable=true 的控件
    - 有 text 或 content-desc 的控件
    - 有 resource-id 的控件

    每个控件只保留关键属性
    """
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return []

    elements = []
    _walk_node(root, elements, screen_w, screen_h)

    # 去重（相同 bounds + text 的控件只保留一个）
    seen = set()
    unique = []
    for e in elements:
        key = (e.text, e.bounds, e.resource_id)
        if key not in seen:
            seen.add(key)
            unique.append(e)

    # 按 Y 坐标排序（从上到下），限制数量
    unique.sort(key=lambda e: (e.center_y, e.center_x))
    return unique[:max_elements]


def _walk_node(
    node: ET.Element,
    result: List[CompactElement],
    screen_w: int,
    screen_h: int,
):
    """递归遍历 XML 节点"""
    text = node.attrib.get("text", "").strip()
    content_desc = node.attrib.get("content-desc", "").strip()
    resource_id = node.attrib.get("resource-id", "").strip()
    clickable = node.attrib.get("clickable", "false") == "true"
    long_clickable = node.attrib.get("long-clickable", "false") == "true"
    bounds_str = node.attrib.get("bounds", "")
    class_name = node.attrib.get("class", "")

    # 过滤: 至少满足一个条件
    has_content = bool(text or content_desc)
    is_interactive = clickable or long_clickable
    has_id = bool(resource_id)

    if has_content or is_interactive or has_id:
        # 解析 bounds "[x1,y1][x2,y2]"
        cx, cy = 0, 0
        bounds_match = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds_str)
        if bounds_match:
            x1 = int(bounds_match.group(1))
            y1 = int(bounds_match.group(2))
            x2 = int(bounds_match.group(3))
            y2 = int(bounds_match.group(4))
            cx = (x1 + x2) // 2
            cy = (y1 + y2) // 2

            # 过滤不可见/零面积控件
            if x2 <= x1 or y2 <= y1:
                pass  # 跳过但继续遍历子节点
            else:
                # 计算相对坐标 (0-1000)
                rel_x = int(cx / screen_w * 1000) if screen_w > 0 else 0
                rel_y = int(cy / screen_h * 1000) if screen_h > 0 else 0

                # class 短名
                class_short = class_name.rsplit(".", 1)[-1] if "." in class_name else class_name

                # resource-id 短名
                id_short = resource_id.split("/")[-1] if "/" in resource_id else resource_id

                elem = CompactElement(
                    text=text[:50],  # 截断过长文字
                    resource_id=id_short,
                    content_desc=content_desc[:50],
                    class_short=class_short,
                    bounds=bounds_str,
                    clickable=clickable,
                    center_x=cx,
                    center_y=cy,
                    rel_x=rel_x,
                    rel_y=rel_y,
                )
                result.append(elem)

    # 递归子节点
    for child in node:
        _walk_node(child, result, screen_w, screen_h)


def format_elements_for_prompt(elements: List[CompactElement]) -> str:
    """将压缩控件列表格式化为 prompt 文本"""
    if not elements:
        return ""
    lines = []
    for i, e in enumerate(elements):
        parts = []
        # 显示内容
        if e.text:
            parts.append(f'"{e.text}"')
        elif e.content_desc:
            parts.append(f'[{e.content_desc}]')
        # 控件类型 + ID
        type_info = []
        if e.class_short:
            type_info.append(e.class_short)
        if e.resource_id:
            type_info.append(e.resource_id)
        if type_info:
            parts.append(f"({', '.join(type_info)})")
        # 可交互标记
        if e.clickable:
            parts.append("可点击")
        # 相对坐标
        parts.append(f"@[{e.rel_x},{e.rel_y}]")

        lines.append(f"{i+1}. {' '.join(parts)}")

    return "\n".join(lines)


# ============================================================
# OCR 格式化
# ============================================================

def format_ocr_for_prompt(
    ocr_raw: str,
    screen_w: int = 0,
    screen_h: int = 0,
    max_items: int = 30,
) -> str:
    """
    将 OCR 结果格式化为 prompt 文本

    OCR 引擎返回格式: JSON array of {text, confidence, box:{x1,y1,...}}
    """
    try:
        items = json.loads(ocr_raw) if isinstance(ocr_raw, str) else ocr_raw
    except (json.JSONDecodeError, TypeError):
        return ""

    if not isinstance(items, list):
        return ""

    lines = []
    for item in items[:max_items]:
        text = item.get("text", "").strip()
        if not text:
            continue

        box = item.get("box", {})
        # 计算中心坐标
        x1 = int(box.get("x1", 0))
        y1 = int(box.get("y1", 0))
        x3 = int(box.get("x3", 0))
        y3 = int(box.get("y3", 0))
        cx = (x1 + x3) // 2
        cy = (y1 + y3) // 2

        # 转换为相对坐标
        if screen_w > 0 and screen_h > 0:
            rel_x = int(cx / screen_w * 1000)
            rel_y = int(cy / screen_h * 1000)
            lines.append(f'"{text}" @[{rel_x},{rel_y}]')
        else:
            lines.append(f'"{text}"')

    return "\n".join(lines)


# ============================================================
# 辅助函数
# ============================================================

def _guess_screen_size_from_image(img_bytes: bytes) -> Tuple[int, int]:
    """从 JPEG/PNG 头部推断图片尺寸（不依赖 PIL）"""
    try:
        if img_bytes[:4] == b'\x89PNG':
            # PNG: IHDR chunk at offset 16, width(4) + height(4) big-endian
            if len(img_bytes) >= 24:
                w = struct.unpack(">I", img_bytes[16:20])[0]
                h = struct.unpack(">I", img_bytes[20:24])[0]
                return w, h
        elif img_bytes[:2] == b'\xff\xd8':
            # JPEG: scan SOF markers for width/height
            i = 2
            while i < len(img_bytes) - 9:
                if img_bytes[i] != 0xFF:
                    break
                marker = img_bytes[i + 1]
                if marker in (0xC0, 0xC1, 0xC2):
                    h = struct.unpack(">H", img_bytes[i + 5:i + 7])[0]
                    w = struct.unpack(">H", img_bytes[i + 7:i + 9])[0]
                    return w, h
                length = struct.unpack(">H", img_bytes[i + 2:i + 4])[0]
                i += 2 + length
    except Exception:
        pass
    return 0, 0


def _should_skip_ui_tree(package: str, activity: str) -> bool:
    """判断是否应跳过 UI 控件树（游戏/Flutter/WebView 等）"""
    if not package:
        return False

    # 已知无控件树的包名
    for prefix in _NO_UI_TREE_PACKAGES:
        if package.startswith(prefix):
            return True

    # 游戏引擎特征
    for marker in _GAME_ENGINES:
        if marker in package:
            return True

    # Flutter 特征（检查 activity 名）
    if activity:
        for marker in _FLUTTER_MARKERS:
            if marker in activity:
                return True

    return False


def coords_to_pixels(
    rel_x: int,
    rel_y: int,
    screen_w: int,
    screen_h: int,
) -> Tuple[int, int]:
    """将相对坐标 (0-1000) 转换为像素坐标"""
    px = int(rel_x / 1000 * screen_w)
    py = int(rel_y / 1000 * screen_h)
    return px, py


def pixels_to_coords(
    px: int,
    py: int,
    screen_w: int,
    screen_h: int,
) -> Tuple[int, int]:
    """将像素坐标转换为相对坐标 (0-1000)"""
    rel_x = int(px / screen_w * 1000) if screen_w > 0 else 0
    rel_y = int(py / screen_h * 1000) if screen_h > 0 else 0
    return rel_x, rel_y
