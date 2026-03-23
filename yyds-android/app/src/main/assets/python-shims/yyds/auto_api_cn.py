"""
Yyds.Auto 中文 API — 让不熟悉英文的用户也能轻松编写自动化脚本

用法::

    from yyds import *

    点击(540, 1200)
    滑动(100, 1800, 100, 600, 500)
    截图()
    文字 = 文字识别()
    打开应用("com.tencent.mm")

所有中文函数与英文函数完全等价, 参数和返回值一致。
"""
from .auto_api import (
    click, random_click, screenshot, ocr, find_color, get_color,
    get_multi_color, toast, ui_dump_xml, screen_find_image, match_images,
    screen_yolo_locate, engine_api, engine_call, engine_set_debug,
    pull_file, post_file, set_touch_mode, get_touch_mode,
    ensure_kernel_click, cancel_kernel_click,
    update_language, media_scan_file,
)
from .auto_api_aux import (
    click_double, swipe, key_back, key_home, key_menu, key_confirm, key_code,
    device_get_screen_size, stop_app, open_app, open_url,
    device_foreground, device_foreground_activity, device_foreground_package,
    is_app_running, bring_app_to_top, is_in_app,
    device_code, device_model, is_net_online,
    ui_match, ui_parent, ui_child, ui_sib, ui_sib_offset, ui_exist,
    shell, input_text, x_input_text, x_input_clear,
    screen_find_image_x, screen_find_image_first_x,
    screen_yolo_find_x, screen_yolo_find_first_x,
    screen_ocr_x, screen_ocr_first_x,
    image_similarity, set_clipboard, get_clipboard,
    model_yolo_reload, model_ocr_reload, model_yolo_info,
    app_data_backup, app_data_recovery, app_apk_backup, app_apk_install,
    set_yy_input_enable,
)
from .auto_plus import (
    sleep, false_sleep, random_sleep,
    swipe_up, swipe_down, swipe_left, swipe_right,
    click_target, toast_print, download,
    ocr_click_if_found, ocr_click_any, ocr_exists_all, ocr_exists_any,
    set_text, open_app_from_desktop, exit_go_home,
    find_image_click, find_image_click_max_prob,
    DeviceScreen, scal_pos_1080_2400, scale_pos_1080_2400,
)
from .auto_api_ext import (
    wait_for_text, wait_for_text_gone,
    wait_for_ui, wait_for_ui_gone, wait_for_image,
    wait_for_activity, wait_for_package,
    long_press, gesture, pinch_in, pinch_out,
    swipe_to_find_text, swipe_to_find_ui, swipe_to_find_image,
    wait_screen_change, wait_screen_stable,
    wait_and_click_text, wait_and_click_ui,
    scale_pos, click_scaled,
    app_uninstall, app_clear_data,
    grant_permission, revoke_permission,
    get_notifications, get_wifi_info, set_wifi_enabled, set_airplane_mode,
    get_screen_orientation, set_screen_brightness, get_battery_info,
)

# ============================================================
# 触摸操作
# ============================================================
点击 = click
双击 = click_double
随机点击 = random_click
长按 = long_press
滑动 = swipe
上滑 = swipe_up
下滑 = swipe_down
左滑 = swipe_left
右滑 = swipe_right
手势 = gesture
双指缩小 = pinch_in
双指放大 = pinch_out
点击目标 = click_target

# ============================================================
# 按键操作
# ============================================================
返回键 = key_back
主页键 = key_home
菜单键 = key_menu
确认键 = key_confirm
按键 = key_code

# ============================================================
# 截图 & OCR 文字识别
# ============================================================
截图 = screenshot
文字识别 = ocr
识别文字 = ocr
屏幕找字 = screen_ocr_x
屏幕找字_首个 = screen_ocr_first_x
文字点击 = ocr_click_if_found
文字点击任一 = ocr_click_any
文字全部存在 = ocr_exists_all
文字任一存在 = ocr_exists_any

# ============================================================
# YOLO 目标检测
# ============================================================
目标检测 = screen_yolo_locate
屏幕找目标 = screen_yolo_find_x
屏幕找目标_首个 = screen_yolo_find_first_x

# ============================================================
# 找图
# ============================================================
找图 = screen_find_image
屏幕找图 = screen_find_image_x
屏幕找图_首个 = screen_find_image_first_x
模板匹配 = match_images
找图点击 = find_image_click
找图点击_最高匹配 = find_image_click_max_prob
图片相似度 = image_similarity

# ============================================================
# 找色
# ============================================================
找色 = find_color
取色 = get_color
多点取色 = get_multi_color

# ============================================================
# UI 控件
# ============================================================
控件匹配 = ui_match
控件存在 = ui_exist
控件父级 = ui_parent
控件子级 = ui_child
控件兄弟 = ui_sib
控件偏移 = ui_sib_offset
控件树 = ui_dump_xml

# ============================================================
# 等待类
# ============================================================
等待文字 = wait_for_text
等待文字消失 = wait_for_text_gone
等待控件 = wait_for_ui
等待控件消失 = wait_for_ui_gone
等待图片 = wait_for_image
等待页面 = wait_for_activity
等待应用 = wait_for_package
等待并点击文字 = wait_and_click_text
等待并点击控件 = wait_and_click_ui
等待画面变化 = wait_screen_change
等待画面稳定 = wait_screen_stable

