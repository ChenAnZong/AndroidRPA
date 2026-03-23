"""
Tools 层 — 原子能力注册表
借鉴肉包项目的 Tool 接口 + ToolRegistry 设计
每个 Tool 是一个独立的原子能力，Agent 可通过名称调用

独特优势：
- Root shell 能力（su -c）
- RunPythonTool：Agent 生成 Python 代码在引擎上下文中执行
- 与 auto_engine / screen_capture 深度集成
"""

import json
import os
import re
import subprocess
import time
import traceback
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional
from xml.etree import ElementTree

from auto_engine_proxy import auto_engine
from screen_capture import screen_capture


# ==========================================================
# 共享 UI 查找工具函数（消除重复代码）
# ==========================================================

def find_elements_in_ui(text="", resource_id="", class_name="",
                        clickable=False, limit=20) -> list:
    """在 UI 控件树中查找元素，返回 [{text, x, y, bounds, resource_id, class}, ...]"""
    xml_str = auto_engine.ui_dump_text()
    if not xml_str:
        return []
    try:
        root = ElementTree.fromstring(xml_str)
    except Exception:
        return []

    matches = []
    for node in root.iter():
        attrs = node.attrib
        if clickable and attrs.get("clickable") != "true":
            continue
        node_text = (attrs.get("text", "") + " " + attrs.get("content-desc", "")).strip()
        if text and text.lower() not in node_text.lower():
            continue
        if resource_id and resource_id not in attrs.get("resource-id", ""):
            continue
        if class_name and class_name not in attrs.get("class", ""):
            continue
        bounds = attrs.get("bounds", "")
        m = re.findall(r'\d+', bounds)
        if len(m) == 4:
            x = (int(m[0]) + int(m[2])) // 2
            y = (int(m[1]) + int(m[3])) // 2
            matches.append({
                "text": node_text,
                "resource_id": attrs.get("resource-id", ""),
                "class": attrs.get("class", ""),
                "x": x, "y": y,
                "bounds": bounds,
            })
        if len(matches) >= limit:
            break
    return matches


# ==========================================================
# Tool 基类 & 结果
# ==========================================================

@dataclass
class ToolResult:
    """工具执行结果"""
    success: bool = True
    data: Any = None
    error: str = ""

    def to_agent_str(self) -> str:
        """转为 Agent 可读的字符串"""
        if not self.success:
            return f"[ERROR] {self.error}"
        if self.data is None:
            return "[OK]"
        if isinstance(self.data, str):
            return self.data[:2000]  # 截断防止 token 爆炸
        return json.dumps(self.data, ensure_ascii=False, default=str)[:2000]


@dataclass
class ToolParam:
    """工具参数定义"""
    name: str
    type: str  # string / int / float / bool
    description: str
    required: bool = True
    default: Any = None


class Tool:
    """工具基类 — 所有原子能力继承此类"""
    name: str = ""
    description: str = ""

    def __init__(self):
        # 每个实例独立的 params 列表，避免类变量共享污染
        if not hasattr(self, '_params_initialized'):
            self.params: List[ToolParam] = list(getattr(self.__class__, 'params', []))
            self._params_initialized = True

    def execute(self, **kwargs) -> ToolResult:
        raise NotImplementedError

    def to_llm_desc(self) -> str:
        """生成 LLM 可读的工具描述"""
        param_parts = []
        for p in self.params:
            req = "必填" if p.required else f"可选,默认{p.default}"
            param_parts.append(f'    "{p.name}": ({p.type}, {req}) {p.description}')
        params_str = "\n".join(param_parts) if param_parts else "    无参数"
        return f"- {self.name}: {self.description}\n  参数:\n{params_str}"


# ==========================================================
# ToolRegistry — 注册、查找、执行
# ==========================================================

class ToolRegistry:
    """工具注册表"""

    def __init__(self):
        self._tools: Dict[str, Tool] = {}

    def register(self, tool: Tool):
        self._tools[tool.name] = tool

    def get(self, name: str) -> Optional[Tool]:
        return self._tools.get(name)

    def execute(self, tool_name: str, **kwargs) -> ToolResult:
        tool = self._tools.get(tool_name)
        if not tool:
            return ToolResult(success=False, error=f"未知工具: {tool_name}")
        # 参数校验
        for p in tool.params:
            if p.required and p.name not in kwargs:
                return ToolResult(success=False, error=f"缺少必填参数: {p.name}")
            if p.name not in kwargs and p.default is not None:
                kwargs[p.name] = p.default
        try:
            return tool.execute(**kwargs)
        except Exception as e:
            return ToolResult(success=False, error=f"{tool.name} 执行异常: {e}")

    def list_names(self) -> List[str]:
        return list(self._tools.keys())

    def generate_llm_description(self) -> str:
        """生成所有工具的 LLM 描述（用于注入 prompt）"""
        parts = ["可用工具（通过 tool 动作调用）："]
        for tool in self._tools.values():
            parts.append(tool.to_llm_desc())
        return "\n".join(parts)


