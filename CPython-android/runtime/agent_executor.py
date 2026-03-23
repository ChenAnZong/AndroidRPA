"""
Python 代码沙箱执行器 — Agent 生成的代码在此执行
预注入 auto_engine / screen_capture / subprocess 等全部自动化 API
提供便捷函数：shell() / root_shell() / click() / find() / ocr() 等

安全措施：
- 超时保护（默认 30s）
- stdout 捕获
- 异常捕获并返回给 Agent
- 禁止 import os.system / eval 嵌套等危险操作（可选）
"""

import io
import json
import os
import re
import subprocess
import sys
import threading
import time
import traceback
from typing import Any, Dict, Optional

from auto_engine_proxy import auto_engine
from screen_capture import screen_capture
from agent_tools import find_elements_in_ui, ToolResult


# ==========================================================
# 便捷函数 — 注入到 Agent 代码的执行环境中
# ==========================================================

def _shell(cmd: str, timeout: int = 15) -> str:
    """执行 shell 命令，返回输出"""
    ret = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    return (ret.stdout + ret.stderr).strip()


def _root_shell(cmd: str, timeout: int = 15) -> str:
    """以 Root 权限执行 shell 命令"""
    ret = subprocess.run(["su", "-c", cmd], capture_output=True, text=True, timeout=timeout)
    return (ret.stdout + ret.stderr).strip()


def _click(x: int, y: int) -> bool:
    """点击坐标"""
    return auto_engine.touch(x, y)


def _long_press(x: int, y: int) -> bool:
    """长按坐标"""
    return auto_engine.long_click(x, y)


def _swipe(x1: int, y1: int, x2: int, y2: int, duration: int = 300) -> bool:
    """滑动"""
    return auto_engine.swipe(x1, y1, x2, y2, duration)


def _input_text(text: str) -> bool:
    """输入文字"""
    return auto_engine.input_text(text)


def _key(name: str) -> bool:
    """按键"""
    key_map = {"back": "4", "home": "3", "enter": "66", "recent": "187"}
    return auto_engine.key_code(key_map.get(name.lower(), name))


def _open_app(name: str) -> bool:
    """打开应用"""
    return auto_engine.open_app(name)


def _screenshot() -> bytes:
    """截图，返回 bytes"""
    return screen_capture.get_bitmap_data(quality=70)


def _ui_xml() -> str:
    """获取 UI 控件树 XML"""
    return auto_engine.ui_dump_text()


def _ocr() -> list:
    """OCR 识别，返回结果列表"""
    raw = auto_engine.ocr()
    if raw:
        try:
            return json.loads(raw)
        except Exception:
            pass
    return []


def _foreground() -> str:
    """获取前台应用"""
    return auto_engine.foreground() or ""


def _find(text: str = "", resource_id: str = "", clickable: bool = False) -> list:
    """在 UI 控件树中查找元素 — 委托给共享函数"""
    return find_elements_in_ui(text=text, resource_id=resource_id, clickable=clickable, limit=20)


def _find_and_click(text: str = "", resource_id: str = "") -> bool:
    """查找元素并点击第一个匹配项"""
    items = find_elements_in_ui(text=text, resource_id=resource_id, clickable=True, limit=1)
    if not items:
        items = find_elements_in_ui(text=text, resource_id=resource_id, limit=1)
    if items:
        return _click(items[0]["x"], items[0]["y"])
    return False


def _sleep(seconds: float):
    """等待"""
    time.sleep(seconds)


def _screen_info() -> dict:
    """获取屏幕信息"""
    return auto_engine.screen_info() or {"width": 1080, "height": 1920}


def _set_clipboard(text: str) -> bool:
    """设置剪贴板"""
    return auto_engine.set_clipboard(text)


def _get_clipboard() -> str:
    """获取剪贴板"""
    return auto_engine.get_clipboard()


# ==========================================================
# 沙箱执行
# ==========================================================

# 预注入的全局变量（Agent 代码可直接使用）
_SANDBOX_GLOBALS = {
    # 自动化引擎
    "auto": auto_engine,
    "engine": auto_engine,
    # 便捷函数
    "shell": _shell,
    "root_shell": _root_shell,
    "click": _click,
    "long_press": _long_press,
    "swipe": _swipe,
    "input_text": _input_text,
    "key": _key,
    "open_app": _open_app,
    "screenshot": _screenshot,
    "ui_xml": _ui_xml,
    "ocr": _ocr,
    "foreground": _foreground,
    "find": _find,
    "find_and_click": _find_and_click,
    "sleep": _sleep,
    "screen_info": _screen_info,
    "set_clipboard": _set_clipboard,
    "get_clipboard": _get_clipboard,
    # 常用标准库
    "json": json,
    "re": re,
    "os": os,
    "time": time,
    "subprocess": subprocess,
}


def execute_python(code: str, timeout: int = 30) -> "ToolResult":
    """
    在沙箱中执行 Agent 生成的 Python 代码

    Agent 代码中可以：
    - 调用所有便捷函数（click/shell/find/ocr 等）
    - 直接访问 auto（auto_engine 实例）
    - 设置 result 变量作为返回值
    - print() 输出会被捕获

    Returns:
        ToolResult（从 agent_tools 导入）
    """
    from agent_tools import ToolResult

    # 准备执行环境
    local_vars: Dict[str, Any] = {"result": None}
    sandbox = dict(_SANDBOX_GLOBALS)
    sandbox.update(local_vars)

    # 捕获 stdout
    old_stdout = sys.stdout
    captured = io.StringIO()

    exec_error = [None]  # 用列表以便在线程中修改
    exec_done = threading.Event()
    # 用锁保护 stdout 切换，防止超时后子线程竞争
    stdout_lock = threading.Lock()

    def _run():
        try:
            with stdout_lock:
                sys.stdout = captured
            exec(code, sandbox)
        except Exception as e:
            exec_error[0] = traceback.format_exc()
        finally:
            with stdout_lock:
                sys.stdout = old_stdout
            exec_done.set()

    # 在子线程中执行，支持超时
    t = threading.Thread(target=_run, daemon=True)
    t.start()
    finished = exec_done.wait(timeout=timeout)

    if not finished:
        # 超时：确保 stdout 恢复
        with stdout_lock:
            sys.stdout = old_stdout
        return ToolResult(success=False, error=f"Python 代码执行超时({timeout}s)")

    if exec_error[0]:
        return ToolResult(success=False, error=exec_error[0][:1500])

    # 收集结果
    stdout_text = captured.getvalue().strip()
    result_val = sandbox.get("result")

    # 优先返回 result 变量，其次 stdout
    if result_val is not None:
        if isinstance(result_val, str):
            data = result_val
        else:
            try:
                data = json.dumps(result_val, ensure_ascii=False, default=str)
            except Exception:
                data = str(result_val)
    elif stdout_text:
        data = stdout_text
    else:
        data = "[执行完成，无返回值]"

    return ToolResult(success=True, data=data[:2000])
