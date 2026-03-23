"""
Action Parser — 从 VLM 响应中解析 pseudo-code 动作
兼容两种格式:
1. AutoGLM-Phone: 思考文本 + do(action=...)/finish(message=...)
2. 通用 VLM: <think>...</think><answer>do(...)</answer>

使用 AST 安全解析，不使用 eval
"""

import ast
import re
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple


@dataclass
class ParsedAction:
    """解析后的动作"""
    thinking: str = ""          # 模型思考过程
    action_type: str = ""       # "do" | "finish"
    action_name: str = ""       # "Tap" | "Type" | "Swipe" | ... | "" (finish 时为空)
    params: Dict[str, Any] = field(default_factory=dict)  # 动作参数
    raw_action: str = ""        # 原始动作代码
    raw_response: str = ""      # 原始完整响应
    parse_error: bool = False    # 解析是否失败（区分真正的 finish 和解析错误）

    @property
    def is_finish(self) -> bool:
        return self.action_type == "finish"

    @property
    def is_do(self) -> bool:
        return self.action_type == "do"

    @property
    def message(self) -> str:
        return self.params.get("message", "")

    def describe(self) -> str:
        """生成人类可读的动作描述"""
        if self.is_finish:
            return f"完成: {self.message}"
        name = self.action_name
        p = self.params
        if name == "Tap":
            return f"点击 {p.get('element', [])}"
        elif name == "Type":
            return f"输入 \"{p.get('text', '')}\""
        elif name == "Swipe":
            return f"滑动 {p.get('start', [])} → {p.get('end', [])}"
        elif name == "Long Press":
            return f"长按 {p.get('element', [])}"
        elif name == "Double Tap":
            return f"双击 {p.get('element', [])}"
        elif name == "Launch":
            return f"启动 {p.get('app', '')}"
        elif name == "Back":
            return "返回"
        elif name == "Home":
            return "回到桌面"
        elif name == "Wait":
            return f"等待 {p.get('duration', '1')}s"
        elif name == "Take_over":
            return f"请求人工介入: {p.get('message', '')}"
        elif name == "Shell":
            return f"Shell: {p.get('cmd', '')[:50]}"
        elif name == "Clear":
            return "清空输入框"
        elif name == "Notification":
            return "下拉通知栏"
        elif name == "Recent":
            return "最近任务"
        elif name == "Open_url":
            return f"打开网址 {p.get('url', '')}"
        return f"{name} {p}"


def parse_all_actions(response_text: str) -> List[ParsedAction]:
    """
    从 VLM 响应中提取所有动作（支持多动作响应）
    例如: do(Clear)\ndo(Type, text="...") → [ParsedAction(Clear), ParsedAction(Type)]
    """
    text = response_text.strip() if response_text else ""
    if not text:
        return [parse_response(response_text)]

    # 提取 thinking 和 answer 块
    think_match = re.search(r'<think>\s*(.*?)\s*</think>', text, re.DOTALL)
    thinking = think_match.group(1).strip() if think_match else ""

    answer_match = re.search(r'<answer>\s*(.*?)\s*</answer>', text, re.DOTALL)
    answer_block = answer_match.group(1).strip() if answer_match else text

    # 检测是否有 finish
    if "finish(" in answer_block:
        return [parse_response(response_text)]

    # 提取所有 do(...) 调用
    action_codes = _extract_all_do_calls(answer_block)
    if len(action_codes) <= 1:
        return [parse_response(response_text)]

    # 解析每个动作
    results = []
    for i, code in enumerate(action_codes):
        p = ParsedAction(raw_response=response_text)
        p.thinking = thinking if i == 0 else ""
        p.raw_action = code
        try:
            action_type, params = _parse_pseudocode(code)
            p.action_type = action_type
            if action_type == "do":
                p.action_name = params.pop("action", "")
            p.params = params
        except (ValueError, Exception) as e:
            p.action_type = "finish"
            p.params = {"message": f"动作解析失败: {e}"}
            p.parse_error = True
        results.append(p)
    return results


