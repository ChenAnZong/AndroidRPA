"""
测试模块 06: Shell 命令与应用管理
验证 shell 执行、应用启停、Toast 等接口
"""
import pytest
from yyds import *


class TestShell:
    """Shell 命令执行测试"""

    def test_shell_echo(self):
        """执行 echo 命令"""
        result = shell("echo hello_yyds")
        assert "hello_yyds" in result, f"shell echo 应包含 'hello_yyds', 实际: {result!r}"

    def test_shell_whoami(self):
        """执行 whoami — 验证权限"""
        result = shell("whoami")
        assert result is not None and len(result.strip()) > 0, f"whoami 不应为空, 实际: {result!r}"
        print(f"  whoami: {result.strip()}")

    def test_shell_ls(self):
        """执行 ls /sdcard"""
        result = shell("ls /sdcard")
        assert result is not None and len(result.strip()) > 0, f"ls /sdcard 不应为空"
        print(f"  /sdcard 目录项数: {len(result.strip().split())}")

    def test_shell_multi_cmd(self):
        """执行多条命令"""
        result = shell("echo A", "echo B")
        assert "A" in result and "B" in result, f"多命令应包含 A 和 B, 实际: {result!r}"

    def test_shell_getprop(self):
        """获取系统属性"""
        result = shell("getprop ro.build.version.sdk")
        assert result is not None and result.strip().isdigit(), f"SDK 版本应为数字, 实际: {result!r}"
        print(f"  Android SDK: {result.strip()}")


class TestAppManagement:
    """应用管理测试"""

    def test_open_app(self):
        """打开应用 — 打开系统设置"""
        try:
            open_app("com.android.settings")
        except Exception as e:
            pytest.fail(f"open_app 异常: {e}")
        import time
        time.sleep(1)
        pkg = device_foreground_package()
        print(f"  打开设置后前台包名: {pkg.strip()}")

    def test_is_app_running(self):
        """检测应用是否运行"""
        result = is_app_running("com.android.settings")
        assert isinstance(result, bool), f"is_app_running 应返回 bool, 实际: {type(result)}"
        print(f"  Settings 运行状态: {result}")

    def test_bring_app_to_top(self):
        """将后台应用拉到前台"""
        # 先回到桌面, 再拉回
        key_home()
        import time
        time.sleep(0.5)
        result = bring_app_to_top("com.android.settings")
        assert isinstance(result, bool), f"bring_app_to_top 应返回 bool, 实际: {type(result)}"
        print(f"  拉回前台结果: {result}")

    def test_stop_app(self):
        """停止应用"""
        try:
            stop_app("com.android.settings")
        except Exception as e:
            pytest.fail(f"stop_app 异常: {e}")
        import time
        time.sleep(0.5)

    def test_open_url(self):
        """通过 intent 打开 URL"""
        result = open_url("tel:10086")
        assert result is not None, "open_url 返回不应为 None"
        print(f"  open_url 返回: {result.strip()[:80]}")
        # 返回桌面
        import time
        time.sleep(0.5)
        key_back()
        time.sleep(0.3)
        key_home()


class TestToast:
    """Toast 提示测试"""

    def test_toast(self):
        """显示 Toast 提示"""
        try:
            toast("Yyds.Auto 测试通过 ✅")
        except Exception as e:
            pytest.fail(f"toast 异常: {e}")
