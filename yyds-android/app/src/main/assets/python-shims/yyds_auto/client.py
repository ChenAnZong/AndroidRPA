"""HTTP REST 客户端 — 与设备端 yyds.py 引擎通信 (端口 61140)"""

from __future__ import annotations

import json
import time
from typing import Any, Dict, Optional, Union
from urllib.parse import urlencode

import requests

from .exceptions import ConnectError, RequestError

DEFAULT_PORT = 61140
DEFAULT_TIMEOUT = 15


class HttpClient:
    """设备 HTTP REST 客户端"""

    def __init__(self, host: str = "127.0.0.1", port: int = DEFAULT_PORT, timeout: float = DEFAULT_TIMEOUT):
        self._base = f"http://{host}:{port}"
        self._timeout = timeout
        self._session = requests.Session()

    @property
    def base_url(self) -> str:
        return self._base

    def _url(self, path: str) -> str:
        if not path.startswith("/"):
            path = "/" + path
        return self._base + path

    # ---------- 基础请求 ----------

    def get(self, path: str, params: Optional[Dict[str, str]] = None, timeout: Optional[float] = None) -> requests.Response:
        """GET 请求"""
        try:
            resp = self._session.get(self._url(path), params=params, timeout=timeout or self._timeout)
            return resp
        except requests.ConnectionError:
            raise ConnectError(f"无法连接设备 {self._base}，请检查引擎是否运行")
        except requests.Timeout:
            raise ConnectError(f"连接超时 {self._base}")

    def post(self, path: str, data: Any = None, json_data: Any = None,
             timeout: Optional[float] = None, **kwargs) -> requests.Response:
        """POST 请求"""
        try:
            resp = self._session.post(
                self._url(path), data=data, json=json_data,
                timeout=timeout or self._timeout, **kwargs,
            )
            return resp
        except requests.ConnectionError:
            raise ConnectError(f"无法连接设备 {self._base}，请检查引擎是否运行")
        except requests.Timeout:
            raise ConnectError(f"连接超时 {self._base}")

    # ---------- 便捷方法 ----------

    def get_json(self, path: str, params: Optional[Dict[str, str]] = None, **kw) -> Any:
        """GET 并解析 JSON"""
        resp = self.get(path, params=params, **kw)
        if resp.status_code != 200:
            raise RequestError(path, resp.status_code, resp.text)
        return resp.json()

    def get_text(self, path: str, params: Optional[Dict[str, str]] = None, **kw) -> str:
        """GET 返回文本"""
        resp = self.get(path, params=params, **kw)
        if resp.status_code != 200:
            raise RequestError(path, resp.status_code, resp.text)
        return resp.text

    def get_bytes(self, path: str, params: Optional[Dict[str, str]] = None, **kw) -> bytes:
        """GET 返回二进制"""
        resp = self.get(path, params=params, **kw)
        if resp.status_code != 200:
            raise RequestError(path, resp.status_code, resp.text)
        return resp.content

    def post_json(self, path: str, data: Any = None, **kw) -> Any:
        """POST JSON 并解析响应"""
        resp = self.post(path, json_data=data, **kw)
        if resp.status_code != 200:
            raise RequestError(path, resp.status_code, resp.text)
        try:
            return resp.json()
        except ValueError:
            return resp.text

    def post_text(self, path: str, data: Any = None, **kw) -> str:
        """POST 返回文本"""
        resp = self.post(path, json_data=data, **kw)
        if resp.status_code != 200:
            raise RequestError(path, resp.status_code, resp.text)
        return resp.text

    # ---------- yyds.auto 引擎代理 ----------

    def auto_api(self, api: str, params: Optional[Dict[str, str]] = None, **kw) -> Any:
        """调用 yyds.auto 自动化引擎 API（双层 JSON 解包）

        通过 POST /api/{api} 代理到 yyds.auto 引擎 (端口 61100)
        """
        resp_text = self.post_text(f"/api/{api}", data=params or {}, **kw)
        # yyds.auto 返回的是 JSON 字符串，可能需要二次解析
        try:
            result = json.loads(resp_text)
            if isinstance(result, str):
                try:
                    return json.loads(result)
                except (ValueError, TypeError):
                    pass
            return result
        except (ValueError, TypeError):
            return resp_text

    # ---------- 连接检测 ----------

    def ping(self, timeout: float = 3) -> bool:
        """检测引擎是否可达"""
        try:
            resp = self._session.get(self._url("/ping"), timeout=timeout)
            return resp.status_code == 200
        except Exception:
            return False

    def wait_ready(self, timeout: float = 30, interval: float = 1) -> bool:
        """等待引擎就绪"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.ping(timeout=2):
                return True
            time.sleep(interval)
        return False
