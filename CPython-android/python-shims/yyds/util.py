import sys
import time
import warnings


class YydsParamError(TypeError):
    """SDK 参数校验错误 — 提供友好的中文提示"""
    pass


def _check_xy(name: str, x, y):
    """校验坐标参数"""
    if x is None or y is None:
        raise YydsParamError(f"{name}(): 坐标不能为 None, 收到 x={x!r}, y={y!r}")
    if isinstance(x, float) and not x.is_integer():
        warnings.warn(f"{name}(): x={x} 是小数, 坐标应为整数像素值, 已自动取整", stacklevel=3)
    if isinstance(y, float) and not y.is_integer():
        warnings.warn(f"{name}(): y={y} 是小数, 坐标应为整数像素值, 已自动取整", stacklevel=3)
    try:
        int(x); int(y)
    except (ValueError, TypeError):
        raise YydsParamError(f"{name}(): 坐标必须是数字, 收到 x={x!r} (类型 {type(x).__name__}), y={y!r} (类型 {type(y).__name__})")


def _check_str(name: str, param_name: str, value, allow_empty=False):
    """校验字符串参数"""
    if not isinstance(value, str):
        raise YydsParamError(f"{name}(): {param_name} 应为字符串, 收到 {value!r} (类型 {type(value).__name__})")
    if not allow_empty and not value.strip():
        raise YydsParamError(f"{name}(): {param_name} 不能为空字符串")


def _check_positive(name: str, param_name: str, value):
    """校验正数参数"""
    if not isinstance(value, (int, float)):
        raise YydsParamError(f"{name}(): {param_name} 应为数字, 收到 {value!r} (类型 {type(value).__name__})")
    if value < 0:
        raise YydsParamError(f"{name}(): {param_name} 不能为负数, 收到 {value}")


def format_time():
    """
    获取格式化的时间, 格式样式如:2024.02.04 12:56:31
    """
    return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime())


def log_d(*objs):
    """
    打印标准日志, 在Yyds.Auto开发插件中一般日志显示为灰色
    """
    msg = format_time() + "\t" + " ".join([str(i) for i in objs])
    try:
        print(msg, file=sys.stdout)
    except UnicodeEncodeError:
        print(msg.encode(sys.stdout.encoding or 'utf-8', errors='replace').decode(sys.stdout.encoding or 'utf-8', errors='replace'), file=sys.stdout)


def log_e(*objs):
    """
    打印错误日志, 在Yyds.Auto开发插件中一般日志显示为红色
    """
    msg = format_time() + "\t" + " ".join([str(i) for i in objs])
    try:
        print(msg, file=sys.stderr)
    except UnicodeEncodeError:
        print(msg.encode(sys.stderr.encoding or 'utf-8', errors='replace').decode(sys.stderr.encoding or 'utf-8', errors='replace'), file=sys.stderr)