# ============================================================
# 滑动查找
# ============================================================
滑动找字 = swipe_to_find_text
滑动找控件 = swipe_to_find_ui
滑动找图 = swipe_to_find_image

# ============================================================
# 应用管理
# ============================================================
打开应用 = open_app
关闭应用 = stop_app
打开网址 = open_url
应用置顶 = bring_app_to_top
应用是否运行 = is_app_running
是否在应用内 = is_in_app
卸载应用 = app_uninstall
清除应用数据 = app_clear_data
从桌面打开应用 = open_app_from_desktop
回到桌面 = exit_go_home

# ============================================================
# 文本输入
# ============================================================
输入文字 = input_text
智能输入 = x_input_text
清空输入 = x_input_clear
设置文本 = set_text
复制到剪贴板 = set_clipboard
获取剪贴板 = get_clipboard

# ============================================================
# 设备信息
# ============================================================
屏幕尺寸 = device_get_screen_size
前台应用 = device_foreground
前台Activity = device_foreground_activity
前台包名 = device_foreground_package
设备编号 = device_code
设备型号 = device_model
网络是否在线 = is_net_online

# ============================================================
# 系统功能
# ============================================================
执行命令 = shell
提示 = toast
提示打印 = toast_print
下载文件 = download
拉取文件 = pull_file
推送文件 = post_file

# ============================================================
# 延时
# ============================================================
等待 = sleep
假等待 = false_sleep
随机等待 = random_sleep

# ============================================================
# 权限 & 设备控制
# ============================================================
授予权限 = grant_permission
撤销权限 = revoke_permission
获取通知 = get_notifications
获取WiFi信息 = get_wifi_info
设置WiFi开关 = set_wifi_enabled
设置飞行模式 = set_airplane_mode
获取屏幕方向 = get_screen_orientation
设置屏幕亮度 = set_screen_brightness
获取电池信息 = get_battery_info

# ============================================================
# 分辨率适配
# ============================================================
坐标缩放 = scale_pos
缩放点击 = click_scaled

# ============================================================
# 数据备份
# ============================================================
应用数据备份 = app_data_backup
应用数据恢复 = app_data_recovery
应用安装包备份 = app_apk_backup
安装应用 = app_apk_install

# ============================================================
# 模型管理
# ============================================================
重载YOLO模型 = model_yolo_reload
重载OCR模型 = model_ocr_reload
YOLO模型信息 = model_yolo_info


# 导出所有中文别名
__all__ = [
    # 触摸
    "点击", "双击", "随机点击", "长按", "滑动",
    "上滑", "下滑", "左滑", "右滑",
    "手势", "双指缩小", "双指放大", "点击目标",
    # 按键
    "返回键", "主页键", "菜单键", "确认键", "按键",
    # 截图 & OCR
    "截图", "文字识别", "识别文字", "屏幕找字", "屏幕找字_首个",
    "文字点击", "文字点击任一", "文字全部存在", "文字任一存在",
    # YOLO
    "目标检测", "屏幕找目标", "屏幕找目标_首个",
    # 找图
    "找图", "屏幕找图", "屏幕找图_首个", "模板匹配",
    "找图点击", "找图点击_最高匹配", "图片相似度",
    # 找色
    "找色", "取色", "多点取色",
    # UI 控件
    "控件匹配", "控件存在", "控件父级", "控件子级", "控件兄弟", "控件偏移", "控件树",
    # 等待
    "等待文字", "等待文字消失", "等待控件", "等待控件消失",
    "等待图片", "等待页面", "等待应用",
    "等待并点击文字", "等待并点击控件",
    "等待画面变化", "等待画面稳定",
    # 滑动查找
    "滑动找字", "滑动找控件", "滑动找图",
    # 应用管理
    "打开应用", "关闭应用", "打开网址", "应用置顶",
    "应用是否运行", "是否在应用内",
    "卸载应用", "清除应用数据", "从桌面打开应用", "回到桌面",
    # 文本输入
    "输入文字", "智能输入", "清空输入", "设置文本",
    "复制到剪贴板", "获取剪贴板",
    # 设备信息
    "屏幕尺寸", "前台应用", "前台Activity", "前台包名",
    "设备编号", "设备型号", "网络是否在线",
    # 系统
    "执行命令", "提示", "提示打印", "下载文件", "拉取文件", "推送文件",
    # 延时
    "等待", "假等待", "随机等待",
    # 权限 & 设备
    "授予权限", "撤销权限", "获取通知",
    "获取WiFi信息", "设置WiFi开关", "设置飞行模式",
    "获取屏幕方向", "设置屏幕亮度", "获取电池信息",
    # 分辨率
    "坐标缩放", "缩放点击",
    # 数据备份
    "应用数据备份", "应用数据恢复", "应用安装包备份", "安装应用",
    # 模型
    "重载YOLO模型", "重载OCR模型", "YOLO模型信息",
]