def _extract_all_do_calls(text: str) -> List[str]:
    """从文本中提取所有 do(...) 调用代码块"""
    calls = []
    i = 0
    while i < len(text):
        # 查找下一个 do( 起始位置
        idx = text.find("do(", i)
        if idx == -1:
            break
        # 找到匹配的右括号
        depth = 0
        j = idx
        in_str = False
        str_char = None
        while j < len(text):
            ch = text[j]
            if in_str:
                if ch == str_char and (j == 0 or text[j-1] != '\\'):
                    in_str = False
            elif ch in ('"', "'"):
                in_str = True
                str_char = ch
            elif ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
                if depth == 0:
                    calls.append(text[idx:j+1])
                    i = j + 1
                    break
            j += 1
        else:
            break
    return calls


def parse_response(response_text: str) -> ParsedAction:
    """
    从 VLM 响应文本中解析思考和动作

    支持的格式:
    1. AutoGLM-Phone 格式: 思考文本...do(action="Tap", element=[x,y])
    2. XML 标签格式: <think>思考</think><answer>do(...)</answer>
    3. 纯 action 格式: do(action="Tap", element=[x,y])
    """
    result = ParsedAction(raw_response=response_text)

    if not response_text or not response_text.strip():
        result.action_type = "finish"
        result.params = {"message": "模型返回空响应"}
        result.parse_error = True
        return result

    text = response_text.strip()

    # 尝试分离 thinking 和 action
    thinking, action_code = _split_thinking_action(text)
    result.thinking = thinking
    result.raw_action = action_code

    # 解析 action pseudo-code
    if not action_code:
        # 没有找到 action - 长文本视为真正的 finish，短文本才标记为解析错误
        result.action_type = "finish"
        result.params = {"message": thinking or text}
        result.parse_error = len(thinking or text) <= 10
        return result

    try:
        action_type, params = _parse_pseudocode(action_code)
        result.action_type = action_type
        if action_type == "do":
            result.action_name = params.pop("action", "")
        result.params = params
    except ValueError as e:
        # 解析失败，标记为解析错误
        result.action_type = "finish"
        result.params = {"message": f"动作解析失败: {e}"}
        result.parse_error = True

    return result


def _split_thinking_action(text: str) -> Tuple[str, str]:
    """
    分离思考部分和动作部分

    优先级:
    1. <think>...</think> + <answer>...</answer> 标签
    2. 文本中直接出现 do(action= 或 finish(message= 标记
    """
    # 方式 1: XML 标签格式
    think_match = re.search(r'<think>\s*(.*?)\s*</think>', text, re.DOTALL)
    answer_match = re.search(r'<answer>\s*(.*?)\s*</answer>', text, re.DOTALL)
    if answer_match:
        thinking = think_match.group(1).strip() if think_match else ""
        action = answer_match.group(1).strip()
        if action:  # 忽略空的 <answer> 标签
            return thinking, action

    # 方式 2: AutoGLM-Phone 格式 — 直接查找 do(action= 或 finish(message=
    for marker in ["finish(message=", "do(action="]:
        idx = text.find(marker)
        if idx >= 0:
            thinking = text[:idx].strip()
            action = text[idx:].strip()
            # 清理 action 末尾可能的多余文字
            action = _trim_action_tail(action)
            return thinking, action

    # 方式 3: 查找 finish( 或 do( (更宽松的匹配)
    for marker in ["finish(", "do("]:
        idx = text.find(marker)
        if idx >= 0:
            thinking = text[:idx].strip()
            action = text[idx:].strip()
            action = _trim_action_tail(action)
            return thinking, action

    # 没有找到 action 标记
    return text, ""


def _trim_action_tail(action: str) -> str:
    """修剪 action 代码末尾的多余文字（模型可能在 action 后面追加解释）"""
    # 找到最后一个匹配的右括号
    depth = 0
    for i, ch in enumerate(action):
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
            if depth == 0:
                return action[:i + 1]
    return action


