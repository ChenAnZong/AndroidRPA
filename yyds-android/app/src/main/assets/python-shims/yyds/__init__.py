"""
Yyds.Auto Python SDK — 安卓自动化脚本开发框架
官方文档: https://yydsxx.com/docs/yyds-auto/script

模块结构:
    - auto_entity  : 数据实体类 (Point, Color, Node, ResOcr, ResYolo, ResFindImage 等)
    - util         : 工具函数 (log_d, log_e, format_time)
    - auto_api     : 底层引擎 API (click, ocr, screenshot, ui_match, shell 等)
    - auto_api_aux : 辅助 API (swipe, key_*, device_*, screen_find_image_x 等)
    - auto_func    : 装饰器与流程控制 (run_no_hurt, retry_until_true, register_task 等)
    - auto_plus    : 高级封装 (DeviceScreen, ocr_click_*, find_image_click_* 等)
    - auto_api_ext : 扩展 API (等待类, 手势, 滑动查找, 屏幕变化检测, 设备控制 等)
"""

# === 实体类 ===
from .auto_entity import (
    Point,
    Color,
    ResFindImage,
    ResYolo,
    ResOcr,
    DeviceForegroundResponse,
    Node,
    RequestFindImage,
    MatchImageResult,
    EngineResultParser,
)

# === 工具函数 ===
from .util import (
    format_time,
    log_d,
    log_e,
)

# === 核心引擎 API ===
from .auto_api import (
    ProjectEnvironment,
    Config,
    EngineDebug,
    engine_set_debug,
    engine_api,
    engine_call,
    click,
    update_language,
    media_scan_file,
    random_click,
    md5_file,
    md5_str,
    toast,
    screenshot,
    ocr,
    screen_yolo_locate,
    screen_find_image,
    ui_dump_xml,
    match_images,
    find_color,
    get_color,
    get_multi_color,
    ensure_kernel_click,
    cancel_kernel_click,
    pull_file,
    post_file,
)

# === 辅助 API ===
from .auto_api_aux import (
    click_double,
    swipe,
    key_back,
    key_home,
    key_menu,
    key_confirm,
    key_code,
    device_get_screen_size,
    stop_app,
    open_app,
    open_url,
    device_foreground,
    device_foreground_activity,
    device_foreground_package,
    is_app_running,
    bring_app_to_top,
    is_in_app,
    device_code,
    device_model,
    is_net_online,
    model_yolo_reload,
    model_ocr_reload,
    ui_match,
    ui_parent,
    ui_child,
    ui_sib,
    ui_sid_offset,
    ui_sib_offset,
    ui_exist,
    shell,
    screen_find_image_x,
    screen_find_image_first_x,
    screen_yolo_find_x,
    screen_yolo_find_first_x,
    screen_ocr_x,
    screen_ocr_first_x,
    image_similarity,
    input_text,
    x_input_text,
    x_input_clear,
    set_yy_input_enable,
    app_data_backup,
    app_data_recovery,
    app_apk_backup,
    app_apk_install,
    set_clipboard,
    get_clipboard,
)

# === 装饰器与流程控制 ===
from .auto_func import (
    WrapRecord,
    try_run,
    run,
    run_no_hurt,
    retry_until_true,
    register_task,
    handle_task,
    get_activity_handler,
    run_activity_handler,
    do,
    loop_activity_handle,
    run_until_true,
)

# === 扩展 API ===
from .auto_api_ext import (
    # 等待类
    wait_for_text,
    wait_for_text_gone,
    wait_for_ui,
    wait_for_ui_gone,
    wait_for_image,
    wait_for_activity,
    wait_for_package,
    wait_and_click_text,
    wait_and_click_ui,
    # 手势
    long_press,
    gesture,
    pinch_in,
    pinch_out,
    # 滑动查找
    swipe_to_find_text,
    swipe_to_find_ui,
    swipe_to_find_image,
    # 屏幕变化检测
    wait_screen_change,
    wait_screen_stable,
    # 应用管理扩展
    app_uninstall,
    app_clear_data,
    grant_permission,
    revoke_permission,
    # 设备信息扩展
    get_notifications,
    get_wifi_info,
    set_wifi_enabled,
    set_airplane_mode,
    get_screen_orientation,
    set_screen_brightness,
    get_battery_info,
    # 分辨率适配
    scale_pos,
    click_scaled,
)

# === 悬浮日志控制台 ===
try:
    from console import console
except ImportError:
    try:
        from .console_shim import console
    except ImportError:
        console = None

# === 高级封装 ===
from .auto_plus import (
    DeviceScreen,
    download,
    toast_print,
    click_target,
    sleep,
    false_sleep,
    random_sleep,
    swipe_up,
    swipe_down,
    swipe_right,
    swipe_left,
    scal_pos_1080_2400,
    scale_pos_1080_2400,
    ocr_click_if_found,
    ocr_click_any,
    find_image_click,
    ocr_exists_all,
    ocr_exists_any,
    set_text,
    open_app_from_desktop,
    exit_go_home,
    find_image_click_max_prob,
)

from .auto_api_cn import *
from .auto_api_templates import *

