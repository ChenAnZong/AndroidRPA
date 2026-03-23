"""
引擎预导入环境 - 为 run-code-snippet 和交互式执行提供开箱即用的命名空间

当用户在 IDE 中选中代码片段运行时，自动注入此模块的所有符号到 __main__，
使 click(), ocr(), screenshot() 等官方 API 以及 os, time, json 等常用库
无需手动 import 即可直接使用。
"""
import os
import sys
import time
import json
import re
import random
import threading
import traceback

try:
    import requests
except ImportError:
    pass

try:
    import yaml
except ImportError:
    pass

from yyds import *
from yyds.console_shim import console
