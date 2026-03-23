"""
测试模块 01: 引擎连接与基础信息
验证自动化引擎 RPC 通信是否正常, 以及基础引擎状态查询接口
"""
import pytest
from yyds import *


class TestEngineDebug:
    """EngineDebug 引擎调试接口测试"""

    def test_ping(self):
        """引擎通信检测 — 最基础的连通性测试"""
        result = EngineDebug._ping()
        assert result is True, f"ping 应返回 True, 实际: {result}"

    def test_version(self):
        """获取引擎版本号"""
        version = EngineDebug._version()
        assert version is not None and len(str(version)) > 0, f"version 不应为空, 实际: {version!r}"
        print(f"  引擎版本: {version}")

    def test_pid(self):
        """获取引擎进程 PID"""
        pid = EngineDebug._pid()
        assert isinstance(pid, int), f"pid 应为 int, 实际类型: {type(pid)}"
        assert pid > 0, f"pid 应大于 0, 实际: {pid}"
        print(f"  引擎 PID: {pid}")

    def test_uid(self):
        """获取引擎进程 UID (0=ROOT, 2000=SHELL)"""
        uid = EngineDebug._uid()
        assert isinstance(uid, int), f"uid 应为 int, 实际类型: {type(uid)}"
        assert uid in (0, 2000), f"uid 应为 0(ROOT) 或 2000(SHELL), 实际: {uid}"
        print(f"  引擎 UID: {uid} ({'ROOT' if uid == 0 else 'SHELL'})")


class TestEngineApi:
    """engine_call 底层 RPC 调用测试"""

    def test_engine_api_raw_ping(self):
        """直接调用 engine_call 检测 /ping"""
        result = engine_call("/ping")
        assert result == "pong" or (isinstance(result, dict) and result.get("data") == "pong"), \
            f"engine_call('/ping') 应返回 'pong', 实际: {result!r}"

    def test_engine_api_with_params(self):
        """engine_call 带参数调用 — toast 接口"""
        try:
            engine_call("/toast", {"content": "API 测试中..."})
        except Exception as e:
            pytest.fail(f"engine_call('/toast') 调用异常: {e}")

    def test_engine_set_debug(self):
        """切换调试模式"""
        engine_set_debug(True)
        assert ProjectEnvironment.DEBUG_MODE is True
        engine_set_debug(False)
        assert ProjectEnvironment.DEBUG_MODE is False
