"""
自动化引擎代理 - 双层架构:
1. http() 方法: 直连 yyds.auto (49009) — 供 server.py 的 /engine/auto 端点使用
2. 高层方法 (touch/swipe等): 走 61140 /engine/auto — 供 agent 使用

架构:
  Agent → touch() → _auto_api("/touch") → 61140 /engine/auto
  server.py 收到 → auto_engine.http("/touch") → 49009 yyds.auto
"""

import json
import subprocess
import time
import urllib.request
import urllib.error
from typing import Optional, Dict, Any

PY_ENGINE_HOST = "127.0.0.1"
PY_ENGINE_PORT = 61140
AUTO_ENGINE_PORT = 49009  # yyds.auto 默认端口


def _detect_auto_port() -> int:
    """自动探测 yyds.auto 监听端口"""
    try:
        out = subprocess.check_output(
            ["sh", "-c", "netstat -tlnp 2>/dev/null | grep yyds.auto"],
            timeout=3
        ).decode()
        for line in out.strip().splitlines():
            parts = line.split()
            for p in parts:
                if ":" in p:
                    port_str = p.rsplit(":", 1)[-1]
                    if port_str.isdigit():
                        port = int(port_str)
                        if 1024 < port < 65535:
                            return port
    except Exception:
        pass
    return AUTO_ENGINE_PORT


