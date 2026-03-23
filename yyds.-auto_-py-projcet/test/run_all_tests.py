"""
Yyds.Auto API 全量测试入口
用法:
  cd yyds.-auto_-py-projcet
  python -m pytest test/ -v --tb=short -s

或直接运行此文件:
  python test/run_all_tests.py

前置条件:
  1. ADB 已连接:  adb devices
  2. 端口已转发:  adb forward tcp:61140 tcp:61140
  3. Yyds.Auto App 已启动, 三个引擎进程运行中 (yyds.keep, yyds.auto, yyds.py)
  4. 已安装依赖:  pip install pytest requests pyyaml pillow
"""
import sys
import os

# 确保项目根目录在 path 中
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)

if __name__ == "__main__":
    import pytest
    # -v 详细输出, --tb=short 简短回溯, -s 打印 print 输出
    exit_code = pytest.main([
        os.path.join(PROJECT_ROOT, "test"),
        "-v",
        "--tb=short",
        "-s",
        "--no-header",
    ])
    sys.exit(exit_code)
