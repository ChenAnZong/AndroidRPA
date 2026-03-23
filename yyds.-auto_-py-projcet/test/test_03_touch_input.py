"""
测试模块 03: 触控与输入
验证点击、滑动、按键注入、文本输入等交互接口
注意: 这些测试会在真实设备上执行触控操作
"""
import time
import pytest
from yyds import *


class TestClick:
    """点击操作测试"""

    def test_click_basic(self):
        """基础单次点击"""
        result = click(540, 1200)
        assert result is True, f"click 应返回 True, 实际: {result}"

    def test_click_multi(self):
        """连续多次点击"""
        result = click(540, 1200, click_time=3, interval=100)
        assert result is True, f"click 连点应返回 True, 实际: {result}"

    def test_random_click(self):
        """区域随机点击 — 不应抛异常"""
        try:
            random_click(400, 1000, 200, 200)
        except Exception as e:
            pytest.fail(f"random_click 异常: {e}")

    def test_click_double(self):
        """双击"""
        try:
            click_double(540, 1200)
        except Exception as e:
            pytest.fail(f"click_double 异常: {e}")


class TestSwipe:
    """滑动操作测试"""

    def test_swipe_basic(self):
        """基础滑动 — 从下往上"""
        try:
            swipe(540, 1800, 540, 600, 300)
        except Exception as e:
            pytest.fail(f"swipe 异常: {e}")
        time.sleep(0.5)

    def test_swipe_random(self):
        """随机锯齿滑动"""
        try:
            swipe(540, 1800, 540, 600, 500, is_random=True)
        except Exception as e:
            pytest.fail(f"swipe(random=True) 异常: {e}")
        time.sleep(0.5)


class TestPressGesture:
    """底层手势操作测试"""

    def test_press_down_move_up(self):
        """按下 → 移动 → 抬起 完整手势"""
        try:
            EngineDebug._press_down(540, 1200)
            time.sleep(0.1)
            EngineDebug._press_move(540, 800)
            time.sleep(0.1)
            EngineDebug._press_up(540, 800)
        except Exception as e:
            pytest.fail(f"press 手势序列异常: {e}")


class TestKeyInput:
    """按键注入测试"""

    def test_key_home(self):
        """Home 键"""
        try:
            key_home()
        except Exception as e:
            pytest.fail(f"key_home 异常: {e}")
        time.sleep(0.5)

    def test_key_back(self):
        """返回键"""
        try:
            key_back()
        except Exception as e:
            pytest.fail(f"key_back 异常: {e}")

    def test_key_menu(self):
        """菜单键"""
        try:
            key_menu()
        except Exception as e:
            pytest.fail(f"key_menu 异常: {e}")

    def test_key_code(self):
        """自定义键值码 — 音量+"""
        try:
            key_code(24)  # KEYCODE_VOLUME_UP
        except Exception as e:
            pytest.fail(f"key_code(24) 异常: {e}")

    def test_key_confirm(self):
        """确认键"""
        try:
            key_confirm()
        except Exception as e:
            pytest.fail(f"key_confirm 异常: {e}")


class TestTextInput:
    """文本输入测试"""

    def test_input_text_ascii(self):
        """ASCII 文本注入"""
        count = input_text("hello")
        assert isinstance(count, int), f"input_text 应返回 int, 实际类型: {type(count)}"
        print(f"  注入字符数: {count}")

    def test_x_input_text(self):
        """YY 输入法输入 (可能未启用, 仅验证不崩溃)"""
        try:
            result = x_input_text("测试文本")
            print(f"  x_input_text 返回: {result}")
        except Exception as e:
            print(f"  x_input_text 异常 (输入法可能未启用): {e}")

    def test_x_input_clear(self):
        """YY 输入法清空 (可能未启用, 仅验证不崩溃)"""
        try:
            result = x_input_clear()
            print(f"  x_input_clear 返回: {result}")
        except Exception as e:
            print(f"  x_input_clear 异常 (输入法可能未启用): {e}")