class AutoEngineProxy:
    """双层代理: http() 直连 yyds.auto, 高层方法走 /engine/auto"""

    def __init__(self, host=PY_ENGINE_HOST, port=PY_ENGINE_PORT):
        self.host = host
        self.port = port
        self._base_url = f"http://{host}:{port}"  # 61140
        self._auto_port = _detect_auto_port()
        self._auto_url = f"http://{host}:{self._auto_port}"  # 49009
        self._connected = False

    def check_engine(self) -> bool:
        """检查 yyds.auto 引擎是否在运行"""
        try:
            req = urllib.request.Request(self._auto_url)
            with urllib.request.urlopen(req, timeout=3) as resp:
                self._connected = resp.status == 200
                return self._connected
        except Exception:
            self._connected = False
            return False

    def http(self, uri: str, params: Optional[Dict] = None, timeout=10) -> Optional[str]:
        """直连 yyds.auto HTTP API — 供 server.py /engine/auto 端点调用
        
        发送: POST http://127.0.0.1:49009{uri}  body=params
        返回: 原始响应文本
        """
        try:
            url = f"{self._auto_url}{uri}"
            if params:
                # 移除 uri 字段，只传参数
                p = {k: v for k, v in params.items() if k != "uri"}
                data = json.dumps(p).encode("utf-8")
            else:
                data = b"{}"
            req = urllib.request.Request(url, data=data)
            req.add_header("Content-Type", "application/json")
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.read().decode("utf-8")
        except Exception as e:
            print(f"[AutoEngine] http {uri} 异常: {e}")
            return None

    def _auto_api(self, uri: str, params: Optional[Dict[str, str]] = None, timeout=10) -> Optional[str]:
        """直连 yyds.auto API 并解析 ExportApi 响应
        
        ExportApi 格式: {"ok": true, "data": "..."} 或 {"ok": false, "error": "..."}
        返回: data 字段的内容（字符串化）
        """
        raw = self.http(uri, params, timeout)
        if raw is None:
            return None
        try:
            resp = json.loads(raw)
            if isinstance(resp, dict):
                if resp.get("ok") is False:
                    print(f"[AutoEngine] {uri} API错误: {resp.get('error')}")
                    return None
                if "data" in resp:
                    d = resp["data"]
                    return json.dumps(d) if isinstance(d, (dict, list)) else str(d)
            return raw
        except (json.JSONDecodeError, TypeError):
            return raw

    # ----------------------------------------------------------
    # Agent 操作方法 - 全部走 _auto_api (直连 yyds.auto)
    # ----------------------------------------------------------

    def touch(self, x, y) -> bool:
        ret = self._auto_api("/touch", {"x": str(x), "y": str(y)})
        if not ret:
            return False
        try:
            d = json.loads(ret)
            return d.get("result", False) if isinstance(d, dict) else bool(ret)
        except (json.JSONDecodeError, TypeError):
            return ret == "true"

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration: int = 300) -> bool:
        ret = self._auto_api("/swipe", {
            "x1": str(x1), "y1": str(y1),
            "x2": str(x2), "y2": str(y2),
            "duration": str(duration),
        })
        return ret is not None

    def long_click(self, x: int, y: int, duration: int = 1500) -> bool:
        """长按（同步版本，会阻塞调用线程）"""
        ret_down = self._auto_api("/touch_down", {"x": str(x), "y": str(y)})
        if ret_down is None:
            return False
        time.sleep(duration / 1000.0)
        ret_up = self._auto_api("/touch_up", {"x": str(x), "y": str(y)})
        return ret_up is not None

    def touch_down(self, x: int, y: int) -> bool:
        """触摸按下"""
        return self._auto_api("/touch_down", {"x": str(x), "y": str(y)}) is not None

    def touch_up(self, x: int, y: int) -> bool:
        """触摸抬起"""
        return self._auto_api("/touch_up", {"x": str(x), "y": str(y)}) is not None

    def key_code(self, code: str) -> bool:
        ret = self._auto_api("/key_code", {"code": code})
        return ret is not None

    def input_text(self, text: str) -> bool:
        ret = self._auto_api("/input_text", {"text": text})
        return ret is not None

    def x_input_text(self, text: str) -> bool:
        """通过 YY 输入法输入文本（更稳定，兼容中文）"""
        ret = self._auto_api("/xinput_text", {"text": text})
        return ret is not None

    def x_input_clear(self) -> bool:
        """通过 YY 输入法清空编辑框"""
        ret = self._auto_api("/xinput_clear")
        return ret is not None

    def installed_apps(self) -> list:
        """获取已安装非系统应用列表 — 走 yydspy 的 /package/installed-apps
        返回: [{packageName, appName}, ...]
        """
        try:
            url = f"{self._base_url}/package/installed-apps"
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=10) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as e:
            print(f"[AutoEngine] installed_apps 异常: {e}")
            return []

    def _resolve_package_name(self, name: str) -> Optional[str]:
        """将应用名/包名解析为包名。已是包名则直接返回，否则模糊匹配已安装应用"""
        # 已经是包名格式 (含 .)
        if "." in name:
            return name
        # 中文名 → 查已安装列表匹配
        apps = self.installed_apps()
        for app in apps:
            if app.get("appName") == name:
                return app["packageName"]
        # 模糊匹配
        for app in apps:
            if name in app.get("appName", ""):
                return app["packageName"]
        return None

    def open_app(self, name: str) -> bool:
        """通过包名或应用名打开应用（自动解析中文名→包名）"""
        pkg = self._resolve_package_name(name)
        if not pkg:
            print(f"[AutoEngine] open_app: 找不到应用 '{name}'")
            return False
        ret = self._auto_api("/open_app", {"pkg": pkg})
        return ret is not None

    def foreground(self) -> Optional[str]:
        return self._auto_api("/foreground")

    def ui_dump(self, save_to: str, all_window: bool = True) -> bool:
        """获取 UI dump 并保存到文件"""
        xml = self.ui_dump_text(all_window)
        if xml:
            try:
                with open(save_to, "w", encoding="utf-8") as f:
                    f.write(xml)
                return True
            except Exception:
                pass
        return False

    def ui_dump_text(self, all_window: bool = True) -> str:
        """获取 UI 控件树 XML — 走 61140 Kotlin 服务器的 /uia_dump"""
        try:
            url = f"{self._base_url}/uia_dump"
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=15) as resp:
                return resp.read().decode("utf-8")
        except Exception as e:
            print(f"[AutoEngine] ui_dump_text 异常: {e}")
            return ""

    def ocr(self) -> Optional[str]:
        return self._auto_api("/screen_ocr", timeout=15)

    def set_ocr_version(self, version: str = "v5_mobile", target_size: int = 0) -> Optional[str]:
        params = {"version": version}
        if target_size > 0:
            params["target_size"] = str(target_size)
        return self._auto_api("/set_ocr_version", params)

    def screenshot_bytes(self, quality: int = 70) -> Optional[bytes]:
        """获取截图 bytes（JPEG，全尺寸）- 走 /screenshot 端点"""
        try:
            url = f"{self._base_url}/screenshot?quality={quality}"
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=15) as resp:
                return resp.read()
        except Exception:
            return None

    def som_screenshot(self, use_ui: bool = True, quality: int = 80) -> Optional[str]:
        """SoM 标注截图 — 返回 JSON 字符串 {marks: [...], image_path: "..."}"""
        params = {"use_ui": str(use_ui).lower(), "quality": str(quality)}
        return self._auto_api("/som_screenshot", params, timeout=15)

    def screen_info(self) -> Optional[Dict[str, Any]]:
        """获取屏幕信息 — 走 61140 的 /screen-info"""
        try:
            url = f"{self._base_url}/screen-info"
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=5) as resp:
                raw = resp.read().decode("utf-8")
                return json.loads(raw)
        except Exception as e:
            print(f"[AutoEngine] screen_info 异常: {e}")
            return None

    def ping(self) -> Optional[str]:
        """ping yyds.auto"""
        try:
            req = urllib.request.Request(self._auto_url)
            with urllib.request.urlopen(req, timeout=3) as resp:
                return resp.read().decode("utf-8")
        except Exception:
            return None

    def set_clipboard(self, text: str) -> bool:
        ret = self._auto_api("/set_clipboard", {"text": text})
        return ret is not None

    def get_clipboard(self) -> str:
        ret = self._auto_api("/get_clipboard")
        return ret if isinstance(ret, str) else ""

    def imei(self) -> Optional[str]:
        return self._auto_api("/imei")


# 全局单例
auto_engine = AutoEngineProxy()
