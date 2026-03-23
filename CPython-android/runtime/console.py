"""
悬浮日志控制台 Python API
提供类似 Auto.js / Chrome DevTools 的 console 对象，用于在屏幕上显示悬浮日志窗口。
不同日志级别有不同颜色: VERBOSE(灰) DEBUG(蓝) INFO(绿) WARN(黄) ERROR(红)

基本用法:
    from console import console

    console.show()              # 显示悬浮控制台
    console.log("Hello")        # INFO 级别 (绿色)
    console.warn("注意")        # WARN 级别 (黄色)
    console.error("出错了")     # ERROR 级别 (红色)
    console.debug("调试信息")   # DEBUG 级别 (蓝色)
    console.verbose("详细")     # VERBOSE 级别 (灰色)
    console.clear()             # 清空日志
    console.hide()              # 最小化控制台
    console.close()             # 关闭控制台

开发者工具:
    console.time("请求")        # 开始计时
    console.time_end("请求")    # 结束计时 → "⏱ 请求: 123.45ms"
    console.count("循环")       # 调用计数 → "循环: 1", "循环: 2" ...
    console.count_reset("循环") # 重置计数器
    console.trace("定位")       # 输出 Python 调用栈
    console.assert_(x > 0, "x必须>0")  # 断言失败时输出 ERROR
    console.success("完成!")    # ✔ 成功消息 (绿色)
    console.fail("失败!")       # ✘ 失败消息 (红色)

分组与格式化:
    console.group("网络请求")   # ▼ 开始分组 (后续输出缩进)
    console.log("GET /api")
    console.group_end()         # 结束分组
    console.divider()           # ──────── 分隔线
    console.divider(label="结果")  # ──── 结果 ────

数据展示:
    console.table([{"name": "Alice", "age": 30}, {"name": "Bob", "age": 25}])
    console.json({"key": "value", "list": [1, 2, 3]})

窗口控制:
    console.set_alpha(0.8)      # 设置透明度 (0.2~1.0)
    console.set_size(300, 400)  # 设置窗口大小 (dp)
    console.set_position(50, 100) # 设置窗口位置 (dp)
    console.set_title("我的脚本") # 设置标题

简写方式 (自动 show + log):
    console("Hello World")      # 等同于 console.show() + console.log("Hello World")
"""

import json
import time
import traceback

# 控制台命令前缀 - 与 Android 端 FloatingLogService.CONSOLE_CMD_PREFIX 一致
_CONSOLE_CMD_PREFIX = "##YYDS_CONSOLE##"

# 引擎通信方式: 优先 HTTP API, 回退 stdout 标签
_ENGINE_PORT = 61140
_use_http = None       # None=未检测, True=HTTP可用, False=上次失败
_http_fail_time = 0.0  # 上次HTTP失败的时间戳
_HTTP_RETRY_TTL = 30.0 # 失败后30秒重试


def _try_http_post(path, data=None):
    """尝试通过 HTTP API 发送控制台命令到工作进程"""
    global _use_http, _http_fail_time
    try:
        import urllib.request
        url = f"http://127.0.0.1:{_ENGINE_PORT}{path}"
        body = json.dumps(data).encode("utf-8") if data else b"{}"
        req = urllib.request.Request(url, data=body, method="POST")
        req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=2) as resp:
            _use_http = True
            return json.loads(resp.read().decode("utf-8"))
    except Exception:
        _use_http = False
        _http_fail_time = time.monotonic()
        return None


def _send_cmd(cmd):
    """发送控制台命令 (stdout标签方式，经过 PyOut 管道传输)"""
    print(f"{_CONSOLE_CMD_PREFIX}{cmd}", flush=True)


def _send_cmd_http(path, data=None):
    """通过 HTTP 发送控制台命令，失败后有TTL重试机制"""
    global _use_http
    if _use_http is False:
        # TTL重试: 距上次失败超过30秒则重新尝试
        if time.monotonic() - _http_fail_time < _HTTP_RETRY_TTL:
            return False
        _use_http = None  # 重置为未检测
    result = _try_http_post(path, data)
    return result is not None and result.get("success", False)


