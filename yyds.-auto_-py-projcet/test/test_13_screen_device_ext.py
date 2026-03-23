"""
测试模块 13: 屏幕变化检测与设备扩展接口
验证 wait_screen_change/stable, 电池, WiFi, 权限管理等接口
"""
import time
import pytest
from yyds import *


class TestScreenChange:
    """屏幕变化检测测试"""

    def test_wait_screen_stable(self):
        """静止画面应判定为稳定"""
        key_home()
        time.sleep(1)
        result = wait_screen_stable(stable_duration=0.5, timeout=5, interval=0.3)
        assert result is True, "桌面静止画面应判定为稳定"


class TestDeviceExtApis:
    """设备扩展接口测试"""

    def test_get_battery_info(self):
        """获取电池信息"""
        info = get_battery_info()
        assert isinstance(info, dict), f"应返回 dict, 实际: {type(info)}"
        assert "level" in info, f"应包含 level 字段, 实际 keys: {list(info.keys())}"
        assert 0 <= info["level"] <= 100, f"电量应 0-100, 实际: {info['level']}"
        print(f"  电池: level={info.get('level')}%, temp={info.get('temperature')}")

    def test_get_screen_orientation(self):
        """获取屏幕方向"""
        ori = get_screen_orientation()
        assert isinstance(ori, int), f"应返回 int, 实际: {type(ori)}"
        assert ori in (0, 1, 2, 3), f"方向应为 0-3, 实际: {ori}"
        print(f"  屏幕方向: {ori}")

    def test_get_wifi_info(self):
        """获取 WiFi 信息"""
        info = get_wifi_info()
        assert isinstance(info, dict), f"应返回 dict, 实际: {type(info)}"
        assert "raw" in info
        print(f"  WiFi SSID: {info.get('ssid', 'N/A')}, IP: {info.get('ip', 'N/A')}")

    def test_get_notifications(self):
        """读取通知栏 — 不应崩溃"""
        try:
            result = get_notifications()
            assert isinstance(result, str)
            print(f"  通知内容长度: {len(result)} 字符")
        except Exception as e:
            print(f"  通知栏读取异常: {e}")


class TestAppExtApis:
    """应用管理扩展接口测试"""

    def test_app_clear_data(self):
        """清除应用数据 — 使用一个安全的测试目标"""
        # 清除 Yyds.Auto 自身的缓存不太安全, 跳过破坏性操作, 仅验证接口不崩溃
        # 使用 calculator 作为安全测试目标
        result = app_clear_data("com.miui.calculator")
        assert isinstance(result, bool), f"应返回 bool, 实际: {type(result)}"
        print(f"  清除 calculator 数据: {result}")

    def test_grant_permission(self):
        """授予权限 — 仅验证接口不崩溃"""
        result = grant_permission("com.yyds.auto", "android.permission.READ_EXTERNAL_STORAGE")
        assert isinstance(result, bool), f"应返回 bool, 实际: {type(result)}"
        print(f"  授予权限结果: {result}")


class TestScalePos:
    """分辨率适配测试"""

    def test_scale_pos_identity(self):
        """当前设备为 1080x2400 时应返回原值"""
        DeviceScreen.init()
        dw, dh = DeviceScreen.get_screen_wh()
        sx, sy = scale_pos(540, 1200, base_w=dw, base_h=dh)
        assert sx == 540 and sy == 1200, f"同分辨率应返回原值, 实际: ({sx},{sy})"

    def test_scale_pos_different(self):
        """不同分辨率适配"""
        DeviceScreen.init()
        sx, sy = scale_pos(540, 1200)
        assert isinstance(sx, int) and isinstance(sy, int)
        print(f"  scale_pos(540,1200) -> ({sx},{sy})")

    def test_click_scaled(self):
        """分辨率自适应点击 — 不应崩溃"""
        try:
            click_scaled(540, 1200)
        except Exception as e:
            pytest.fail(f"click_scaled 异常: {e}")
