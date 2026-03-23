"""
测试模块 12: 手势操作与 Node 控件直接操作
验证 long_press, gesture, Node.click/set_text/scroll 等接口
"""
import time
import pytest
from yyds import *


class TestLongPress:
    """长按测试"""

    def test_long_press(self):
        """长按屏幕中心"""
        try:
            long_press(540, 1200, duration=600)
        except Exception as e:
            pytest.fail(f"long_press 异常: {e}")
        time.sleep(0.3)
        key_back()

    def test_long_press_short(self):
        """短时长按"""
        try:
            long_press(540, 1200, duration=200)
        except Exception as e:
            pytest.fail(f"long_press(200ms) 异常: {e}")


class TestGesture:
    """gesture 路径手势测试"""

    def test_gesture_basic(self):
        """三点路径手势"""
        try:
            gesture([(200, 1200), (540, 800), (800, 400)], duration=500)
        except Exception as e:
            pytest.fail(f"gesture 异常: {e}")

    def test_gesture_two_points(self):
        """两点手势 (等价于 swipe)"""
        try:
            gesture([(540, 1800), (540, 600)], duration=300)
        except Exception as e:
            pytest.fail(f"gesture 两点异常: {e}")

    def test_gesture_single_point(self):
        """单点应抛出友好参数错误"""
        from yyds.util import YydsParamError
        with pytest.raises(YydsParamError):
            gesture([(540, 1200)])


class TestNodeActions:
    """Node 控件直接操作测试"""

    def test_node_click(self):
        """Node.click() 方法"""
        key_home()
        time.sleep(1)
        nodes = ui_match(visible_to_user="true", limit=1)
        if not nodes:
            pytest.skip("无可见控件")
        try:
            nodes[0].click()
        except Exception as e:
            pytest.fail(f"Node.click() 异常: {e}")
        print(f"  Node.click() 成功: {nodes[0].class_name}")

    def test_node_bounds(self):
        """Node.bounds 属性"""
        nodes = ui_match(visible_to_user="true", limit=1)
        if not nodes:
            pytest.skip("无可见控件")
        x1, y1, x2, y2 = nodes[0].bounds
        assert x2 >= x1, f"x2 应 >= x1, 实际: {x1},{x2}"
        assert y2 >= y1, f"y2 应 >= y1, 实际: {y1},{y2}"
        print(f"  bounds: ({x1},{y1},{x2},{y2})")

    def test_node_width_height(self):
        """Node.width / Node.height 属性"""
        nodes = ui_match(visible_to_user="true", limit=1)
        if not nodes:
            pytest.skip("无可见控件")
        assert nodes[0].width >= 0, f"width 应 >= 0, 实际: {nodes[0].width}"
        assert nodes[0].height >= 0, f"height 应 >= 0, 实际: {nodes[0].height}"
        print(f"  width={nodes[0].width}, height={nodes[0].height}")

    def test_node_long_press(self):
        """Node.long_press() 方法"""
        nodes = ui_match(visible_to_user="true", limit=1)
        if not nodes:
            pytest.skip("无可见控件")
        try:
            nodes[0].long_press(300)
        except Exception as e:
            pytest.fail(f"Node.long_press() 异常: {e}")
        time.sleep(0.3)
        key_back()
