"""
Yyds.Auto Python SDK 类型存根
为 IDE 提供完整的自动补全、参数提示和文档支持
"""

from typing import Union, Tuple, List, Optional

# ============================================================
# 实体类
# ============================================================

class Point:
    """屏幕坐标点"""
    x: int
    y: int
    def __init__(self, x: int, y: int) -> None: ...

class Color:
    """RGB 颜色"""
    r: int
    g: int
    b: int
    def __init__(self, r: int, g: int, b: int) -> None: ...
    def similarity_to(self, color2: "Color") -> float:
        """计算与另一个颜色的相似度 (0~1, 越大越相似)"""
        ...

class ResFindImage:
    """找图结果"""
    name: str
    path: str
    prob: float
    width: int
    height: int
    x: int
    y: int
    @property
    def cx(self) -> int:
        """中心点 X"""
        ...
    @property
    def cy(self) -> int:
        """中心点 Y"""
        ...
    def __init__(self, name: str, path: str, prob: float, width: int, height: int, x: int, y: int) -> None: ...

class ResYolo:
    """YOLO 目标检测结果"""
    label: str
    cx: int
    cy: int
    x: float
    y: float
    w: float
    h: float
    prob: float
    def __init__(self, label: str, cx: int, cy: int, x: float, y: float, w: float, h: float, prob: float) -> None: ...

class ResOcr:
    """OCR 文字识别结果"""
    prob: float
    text: str
    x1: int; y1: int
    x2: int; y2: int
    x3: int; y3: int
    x4: int; y4: int
    @property
    def cx(self) -> int:
        """中心点 X"""
        ...
    @property
    def cy(self) -> int:
        """中心点 Y"""
        ...
    @property
    def w(self) -> int:
        """宽度"""
        ...
    @property
    def h(self) -> int:
        """高度"""
        ...

class DeviceForegroundResponse:
    """设备前台应用信息"""
    package: str
    activity_name: str
    pid: int
    @property
    def full_activity_name(self) -> str: ...

class Node:
    """UI 控件节点"""
    bound_str: str
    child_count: int
    parent_count: int
    class_name: str
    pkg: str
    text: str
    desc: str
    id: str
    hash_code: int
    index: int
    is_check_able: bool
    is_clicked: bool
    is_enable: bool
    is_foucuable: bool
    is_foucesed: bool
    is_long_click_able: bool
    is_password: bool
    is_scroll_able: bool
    is_selected: bool
    is_visible: bool
    dump_time_ms: int
    @property
    def cx(self) -> int:
        """中心点 X"""
        ...
    @property
    def cy(self) -> int:
        """中心点 Y"""
        ...
    @property
    def center_point(self) -> Tuple[int, int]:
        """返回节点中心坐标 (x, y)"""
        ...

class RequestFindImage:
    """找图请求参数"""
    name: str
    path: str
    min_prob: float
    def __init__(self, name: str, path: str, min_prob: float) -> None: ...

class MatchImageResult:
    """图片多次匹配结果"""
    x: int
    y: int
    w: int
    h: int
    prob: float
    def __init__(self, x: int, y: int, w: int, h: int, prob: float) -> None: ...

class EngineResultParser:
    """引擎返回结果解析器"""
    @staticmethod
    def parse_color(rgb_text: str) -> Color: ...
    @staticmethod
    def parse_multi_color(rgb_text: str) -> Tuple[Color, ...]: ...
    @staticmethod
    def parse_point(text: str) -> Point: ...
    @staticmethod
    def parse_match_result(text: str) -> MatchImageResult: ...

# ============================================================
# 工具函数
# ============================================================

def format_time() -> str:
    """获取格式化时间字符串, 如 2024-02-04 12:56:31"""
    ...

def log_d(*objs: object) -> None:
    """打印标准日志 (灰色)"""
    ...

def log_e(*objs: object) -> None:
    """打印错误日志 (红色)"""
    ...

