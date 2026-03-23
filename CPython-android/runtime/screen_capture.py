"""
截图模块 - 替代 ScreenCapture.getBitmapData()
在 ROOT/SHELL 权限下使用 screencap 命令获取截图
也可通过 yyds.auto 代理获取
"""

import os
import subprocess
import time
from typing import Optional


class ScreenCapture:
    """截图工具，使用 shell 命令或 yyds.auto 代理"""

    def __init__(self):
        self._last_data = b""
        self._last_size = 0
        self._tmp_dir = "/data/local/tmp"

    def get_bitmap_data(self, quality: int = 100) -> bytes:
        """获取 JPEG 格式截图数据"""
        try:
            tmp_file = f"{self._tmp_dir}/.yyds_screen_{quality}.jpg"
            # screencap 输出 PNG，用 shell 管道转 JPEG
            # 方案1: 直接 screencap -p 输出 PNG
            ret = subprocess.run(
                ["screencap", "-p", f"{self._tmp_dir}/.yyds_screen.png"],
                capture_output=True, timeout=5
            )
            if ret.returncode != 0:
                return b""

            png_file = f"{self._tmp_dir}/.yyds_screen.png"
            if not os.path.exists(png_file):
                return b""

            # 如果需要 JPEG 且有 PIL
            if quality < 100:
                try:
                    from PIL import Image
                    import io
                    img = Image.open(png_file)
                    buf = io.BytesIO()
                    img.save(buf, format='JPEG', quality=quality)
                    data = buf.getvalue()
                    self._last_data = data
                    self._last_size = len(data)
                    return data
                except ImportError:
                    pass

            # 直接返回 PNG 数据
            with open(png_file, 'rb') as f:
                data = f.read()
            self._last_data = data
            self._last_size = len(data)
            return data

        except Exception as e:
            print(f"[ScreenCapture] 截图失败: {e}")
            return b""

    def write_to(self, save_path: str) -> bool:
        """截图保存到指定路径"""
        try:
            ret = subprocess.run(
                ["screencap", "-p", save_path],
                capture_output=True, timeout=5
            )
            return ret.returncode == 0 and os.path.exists(save_path)
        except Exception as e:
            print(f"[ScreenCapture] 截图保存失败: {e}")
            return False

    @property
    def last_size(self) -> int:
        return self._last_size


# 全局单例
screen_capture = ScreenCapture()