__all__ = [
    # 实体类
    "Point", "Color", "ResFindImage", "ResYolo", "ResOcr",
    "DeviceForegroundResponse", "Node", "RequestFindImage",
    "MatchImageResult", "EngineResultParser",
    # 工具
    "format_time", "log_d", "log_e",
    # 核心引擎
    "ProjectEnvironment", "Config", "EngineDebug",
    "engine_set_debug", "engine_api", "engine_call",
    "click", "update_language", "media_scan_file", "random_click",
    "md5_file", "md5_str", "toast", "screenshot",
    "ocr", "screen_yolo_locate", "screen_find_image",
    "ui_dump_xml", "match_images", "find_color", "get_color", "get_multi_color",
    "ensure_kernel_click", "cancel_kernel_click",
    "pull_file", "post_file",
    # 辅助 API
    "click_double", "swipe",
    "key_back", "key_home", "key_menu", "key_confirm", "key_code",
    "device_get_screen_size", "stop_app", "open_app", "open_url",
    "device_foreground", "device_foreground_activity", "device_foreground_package",
    "is_app_running", "bring_app_to_top", "is_in_app",
    "device_code", "device_model", "is_net_online",
    "model_yolo_reload", "model_ocr_reload",
    "ui_match", "ui_parent", "ui_child", "ui_sib", "ui_sib_offset", "ui_sid_offset", "ui_exist",
    "shell",
    "screen_find_image_x", "screen_find_image_first_x",
    "screen_yolo_find_x", "screen_yolo_find_first_x",
    "screen_ocr_x", "screen_ocr_first_x",
    "image_similarity",
    "input_text", "x_input_text", "x_input_clear", "set_yy_input_enable",
    "app_data_backup", "app_data_recovery", "app_apk_backup", "app_apk_install",
    "set_clipboard", "get_clipboard",
    # 装饰器与流程控制
    "WrapRecord", "try_run", "run", "run_no_hurt",
    "retry_until_true", "register_task", "handle_task",
    "get_activity_handler", "run_activity_handler",
    "do", "loop_activity_handle", "run_until_true",
    # 扩展 API - 等待类
    "wait_for_text", "wait_for_text_gone",
    "wait_for_ui", "wait_for_ui_gone",
    "wait_for_image", "wait_for_activity", "wait_for_package",
    "wait_and_click_text", "wait_and_click_ui",
    # 扩展 API - 手势
    "long_press", "gesture", "pinch_in", "pinch_out",
    # 扩展 API - 滑动查找
    "swipe_to_find_text", "swipe_to_find_ui", "swipe_to_find_image",
    # 扩展 API - 屏幕变化检测
    "wait_screen_change", "wait_screen_stable",
    # 扩展 API - 应用管理
    "app_uninstall", "app_clear_data",
    "grant_permission", "revoke_permission",
    # 扩展 API - 设备控制
    "get_notifications", "get_wifi_info", "set_wifi_enabled",
    "set_airplane_mode", "get_screen_orientation",
    "set_screen_brightness", "get_battery_info",
    # 扩展 API - 分辨率适配
    "scale_pos", "click_scaled",
    # 悬浮日志控制台
    "console",
    # 高级封装
    "DeviceScreen", "download", "toast_print", "click_target",
    "sleep", "false_sleep", "random_sleep",
    "swipe_up", "swipe_down", "swipe_right", "swipe_left",
    "scal_pos_1080_2400", "scale_pos_1080_2400",
    "ocr_click_if_found", "ocr_click_any",
    "find_image_click", "find_image_click_max_prob",
    "ocr_exists_all", "ocr_exists_any",
    "set_text", "open_app_from_desktop", "exit_go_home",
    # ---- 中文 API 别名 ----
    "点击", "双击", "随机点击", "长按", "滑动",
    "上滑", "下滑", "左滑", "右滑",
    "手势", "双指缩小", "双指放大", "点击目标",
    "返回键", "主页键", "菜单键", "确认键", "按键",
    "截图", "文字识别", "识别文字", "屏幕找字", "屏幕找字_首个",
    "文字点击", "文字点击任一", "文字全部存在", "文字任一存在",
    "目标检测", "屏幕找目标", "屏幕找目标_首个",
    "找图", "屏幕找图", "屏幕找图_首个", "模板匹配",
    "找图点击", "找图点击_最高匹配", "图片相似度",
    "找色", "取色", "多点取色",
    "控件匹配", "控件存在", "控件父级", "控件子级", "控件兄弟", "控件偏移", "控件树",
    "等待文字", "等待文字消失", "等待控件", "等待控件消失",
    "等待图片", "等待页面", "等待应用",
    "等待并点击文字", "等待并点击控件",
    "等待画面变化", "等待画面稳定",
    "滑动找字", "滑动找控件", "滑动找图",
    "打开应用", "关闭应用", "打开网址", "应用置顶",
    "应用是否运行", "是否在应用内",
    "卸载应用", "清除应用数据", "从桌面打开应用", "回到桌面",
    "输入文字", "智能输入", "清空输入", "设置文本",
    "复制到剪贴板", "获取剪贴板",
    "屏幕尺寸", "前台应用", "前台Activity", "前台包名",
    "设备编号", "设备型号", "网络是否在线",
    "执行命令", "提示", "提示打印", "下载文件", "拉取文件", "推送文件",
    "等待", "假等待", "随机等待",
    "授予权限", "撤销权限", "获取通知",
    "获取WiFi信息", "设置WiFi开关", "设置飞行模式",
    "获取屏幕方向", "设置屏幕亮度", "获取电池信息",
    "坐标缩放", "缩放点击",
    "应用数据备份", "应用数据恢复", "应用安装包备份", "安装应用",
    "重载YOLO模型", "重载OCR模型", "YOLO模型信息",
    # ---- 场景模板 ----
    "启动并等待", "关闭并重启",
    "滑动找字并点击", "重复点击直到",
    "输入中文", "条件等待循环",
    "安全点击文字", "批量点击文字",
]