def _parse_pseudocode(code: str) -> Tuple[str, Dict[str, Any]]:
    """
    安全解析 pseudo-code 到 (type, params)

    支持:
    - do(action="Tap", element=[500, 300])
    - do(action="Type", text="你好")
    - do(action="Swipe", start=[100, 500], end=[900, 500])
    - do(action="Launch", app="微信")
    - do(action="Back")
    - do(action="Wait", duration="2")
    - do(action="Shell", cmd="am start ...")
    - do(action="Clear")
    - do(action="Notification")
    - do(action="Recent")
    - finish(message="任务完成")
    """
    code = code.strip()

    # 特殊处理 Type 动作（文本中可能含特殊字符）
    if code.startswith('do(action="Type"') or code.startswith("do(action='Type'"):
        return _parse_type_action(code)

    # 处理转义字符，避免 AST 解析失败
    safe_code = code.replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t')

    try:
        tree = ast.parse(safe_code, mode="eval")
        if not isinstance(tree.body, ast.Call):
            raise ValueError("Expected a function call")

        call = tree.body
        func_name = ""
        if isinstance(call.func, ast.Name):
            func_name = call.func.id  # "do" or "finish"

        if func_name not in ("do", "finish"):
            raise ValueError(f"Unknown function: {func_name}")

        # 提取关键字参数
        params = {}
        for kw in call.keywords:
            key = kw.arg
            try:
                value = ast.literal_eval(kw.value)
            except (ValueError, SyntaxError):
                # 无法安全解析的值，转为字符串
                value = ast.dump(kw.value)
            params[key] = value

        return func_name, params

    except (SyntaxError, ValueError) as e:
        # AST 解析失败，尝试正则回退
        return _parse_with_regex(code)


def _parse_type_action(code: str) -> Tuple[str, Dict[str, Any]]:
    """特殊处理 Type 动作（文本中可能有引号、换行等）"""
    # 提取 text= 参数
    match = re.search(r'text\s*=\s*(["\'])(.*?)\1\s*\)', code, re.DOTALL)
    if match:
        text = match.group(2)
        return "do", {"action": "Type", "text": text}

    # 回退: 尝试更宽松的匹配
    match = re.search(r'text\s*=\s*["\'](.+)', code, re.DOTALL)
    if match:
        text = match.group(1).rstrip("')\"")
        return "do", {"action": "Type", "text": text}

    raise ValueError(f"Cannot parse Type action: {code}")


def _parse_with_regex(code: str) -> Tuple[str, Dict[str, Any]]:
    """正则回退解析（AST 失败时使用）"""

    # finish(message="...")
    match = re.match(r'finish\s*\(\s*message\s*=\s*["\'](.+?)["\']\s*\)', code, re.DOTALL)
    if match:
        return "finish", {"message": match.group(1)}

    # finish() 无参数
    if re.match(r'finish\s*\(\s*\)', code):
        return "finish", {"message": "Task finished"}

    # do(action="Name", ...) 提取 action 名
    action_match = re.match(r'do\s*\(\s*action\s*=\s*["\'](\w[\w\s]*?)["\']\s*(?:,|\))', code)
    if not action_match:
        raise ValueError(f"Cannot parse action: {code}")

    action_name = action_match.group(1)
    params = {"action": action_name}

    # 提取 element=[x, y]
    elem_match = re.search(r'element\s*=\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]', code)
    if elem_match:
        params["element"] = [int(elem_match.group(1)), int(elem_match.group(2))]

    # 提取 start=[x, y], end=[x, y]
    start_match = re.search(r'start\s*=\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]', code)
    end_match = re.search(r'end\s*=\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]', code)
    if start_match:
        params["start"] = [int(start_match.group(1)), int(start_match.group(2))]
    if end_match:
        params["end"] = [int(end_match.group(1)), int(end_match.group(2))]

    # 提取 text="..."
    text_match = re.search(r'text\s*=\s*["\'](.+?)["\']', code, re.DOTALL)
    if text_match:
        params["text"] = text_match.group(1)

    # 提取 app="..."
    app_match = re.search(r'app\s*=\s*["\'](.+?)["\']', code)
    if app_match:
        params["app"] = app_match.group(1)

    # 提取 cmd="..."
    cmd_match = re.search(r'cmd\s*=\s*["\'](.+?)["\']', code, re.DOTALL)
    if cmd_match:
        params["cmd"] = cmd_match.group(1)

    # 提取 url="..."
    url_match = re.search(r'url\s*=\s*["\'](.+?)["\']', code)
    if url_match:
        params["url"] = url_match.group(1)

    # 提取 duration="..."
    dur_match = re.search(r'duration\s*=\s*["\'](.+?)["\']', code)
    if dur_match:
        params["duration"] = dur_match.group(1)

    # 提取 message="..."
    msg_match = re.search(r'message\s*=\s*["\'](.+?)["\']', code, re.DOTALL)
    if msg_match:
        params["message"] = msg_match.group(1)

    return "do", params