class Console:
    """悬浮日志控制台对象"""

    def __init__(self):
        self._shown = False
        self._title = "日志控制台"
        self._counters = {}
        self._group_depth = 0

    def __call__(self, *args, **kwargs):
        """快捷方式: console("msg") 等同于 console.show() + console.log("msg")"""
        if not self._shown:
            self.show()
        if args:
            self.log(*args, **kwargs)

    def show(self):
        """显示悬浮日志控制台"""
        self._shown = True
        if not _send_cmd_http("/console/show"):
            _send_cmd("show")

    def hide(self):
        """最小化控制台 (不关闭)"""
        if not _send_cmd_http("/console/hide"):
            _send_cmd("hide")

    def close(self):
        """关闭控制台"""
        self._shown = False
        if not _send_cmd_http("/console/close"):
            _send_cmd("close")

    def clear(self):
        """清空所有日志"""
        if not _send_cmd_http("/console/clear"):
            _send_cmd("clear")

    # ========== 日志输出 ==========

    def log(self, *args, sep=" "):
        """输出 INFO 级别日志"""
        text = self._indent() + sep.join(str(a) for a in args)
        if not _send_cmd_http("/console/log", {"text": text, "level": "I"}):
            _send_cmd(f"log:I:{text}")

    def info(self, *args, sep=" "):
        """输出 INFO 级别日志 (log 的别名)"""
        self.log(*args, sep=sep)

    def warn(self, *args, sep=" "):
        """输出 WARN 级别日志"""
        text = self._indent() + sep.join(str(a) for a in args)
        if not _send_cmd_http("/console/log", {"text": text, "level": "W"}):
            _send_cmd(f"log:W:{text}")

    def warning(self, *args, sep=" "):
        """输出 WARN 级别日志 (warn 的别名)"""
        self.warn(*args, sep=sep)

    def error(self, *args, sep=" "):
        """输出 ERROR 级别日志"""
        text = self._indent() + sep.join(str(a) for a in args)
        if not _send_cmd_http("/console/log", {"text": text, "level": "E"}):
            _send_cmd(f"log:E:{text}")

    def debug(self, *args, sep=" "):
        """输出 DEBUG 级别日志"""
        text = self._indent() + sep.join(str(a) for a in args)
        if not _send_cmd_http("/console/log", {"text": text, "level": "D"}):
            _send_cmd(f"log:D:{text}")

    def verbose(self, *args, sep=" "):
        """输出 VERBOSE 级别日志"""
        text = self._indent() + sep.join(str(a) for a in args)
        if not _send_cmd_http("/console/log", {"text": text, "level": "V"}):
            _send_cmd(f"log:V:{text}")

    # ========== 格式化输出 ==========

    def _indent(self):
        """返回当前分组缩进前缀"""
        return "  " * self._group_depth if self._group_depth > 0 else ""

    def print(self, *args, sep=" ", end="\n"):
        """类似 print() 的输出, 显示在控制台"""
        text = sep.join(str(a) for a in args)
        if end and end != "\n":
            text += end
        self.log(text)

    def assert_(self, condition, *args, sep=" "):
        """断言: 条件为 False 时输出 ERROR 日志"""
        if not condition:
            text = sep.join(str(a) for a in args) if args else "Assertion failed"
            self.error(f"✘ AssertionError: {text}")

    def time(self, label="default"):
        """开始计时"""
        if not hasattr(self, "_timers"):
            self._timers = {}
        self._timers[label] = time.time()

    def time_end(self, label="default"):
        """结束计时并输出耗时"""
        if not hasattr(self, "_timers") or label not in self._timers:
            self.warn(f"Timer '{label}' not found")
            return
        elapsed = time.time() - self._timers.pop(label)
        self.log(f"⏱ {label}: {elapsed * 1000:.2f}ms")

    # ========== 计数器 ==========

    def count(self, label="default"):
        """调用计数器: 每次调用 +1 并输出当前计数"""
        self._counters[label] = self._counters.get(label, 0) + 1
        self.log(f"{label}: {self._counters[label]}")

    def count_reset(self, label="default"):
        """重置计数器"""
        self._counters[label] = 0
        self.debug(f"Counter '{label}' reset")

    # ========== 调用栈 ==========

    def trace(self, *args, sep=" "):
        """输出当前 Python 调用栈 (用于调试定位)"""
        msg = sep.join(str(a) for a in args) if args else "Trace"
        stack = traceback.format_stack()
        # 去掉最后一帧 (trace 自身)
        stack = stack[:-1]
        lines = [f"┌─ {msg}"]
        for frame in stack:
            for line in frame.strip().splitlines():
                lines.append(f"│  {line}")
        lines.append("└─")
        self.debug("\n".join(lines))

    # ========== 分组 ==========

    def group(self, *args, sep=" "):
        """开始一个日志分组 (后续输出缩进显示)"""
        label = sep.join(str(a) for a in args) if args else "Group"
        self.log(f"▼ {label}")
        self._group_depth += 1

    def group_end(self):
        """结束当前日志分组"""
        if self._group_depth > 0:
            self._group_depth -= 1

    # ========== 数据展示 ==========

    def table(self, data, columns=None):
        """表格化展示数据 (支持 list[dict], list[list], dict)

        示例:
            console.table([
                {"name": "Alice", "age": 30},
                {"name": "Bob", "age": 25},
            ])
        """
        if not data:
            self.log("(empty table)")
            return

        rows = []
        headers = []

        if isinstance(data, dict):
            headers = ["Key", "Value"]
            for k, v in data.items():
                rows.append([str(k), str(v)])
        elif isinstance(data, (list, tuple)):
            first = data[0] if data else None
            if isinstance(first, dict):
                headers = columns or list(first.keys())
                for item in data:
                    rows.append([str(item.get(h, "")) for h in headers])
            elif isinstance(first, (list, tuple)):
                if columns:
                    headers = columns
                else:
                    headers = [str(i) for i in range(len(first))]
                for item in data:
                    rows.append([str(v) for v in item])
            else:
                headers = ["Index", "Value"]
                for i, v in enumerate(data):
                    rows.append([str(i), str(v)])
        else:
            self.log(str(data))
            return

        # 计算列宽
        col_widths = [len(h) for h in headers]
        for row in rows:
            for i, cell in enumerate(row):
                if i < len(col_widths):
                    col_widths[i] = max(col_widths[i], len(cell))

        def fmt_row(cells):
            parts = []
            for i, cell in enumerate(cells):
                w = col_widths[i] if i < len(col_widths) else len(cell)
                parts.append(cell.ljust(w))
            return " │ ".join(parts)

        sep_line = "─┼─".join("─" * w for w in col_widths)
        lines = [fmt_row(headers), sep_line]
        for row in rows:
            lines.append(fmt_row(row))
        self.log("\n".join(lines))

    def json(self, obj, indent=2):
        """格式化输出 JSON 对象 (dict/list/基本类型)"""
        try:
            text = json.dumps(obj, indent=indent, ensure_ascii=False, default=str)
        except (TypeError, ValueError):
            text = str(obj)
        self.debug(text)

    # ========== 可视化工具 ==========

    def divider(self, char="─", length=40, label=""):
        """输出可视分隔线"""
        if label:
            pad = max(0, length - len(label) - 2)
            left = pad // 2
            right = pad - left
            line = f"{char * left} {label} {char * right}"
        else:
            line = char * length
        self.log(line)

    def success(self, *args, sep=" "):
        """输出成功消息 (INFO级别, 带 ✔ 标记)"""
        text = sep.join(str(a) for a in args)
        self.log(f"✔ {text}")

    def fail(self, *args, sep=" "):
        """输出失败消息 (ERROR级别, 带 ✘ 标记)"""
        text = sep.join(str(a) for a in args)
        self.error(f"✘ {text}")

    # ========== 窗口控制 ==========

    def set_alpha(self, alpha):
        """设置窗口透明度 (0.2 ~ 1.0)"""
        alpha = max(0.2, min(1.0, float(alpha)))
        if not _send_cmd_http("/console/set-alpha", {"alpha": alpha}):
            _send_cmd(f"alpha:{alpha}")

    def set_size(self, width, height):
        """设置窗口大小 (dp单位)"""
        if not _send_cmd_http("/console/set-size", {"width": int(width), "height": int(height)}):
            _send_cmd(f"size:{int(width)},{int(height)}")

    def set_position(self, x, y):
        """设置窗口位置 (dp单位)"""
        if not _send_cmd_http("/console/set-position", {"x": int(x), "y": int(y)}):
            _send_cmd(f"pos:{int(x)},{int(y)}")

    def set_title(self, title):
        """设置控制台标题"""
        self._title = str(title)
        if not _send_cmd_http("/console/set-title", {"title": self._title}):
            _send_cmd(f"title:{self._title}")

    # ========== Agent Tab 专用 ==========

    def agent_log(self, *args, level="I", sep=" "):
        """向统一悬浮窗的 Agent Tab 写入步骤日志（不影响脚本日志 Tab）"""
        text = sep.join(str(a) for a in args)
        if not _send_cmd_http("/console/agent-log", {"text": text, "level": level}):
            _send_cmd(f"agent_log:{level}:{text}")

    def switch_tab(self, tab):
        """切换悬浮窗显示的 Tab，tab 可为 'scripts'|'log'|'agent'"""
        if not _send_cmd_http("/console/switch-tab", {"tab": tab}):
            _send_cmd(f"switch_tab:{tab}")


# 全局单例
console = Console()
