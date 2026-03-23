"""
console_shim.py - 悬浮日志控制台 fallback
当 runtime/console.py 不在 PYTHONPATH 中时，从 yyds 包内提供相同功能。
此文件内容与 CPython-android/runtime/console.py 保持同步。
"""

import json
import time
import traceback

_CONSOLE_CMD_PREFIX = "##YYDS_CONSOLE##"
_ENGINE_PORT = 61140
_use_http = None       # None=未检测, True=HTTP可用, False=上次失败
_http_fail_time = 0.0  # 上次HTTP失败的时间戳
_HTTP_RETRY_TTL = 30.0 # 失败后30秒重试


def _try_http_post(path, data=None):
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
    print(f"{_CONSOLE_CMD_PREFIX}{cmd}", flush=True)


def _send_cmd_http(path, data=None):
    global _use_http
    if _use_http is False:
        if time.monotonic() - _http_fail_time < _HTTP_RETRY_TTL:
            return False
        _use_http = None
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
        if not self._shown:
            self.show()
        if args:
            self.log(*args, **kwargs)

    def show(self):
        self._shown = True
        if not _send_cmd_http("/console/show"):
            _send_cmd("show")

    def hide(self):
        if not _send_cmd_http("/console/hide"):
            _send_cmd("hide")

    def close(self):
        self._shown = False
        if not _send_cmd_http("/console/close"):
            _send_cmd("close")

    def clear(self):
        if not _send_cmd_http("/console/clear"):
            _send_cmd("clear")

    def log(self, *args, sep=" "):
        text = self._indent() + sep.join(str(a) for a in args)
        if not _send_cmd_http("/console/log", {"text": text, "level": "I"}):
            _send_cmd(f"log:I:{text}")

    def info(self, *args, sep=" "):
        self.log(*args, sep=sep)

    def warn(self, *args, sep=" "):
        text = self._indent() + sep.join(str(a) for a in args)
        if not _send_cmd_http("/console/log", {"text": text, "level": "W"}):
            _send_cmd(f"log:W:{text}")

    def warning(self, *args, sep=" "):
        self.warn(*args, sep=sep)

    def error(self, *args, sep=" "):
        text = self._indent() + sep.join(str(a) for a in args)
        if not _send_cmd_http("/console/log", {"text": text, "level": "E"}):
            _send_cmd(f"log:E:{text}")

    def debug(self, *args, sep=" "):
        text = self._indent() + sep.join(str(a) for a in args)
        if not _send_cmd_http("/console/log", {"text": text, "level": "D"}):
            _send_cmd(f"log:D:{text}")

    def verbose(self, *args, sep=" "):
        text = self._indent() + sep.join(str(a) for a in args)
        if not _send_cmd_http("/console/log", {"text": text, "level": "V"}):
            _send_cmd(f"log:V:{text}")

    def _indent(self):
        return "  " * self._group_depth if self._group_depth > 0 else ""

    def print(self, *args, sep=" ", end="\n"):
        text = sep.join(str(a) for a in args)
        if end and end != "\n":
            text += end
        self.log(text)

    def assert_(self, condition, *args, sep=" "):
        if not condition:
            text = sep.join(str(a) for a in args) if args else "Assertion failed"
            self.error(f"✘ AssertionError: {text}")

    def time(self, label="default"):
        if not hasattr(self, "_timers"):
            self._timers = {}
        self._timers[label] = time.time()

    def time_end(self, label="default"):
        if not hasattr(self, "_timers") or label not in self._timers:
            self.warn(f"Timer '{label}' not found")
            return
        elapsed = time.time() - self._timers.pop(label)
        self.log(f"⏱ {label}: {elapsed * 1000:.2f}ms")

    def count(self, label="default"):
        self._counters[label] = self._counters.get(label, 0) + 1
        self.log(f"{label}: {self._counters[label]}")

    def count_reset(self, label="default"):
        self._counters[label] = 0
        self.debug(f"Counter '{label}' reset")

    def trace(self, *args, sep=" "):
        msg = sep.join(str(a) for a in args) if args else "Trace"
        stack = traceback.format_stack()[:-1]
        lines = [f"┌─ {msg}"]
        for frame in stack:
            for line in frame.strip().splitlines():
                lines.append(f"│  {line}")
        lines.append("└─")
        self.debug("\n".join(lines))

    def group(self, *args, sep=" "):
        label = sep.join(str(a) for a in args) if args else "Group"
        self.log(f"▼ {label}")
        self._group_depth += 1

    def group_end(self):
        if self._group_depth > 0:
            self._group_depth -= 1

    def table(self, data, columns=None):
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
                headers = columns or [str(i) for i in range(len(first))]
                for item in data:
                    rows.append([str(v) for v in item])
            else:
                headers = ["Index", "Value"]
                for i, v in enumerate(data):
                    rows.append([str(i), str(v)])
        else:
            self.log(str(data))
            return
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
        try:
            text = json.dumps(obj, indent=indent, ensure_ascii=False, default=str)
        except (TypeError, ValueError):
            text = str(obj)
        self.debug(text)

    def divider(self, char="─", length=40, label=""):
        if label:
            pad = max(0, length - len(label) - 2)
            left = pad // 2
            right = pad - left
            line = f"{char * left} {label} {char * right}"
        else:
            line = char * length
        self.log(line)

    def success(self, *args, sep=" "):
        text = sep.join(str(a) for a in args)
        self.log(f"✔ {text}")

    def fail(self, *args, sep=" "):
        text = sep.join(str(a) for a in args)
        self.error(f"✘ {text}")

    def set_alpha(self, alpha):
        alpha = max(0.2, min(1.0, float(alpha)))
        if not _send_cmd_http("/console/set-alpha", {"alpha": alpha}):
            _send_cmd(f"alpha:{alpha}")

    def set_size(self, width, height):
        if not _send_cmd_http("/console/set-size", {"width": int(width), "height": int(height)}):
            _send_cmd(f"size:{int(width)},{int(height)}")

    def set_position(self, x, y):
        if not _send_cmd_http("/console/set-position", {"x": int(x), "y": int(y)}):
            _send_cmd(f"pos:{int(x)},{int(y)}")

    def set_title(self, title):
        self._title = str(title)
        if not _send_cmd_http("/console/set-title", {"title": self._title}):
            _send_cmd(f"title:{self._title}")


console = Console()