# ============================================================
# 核心类
# ============================================================

class ProjectEnvironment:
    """工程运行环境与全局变量"""
    DEFAULT_SCREEN_SHOT_PATH: str
    DEFAULT_UI_DUMP_PATH: str
    STOP_ENGINE_FLAG: str
    CWD: str
    DEBUG_MODE: bool
    PROJECT_NAME: str
    DEBUG_IP: str
    IMPORT_JAVA_SUCCESS: bool
    GLOBAL_CONFIG: dict
    @classmethod
    def current_project(cls) -> str:
        """返回当前工程目录名 (唯一 ID)"""
        ...
    @classmethod
    def current_project_dir(cls) -> str:
        """返回当前手机上工程目录的完整路径"""
        ...

class Config:
    """配置存储类, 与设备 UI 配置共享同一存储"""
    @classmethod
    def get_config_path(cls) -> str:
        """获取当前项目配置文件路径"""
        ...
    @classmethod
    def reload_config(cls) -> None:
        """重新加载配置"""
        ...
    @classmethod
    def read_config_value(cls, config_name: str, read_load: bool = False) -> Union[bool, str, int, None]:
        """
        读取配置值 (同时支持 UI 配置和自定义配置)

        当用户未在 App 中配置过该项时，会自动 fallback 到 ui.yml 中定义的 value 默认值。
        仅当 config_name 既不在已保存配置中、也不在 ui.yml 中时返回 None。

        :param config_name: 配置键名, 如 ``"edit-user"``
        :param read_load: 是否强制重新读取配置文件
        :returns: 配置值, 类型取决于配置项; 不存在则返回 None
        """
        ...
    @classmethod
    def write_config_value(cls, config_name: str, value: object) -> None:
        """
        写入配置值

        :param config_name: 配置键名
        :param value: 值
        """
        ...
    @classmethod
    def read_ui_value(cls, config_name: str) -> Union[str, None]:
        """
        从 ui.yml 读取默认值, config_name 不存在时安全返回 None

        :param config_name: UI 配置键名
        :returns: 配置值或 None
        """
        ...

class EngineDebug:
    """引擎调试接口 (内部使用)"""
    @staticmethod
    def _version() -> str: ...
    @staticmethod
    def _pid() -> int: ...
    @staticmethod
    def _uid() -> int: ...
    @staticmethod
    def _ping() -> bool: ...
    @staticmethod
    def _reboot() -> str: ...
    @staticmethod
    def _exit() -> str: ...
    @staticmethod
    def _press_down(x: int, y: int) -> str: ...
    @staticmethod
    def _press_up(x: int, y: int) -> str: ...
    @staticmethod
    def _press_move(x: int, y: int) -> str: ...
    @staticmethod
    def _reload_py_module(module_name: str) -> None: ...

class DeviceScreen:
    """设备屏幕参数管理, 使用前需调用 ``DeviceScreen.init()``"""
    @classmethod
    def init(cls) -> None:
        """初始化屏幕宽高, 屏幕旋转后需重新调用"""
        ...
    @classmethod
    def get_screen_wh(cls) -> Tuple[int, int]:
        """返回 (宽, 高)"""
        ...
    @classmethod
    def get_h(cls) -> int:
        """屏幕高度"""
        ...
    @classmethod
    def get_w(cls) -> int:
        """屏幕宽度"""
        ...

class WrapRecord:
    """界面与任务的注册记录"""
    ACTIVITY_HANDLER: dict
    TASK_HANDLER: dict

# ============================================================
# 引擎底层
# ============================================================

def engine_set_debug(is_debug: bool) -> None:
    """设置是否打印引擎通讯日志"""
    ...

def engine_api(uri: str, options: Optional[dict] = None) -> str:
    """
    底层引擎 RPC 调用 (安卓端反射调用, PC 端 HTTP 调用)
    """
    ...

