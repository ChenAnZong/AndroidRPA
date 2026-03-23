"""
Yyds.Auto 扩展 API — 高级自动化接口
提供等待、手势、控件操作、滑动查找、屏幕变化检测等高级功能

竞品对标: AutoJS, 懒人精灵, 按键精灵, EasyClick
"""
import time
import random
import hashlib
from typing import Union, Optional, List, Tuple, Callable
from .auto_api import *
from .auto_api_aux import *
from .util import *
from .util import _check_xy, _check_str, _check_positive, YydsParamError


# ============================================================
# P0: 等待类 API — 自动化稳定性核心
# ============================================================

def wait_for_text(
    *text: str,
    timeout: float = 10,
    interval: float = 0.5,
    x=None, y=None, w=None, h=None,
    match_all: bool = False,
) -> Optional[Tuple[ResOcr]]:
    """
    等待屏幕出现指定文字, 出现后立即返回

    :param text: 要等待的文字 (支持正则), 可传入多个
    :param timeout: 超时秒数, 超时返回 None
    :param interval: 轮询间隔秒数
    :param x: 识别区域起始 x
    :param y: 识别区域起始 y
    :param w: 识别区域宽
    :param h: 识别区域高
    :param match_all: True=所有文字都出现才返回, False=任意一个出现即返回
    :returns: OCR 结果元组, 超时返回 None
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        results = screen_ocr_x(text, x=x, y=y, w=w, h=h)
        if match_all:
            if len(results) >= len(text):
                return results
        else:
            if len(results) > 0:
                return results
        time.sleep(interval)
    return None


def wait_for_text_gone(
    *text: str,
    timeout: float = 10,
    interval: float = 0.5,
    x=None, y=None, w=None, h=None,
) -> bool:
    """
    等待屏幕上的指定文字消失

    :param text: 要等待消失的文字 (支持正则)
    :param timeout: 超时秒数
    :param interval: 轮询间隔秒数
    :returns: True=文字已消失, False=超时仍存在
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        results = screen_ocr_x(text, x=x, y=y, w=w, h=h)
        if len(results) == 0:
            return True
        time.sleep(interval)
    return False


def wait_for_ui(
    timeout: float = 10,
    interval: float = 0.5,
    **match_params,
) -> Optional[List[Node]]:
    """
    等待屏幕出现符合条件的 UI 控件

    用法示例::


        # 等待出现 "确定" 按钮, 最多等 5 秒
        nodes = wait_for_ui(text="确定", timeout=5)
        if nodes:
            nodes[0].click()

    :param timeout: 超时秒数
    :param interval: 轮询间隔秒数
    :param match_params: 控件匹配参数, 同 ui_match()
    :returns: 匹配到的节点列表, 超时返回 None
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = ui_match(**match_params)
        if len(nodes) > 0:
            return nodes
        time.sleep(interval)
    return None


def wait_for_ui_gone(
    timeout: float = 10,
    interval: float = 0.5,
    **match_params,
) -> bool:
    """
    等待指定 UI 控件消失

    :param timeout: 超时秒数
    :param interval: 轮询间隔秒数
    :param match_params: 控件匹配参数, 同 ui_match()
    :returns: True=控件已消失, False=超时仍存在
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = ui_match(**match_params)
        if len(nodes) == 0:
            return True
        time.sleep(interval)
    return False


