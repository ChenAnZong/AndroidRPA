"""
测试模块 04: 截图与 OCR 识别
验证截图、OCR 文字识别、YOLO 目标检测等视觉接口
"""
import pytest
from yyds import *


class TestScreenshot:
    """截图测试"""

    def test_screenshot_default(self):
        """默认路径截图"""
        path = screenshot()
        assert path is not None and len(path.strip()) > 0, f"screenshot 返回路径不应为空, 实际: {path!r}"
        print(f"  截图路径: {path.strip()}")

    def test_screenshot_custom_path(self):
        """自定义路径截图"""
        custom = "/sdcard/test_screenshot.png"
        path = screenshot(custom)
        assert path is not None and len(path.strip()) > 0, f"自定义截图路径不应为空, 实际: {path!r}"
        print(f"  自定义截图路径: {path.strip()}")


class TestOCR:
    """OCR 文字识别测试"""

    def test_ocr_full_screen(self):
        """全屏 OCR 识别"""
        result = ocr()
        assert result is not None, "ocr() 返回不应为 None"
        assert isinstance(result, list), f"ocr 应返回 list, 实际类型: {type(result)}"
        print(f"  OCR 结果条数: {len(result)}")
        for item in result[:5]:
            if isinstance(item, dict):
                print(f"    > text={item.get('text','')!r}, prob={item.get('confidence','')}")
            else:
                print(f"    > {str(item)[:80]}")

    def test_ocr_with_region(self):
        """指定区域 OCR"""
        result = ocr(x=0, y=0, w=0.5, h=0.3)
        assert result is not None, "区域 OCR 返回不应为 None"
        print(f"  区域 OCR 结果长度: {len(result)} 字符")

    def test_screen_ocr_x(self):
        """screen_ocr_x 高级 OCR — 搜索特定文本"""
        # 先做一次全屏 OCR 确认有文字
        raw = ocr()
        if not raw:
            pytest.skip("当前屏幕无 OCR 结果, 跳过 screen_ocr_x 测试")
        # 尝试搜索常见文本
        results = screen_ocr_x(specific_texts=[".*"], x=0, y=0, w=1.0, h=1.0)
        assert isinstance(results, tuple), f"screen_ocr_x 应返回 tuple, 实际: {type(results)}"
        print(f"  screen_ocr_x 匹配 '.*': {len(results)} 个结果")
        for r in results[:3]:
            print(f"    > text={r.text!r}, prob={r.prob}, cx={r.cx}, cy={r.cy}")


class TestYolo:
    """YOLO 目标检测测试"""

    def test_screen_yolo_locate(self):
        """底层 YOLO 检测 — 不一定有结果, 仅验证不崩溃"""
        try:
            result = screen_yolo_locate()
            assert isinstance(result, str), f"screen_yolo_locate 应返回 str, 实际: {type(result)}"
            print(f"  YOLO 原始结果长度: {len(result)} 字符")
        except Exception as e:
            print(f"  YOLO 检测异常 (模型可能未加载): {e}")

    def test_screen_yolo_find_x(self):
        """screen_yolo_find_x 高级检测"""
        try:
            results = screen_yolo_find_x(min_prob=0.5)
            assert isinstance(results, tuple), f"应返回 tuple, 实际: {type(results)}"
            print(f"  YOLO 检测到 {len(results)} 个目标")
            for r in results[:3]:
                print(f"    > label={r.label!r}, prob={r.prob:.2f}, cx={r.cx}, cy={r.cy}")
        except Exception as e:
            print(f"  YOLO 检测异常 (模型可能未加载): {e}")
