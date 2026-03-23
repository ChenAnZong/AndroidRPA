"""
测试模块 11: 等待类 API
验证 wait_for_text, wait_for_ui, wait_for_image, wait_for_activity 等接口
"""
import time
import pytest
from yyds import *


class TestWaitForText:
    """wait_for_text 文字等待测试"""

    def test_wait_for_text_found(self):
        """当前屏幕有文字时应立即返回"""
        # 先回桌面确保有文字
        key_home()
        time.sleep(1)
        # 先检查 OCR 是否能识别到文字
        raw = ocr()
        if not raw:
            pytest.skip("当前屏幕 OCR 返回空, 引擎 OCR 模型可能未就绪")
        result = wait_for_text(".*", timeout=5)
        assert result is not None, "桌面应能 OCR 到文字"
        assert len(result) > 0
        print(f"  wait_for_text 找到: {result[0].text!r}")

    def test_wait_for_text_timeout(self):
        """等不到的文字应超时返回 None"""
        result = wait_for_text("ZZZZZ_NOT_EXIST_99999", timeout=2, interval=0.5)
        assert result is None, "不存在的文字应超时返回 None"

    def test_wait_for_text_match_all(self):
        """match_all=True 需要所有文字都匹配"""
        result = wait_for_text("AAAA_NOT_EXIST", "BBBB_NOT_EXIST", timeout=2, match_all=True)
        assert result is None


class TestWaitForTextGone:
    """wait_for_text_gone 文字消失等待"""

    def test_wait_for_text_gone_already(self):
        """文字本来就不存在, 应立即返回 True"""
        result = wait_for_text_gone("ZZZZZ_NOT_EXIST_99999", timeout=3)
        assert result is True


class TestWaitForUi:
    """wait_for_ui 控件等待测试"""

    def test_wait_for_ui_found(self):
        """当前屏幕有可见控件时应返回"""
        key_home()
        time.sleep(1)
        nodes = wait_for_ui(visible_to_user="true", timeout=5)
        assert nodes is not None, "应找到可见控件"
        assert len(nodes) > 0
        print(f"  wait_for_ui 找到: {nodes[0].class_name}")

    def test_wait_for_ui_timeout(self):
        """等不到的控件应超时"""
        nodes = wait_for_ui(text="ZZZZZ_NOT_EXIST_NODE", timeout=2)
        assert nodes is None

    def test_wait_for_ui_gone(self):
        """不存在的控件 gone 应立即返回 True"""
        result = wait_for_ui_gone(text="ZZZZZ_NOT_EXIST_NODE", timeout=2)
        assert result is True


class TestWaitForActivity:
    """wait_for_activity / wait_for_package 测试"""

    def test_wait_for_package_current(self):
        """等待当前前台包名 — 应立即返回"""
        pkg = device_foreground_package().strip()
        result = wait_for_package(pkg, timeout=3)
        assert result is True

    def test_wait_for_package_timeout(self):
        """等待不存在的包名 — 应超时"""
        result = wait_for_package("com.nonexistent.fake.app", timeout=2)
        assert result is False


class TestWaitAndClick:
    """wait_and_click 复合操作测试"""

    def test_wait_and_click_text_timeout(self):
        """等不到的文字点击应返回 False"""
        result = wait_and_click_text("ZZZZZ_NOT_EXIST", timeout=2)
        assert result is False

    def test_wait_and_click_ui_timeout(self):
        """等不到的控件点击应返回 False"""
        result = wait_and_click_ui(text="ZZZZZ_NOT_EXIST", timeout=2)
        assert result is False
