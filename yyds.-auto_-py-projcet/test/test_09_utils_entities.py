"""
测试模块 09: 工具函数与实体类
验证 MD5、日志、配置、数据实体等纯逻辑接口 (不依赖设备)
"""
import os
import tempfile
import pytest
from yyds import *


class TestMD5:
    """MD5 计算测试"""

    def test_md5_str(self):
        """文本 MD5"""
        result = md5_str("hello")
        assert isinstance(result, str), f"md5_str 应返回 str, 实际: {type(result)}"
        assert len(result) == 32, f"MD5 应为 32 位, 实际: {len(result)}"
        assert result == "5d41402abc4b2a76b9719d911017c592", f"'hello' 的 MD5 不匹配: {result}"

    def test_md5_file(self):
        """文件 MD5"""
        tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".txt", mode="w", encoding="utf-8")
        tmp.write("hello")
        tmp.close()
        try:
            result = md5_file(tmp.name)
            assert isinstance(result, str) and len(result) == 32, f"文件 MD5 格式不正确: {result!r}"
            assert result == "5d41402abc4b2a76b9719d911017c592", f"文件 MD5 不匹配: {result}"
        finally:
            os.unlink(tmp.name)


class TestLogUtils:
    """日志工具测试"""

    def test_log_d(self):
        """log_d 标准日志 — 不应抛异常"""
        try:
            log_d("测试日志消息")
        except Exception as e:
            pytest.fail(f"log_d 异常: {e}")

    def test_log_e(self):
        """log_e 错误日志 — 不应抛异常"""
        try:
            log_e("测试错误日志")
        except Exception as e:
            pytest.fail(f"log_e 异常: {e}")

    def test_format_time(self):
        """format_time 格式化时间"""
        t = format_time()
        assert isinstance(t, str), f"format_time 应返回 str, 实际: {type(t)}"
        assert len(t) > 0, "format_time 不应返回空串"
        print(f"  当前时间: {t}")


class TestEntityPoint:
    """Point 实体测试"""

    def test_point_creation(self):
        p = Point(100, 200)
        assert p.x == 100 and p.y == 200

    def test_point_str(self):
        p = Point(10, 20)
        assert "10" in str(p) and "20" in str(p)


class TestEntityColor:
    """Color 实体测试"""

    def test_color_creation(self):
        c = Color(255, 128, 0)
        assert c.r == 255 and c.g == 128 and c.b == 0

    def test_color_str(self):
        c = Color(255, 128, 0)
        s = str(c)
        assert "255" in s and "128" in s and "0" in s

    def test_color_similarity_same(self):
        """相同颜色相似度应为 1.0"""
        c1 = Color(100, 150, 200)
        c2 = Color(100, 150, 200)
        sim = c1.similarity_to(c2)
        assert abs(sim - 1.0) < 0.01, f"相同颜色相似度应接近 1.0, 实际: {sim}"

    def test_color_similarity_different(self):
        """不同颜色相似度应 < 1.0"""
        c1 = Color(255, 0, 0)
        c2 = Color(0, 0, 255)
        sim = c1.similarity_to(c2)
        assert sim < 1.0, f"红色与蓝色相似度应 < 1.0, 实际: {sim}"
        print(f"  红色 vs 蓝色 相似度: {sim:.4f}")


class TestEntityResFindImage:
    """ResFindImage 实体测试"""

    def test_res_find_image(self):
        r = ResFindImage("test", "/path/test.png", 0.95, 100, 50, 200, 300)
        assert r.name == "test"
        assert r.prob == 0.95
        assert r.cx == 250  # 200 + 100/2
        assert r.cy == 325  # 300 + 50/2


class TestProjectEnvironment:
    """ProjectEnvironment 测试"""

    def test_project_name(self):
        """项目名不应为空"""
        name = ProjectEnvironment.PROJECT_NAME
        assert name is not None and len(name) > 0, f"PROJECT_NAME 不应为空: {name!r}"
        print(f"  项目名: {name}")

    def test_debug_ip(self):
        """调试 IP 应已加载"""
        ip = ProjectEnvironment.DEBUG_IP
        assert ip is not None and len(ip) > 0, f"DEBUG_IP 不应为空: {ip!r}"
        print(f"  调试 IP: {ip}")

    def test_current_project(self):
        """当前项目名"""
        name = ProjectEnvironment.current_project()
        assert name is not None and len(name) > 0, f"current_project() 不应为空: {name!r}"
        print(f"  current_project: {name}")

    def test_import_java_flag(self):
        """PC 环境下 IMPORT_JAVA_SUCCESS 应为 False"""
        assert ProjectEnvironment.IMPORT_JAVA_SUCCESS is False, \
            "PC 环境下 IMPORT_JAVA_SUCCESS 应为 False"


class TestConfig:
    """Config 配置类测试"""

    def test_config_path(self):
        """配置文件路径"""
        path = Config.get_config_path()
        assert path is not None and len(path) > 0, f"config 路径不应为空: {path!r}"
        assert "Yyds.Py" in path, f"config 路径应包含 'Yyds.Py': {path}"
        print(f"  配置路径: {path}")

    def test_read_config_value_none(self):
        """读取不存在的配置应返回 None"""
        val = Config.read_config_value("nonexistent_key_999")
        assert val is None, f"不存在的 key 应返回 None, 实际: {val!r}"

    def test_read_ui_value(self):
        """读取 ui.yml 配置值"""
        val = Config.read_ui_value("text-notice")
        if val is not None:
            print(f"  ui.yml text-notice value: {val!r}")
        else:
            print("  ui.yml text-notice 未找到 (可能不在工程目录运行)")