# ==========================================================
# 内置工具实现
# ==========================================================

class ClickTool(Tool):
    name = "click"
    description = "点击屏幕坐标"
    params = [
        ToolParam("x", "int", "X 坐标"),
        ToolParam("y", "int", "Y 坐标"),
    ]

    def execute(self, x: int, y: int, **kw) -> ToolResult:
        ok = auto_engine.touch(int(x), int(y))
        return ToolResult(success=ok, data="点击成功" if ok else None, error="" if ok else "点击失败")


class LongPressTool(Tool):
    name = "long_press"
    description = "长按屏幕坐标"
    params = [
        ToolParam("x", "int", "X 坐标"),
        ToolParam("y", "int", "Y 坐标"),
        ToolParam("duration", "int", "长按时长ms（默认1500，保存图片建议2000）", required=False, default=1500),
    ]

    def execute(self, x: int, y: int, duration: int = 1500, **kw) -> ToolResult:
        ok = auto_engine.long_click(int(x), int(y), int(duration))
        return ToolResult(success=ok, data=f"长按({x},{y}) {duration}ms 成功" if ok else None,
                          error="" if ok else "长按失败")


class SwipeTool(Tool):
    name = "swipe"
    description = "从(x1,y1)滑动到(x2,y2)"
    params = [
        ToolParam("x1", "int", "起点X"), ToolParam("y1", "int", "起点Y"),
        ToolParam("x2", "int", "终点X"), ToolParam("y2", "int", "终点Y"),
        ToolParam("duration", "int", "滑动时长ms", required=False, default=300),
    ]

    def execute(self, x1, y1, x2, y2, duration=300, **kw) -> ToolResult:
        ok = auto_engine.swipe(int(x1), int(y1), int(x2), int(y2), int(duration))
        return ToolResult(success=ok)


class TypeTool(Tool):
    name = "type"
    description = "在当前焦点输入文字"
    params = [ToolParam("text", "string", "要输入的文字")]

    def execute(self, text: str, **kw) -> ToolResult:
        ok = auto_engine.input_text(text)
        return ToolResult(success=ok)


class KeyTool(Tool):
    name = "key"
    description = "按键（back/home/enter/recent）"
    params = [ToolParam("name", "string", "按键名称")]

    _KEY_MAP = {"back": "4", "home": "3", "enter": "66", "recent": "187", "power": "26", "volume_up": "24", "volume_down": "25"}

    def execute(self, name: str, **kw) -> ToolResult:
        code = self._KEY_MAP.get(name.lower(), name)
        ok = auto_engine.key_code(code)
        return ToolResult(success=ok)


class OpenAppTool(Tool):
    name = "open_app"
    description = "通过应用名打开应用"
    params = [ToolParam("name", "string", "应用名称，如'微信'")]

    def execute(self, name: str, **kw) -> ToolResult:
        ok = auto_engine.open_app(name)
        return ToolResult(success=ok, data=f"已打开 {name}" if ok else None, error="" if ok else f"打开 {name} 失败")


class ShellTool(Tool):
    name = "shell"
    description = "执行 shell 命令（普通权限）"
    params = [ToolParam("cmd", "string", "shell 命令")]

    def execute(self, cmd: str, **kw) -> ToolResult:
        try:
            ret = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=15)
            output = (ret.stdout + ret.stderr).strip()
            return ToolResult(success=ret.returncode == 0, data=output,
                              error=output if ret.returncode != 0 else "")
        except subprocess.TimeoutExpired:
            return ToolResult(success=False, error="命令超时(15s)")
        except Exception as e:
            return ToolResult(success=False, error=str(e))


