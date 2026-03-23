"""
测试模块 02: 设备信息查询
验证设备硬件信息、屏幕参数、前台应用等查询接口
"""
import pytest
from yyds import *


class TestScreenInfo:
    """屏幕信息测试"""

    def test_screen_size(self):
        """获取屏幕分辨率"""
        w, h = device_get_screen_size()
        assert isinstance(w, int) and isinstance(h, int), f"宽高应为 int, 实际: w={type(w)}, h={type(h)}"
        assert w > 0 and h > 0, f"宽高应 > 0, 实际: {w}x{h}"
        assert w >= 720, f"宽度应 >= 720, 实际: {w}"
        assert h >= 1280, f"高度应 >= 1280, 实际: {h}"
        print(f"  屏幕分辨率: {w}x{h}")

    def test_device_screen_init(self):
        """DeviceScreen 初始化"""
        DeviceScreen.init()
        w = DeviceScreen.get_w()
        h = DeviceScreen.get_h()
        assert w > 0 and h > 0, f"DeviceScreen 宽高应 > 0, 实际: {w}x{h}"
        wh = DeviceScreen.get_screen_wh()
        assert wh == (w, h), f"get_screen_wh 应返回 ({w}, {h}), 实际: {wh}"
        print(f"  DeviceScreen: {w}x{h}")


class TestDeviceHardware:
    """设备硬件信息测试"""

    def test_device_model(self):
        """获取手机型号"""
        model = device_model()
        assert model is not None and len(model.strip()) > 0, f"手机型号不应为空, 实际: {model!r}"
        print(f"  手机型号: {model.strip()}")

    def test_device_code(self):
        """获取设备唯一硬件码"""
        code = device_code()
        assert code is not None and len(code.strip()) > 0, f"设备码不应为空, 实际: {code!r}"
        print(f"  设备码: {code.strip()}")


class TestForegroundInfo:
    """前台应用信息测试"""

    def test_device_foreground(self):
        """获取前台应用完整信息"""
        fg = device_foreground()
        assert fg is not None, "device_foreground 不应返回 None"
        assert hasattr(fg, 'pid'), "返回对象应有 pid 属性"
        assert hasattr(fg, 'activity_name'), "返回对象应有 activity_name 属性"
        assert hasattr(fg, 'package'), "返回对象应有 package 属性"
        print(f"  前台: pid={fg.pid}, pkg={fg.package}, activity={fg.activity_name}")

    def test_device_foreground_activity(self):
        """获取前台 Activity 名"""
        activity = device_foreground_activity()
        assert activity is not None and len(activity.strip()) > 0, f"activity 不应为空, 实际: {activity!r}"
        print(f"  前台 Activity: {activity.strip()}")

    def test_device_foreground_package(self):
        """获取前台包名"""
        pkg = device_foreground_package()
        assert pkg is not None and len(pkg.strip()) > 0, f"包名不应为空, 实际: {pkg!r}"
        assert "." in pkg, f"包名格式应含 '.', 实际: {pkg!r}"
        print(f"  前台包名: {pkg.strip()}")

    def test_is_in_app(self):
        """检测当前是否在某应用"""
        pkg = device_foreground_package()
        assert is_in_app(pkg.strip()) is True, f"is_in_app 对当前前台包名应返回 True"
        assert is_in_app("com.nonexistent.fake") is False, "is_in_app 对不存在的包名应返回 False"


class TestNetworkInfo:
    """网络状态测试"""

    def test_is_net_online(self):
        """网络连通性检测"""
        online = is_net_online()
        assert isinstance(online, bool), f"is_net_online 应返回 bool, 实际类型: {type(online)}"
        print(f"  网络状态: {'在线' if online else '离线'}")
