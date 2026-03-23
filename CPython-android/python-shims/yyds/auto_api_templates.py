"""
Yyds.Auto 常用场景模板 — 一行代码完成常见自动化任务

面向不熟悉编程的用户, 将多步操作封装为单个函数调用。
每个模板函数都有详细的中文说明和参数提示。

用法::

    from yyds import *

    # 打开微信并等待首页加载
    启动并等待("com.tencent.mm", 等待文字="微信")

    # 滑动找到某个文字并点击它
    滑动找字并点击("设置")

    # 循环点击直到某个界面出现
    重复点击直到(540, 1200, 出现文字="登录成功")
"""
import time
from typing import Optional, Union, List, Tuple

from .auto_api import click, screenshot, toast
from .auto_api_aux import (
    open_app, stop_app, is_in_app, swipe,
    screen_ocr_x, ui_match, ui_exist, shell,
    input_text, set_clipboard,
)
from .auto_plus import (
    sleep, swipe_up, swipe_down, click_target,
    ocr_click_if_found, DeviceScreen,
)
from .auto_api_ext import (
    wait_for_text, wait_for_ui, wait_for_package,
    long_press, wait_and_click_text, wait_and_click_ui,
    swipe_to_find_text,
)
from .auto_entity import Node, ResOcr
from .util import log_d, log_e, YydsParamError


def 启动并等待(包名: str, 等待秒数: float = 15, 等待文字: str = None, **控件条件) -> bool:
    """
    打开应用并等待它加载完成

    :param 包名: 应用包名, 如 "com.tencent.mm"
    :param 等待秒数: 最多等待多少秒
    :param 等待文字: 等待屏幕出现指定文字 (OCR识别)
    :param 控件条件: 等待屏幕出现指定控件, 如 text="微信", cls="android.widget.TextView"
    :returns: 是否成功等待到目标

    用法::

        # 打开微信, 等待出现"微信"文字
        启动并等待("com.tencent.mm", 等待文字="微信")

        # 打开设置, 等待出现指定控件
        启动并等待("com.android.settings", text="WLAN")
    """
    open_app(包名)
    deadline = time.time() + 等待秒数

    if 等待文字:
        while time.time() < deadline:
            results = screen_ocr_x(等待文字)
            if results and len(results) > 0:
                log_d(f"启动并等待: 已找到文字 '{等待文字}'")
                return True
            sleep(0.5)
        log_e(f"启动并等待: {等待秒数}秒内未找到文字 '{等待文字}'")
        return False

    if 控件条件:
        while time.time() < deadline:
            if ui_exist(**控件条件):
                log_d(f"启动并等待: 已找到控件 {控件条件}")
                return True
            sleep(0.5)
        log_e(f"启动并等待: {等待秒数}秒内未找到控件 {控件条件}")
        return False

    # 没有指定等待条件, 等待应用进入前台
    result = wait_for_package(包名, timeout=等待秒数)
    return result is not None


def 关闭并重启(包名: str, 等待秒数: float = 15, 等待文字: str = None, **控件条件) -> bool:
    """
    强制关闭应用后重新打开, 并等待加载完成

    :param 包名: 应用包名
    :param 等待秒数: 最多等待多少秒
    :param 等待文字: 等待屏幕出现指定文字
    :returns: 是否成功

    用法::

        关闭并重启("com.tencent.mm", 等待文字="微信")
    """
    stop_app(包名)
    sleep(1)
    return 启动并等待(包名, 等待秒数=等待秒数, 等待文字=等待文字, **控件条件)


def 滑动找字并点击(文字: str, 方向: str = "up", 最大滑动次数: int = 10,
               间隔: float = 0.8) -> bool:
    """
    反复滑动屏幕直到找到指定文字, 然后点击它

    :param 文字: 要查找并点击的文字
    :param 方向: 滑动方向 "up"(向上翻) 或 "down"(向下翻)
    :param 最大滑动次数: 最多滑动几次
    :param 间隔: 每次滑动后等待秒数
    :returns: 是否找到并点击成功

    用法::

        滑动找字并点击("隐私设置")
        滑动找字并点击("关于手机", 方向="down", 最大滑动次数=20)
    """
    results = swipe_to_find_text(文字, direction=方向, max_swipes=最大滑动次数, interval=间隔)
    if results and len(results) > 0:
        click_target(results[0])
        return True
    log_e(f"滑动找字并点击: 滑动{最大滑动次数}次后未找到 '{文字}'")
    return False


