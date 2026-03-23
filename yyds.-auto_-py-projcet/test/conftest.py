"""
Yyds.Auto API 测试配置
确保测试前:
  1. ADB 已连接设备: adb devices
  2. 端口已转发: adb forward tcp:61140 tcp:61140
  3. Yyds.Auto App 已启动, 引擎进程 (yyds.auto, yyds.py, yyds.keep) 正在运行
"""
import sys
import os
import pytest

# yyds SDK 已内置于引擎, 源码位于 CPython-android/python-shims/
# 本地测试时将其加入 path 以便 import yyds
_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SDK_PATH = os.path.join(_REPO_ROOT, "CPython-android", "python-shims")
if _SDK_PATH not in sys.path:
    sys.path.insert(0, _SDK_PATH)


@pytest.fixture(scope="session", autouse=True)
def setup_engine():
    """会话级别 fixture: 验证引擎连通性"""
    import requests
    ip = "127.0.0.1"
    port = 61140
    url = f"http://{ip}:{port}/api/ping"
    try:
        resp = requests.post(url, json={}, timeout=5)
        try:
            data = resp.json()
            pong = data.get("data", "") if isinstance(data, dict) else resp.text.strip()
        except Exception:
            pong = resp.text.strip()
        if pong != "pong":
            pytest.skip(f"引擎 ping 未返回 pong, 实际: {resp.text!r}")
    except requests.ConnectionError:
        pytest.skip(f"无法连接引擎 {ip}:{port}, 请确认 ADB forward 和引擎运行状态")
    except Exception as e:
        pytest.skip(f"引擎连接异常: {e}")
    yield