class RootShellTool(Tool):
    name = "root_shell"
    description = "以 Root 权限执行 shell 命令（su -c），可操作系统文件、修改设置等"
    params = [ToolParam("cmd", "string", "要以 root 执行的命令")]

    def execute(self, cmd: str, **kw) -> ToolResult:
        try:
            ret = subprocess.run(
                ["su", "-c", cmd], capture_output=True, text=True, timeout=15
            )
            output = (ret.stdout + ret.stderr).strip()
            return ToolResult(success=ret.returncode == 0, data=output,
                              error=output if ret.returncode != 0 else "")
        except FileNotFoundError:
            return ToolResult(success=False, error="设备未 Root 或 su 不可用")
        except subprocess.TimeoutExpired:
            return ToolResult(success=False, error="Root 命令超时(15s)")
        except Exception as e:
            return ToolResult(success=False, error=str(e))


class FindElementTool(Tool):
    name = "find_element"
    description = "在 UI 控件树中按 text/resource-id/class 查找元素，返回坐标"
    params = [
        ToolParam("text", "string", "按文本查找（模糊匹配）", required=False, default=""),
        ToolParam("resource_id", "string", "按 resource-id 查找", required=False, default=""),
        ToolParam("class_name", "string", "按 class 名查找", required=False, default=""),
        ToolParam("clickable", "bool", "是否只查找可点击元素", required=False, default=False),
    ]

    def execute(self, text="", resource_id="", class_name="", clickable=False, **kw) -> ToolResult:
        matches = find_elements_in_ui(text=text, resource_id=resource_id,
                                       class_name=class_name, clickable=clickable, limit=10)
        if not matches:
            return ToolResult(success=False, error="未找到匹配元素")
        return ToolResult(success=True, data=matches)


class OcrFindTool(Tool):
    name = "ocr_find"
    description = "OCR 识别屏幕文字，按关键词查找坐标"
    params = [
        ToolParam("keyword", "string", "要查找的文字关键词"),
    ]

    def execute(self, keyword: str, **kw) -> ToolResult:
        raw = auto_engine.ocr()
        if not raw:
            return ToolResult(success=False, error="OCR 识别失败")

        # 解析 OCR 结果 — 支持两种格式:
        # 1) 纯文本行: "confidence\ttext\tx0,y0 x1,y1 x2,y2 x3,y3"
        # 2) JSON 数组: [{"text":"...", "box":...}, ...]
        items = []
        try:
            parsed = json.loads(raw)
            if isinstance(parsed, list):
                items = parsed
        except (json.JSONDecodeError, TypeError):
            pass

        if not items:
            # 按纯文本行格式解析
            for line in raw.strip().split("\n"):
                line = line.strip()
                if not line:
                    continue
                parts = line.split("\t")
                if len(parts) >= 3:
                    txt = parts[1]
                    coords_str = parts[2]  # "x0,y0 x1,y1 x2,y2 x3,y3"
                    try:
                        points = []
                        for pt in coords_str.strip().split(" "):
                            xy = pt.split(",")
                            if len(xy) == 2:
                                points.append((int(xy[0]), int(xy[1])))
                        if len(points) >= 4:
                            cx = sum(p[0] for p in points) // len(points)
                            cy = sum(p[1] for p in points) // len(points)
                            items.append({"text": txt, "x": cx, "y": cy, "_parsed": True})
                    except (ValueError, IndexError):
                        continue
                elif len(parts) == 2:
                    items.append({"text": parts[1], "x": 0, "y": 0, "_parsed": True})

        if not items:
            return ToolResult(success=False, error="OCR 结果解析失败")

        matches = []
        kw_lower = keyword.lower()
        for item in items:
            if item.get("_parsed"):
                txt = item.get("text", "")
                if kw_lower in txt.lower():
                    matches.append({"text": txt, "x": item["x"], "y": item["y"]})
                continue

            txt = item.get("text", "") or item.get("label", "")
            if kw_lower in txt.lower():
                box = item.get("box") or item.get("bounds") or item.get("rect")
                if box and isinstance(box, list) and len(box) >= 4:
                    if isinstance(box[0], list):
                        xs = [p[0] for p in box]
                        ys = [p[1] for p in box]
                    else:
                        xs = [box[0], box[2]]
                        ys = [box[1], box[3]]
                    cx = sum(xs) // len(xs)
                    cy = sum(ys) // len(ys)
                    matches.append({"text": txt, "x": cx, "y": cy})
                elif isinstance(box, dict):
                    cx = box.get("x", 0) + box.get("width", 0) // 2
                    cy = box.get("y", 0) + box.get("height", 0) // 2
                    matches.append({"text": txt, "x": cx, "y": cy})

        if not matches:
            return ToolResult(success=False, error=f"OCR 未找到包含 '{keyword}' 的文字")
        return ToolResult(success=True, data=matches)


