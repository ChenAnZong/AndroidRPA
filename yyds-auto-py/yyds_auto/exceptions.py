"""yyds-auto 异常定义"""


class YydsError(Exception):
    """yyds-auto 基础异常"""


class AdbError(YydsError):
    """ADB 相关错误"""


class AdbNotFoundError(AdbError):
    """找不到 ADB 可执行文件"""


class DeviceNotFoundError(YydsError):
    """找不到指定设备"""


class DeviceOfflineError(YydsError):
    """设备离线"""


class EngineNotRunningError(YydsError):
    """引擎未运行且无法自动启动"""


class EngineStartError(YydsError):
    """引擎启动失败"""


class AppNotInstalledError(YydsError):
    """yyds.auto APK 未安装"""


class ConnectError(YydsError):
    """连接设备失败"""


class RequestError(YydsError):
    """HTTP 请求失败"""

    def __init__(self, url: str, status: int = 0, body: str = ""):
        self.url = url
        self.status = status
        self.body = body
        super().__init__(f"HTTP {status} {url}: {body[:200]}")


class UiObjectNotFoundError(YydsError):
    """UI 元素未找到"""

    def __init__(self, selector=None):
        self.selector = selector
        msg = f"UiObject not found: {selector}" if selector else "UiObject not found"
        super().__init__(msg)


class SessionBrokenError(YydsError):
    """与设备的会话已断开"""