def wait_for_image(
    *img: str,
    timeout: float = 10,
    interval: float = 1.0,
    min_prob: float = 0.8,
    x=None, y=None, w=None, h=None,
    threshold: int = -1,
) -> Optional[Tuple[ResFindImage]]:
    """
    等待屏幕出现指定图片

    :param img: 图片路径 (相对工程目录)
    :param timeout: 超时秒数
    :param interval: 轮询间隔秒数
    :param min_prob: 最低置信率
    :param threshold: 图片预处理方式, 参考 screen_find_image()
    :returns: 找图结果, 超时返回 None
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        results = screen_find_image_x(img, min_prob=min_prob, x=x, y=y, w=w, h=h, threshold=threshold)
        if len(results) > 0:
            return results
        time.sleep(interval)
    return None


def wait_for_activity(
    activity: str,
    timeout: float = 10,
    interval: float = 0.5,
) -> bool:
    """
    等待进入指定 Activity 界面

    :param activity: Activity 名 (支持部分匹配)
    :param timeout: 超时秒数
    :param interval: 轮询间隔秒数
    :returns: True=已进入, False=超时
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        cur = device_foreground_activity()
        if activity in cur:
            return True
        time.sleep(interval)
    return False


def wait_for_package(
    package: str,
    timeout: float = 10,
    interval: float = 0.5,
) -> bool:
    """
    等待进入指定应用

    :param package: 应用包名
    :param timeout: 超时秒数
    :param interval: 轮询间隔秒数
    :returns: True=已进入, False=超时
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        cur = device_foreground_package()
        if cur.strip() == package:
            return True
        time.sleep(interval)
    return False


# ============================================================
# P0: long_press / gesture — 手势操作
# ============================================================

def long_press(x: int, y: int, duration: int = 500):
    """
    长按指定坐标

    :param x: 屏幕坐标 x
    :param y: 屏幕坐标 y
    :param duration: 长按时长 (毫秒), 默认 500ms
    """
    _check_xy("long_press", x, y)
    _check_positive("long_press", "duration(长按时长)", duration)
    # 利用 swipe 实现长按: 起点终点相同, duration 控制按压时长
    swipe(x, y, x, y, duration)


def gesture(points: List[Tuple[int, int]], duration: int = 300):
    """
    沿路径点执行手势滑动 (模拟贝塞尔曲线手势)

    :param points: 路径坐标点列表, 如 [(100,200), (300,400), (500,600)]
    :param duration: 总手势时长 (毫秒)
    """
    if not isinstance(points, (list, tuple)) or len(points) < 2:
        raise YydsParamError(
            f"gesture(): points 需要至少2个坐标点, 如 [(100,200), (300,400)], 收到 {points!r}"
        )
    for i, p in enumerate(points):
        if not isinstance(p, (list, tuple)) or len(p) != 2:
            raise YydsParamError(
                f"gesture(): points[{i}] 应为 (x, y) 格式, 收到 {p!r}"
            )
    _check_positive("gesture", "duration(手势时长)", duration)
    # 将路径拆分为多段 swipe, 每段均分时间
    segment_duration = max(50, duration // (len(points) - 1))
    for i in range(len(points) - 1):
        x1, y1 = points[i]
        x2, y2 = points[i + 1]
        swipe(x1, y1, x2, y2, segment_duration)


def pinch_in(cx: int, cy: int, distance: int = 300, duration: int = 400):
    """
    双指捏合 (缩小) — 通过两次快速滑动模拟

    :param cx: 中心点 x
    :param cy: 中心点 y
    :param distance: 起始两指间距 (像素)
    :param duration: 手势时长 (毫秒)
    """
    half = distance // 2
    # 先执行一个从外到中心的滑动
    swipe(cx - half, cy, cx - 30, cy, duration)
    swipe(cx + half, cy, cx + 30, cy, duration)


def pinch_out(cx: int, cy: int, distance: int = 300, duration: int = 400):
    """
    双指张开 (放大) — 通过两次快速滑动模拟

    :param cx: 中心点 x
    :param cy: 中心点 y
    :param distance: 目标两指间距 (像素)
    :param duration: 手势时长 (毫秒)
    """
    half = distance // 2
    swipe(cx - 30, cy, cx - half, cy, duration)
    swipe(cx + 30, cy, cx + half, cy, duration)


# ============================================================
# P1: 滑动查找 — 列表/长页面场景必备
# ============================================================

def swipe_to_find_text(
    *text: str,
    direction: str = "up",
    max_swipes: int = 10,
    interval: float = 0.8,
    x=None, y=None, w=None, h=None,
) -> Optional[Tuple[ResOcr]]:
    """
    反复滑动直到找到指定文字

    :param text: 要查找的文字 (支持正则)
    :param direction: 滑动方向 "up"=向上翻(看下方内容), "down"=向下翻(看上方内容)
    :param max_swipes: 最大滑动次数
    :param interval: 每次滑动后等待秒数
    :param x: OCR 识别区域
    :param y: OCR 识别区域
    :param w: OCR 识别区域
    :param h: OCR 识别区域
    :returns: OCR 结果, 未找到返回 None
    """
    _VALID_DIRECTIONS = ("up", "down", "left", "right")
    if direction not in _VALID_DIRECTIONS:
        raise YydsParamError(
            f'swipe_to_find_text(): direction 应为 {_VALID_DIRECTIONS} 之一, 收到 {direction!r}'
        )
    if not text:
        raise YydsParamError("swipe_to_find_text(): 至少传入一个要查找的文字")
    from .auto_plus import DeviceScreen, swipe_up, swipe_down
    DeviceScreen._ensure_init()

    for i in range(max_swipes):
        results = screen_ocr_x(text, x=x, y=y, w=w, h=h)
        if len(results) > 0:
            log_d(f"滑动第{i}次找到文字: {results[0].text}")
            return results
        if direction == "up":
            swipe_up()
        else:
            swipe_down()
        time.sleep(interval)
    return None


def swipe_to_find_ui(
    direction: str = "up",
    max_swipes: int = 10,
    interval: float = 0.8,
    **match_params,
) -> Optional[List[Node]]:
    """
    反复滑动直到找到指定 UI 控件

    :param direction: 滑动方向 "up" 或 "down"
    :param max_swipes: 最大滑动次数
    :param interval: 每次滑动后等待秒数
    :param match_params: 控件匹配参数, 同 ui_match()
    :returns: 匹配到的节点列表, 未找到返回 None
    """
    _VALID_DIRECTIONS = ("up", "down", "left", "right")
    if direction not in _VALID_DIRECTIONS:
        raise YydsParamError(
            f'swipe_to_find_ui(): direction 应为 {_VALID_DIRECTIONS} 之一, 收到 {direction!r}'
        )
    if not match_params:
        raise YydsParamError("swipe_to_find_ui(): 至少传入一个控件匹配条件, 如 text='登录'")
    from .auto_plus import DeviceScreen, swipe_up, swipe_down
    DeviceScreen._ensure_init()

    for i in range(max_swipes):
        nodes = ui_match(**match_params)
        if len(nodes) > 0:
            log_d(f"滑动第{i}次找到控件: {nodes[0].class_name} text={nodes[0].text}")
            return nodes
        if direction == "up":
            swipe_up()
        else:
            swipe_down()
        time.sleep(interval)
    return None


def swipe_to_find_image(
    *img: str,
    direction: str = "up",
    max_swipes: int = 10,
    interval: float = 1.0,
    min_prob: float = 0.8,
    threshold: int = -1,
) -> Optional[Tuple[ResFindImage]]:
    """
    反复滑动直到找到指定图片

    :param img: 图片路径
    :param direction: 滑动方向
    :param max_swipes: 最大滑动次数
    :param interval: 每次滑动后等待秒数
    :param min_prob: 最低置信率
    :param threshold: 图片预处理方式
    :returns: 找图结果, 未找到返回 None
    """
    from .auto_plus import DeviceScreen, swipe_up, swipe_down
    DeviceScreen._ensure_init()

    for i in range(max_swipes):
        results = screen_find_image_x(img, min_prob=min_prob, threshold=threshold)
        if len(results) > 0:
            log_d(f"滑动第{i}次找到图片: {results[0].name}")
            return results
        if direction == "up":
            swipe_up()
        else:
            swipe_down()
        time.sleep(interval)
    return None


# ============================================================
# P1: 屏幕变化检测
# ============================================================

def wait_screen_change(
    timeout: float = 10,
    interval: float = 0.3,
    threshold: float = 0.95,
) -> bool:
    """
    等待屏幕发生变化 (页面跳转、加载完成等)

    :param timeout: 超时秒数
    :param interval: 检测间隔秒数
    :param threshold: 相似度阈值 (低于此值认为发生了变化), 0-1.0
    :returns: True=屏幕发生了变化, False=超时未变化
    """
    path1 = "/data/local/tmp/_yyds_sc1.png"
    path2 = "/data/local/tmp/_yyds_sc2.png"
    screenshot(path1)
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(interval)
        screenshot(path2)
        try:
            sim = image_similarity(path1, path2)
            if sim < threshold:
                return True
        except:
            pass
    return False


def wait_screen_stable(
    stable_duration: float = 1.0,
    timeout: float = 10,
    interval: float = 0.3,
    threshold: float = 0.98,
) -> bool:
    """
    等待屏幕稳定 (不再变化), 常用于等待页面加载完成

    :param stable_duration: 连续稳定的时长 (秒), 在此时间内屏幕无变化则认为稳定
    :param timeout: 超时秒数
    :param interval: 检测间隔秒数
    :param threshold: 相似度阈值 (高于此值认为未变化), 0-1.0
    :returns: True=屏幕已稳定, False=超时仍在变化
    """
    path1 = "/data/local/tmp/_yyds_stable1.png"
    path2 = "/data/local/tmp/_yyds_stable2.png"
    screenshot(path1)
    stable_start = time.time()
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(interval)
        screenshot(path2)
        try:
            sim = image_similarity(path1, path2)
            if sim >= threshold:
                if time.time() - stable_start >= stable_duration:
                    return True
            else:
                stable_start = time.time()
                screenshot(path1)
        except:
            stable_start = time.time()
    return False


# ============================================================
# P2: 补充接口
# ============================================================

def app_uninstall(pkg: str) -> bool:
    """
    卸载应用

    :param pkg: 应用包名
    :returns: 是否卸载成功
    """
    result = shell(f"pm uninstall {pkg}")
    return "Success" in result


def app_clear_data(pkg: str) -> bool:
    """
    清除应用数据 (相当于 "清除存储")

    :param pkg: 应用包名
    :returns: 是否成功
    """
    result = shell(f"pm clear {pkg}")
    return "Success" in result


def grant_permission(pkg: str, permission: str) -> bool:
    """
    授予应用权限

    :param pkg: 应用包名
    :param permission: 权限名, 如 android.permission.READ_EXTERNAL_STORAGE
    :returns: 是否操作成功
    """
    result = shell(f"pm grant {pkg} {permission}")
    return "Exception" not in result


def revoke_permission(pkg: str, permission: str) -> bool:
    """
    撤销应用权限

    :param pkg: 应用包名
    :param permission: 权限名
    :returns: 是否操作成功
    """
    result = shell(f"pm revoke {pkg} {permission}")
    return "Exception" not in result


def get_notifications() -> str:
    """
    读取当前通知栏内容 (需要 ROOT/SHELL 权限)

    :returns: 通知内容的原始文本
    """
    return shell("dumpsys notification --noredact")


def get_wifi_info() -> dict:
    """
    获取当前 WiFi 连接信息

    :returns: 包含 ssid, ip, link_speed 等信息的字典
    """
    raw = shell("dumpsys wifi | grep 'mWifiInfo'")
    info = {"raw": raw.strip()}
    import re
    ssid_match = re.search(r'SSID: ([^,]+)', raw)
    if ssid_match:
        info["ssid"] = ssid_match.group(1).strip()
    ip_match = re.search(r'IP: ([^,]+)', raw)
    if ip_match:
        info["ip"] = ip_match.group(1).strip()
    return info


def set_wifi_enabled(enabled: bool):
    """
    开关 WiFi

    :param enabled: True=打开, False=关闭
    """
    shell(f"svc wifi {'enable' if enabled else 'disable'}")


def set_airplane_mode(enabled: bool):
    """
    开关飞行模式

    :param enabled: True=打开, False=关闭
    """
    value = "1" if enabled else "0"
    shell(f"settings put global airplane_mode_on {value}",
          f"am broadcast -a android.intent.action.AIRPLANE_MODE --ez state {str(enabled).lower()}")


def get_screen_orientation() -> int:
    """
    获取当前屏幕旋转方向

    :returns: 0=竖屏, 1=横屏(左转), 2=反向竖屏, 3=横屏(右转)
    """
    result = shell("settings get system user_rotation")
    try:
        return int(result.strip())
    except:
        return 0


def set_screen_brightness(level: int):
    """
    设置屏幕亮度

    :param level: 亮度值 0-255
    """
    level = max(0, min(255, level))
    shell(f"settings put system screen_brightness {level}")


def get_battery_info() -> dict:
    """
    获取电池信息

    :returns: 包含 level, status, temperature 等信息的字典
    """
    raw = shell("dumpsys battery")
    info = {"raw": raw.strip()}
    import re
    for key in ("level", "status", "temperature", "voltage"):
        match = re.search(rf'{key}: (\d+)', raw)
        if match:
            info[key] = int(match.group(1))
    return info


def wait_and_click_text(
    *text: str,
    timeout: float = 10,
    interval: float = 0.5,
    x=None, y=None, w=None, h=None,
) -> bool:
    """
    等待文字出现并点击 — 最常用的复合操作

    :param text: 要等待并点击的文字
    :param timeout: 超时秒数
    :param interval: 轮询间隔
    :returns: True=找到并点击, False=超时
    """
    results = wait_for_text(*text, timeout=timeout, interval=interval, x=x, y=y, w=w, h=h)
    if results and len(results) > 0:
        target = results[-1]
        click(target.cx, target.cy)
        log_d(f"等待点击文字: {target.text} -> ({target.cx}, {target.cy})")
        return True
    return False


def wait_and_click_ui(
    timeout: float = 10,
    interval: float = 0.5,
    **match_params,
) -> bool:
    """
    等待控件出现并点击 — 最常用的复合操作

    :param timeout: 超时秒数
    :param interval: 轮询间隔
    :param match_params: 控件匹配参数, 同 ui_match()
    :returns: True=找到并点击, False=超时
    """
    nodes = wait_for_ui(timeout=timeout, interval=interval, **match_params)
    if nodes and len(nodes) > 0:
        target = nodes[0]
        click(target.cx, target.cy)
        log_d(f"等待点击控件: {target.class_name} text={target.text} -> ({target.cx}, {target.cy})")
        return True
    return False


def scale_pos(x: int, y: int, base_w: int = 1080, base_h: int = 2400) -> Tuple[int, int]:
    """
    通用分辨率适配 — 将基准分辨率坐标转换为当前设备坐标

    :param x: 基准分辨率下的 x 坐标
    :param y: 基准分辨率下的 y 坐标
    :param base_w: 基准屏幕宽 (默认 1080)
    :param base_h: 基准屏幕高 (默认 2400)
    :returns: 适配后的 (x, y) 坐标
    """
    from .auto_plus import DeviceScreen
    DeviceScreen._ensure_init()
    return int(DeviceScreen._dw * x / base_w), int(DeviceScreen._dh * y / base_h)


def click_scaled(x: int, y: int, base_w: int = 1080, base_h: int = 2400):
    """
    分辨率自适应点击 — 传入基准分辨率坐标, 自动换算到当前设备

    :param x: 基准分辨率下的 x 坐标
    :param y: 基准分辨率下的 y 坐标
    :param base_w: 基准屏幕宽
    :param base_h: 基准屏幕高
    """
    sx, sy = scale_pos(x, y, base_w, base_h)
    click(sx, sy)