class ScrollToFindTool(Tool):
    name = "scroll_to_find"
    description = "滚动屏幕查找目标文字（最多滚动5次），找到后返回坐标"
    params = [
        ToolParam("keyword", "string", "要查找的文字"),
        ToolParam("direction", "string", "滚动方向 up/down", required=False, default="down"),
        ToolParam("max_scrolls", "int", "最大滚动次数", required=False, default=5),
    ]

    def execute(self, keyword: str, direction="down", max_scrolls=5, **kw) -> ToolResult:
        # 获取屏幕尺寸
        info = auto_engine.screen_info()
        w = info.get("width", 1080) if info else 1080
        h = info.get("height", 1920) if info else 1920
        cx = w // 2

        for i in range(int(max_scrolls)):
            # 先尝试在当前屏幕查找
            ocr_tool = OcrFindTool()
            result = ocr_tool.execute(keyword=keyword)
            if result.success:
                result.data = result.data if isinstance(result.data, list) else [result.data]
                # 附加滚动进度信息
                for item in result.data:
                    if isinstance(item, dict):
                        item["scrolled_times"] = i
                return result

            # 滚动
            if direction == "down":
                auto_engine.swipe(cx, (h * 3) // 4, cx, h // 4, 400)
            else:
                auto_engine.swipe(cx, h // 4, cx, (h * 3) // 4, 400)
            time.sleep(1)

        return ToolResult(
            success=False,
            error=f"滚动 {max_scrolls} 次({direction})后仍未找到 '{keyword}'"
        )


class ScreenshotTool(Tool):
    name = "screenshot"
    description = "截取当前屏幕，返回截图信息"
    params = []

    def execute(self, **kw) -> ToolResult:
        data = screen_capture.get_bitmap_data(quality=70)
        if data:
            return ToolResult(success=True, data=f"截图成功，大小 {len(data)} bytes")
        return ToolResult(success=False, error="截图失败")


class ClipboardTool(Tool):
    name = "clipboard"
    description = "读写剪贴板"
    params = [
        ToolParam("action", "string", "get 或 set"),
        ToolParam("text", "string", "要设置的文本（action=set 时必填）", required=False, default=""),
    ]

    def execute(self, action: str, text="", **kw) -> ToolResult:
        if action == "set":
            ok = auto_engine.set_clipboard(text)
            return ToolResult(success=ok, data="剪贴板已设置" if ok else None,
                              error="" if ok else "设置剪贴板失败")
        elif action == "get":
            result = auto_engine.get_clipboard()
            return ToolResult(success=True, data=result)
        return ToolResult(success=False, error=f"未知 action: {action}")


class HttpTool(Tool):
    name = "http_request"
    description = "发送 HTTP 请求（GET/POST），可调用外部 API"
    params = [
        ToolParam("url", "string", "请求 URL"),
        ToolParam("method", "string", "GET 或 POST", required=False, default="GET"),
        ToolParam("body", "string", "POST 请求体（JSON 字符串）", required=False, default=""),
    ]

    def execute(self, url: str, method="GET", body="", **kw) -> ToolResult:
        try:
            req = urllib.request.Request(url, method=method.upper())
            if body and method.upper() == "POST":
                req.add_header("Content-Type", "application/json")
                req.data = body.encode("utf-8")
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = resp.read().decode("utf-8")
            return ToolResult(success=True, data=data[:2000])
        except Exception as e:
            return ToolResult(success=False, error=str(e))


class RunPythonTool(Tool):
    name = "run_python"
    description = "执行 Python 代码片段，可调用自动化引擎全部 API。代码中设置 result 变量作为返回值"
    params = [
        ToolParam("code", "string", "Python 代码（可多行）"),
    ]

    def execute(self, code: str, **kw) -> ToolResult:
        # 延迟导入避免循环依赖
        from agent_executor import execute_python
        return execute_python(code)


# ==========================================================
# 全局注册表 & 初始化
# ==========================================================

_registry: Optional[ToolRegistry] = None


def get_tool_registry() -> ToolRegistry:
    """获取全局工具注册表（单例）"""
    global _registry
    if _registry is None:
        _registry = ToolRegistry()
        # 注册所有内置工具
        for tool_cls in [
            ClickTool, LongPressTool, SwipeTool, TypeTool, KeyTool,
            OpenAppTool, ShellTool, RootShellTool,
            FindElementTool, OcrFindTool, ScrollToFindTool,
            ScreenshotTool, ClipboardTool, HttpTool, RunPythonTool,
        ]:
            _registry.register(tool_cls())
    return _registry
