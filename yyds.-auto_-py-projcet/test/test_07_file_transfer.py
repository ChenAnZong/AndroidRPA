"""
测试模块 07: 文件传输
验证 PC 与设备之间的文件推送和拉取接口
"""
import os
import tempfile
import pytest
from yyds import *


class TestFileTransfer:
    """文件传输测试"""

    def test_post_file(self):
        """推送文件到设备"""
        # 创建临时测试文件
        tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".txt", mode="w", encoding="utf-8")
        tmp.write("yyds_test_content_12345")
        tmp.close()
        try:
            result = post_file(tmp.name, "/sdcard")
            assert result is True, f"post_file 应返回 True, 实际: {result}"
            print(f"  推送文件成功: {os.path.basename(tmp.name)} -> /sdcard/")
        finally:
            os.unlink(tmp.name)

    def test_pull_file(self):
        """从设备拉取文件"""
        # 先确保设备上有截图文件
        screenshot()
        local_path = os.path.join(tempfile.gettempdir(), "yyds_pull_test.png")
        try:
            result = pull_file("/sdcard/screenshot.png", local_path)
            assert result is True, f"pull_file 应返回 True, 实际: {result}"
            assert os.path.exists(local_path), f"拉取的文件应存在: {local_path}"
            size = os.path.getsize(local_path)
            assert size > 0, f"拉取的文件大小应 > 0, 实际: {size}"
            print(f"  拉取文件成功: {local_path}, 大小: {size} bytes")
        finally:
            if os.path.exists(local_path):
                os.unlink(local_path)

    def test_pull_file_not_found(self):
        """拉取不存在的文件"""
        local_path = os.path.join(tempfile.gettempdir(), "yyds_pull_notfound.txt")
        result = pull_file("/sdcard/this_file_should_not_exist_999.txt", local_path)
        assert result is False, f"拉取不存在的文件应返回 False, 实际: {result}"
        # 清理可能生成的空文件
        if os.path.exists(local_path):
            os.unlink(local_path)
