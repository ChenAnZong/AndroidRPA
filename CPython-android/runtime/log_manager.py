"""
日志管理模块 - 替代 PyOut.kt
负责收集 Python 脚本的 stdout/stderr 输出
使用简单的线程安全队列，通过 HTTP 轮询推送给客户端
"""

import sys
import threading
from collections import deque
from io import TextIOBase


class LogManager:
    """日志管理器 - 纯线程安全，无 asyncio 依赖"""

    def __init__(self):
        self._lock = threading.Lock()
        self._queue = deque(maxlen=50000)
        self._original_stdout = sys.__stdout__
        self._original_stderr = sys.__stderr__

    def out(self, text):
        """标准输出（写原始流 + 入队）"""
        try:
            if self._original_stdout:
                self._original_stdout.write(text)
                self._original_stdout.flush()
        except Exception:
            pass
        with self._lock:
            self._queue.append("O:" + text)

    def err(self, text):
        """错误输出（写原始流 + 入队）"""
        try:
            if self._original_stderr:
                self._original_stderr.write(text)
                self._original_stderr.flush()
        except Exception:
            pass
        with self._lock:
            self._queue.append("E:" + text)

    def drain(self) -> str:
        """取出队列中所有日志，拼接返回。无日志返回空字符串"""
        with self._lock:
            if not self._queue:
                return ""
            lines = list(self._queue)
            self._queue.clear()
        return "".join(lines)

    def install_stream_hooks(self):
        """替换 sys.stdout/sys.stderr"""
        sys.stdout = _ConsoleOutputStream(self, is_error=False)
        sys.stderr = _ConsoleOutputStream(self, is_error=True)

    def restore_streams(self):
        sys.stdout = self._original_stdout
        sys.stderr = self._original_stderr


class _ConsoleOutputStream(TextIOBase):
    """拦截 stdout/stderr 写入，转发到 LogManager"""

    def __init__(self, log_mgr: LogManager, is_error=False):
        self._log_mgr = log_mgr
        self._is_error = is_error

    @property
    def encoding(self):
        return 'utf-8'

    @property
    def errors(self):
        return 'strict'

    def write(self, s):
        if self._is_error:
            self._log_mgr.err(s)
        else:
            self._log_mgr.out(s)
        return len(s) if s else 0

    def flush(self):
        pass

    def writable(self):
        return True

    def readable(self):
        return False


# 全局单例
log_manager = LogManager()
