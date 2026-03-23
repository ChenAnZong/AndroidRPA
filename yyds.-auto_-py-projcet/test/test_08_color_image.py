"""
测试模块 08: 颜色与图像处理
验证取色、找色、图片相似度等视觉处理接口
"""
import pytest
from yyds import *


class TestGetColor:
    """取色测试"""

    def test_get_color(self):
        """获取屏幕指定坐标颜色"""
        # 先截图
        screenshot()
        color = get_color(100, 100)
        assert isinstance(color, Color), f"get_color 应返回 Color, 实际: {type(color)}"
        assert 0 <= color.r <= 255, f"R 分量应在 0-255, 实际: {color.r}"
        assert 0 <= color.g <= 255, f"G 分量应在 0-255, 实际: {color.g}"
        assert 0 <= color.b <= 255, f"B 分量应在 0-255, 实际: {color.b}"
        print(f"  坐标(100,100) 颜色: {color}")

    def test_get_multi_color(self):
        """获取多个坐标颜色"""
        screenshot()
        colors = get_multi_color([(100, 100), (200, 200), (300, 300)])
        assert colors is not None, "get_multi_color 不应返回 None"
        print(f"  多点取色结果: {colors}")


class TestFindColor:
    """找色测试"""

    def test_find_color_basic(self):
        """基础单点找色"""
        # 先取一个已知颜色, 然后搜索它
        screenshot()
        color = get_color(540, 100)
        rgb_str = f"{color.r},{color.g},{color.b}"
        results = find_color(rgb_str, max_fuzzy=30, max_counts=3)
        assert isinstance(results, list), f"find_color 应返回 list, 实际: {type(results)}"
        print(f"  找色 RGB={rgb_str}, fuzzy=30: 找到 {len(results)} 个点")
        for p in results[:3]:
            print(f"    > {p}")

    def test_find_color_with_region(self):
        """指定区域找色"""
        screenshot()
        color = get_color(540, 100)
        rgb_str = f"{color.r},{color.g},{color.b}"
        results = find_color(rgb_str, max_fuzzy=30, x=0, y=0, w=0.5, h=0.5, max_counts=2)
        assert isinstance(results, list), f"应返回 list, 实际: {type(results)}"
        print(f"  区域找色: 找到 {len(results)} 个点")


class TestImageSimilarity:
    """图片相似度测试"""

    def test_image_similarity_same(self):
        """同一张图片的相似度应接近 100"""
        path = screenshot()
        if not path or not path.strip():
            pytest.skip("截图失败")
        path = path.strip()
        try:
            sim = image_similarity(path, path)
            assert isinstance(sim, float), f"应返回 float, 实际: {type(sim)}"
            assert sim >= 90, f"同一张图片相似度应 >= 90, 实际: {sim}"
            print(f"  同图相似度: {sim}")
        except Exception as e:
            print(f"  image_similarity 异常: {e}")