def engine_call(uri: str, options: Optional[dict] = None):
    """
    底层引擎 RPC 调用, 自动解析JSON返回
    """
    ...
    :param uri: 接口路径, 如 ``"/touch"``
    :param options: 参数字典
    :returns: 引擎返回的字符串结果
    """
    ...

# ============================================================
# 触摸与点击
# ============================================================

def click(x: Union[str, int], y: Union[str, int], click_time: int = 1, interval: int = 50) -> bool:
    """
    点击屏幕坐标

    :param x: 屏幕绝对坐标 X
    :param y: 屏幕绝对坐标 Y
    :param click_time: 连续点击次数 (用于抢购/高速点赞)
    :param interval: 连续点击间隔 (毫秒)
    """
    ...

def click_double(x: Union[str, int], y: Union[str, int]) -> None:
    """双击坐标"""
    ...

def random_click(x: int, y: int, w: int, h: int) -> None:
    """
    在指定矩形区域内随机点击, 模拟人工操作

    :param x: 区域左上角 X
    :param y: 区域左上角 Y
    :param w: 区域宽度
    :param h: 区域高度
    """
    ...

def click_target(t: Union[ResOcr, ResYolo, ResFindImage]) -> None:
    """
    点击识别结果对象的中心点

    :param t: OCR / YOLO / 找图的返回结果对象
    """
    ...

# ============================================================
# 滑动
# ============================================================

def swipe(x1: int, y1: int, x2: int, y2: int, duration: int, is_random: bool = False) -> None:
    """
    屏幕滑动

    :param x1: 起始 X
    :param y1: 起始 Y
    :param x2: 终点 X
    :param y2: 终点 Y
    :param duration: 滑动耗时 (毫秒), 越小越快
    :param is_random: 是否随机锯齿滑动
    """
    ...

def swipe_up() -> None:
    """往上滑动 (需先调用 ``DeviceScreen.init()``)"""
    ...

def swipe_down() -> None:
    """往下滑动"""
    ...

def swipe_left() -> None:
    """往左滑动"""
    ...

def swipe_right() -> None:
    """往右滑动"""
    ...

# ============================================================
# 按键
# ============================================================

def key_back() -> None:
    """返回键"""
    ...

def key_home() -> None:
    """Home 键"""
    ...

def key_menu() -> None:
    """菜单键"""
    ...

def key_confirm() -> str:
    """确认键 (搜索/提交)"""
    ...

def key_code(code: int) -> None:
    """
    注入 Android KeyCode

    :param code: 键值码, 参考 http://yydsxx.com/blog/android%20keycode%20table
    """
    ...

# ============================================================
# 截图
# ============================================================

def screenshot(path: Optional[str] = None) -> str:
    """
    屏幕截图

    :param path: 保存路径, 默认 ``/sdcard/screenshot.png``
    :returns: 截图保存的最终路径
    """
    ...

# ============================================================
# OCR 文字识别
# ============================================================

def ocr(image: Optional[str] = None, x: Optional[float] = None, y: Optional[float] = None,
        w: Optional[float] = None, h: Optional[float] = None, use_gpu: bool = False) -> str:
    """
    底层 OCR 接口, 识别屏幕或图片中的文字

    :param image: 图片路径, None 则截屏识别
    :param x: 识别区域起始 X (支持 0~1 相对坐标)
    :param y: 识别区域起始 Y
    :param w: 识别区域宽
    :param h: 识别区域高
    :param use_gpu: 是否使用 GPU 加速
    """
    ...

def screen_ocr_x(specific_texts: Union[List[str], Tuple[str, ...], None] = None,
                 x: Optional[float] = None, y: Optional[float] = None,
                 w: Optional[float] = None, h: Optional[float] = None,
                 use_gpu: bool = False) -> Tuple[ResOcr, ...]:
    """
    屏幕 OCR 识别, 返回匹配指定文字的结果

    :param specific_texts: 要查找的文字列表 (支持正则)
    :param x: 区域起始 X (0~1 相对坐标)
    :param y: 区域起始 Y
    :param w: 区域宽
    :param h: 区域高
    :param use_gpu: 是否 GPU 加速
    :returns: 匹配到的 OCR 结果元组
    """
    ...

def screen_ocr_first_x(specific_texts: Union[List[str], Tuple[str, ...], None] = None,
                       x: Optional[float] = None, y: Optional[float] = None,
                       w: Optional[float] = None, h: Optional[float] = None,
                       use_gpu: bool = False) -> Optional[ResOcr]:
    """屏幕 OCR, 仅返回第一个匹配结果"""
    ...

def ocr_click_if_found(*text: str, x: Optional[float] = None, y: Optional[float] = None,
                       w: Optional[float] = None, h: Optional[float] = None,
                       offset_h: Optional[float] = None, offset_w: Optional[float] = None) -> bool:
    """搜索到**所有**指定文字时, 点击最后一个"""
    ...

def ocr_click_any(*text: str, x: Optional[float] = None, y: Optional[float] = None,
                  w: Optional[float] = None, h: Optional[float] = None,
                  offset_h: Optional[float] = None, offset_w: Optional[float] = None) -> bool:
    """搜索到**任一**指定文字时, 点击最后一个"""
    ...

def ocr_exists_all(*text: str, x: Optional[float] = None, y: Optional[float] = None,
                   w: Optional[float] = None, h: Optional[float] = None) -> bool:
    """判断屏幕上是否**所有**指定文字都存在"""
    ...

def ocr_exists_any(*text: str, x: Optional[float] = None, y: Optional[float] = None,
                   w: Optional[float] = None, h: Optional[float] = None) -> bool:
    """判断屏幕上是否存在**任一**指定文字"""
    ...

# ============================================================
# YOLO 目标检测
# ============================================================

def screen_yolo_locate(x: Optional[float] = None, y: Optional[float] = None,
                       w: Optional[float] = None, h: Optional[float] = None,
                       use_gpu: bool = True) -> str:
    """底层 YOLO 检测接口"""
    ...

def screen_yolo_find_x(specify_labels: Optional[List[str]] = None, min_prob: float = 0.9,
                       x: Optional[float] = None, y: Optional[float] = None,
                       w: Optional[float] = None, h: Optional[float] = None,
                       use_gpu: bool = False) -> Tuple[ResYolo, ...]:
    """
    YOLO 目标检测

    :param specify_labels: 仅返回指定 label 的结果
    :param min_prob: 最低置信率
    :returns: 检测结果元组
    """
    ...

def screen_yolo_find_first_x(labels: Optional[Union[str, List[str]]] = None, prob: float = 0.9,
                             x: Optional[float] = None, y: Optional[float] = None,
                             w: Optional[float] = None, h: Optional[float] = None,
                             use_gpu: bool = False) -> Optional[ResYolo]:
    """YOLO 检测, 仅返回第一个结果"""
    ...

# ============================================================
# 找图 (模板匹配)
# ============================================================

def screen_find_image(*img: str, x: Optional[float] = None, y: Optional[float] = None,
                      w: Optional[float] = None, h: Optional[float] = None,
                      threshold: int = -1) -> str:
    """
    底层找图接口, 在屏幕上同时查找多张图片

    :param img: 图片路径 (相对于项目目录)
    :param x: 区域起始 X (0~1 相对坐标或绝对像素)
    :param y: 区域起始 Y
    :param w: 区域宽
    :param h: 区域高
    :param threshold: 图片预处理方式 (<0 彩色匹配, ==0 灰度反相, >0 二值化阈值 1~255)
    """
    ...

def screen_find_image_x(fd_images: Union[Tuple[str, ...], Tuple[RequestFindImage, ...]],
                        min_prob: float = 0.5,
                        x: Optional[float] = None, y: Optional[float] = None,
                        w: Optional[float] = None, h: Optional[float] = None,
                        threshold: int = -1) -> Tuple[ResFindImage, ...]:
    """
    高级找图, 同时查找多张图片并返回结构化结果

    :param fd_images: 图片路径元组
    :param min_prob: 最低置信率
    :returns: 找图结果元组
    """
    ...

def screen_find_image_first_x(fd_images: Tuple[Union[str, RequestFindImage], ...],
                              min_prob: float = 0.9,
                              x: Optional[float] = None, y: Optional[float] = None,
                              w: Optional[float] = None, h: Optional[float] = None,
                              threshold: int = -1) -> Optional[ResFindImage]:
    """高级找图, 仅返回第一个结果"""
    ...

def match_images(template_image: str, prob: float, threshold: int = 0,
                 image: Optional[str] = None,
                 x: Optional[float] = None, y: Optional[float] = None,
                 w: Optional[float] = None, h: Optional[float] = None) -> List[MatchImageResult]:
    """
    对图片进行多次匹配

    :param template_image: 模板图片路径
    :param prob: 最低相似度 (0~1.0)
    :param threshold: 预处理方式
    """
    ...

def find_image_click(*img: str, min_prob: float = 0.5,
                     x: Optional[float] = None, y: Optional[float] = None,
                     w: Optional[float] = None, h: Optional[float] = None,
                     offset_x: Optional[float] = None, offset_y: Optional[float] = None,
                     threshold: int = -1, wait: int = 0) -> bool:
    """找到所有图片后点击最后一张"""
    ...

def find_image_click_max_prob(*img: Union[str, tuple], min_prob: float = 0.5,
                              x: Optional[float] = None, y: Optional[float] = None,
                              w: Optional[float] = None, h: Optional[float] = None,
                              is_random_click: bool = False, threshold: int = -1) -> bool:
    """
    传入一组图片, 找到后点击相似度最高的一张

    :param img: 图片路径, 或元组 ``("路径", 是否必点, 点击前等待秒数, 点击次数)``
    :param min_prob: 最低置信率
    :param is_random_click: 是否随机选择点击目标
    """
    ...

# ============================================================
# 找色
# ============================================================

def find_color(base_rgb: str, bias_points: List[str] = ..., max_fuzzy: int = 3,
              step_x: int = 5, step_y: int = 5, image: Optional[str] = None,
              x: Optional[float] = None, y: Optional[float] = None,
              w: Optional[float] = None, h: Optional[float] = None,
              max_counts: int = 1) -> List[Point]:
    """
    单/多点找色

    :param base_rgb: 基点 RGB, 格式 ``"R,G,B"``
    :param bias_points: 偏移点列表, 格式 ``["偏移X,偏移Y|R,G,B"]``, 反色用 ``~``
    :param max_fuzzy: 颜色相似度临界值 (0~255, 越小越严格)
    :param max_counts: 最大返回结果数
    :returns: 匹配到的坐标列表
    """
    ...

def get_color(x: int, y: int, image: Optional[str] = None) -> Color:
    """获取指定坐标的 RGB 颜色"""
    ...

def get_multi_color(points: List[Tuple[int, int]], image: Optional[str] = None) -> Tuple[Color, ...]:
    """获取多个坐标的颜色"""
    ...

# ============================================================
# 图片相似度
# ============================================================

def image_similarity(img1: str, img2: str, flags: int = 0) -> float:
    """
    计算两张图片的相似度 (需尺寸一致)

    :returns: 0~100 的浮点数
    """
    ...

# ============================================================
# UI 控件
# ============================================================

def ui_match(all_window: bool = False, match_from_cache: bool = False, limit: int = 9999,
             **match_params: Union[str, bool]) -> List[Node]:
    """
    扫描屏幕 UI 控件并匹配

    支持的 match_params:
        - ``text``, ``resource_id``, ``content_desc``, ``class_`` 等 (支持正则)
        - ``width``, ``height`` (支持 ``">10"`` / ``"<50"``)
        - ``x``, ``y``, ``w``, ``h`` (限制区域, 0~1 相对坐标)
        - ``par_*``, ``sib_*``, ``chi_*`` (父/兄/子节点匹配)

    :param all_window: 是否查找所有窗口 (含悬浮窗)
    :param match_from_cache: 从缓存匹配 (提升效率, 需确保界面未变)
    :param limit: 最大返回数量
    :returns: 匹配到的节点列表

    示例::

        nodes = ui_match(text="登录", resource_id="com.app:id/btn")
        click(nodes[0].cx, nodes[0].cy)
    """
    ...

def ui_parent(node: Node) -> List[Node]:
    """获取节点的所有父节点 (由近到远)"""
    ...

def ui_child(node: Node) -> List[Node]:
    """获取节点的所有子节点"""
    ...

def ui_sib(node: Node) -> List[Node]:
    """获取节点的兄弟节点 (不含自身)"""
    ...

def ui_sid_offset(node: Node, next_count: int = 1) -> Optional[Node]:
    """获取偏移指定位置的兄弟节点"""
    ...

def ui_exist(all_window: bool = False, match_from_cache: bool = False, **match_params: Union[str, bool]) -> bool:
    """检查符合条件的控件是否存在"""
    ...

def ui_dump_xml(path: Optional[str] = None, all_window: bool = False) -> str:
    """获取控件布局 XML"""
    ...

# ============================================================
# 设备信息
# ============================================================

def device_get_screen_size() -> Tuple[int, int]:
    """获取屏幕分辨率 (宽, 高), 横竖屏数值会交换"""
    ...

def device_foreground() -> Optional[DeviceForegroundResponse]:
    """获取当前前台应用信息"""
    ...

def device_foreground_activity() -> str:
    """获取当前活动界面名"""
    ...

def device_foreground_package() -> str:
    """获取当前前台包名"""
    ...

def device_code() -> str:
    """获取设备唯一硬件码"""
    ...

def device_model() -> str:
    """获取手机型号"""
    ...

def is_net_online() -> bool:
    """检测设备网络是否连通"""
    ...

def is_app_running(pkg: str) -> bool:
    """判断应用是否在后台运行"""
    ...

def is_in_app(pkg: str) -> bool:
    """判断当前是否在某应用界面内"""
    ...

# ============================================================
# 应用管理
# ============================================================

def open_app(pkg: str) -> str:
    """
    根据包名打开应用

    :param pkg: 应用包名, 如 ``"com.android.browser"``
    """
    ...

def stop_app(pkg: str) -> str:
    """停止应用运行"""
    ...

def open_url(url: str) -> str:
    """通过 Intent 打开 URL"""
    ...

def bring_app_to_top(pkg: str) -> bool:
    """将后台应用带回前台"""
    ...

def open_app_from_desktop(app_name: str, pkg_name: str, img: str) -> bool:
    """从桌面找到图标并点击打开应用"""
    ...

# ============================================================
# Shell 命令
# ============================================================

def shell(*cmd: str) -> str:
    """
    执行 Shell 命令 (引擎内置 busybox)

    :returns: 命令输出 (包含错误流)
    """
    ...

# ============================================================
# 输入法
# ============================================================

def input_text(text: str) -> int:
    """注入文本 (仅支持 ASCII 英文数字符号)"""
    ...

def x_input_text(text: str) -> bool:
    """通过 YY 输入法输入文本 (支持中文)"""
    ...

def x_input_clear() -> bool:
    """通过 YY 输入法清空编辑框"""
    ...

def set_yy_input_enable(enable: bool) -> bool:
    """启用/禁用 YY 输入法"""
    ...

def set_text(text: str) -> None:
    """清空编辑框并输入新文本"""
    ...

# ============================================================
# 剪贴板
# ============================================================

def set_clipboard(text: str) -> None:
    """复制文本到剪贴板"""
    ...

def get_clipboard() -> str:
    """获取剪贴板文本 (安卓 9+ 需启用 YY 输入法)"""
    ...

# ============================================================
# 提示
# ============================================================

def toast(content: str) -> None:
    """弹出 Toast 提示"""
    ...

def toast_print(text: str) -> None:
    """弹出 Toast 并打印日志"""
    ...

# ============================================================
# 文件传输 (PC 调试专用)
# ============================================================

def pull_file(remote: str, local: str) -> bool:
    """从手机拉取文件到本地"""
    ...

def post_file(local: str, remote_dir: str = "/sdcard") -> bool:
    """推送本地文件到手机"""
    ...

def download(url: str, save_local_path: str) -> bool:
    """HTTP 下载文件"""
    ...

# ============================================================
# 工具函数
# ============================================================

def md5_file(path: str) -> str:
    """计算文件 MD5"""
    ...

def md5_str(text: str) -> str:
    """计算文本 MD5"""
    ...

def update_language(code: str) -> bool:
    """设置系统语言, 如 ``"en-us"``"""
    ...

def media_scan_file(path: str) -> bool:
    """触发媒体文件扫描, 多文件用 ``;`` 分隔"""
    ...

def ensure_kernel_click() -> str:
    """启用内核点击 (重启引擎生效)"""
    ...

def cancel_kernel_click() -> str:
    """取消内核点击"""
    ...

# ============================================================
# AI 模型
# ============================================================

def model_yolo_reload(ncnn_bin_path: str, ncnn_param_path: str) -> None:
    """加载自定义 YOLO ncnn 模型"""
    ...

def model_ocr_reload(ncnn_bin_path: str, ncnn_param_path: str) -> None:
    """加载自定义 PP-OCR ncnn 模型"""
    ...

# ============================================================
# 应用数据
# ============================================================

def app_data_backup(pkg: str, path: str) -> bool:
    """备份应用数据 (tar 格式)"""
    ...

def app_data_recovery(pkg: str, path: str) -> bool:
    """还原应用数据"""
    ...

def app_apk_backup(pkg: str, path: str) -> bool:
    """提取应用 APK"""
    ...

def app_apk_install(path: str) -> bool:
    """安装 APK"""
    ...

# ============================================================
# 暂停
# ============================================================

def sleep(t: Union[int, float]) -> bool:
    """暂停指定秒数"""
    ...

def false_sleep(t: Union[int, float]) -> bool:
    """暂停指定秒数, 返回 False"""
    ...

def random_sleep(a: int, b: int) -> bool:
    """暂停随机秒数 (a~b)"""
    ...

# ============================================================
# 坐标缩放
# ============================================================

def scal_pos_1080_2400(x: int, y: int) -> Tuple[int, int]:
    """将 1080x2400 基准坐标缩放到当前屏幕分辨率"""
    ...

# ============================================================
# 装饰器与流程控制
# ============================================================

def try_run(func: object, print_exception: bool = True) -> object:
    """运行函数并捕获异常"""
    ...

def run(func: object) -> object:
    """装饰器: 定义时立即执行"""
    ...

def run_no_hurt(func: object) -> object:
    """装饰器: 定义时立即执行, 异常不中断"""
    ...

def retry_until_true(retry_time: int = 40, interval: int = 1) -> object:
    """
    装饰器: 循环重试直到返回 True

    :param retry_time: 最大重试次数
    :param interval: 重试间隔 (秒)
    """
    ...

def register_task(*task_name: str) -> object:
    """装饰器: 注册任务"""
    ...

def handle_task(task_name: str) -> None:
    """执行已注册的任务"""
    ...

def get_activity_handler(name: str) -> object:
    """获取界面处理器"""
    ...

def run_activity_handler(*names: str) -> object:
    """装饰器: 注册并执行界面处理器"""
    ...

def do(times: int, interval: float, pre_interval: bool, *func: object) -> None:
    """循环间隔执行一组函数"""
    ...

def loop_activity_handle(other: Optional[object]) -> None:
    """将主循环交给界面处理器"""
    ...

def run_until_true(func: object, max_times: int) -> None:
    """重复执行函数直到返回 True"""
    ...

def exit_go_home() -> None:
    """返回桌面并退出脚本"""
    ...

# ============================================================
# 悬浮日志控制台
# ============================================================

class Console:
    """
    悬浮日志控制台 — 在屏幕上显示浮动日志窗口

    支持拖拽移动、窗口大小调节、透明度调节、日志级别过滤、清空日志等功能。

    用法::

        from yyds import console

        console.show()               # 显示悬浮控制台
        console.log("Hello")         # INFO 级别日志
        console.warn("注意")         # WARN 级别日志 (橙色)
        console.error("出错了")      # ERROR 级别日志 (红色)
        console.debug("调试信息")    # DEBUG 级别日志 (蓝色)
        console.clear()              # 清空日志
        console.set_alpha(0.8)       # 设置窗口透明度
        console.hide()               # 最小化控制台
        console.close()              # 关闭控制台

    快捷方式::

        console("Hello World")       # 自动 show + log
    """

    def __call__(self, *args: object, **kwargs) -> None:
        """快捷方式: ``console("msg")`` 等同于 ``console.show()`` + ``console.log("msg")``"""
        ...

    def show(self) -> None:
        """显示悬浮日志控制台"""
        ...

    def hide(self) -> None:
        """最小化控制台 (不关闭, 仅显示标题栏)"""
        ...

    def close(self) -> None:
        """关闭并销毁控制台"""
        ...

    def clear(self) -> None:
        """清空所有日志"""
        ...

    def log(self, *args: object, sep: str = " ") -> None:
        """输出 INFO 级别日志 (绿色)"""
        ...

    def info(self, *args: object, sep: str = " ") -> None:
        """输出 INFO 级别日志 (log 的别名)"""
        ...

    def warn(self, *args: object, sep: str = " ") -> None:
        """输出 WARN 级别日志 (橙色)"""
        ...

    def warning(self, *args: object, sep: str = " ") -> None:
        """输出 WARN 级别日志 (warn 的别名)"""
        ...

    def error(self, *args: object, sep: str = " ") -> None:
        """输出 ERROR 级别日志 (红色)"""
        ...

    def debug(self, *args: object, sep: str = " ") -> None:
        """输出 DEBUG 级别日志 (蓝色)"""
        ...

    def verbose(self, *args: object, sep: str = " ") -> None:
        """输出 VERBOSE 级别日志 (灰色)"""
        ...

    def print(self, *args: object, sep: str = " ", end: str = "\n") -> None:
        """类似 print() 的输出, 显示在控制台"""
        ...

    def assert_(self, condition: bool, *args: object, sep: str = " ") -> None:
        """断言: 条件为 False 时输出 ERROR 日志"""
        ...

    def time(self, label: str = "default") -> None:
        """开始计时"""
        ...

    def time_end(self, label: str = "default") -> None:
        """结束计时并输出耗时 (毫秒)"""
        ...

    def set_alpha(self, alpha: float) -> None:
        """
        设置窗口透明度

        :param alpha: 透明度 (0.2 ~ 1.0, 越大越不透明)
        """
        ...

    def set_size(self, width: int, height: int) -> None:
        """
        设置窗口大小 (dp 单位)

        :param width: 窗口宽度
        :param height: 窗口高度
        """
        ...

    def set_position(self, x: int, y: int) -> None:
        """
        设置窗口位置 (dp 单位)

        :param x: 左上角 X 坐标
        :param y: 左上角 Y 坐标
        """
        ...

    def set_title(self, title: str) -> None:
        """设置控制台标题"""
        ...

console: Console
"""悬浮日志控制台全局实例, 用法: ``console.show()``, ``console.log("hello")``"""