def 重复点击直到(x: int, y: int, 最大次数: int = 20, 间隔: float = 1.0,
             出现文字: str = None, 消失文字: str = None, **控件条件) -> bool:
    """
    反复点击某个坐标, 直到屏幕出现/消失指定内容

    :param x: 点击坐标 x
    :param y: 点击坐标 y
    :param 最大次数: 最多点击几次
    :param 间隔: 每次点击后等待秒数
    :param 出现文字: 等待屏幕出现此文字后停止
    :param 消失文字: 等待屏幕此文字消失后停止
    :returns: 是否达成条件

    用法::

        # 反复点击登录按钮, 直到出现"登录成功"
        重复点击直到(540, 1800, 出现文字="登录成功")

        # 反复点击关闭按钮, 直到广告消失
        重复点击直到(980, 200, 消失文字="广告")
    """
    for i in range(最大次数):
        click(x, y)
        sleep(间隔)

        if 出现文字:
            results = screen_ocr_x(出现文字)
            if results and len(results) > 0:
                log_d(f"重复点击直到: 第{i+1}次点击后找到 '{出现文字}'")
                return True

        if 消失文字:
            results = screen_ocr_x(消失文字)
            if not results or len(results) == 0:
                log_d(f"重复点击直到: 第{i+1}次点击后 '{消失文字}' 已消失")
                return True

        if 控件条件:
            if ui_exist(**控件条件):
                log_d(f"重复点击直到: 第{i+1}次点击后找到控件")
                return True

    log_e(f"重复点击直到: 点击{最大次数}次后仍未达成条件")
    return False


def 输入中文(文字: str):
    """
    输入中文文字 (通过剪贴板粘贴方式)

    普通的 input_text 不支持中文, 此函数通过剪贴板实现中文输入。

    :param 文字: 要输入的中文文字

    用法::

        输入中文("你好世界")
    """
    set_clipboard(文字)
    # 模拟 Ctrl+V 粘贴
    shell("input keyevent 279")  # KEYCODE_PASTE


def 条件等待循环(检查函数, 超时秒数: float = 30, 间隔: float = 1.0,
             超时提示: str = "等待超时") -> bool:
    """
    通用条件等待 — 反复执行检查函数直到返回 True 或超时

    :param 检查函数: 一个返回 True/False 的函数
    :param 超时秒数: 最多等待多少秒
    :param 间隔: 每次检查间隔秒数
    :param 超时提示: 超时后的日志提示
    :returns: 是否在超时前满足条件

    用法::

        # 等待直到屏幕上出现"完成"
        条件等待循环(lambda: 文字任一存在("完成"), 超时秒数=60)

        # 等待直到不在微信内
        条件等待循环(lambda: not 是否在应用内("com.tencent.mm"))
    """
    deadline = time.time() + 超时秒数
    while time.time() < deadline:
        try:
            if 检查函数():
                return True
        except Exception as e:
            log_e(f"条件等待循环: 检查函数异常 {e}")
        sleep(间隔)
    log_e(f"条件等待循环: {超时提示} ({超时秒数}秒)")
    return False


def 安全点击文字(文字: str, 超时: float = 10, 找不到则跳过: bool = True) -> bool:
    """
    安全地等待并点击屏幕上的文字, 找不到不会报错

    :param 文字: 要点击的文字
    :param 超时: 最多等待秒数
    :param 找不到则跳过: 找不到时是否静默跳过 (True=跳过, False=抛异常)
    :returns: 是否成功点击

    用法::

        安全点击文字("同意")
        安全点击文字("下一步", 超时=5)
    """
    deadline = time.time() + 超时
    while time.time() < deadline:
        results = screen_ocr_x(文字)
        if results and len(results) > 0:
            click_target(results[0])
            return True
        sleep(0.5)

    if not 找不到则跳过:
        raise YydsParamError(f"安全点击文字: {超时}秒内未找到文字 '{文字}'")
    log_d(f"安全点击文字: 未找到 '{文字}', 已跳过")
    return False


def 批量点击文字(*文字列表: str, 间隔: float = 1.0, 每个超时: float = 5):
    """
    按顺序依次点击多个文字 — 适合引导页、多步确认等场景

    :param 文字列表: 要依次点击的文字
    :param 间隔: 每次点击后等待秒数
    :param 每个超时: 每个文字最多等待秒数

    用法::

        # 跳过引导页
        批量点击文字("同意", "下一步", "下一步", "开始使用")

        # 多步确认
        批量点击文字("确定", "是", "完成")
    """
    for 文字 in 文字列表:
        安全点击文字(文字, 超时=每个超时)
        sleep(间隔)


__all__ = [
    "启动并等待", "关闭并重启",
    "滑动找字并点击",
    "重复点击直到",
    "输入中文",
    "条件等待循环",
    "安全点击文字", "批量点击文字",
]
