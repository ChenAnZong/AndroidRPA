"""
pyengine 兼容层 - 替代 Chaquopy 的 Java 包自动桥接

在 Chaquopy 中，`from pyengine import PyOut` 直接导入 Java 类 pyengine.PyOut。
在 CPython 嵌入式模式下，通过此 Python shim 模块提供等价接口，
内部通过 _yyds_bridge C 扩展模块回调 Java 侧的 PyOut。

用法（与 Chaquopy 完全一致）:
    from pyengine import PyOut
    PyOut.out("hello")
    PyOut.err("error")
"""

import _yyds_bridge


class PyOut:
    """日志输出管理 - 对标 Java 侧 pyengine.PyOut"""

    @staticmethod
    def out(text):
        """标准输出日志，转发到 Java PyOut.out()"""
        _yyds_bridge.log_out(str(text))

    @staticmethod
    def err(text):
        """错误输出日志，转发到 Java PyOut.err()"""
        _yyds_bridge.log_err(str(text))
