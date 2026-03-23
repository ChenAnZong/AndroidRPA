import json
import yaml
import shutil
import sys
import time
import traceback
import requests
import os
import random
import re
import hashlib
import importlib
from typing import Union, Tuple, List, Optional
from yyds.auto_entity import *
from yyds.util import *
from yyds.util import _check_xy, _check_str, _check_positive, YydsParamError


class ProjectEnvironment:
    """
    当前工程的运行环境, 工程级的全局变量
    """
    # 默认的截图保存目录
    DEFAULT_SCREEN_SHOT_PATH = "/sdcard/screenshot.png"
    # 默认的控件信息抓取保存目录
    DEFAULT_UI_DUMP_PATH = "/data/local/tmp/dump.xml"
    # 暂停脚本运行标志
    STOP_ENGINE_FLAG = "/data/local/tmp/stop_engine"
    # 当前手机运行的工程目录
    CWD = os.getcwd()
    # 在开发中, 如果是调试模式, 将会打印更多日志
    DEBUG_MODE = False
    PROJECT_NAME = ""
    DEBUG_IP = ""
    IMPORT_JAVA_SUCCESS = False  # IMPORT_JAVA_SUCCESS == Ture 我们可以认定当前代码使用手机引擎运行, 否则在电脑上运行
    # 全局配置(包括ui配置)
    GLOBAL_CONFIG = dict()

    @classmethod
    def current_project(cls) -> str:
        """
        返回当前正在运行的工程目录名(目录名不一定与工程名字相同, 目录名具有唯一性)
        在引擎内部, 将工程目录名视为唯一ID
        """
        if cls.IMPORT_JAVA_SUCCESS:
            return os.path.basename(cls.CWD)
        else:
            return cls.PROJECT_NAME

    @classmethod
    def current_project_dir(cls) -> str:
        """
        返回当前手机上正在运行的工程目录
        """
        return f"/sdcard/Yyds.Py/{cls.current_project()}"


class Config:
    """
    可视作一个小型的配置存储类, 并与设备的ui的配置共享同一个存储位置
    所有配置序列化为将配置保存为json格式, 如需读写大量数据, 请使用 👉`sqlite`
    """

    @classmethod
    def get_config_path(cls) -> str:
        return f"/sdcard/Yyds.Py/config/{ProjectEnvironment.current_project()}.json"

    @classmethod
    def reload_config(cls):
        try:
            with open(cls.get_config_path(), mode="r") as fr:
                ProjectEnvironment.GLOBAL_CONFIG = json.loads(fr.read())
        except:
            pass

    @classmethod
    def read_config_value(cls, config_name: str, read_load=False) -> Union[bool, str, int, None]:
        """
        可同时获取ui配置键值，其中 select(字符串值配置)、edit(字符串值配置)、check(布尔值配置)为配置值与非ui配置键值。

        edit-user:
          \t title: "账号"\n
          \t value: "输入您的账号"

        如 ui 配置如上，则使用 read_config_value("edit-user") 进行获取。

        当用户未在App中配置过该项时，会自动 fallback 到 ui.yml 中定义的 value 默认值。
        仅当 config_name 既不在已保存配置中、也不在 ui.yml 中时返回 None。

        :param config_name: ui名字
        :param read_load: 是否重新读取配置
        :returns: 返回值的解释⚠️
                     - bool: check组件的值
                     - str: edit/select组件的值
                     - int: 返回整数类型值
                     - None: config_name不存在于配置和ui.yml中
        :rtype: Union[bool, str, int, None]
        """
        # 判断是否要进行重新读取
        if read_load or len(ProjectEnvironment.GLOBAL_CONFIG) == 0:
            cls.reload_config()
        if config_name in ProjectEnvironment.GLOBAL_CONFIG and not read_load:
            return ProjectEnvironment.GLOBAL_CONFIG[config_name]
        if config_name in ProjectEnvironment.GLOBAL_CONFIG:
            return ProjectEnvironment.GLOBAL_CONFIG[config_name]
        # fallback: 从 ui.yml 读取默认值
        fallback = cls.read_ui_value(config_name)
        if fallback is not None:
            return fallback
        return None

    @classmethod
    def write_config_value(cls, config_name: str, value):
        """
        利用代码保存配置 (一般比较少用)

        :param config_name: ui名字
        :param value: 值
        :returns: 无
        """
        config_path = cls.get_config_path()
        # 先读取已有配置
        try:
            with open(config_path, mode="r") as fr:
                ProjectEnvironment.GLOBAL_CONFIG = json.loads(fr.read())
        except (FileNotFoundError, json.JSONDecodeError):
            pass
        # 更新并写入
        ProjectEnvironment.GLOBAL_CONFIG[config_name] = value
        os.makedirs(os.path.dirname(config_path), exist_ok=True)
        with open(config_path, mode="w") as fw:
            fw.write(json.dumps(ProjectEnvironment.GLOBAL_CONFIG, ensure_ascii=False))

    @classmethod
    def read_ui_value(cls, config_name: str) -> Union[str, None]:
        """
        直接从工程目录下的 ui.yml 文件读取 value, 利用这个读取函数, 我们可以在ui.yml中配置默认值
        :returns: 读取到的配置值, 如果 config_name 不存在则返回 None
        """
        try:
            if not os.path.exists("ui.yml"):
                return None
            with open(r"ui.yml", mode="r", encoding="utf-8") as fr:
                c = fr.read()
                y = yaml.unsafe_load(c)
                if y and config_name in y and isinstance(y[config_name], dict):
                    return y[config_name].get("value")
                return None
        except Exception:
            return None


class EngineDebug:
    @staticmethod
    def _version() -> str:
        """
        自动化引擎 插件版本号
        """
        return engine_call("/version")

    @staticmethod
    def _pid() -> int:
        """
        :returns: 自动化引擎进程pid
        """
        return int(engine_call("/pid"))

    @staticmethod
    def _uid() -> int:
        """
        - uid = 0, 为ROOT 权限运行
        - uid = 2000, 为SHELL 权限运行

        :returns: 自动化引擎进程uid
        """
        return int(engine_call("/uid"))

    @staticmethod
    def _ping() -> bool:
        """
        自动化引擎 rpc通讯检测
        :returns: 是否通讯成功
        """
        return engine_call("/ping") == "pong"

    @staticmethod
    def _reboot():
        """
        重启自动化引擎
        """
        return engine_call("/reboot")

    @staticmethod
    def _exit():
        """
        结束自动化引擎
        """
        return engine_call("/exit")

    @staticmethod
    def _press_down(x, y):
        """
        模拟单个手指按下坐标
        """
        return engine_call("/touch_down", {"x": x, "y": y})

    @staticmethod
    def _press_up(x, y):
        """
        模拟单个手指弹起坐标
        """
        return engine_call("/touch_up", {"x": x, "y": y})

    @staticmethod
    def _press_move(x, y):
        """
        模拟单个手指移动坐标
        """
        return engine_call("/touch_move", {"x": x, "y": y})

    @staticmethod
    def _reload_py_module(module_name):
        """
        指定重新加载某个python模块

        :param module_name 模块名
        """
        importlib.reload(sys.modules[module_name])


def engine_set_debug(is_debug: bool):
    """
    项目级别的调试标志

    :param is_debug: 是否打印与自动化引擎通讯的日志
    """
    ProjectEnvironment.DEBUG_MODE = is_debug


def __handle_screen_rect_args(args: dict, x=None, y=None, w=None, h=None):
    """
    内部调用 转化图片裁剪参数
    """
    if x is not None:
        args["x"] = x
    if w is not None:
        args["w"] = w
    if h is not None:
        args["h"] = h
    if y is not None:
        args["y"] = y


def __handle_image_path(image) -> str:
    """
    引擎需要传输绝对的文件路径作为参数, 如果传输为相对文件路径, 将转化为拒绝的文件路径
    """
    real_path = os.path.join(ProjectEnvironment.current_project_dir(), image) if os.path.exists(
        os.path.join(ProjectEnvironment.current_project_dir(), image)) else image
    return real_path


try:
    # 下面两句代码调用java, 与自动化引擎进行通讯, 在IDE中识别不到, 会提示错误请忽略!
    from uiautomator import ExportHandle as EngineApi
    from java.util import HashMap

    ProjectEnvironment.IMPORT_JAVA_SUCCESS = True
except:
    # 如果是PC环境运行, 会导入失败, 使用http与引擎进行通讯, 使用在电脑上可以正常运行代码
    from configparser import ConfigParser

    project_config = ConfigParser()
    # 读取调试机IP地址
    config_path = os.path.join(os.getcwd(), "project.config")
    if not os.path.exists(config_path):
        config_path = os.path.join(os.path.dirname(__file__), "../project.config")
    if not os.path.exists(config_path):
        raise RuntimeError("配置文件不存在:" + config_path)
    project_config.read(config_path, "utf-8")
    ProjectEnvironment.DEBUG_IP = project_config["default"]["DEBUG_DEVICE_IP"]
    ProjectEnvironment.PROJECT_NAME = project_config["default"]["PROJECT_NAME"]
    log_d(f"当前连接调试设备IP: {ProjectEnvironment.DEBUG_IP}")


def engine_api(uri: str, options=None) -> str:
    """
    🫶自动化引擎底层RPC调用接口, 如果在安卓中, 则进行反射调用; 如果在电脑上调用, 则使用http调用

    :param uri: 远程接口
    :param options: 参数，字典类型，所有键值都应为str类型
    :returns: 引擎返回的原始字符串
    """

    # 增加脚本暂停功能, 最大暂停时间2分钟
    count = 0
    while sys.platform == "linux" and os.path.exists(ProjectEnvironment.STOP_ENGINE_FLAG):
        if count == 1:
            print("当前引擎被暂停!最大暂停时间2分钟")
        time.sleep(3)
        count += 1
        if count > 40:
            try:
                os.remove(ProjectEnvironment.STOP_ENGINE_FLAG)
            except FileNotFoundError:
                pass
            finally:
                break

    if options is None:
        options = {}
    _use_java = ProjectEnvironment.IMPORT_JAVA_SUCCESS and os.environ.get('YYDS_SUBPROCESS') != '1'
    if _use_java:
        params = HashMap()
        if options:
            for key in options.keys():
                params.put(key, str(options[key]))
        try:
            ret = EngineApi.http(uri, params)
        except Exception as e:
            ret = json.dumps({"ok": False, "error": f"执行错误:{e}"})
    else:
        # HTTP调用: PC端用DEBUG_IP, 子进程用127.0.0.1走Ktor代理
        _is_sub = os.environ.get('YYDS_SUBPROCESS') == '1'
        _host = "127.0.0.1" if _is_sub else ProjectEnvironment.DEBUG_IP
        _timeout = 10 if _is_sub else 30
        _retries = 1 if _is_sub else 3
        url = f"http://{_host}:61140/api{uri}"
    if not ProjectEnvironment.IMPORT_JAVA_SUCCESS and ProjectEnvironment.DEBUG_MODE:
        print(url)
    if not _use_java:
        for _retry in range(_retries):
            try:
                ret = requests.post(url, json=options, timeout=_timeout).text
                break
            except (requests.ConnectionError, requests.Timeout) as e:
                if _retry < _retries - 1:
                    time.sleep(2)
                else:
                    ret = json.dumps({"ok": False, "error": f"连接失败 {e}"})
    if ProjectEnvironment.DEBUG_MODE:
        t = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime())
        print(f"{t}:{uri}__{options}___))){ret})))))))\n")
    return ret


def engine_call(uri: str, options=None):
    """
    调用引擎接口并自动解析JSON返回值。
    返回 data 字段的值；如果接口报错则抛出 RuntimeError。

    :param uri: 接口路径
    :param options: 参数字典
    :returns: JSON中data字段的值
    """
    raw = engine_api(uri, options)
    try:
        resp = json.loads(raw)
        if isinstance(resp, dict) and "ok" in resp:
            if resp["ok"]:
                data = resp.get("data")
                # 引擎部分接口将 JSON 数组/对象序列化为字符串返回, 这里做二次解析
                if isinstance(data, str) and data and data[0] in ('[', '{'):
                    try:
                        data = json.loads(data)
                    except (json.JSONDecodeError, TypeError):
                        pass
                return data
            else:
                raise RuntimeError(f"引擎接口错误 [{uri}]: {resp.get('error', 'unknown')}")
        # 兼容: 如果返回的不是标准JSON格式, 原样返回
        return raw
    except (json.JSONDecodeError, TypeError):
        return raw


def click(x: Union[str, int], y: Union[str, int], count: int = 1, interval: int = 50, **kwargs) -> bool:
    """
    点击坐标点

    :param x: 屏幕绝对坐标，不支持小数
    :param y: 屏幕绝对坐标，不支持小数
    :param count: 点击次数, 大于1时连续点击 (适用于抢购、开宝箱等场景)
    :param interval: 连点间隔 (毫秒)
    """
    # 向后兼容旧参数名
    if 'click_time' in kwargs:
        count = kwargs['click_time']
    _check_xy("click", x, y)
    x, y = int(x), int(y)
    x += random.randint(-3, 3)
    y += random.randint(-5, 5)
    x = max(1, abs(x))
    y = max(1, abs(y))
    return engine_call("/touch", {"x": int(x), "y": int(y), "time": count, "interval": interval}) is not False


def update_language(code: str) -> bool:
    """
    设置安卓系统语言
    :param code: 语言码-国家码, 如 en-us
    """
    engine_call("/update_language", {"code": code})
    return True


def media_scan_file(path: str) -> bool:
    """
    安卓系统媒体文件扫描

    :param path: 文件路径, 如多个使用;分隔
    """
    return bool(engine_call("/media_scan", {"path": path}))


def random_click(x: int, y: int, w: int, h: int):
    """
    在指定坐标附近随机点击 (模拟人工操作, 防检测)

    :param x: 中心坐标 x
    :param y: 中心坐标 y
    :param w: 横向随机偏移范围 (像素)
    :param h: 纵向随机偏移范围 (像素)
    """
    x = x
    y = y
    y += h * 0.25 + random.uniform(h * 0.25, h * 0.75)
    x += w * 0.25 + random.uniform(w * 0.25, w * 0.75)
    click(x, y)


def md5_file(path) -> str:
    """
    计算文件MD5

    :param path: 文件路径
    :returns: 32位md5计算结果
    """
    md5 = hashlib.md5()
    f = open(path, mode="rb")
    md5.update(f.read())
    f.close()
    md5_sum = md5.hexdigest()
    return md5_sum


def md5_str(text) -> str:
    """
    计算文本MD5

    :param text: 文本内容
    :returns: 文本MD5
    """
    md5 = hashlib.md5()
    md5.update(text.encode("utf-8"))
    md5_sum = md5.hexdigest()
    return md5_sum


def toast(content: str):
    """
    :param content: 提示内容

    提示:某些机型需要打开 应用后台窗口弹出权限
    """
    engine_call("/toast", {"content": content})


def screenshot(path=None) -> str:
    """
    屏幕截图, 注意横屏截图与竖屏对截图对像素是不一样的

    :param path: 截图保存路径, 此接口无需申请任何权限
    :return: 截图对最终保存路径
    """
    if not path:
        path = ProjectEnvironment.DEFAULT_SCREEN_SHOT_PATH
    return engine_call('/screenshot', {"path": path})


def ocr(image=None, x=None, y=None, w=None, h=None, use_gpu=False) -> list:
    """
    使用引擎识别当前屏幕的文字, 可指定识别范围。返回JSON数组。

    :param image: 若image==None, 则截取屏幕进行识别; 若image为文件路径, 则识别该图片
    :param x: 识别起始点 可以使用相对坐标(0-1)
    :param y: 识别起始点 可以使用相对坐标(0-1)
    :param w: 宽
    :param h: 高
    :param use_gpu: 是否使用Gpu运算
    :returns: OCR识别结果列表, 每项包含 text, confidence, x, y, w, h
    """
    args = {"use_gpu": "true" if use_gpu else "false"}
    __handle_screen_rect_args(args, x, y, w, h)
    if image is None:
        data = engine_call("/screen_ocr", args)
    elif isinstance(image, str):
        image_path = __handle_image_path(image)
        args["path"] = image_path
        data = engine_call("/image_ocr", args)
    else:
        return []
    return data if isinstance(data, list) else []


def screen_yolo_locate(x=None, y=None, w=None, h=None, use_gpu=True) -> list:
    """
    使用yolo查找目标, 返回JSON数组

    :param x: 识别起始点 可以使用相对坐标(0-1)
    :param y: 识别起始点 可以使用相对坐标(0-1)
    :param w: 宽 可以使用相对坐标(0-1)
    :param h: 高 可以使用相对坐标(0-1)
    :param use_gpu: 是否使用gpu运行
    :returns: YOLO检测结果列表, 每项包含 label, confidence, x, y, w, h
    """
    args = {"use_gpu": "true" if use_gpu else "false"}
    __handle_screen_rect_args(args, x, y, w, h)
    data = engine_call("/yolo_detect", args)
    return data if isinstance(data, list) else []


def screen_find_image(*img, x=None, y=None, w=None, h=None, threshold: int = -1) -> list:
    """
    在屏幕上同时寻找多张图片, 可以指定范围。返回JSON数组。

    :param img: 图片路径, 建议使用相对路径
    :param x: 识别起始点 可以使用相对坐标(0-1)或绝对像素值
    :param y: 识别起始点 可以使用相对坐标(0-1)或绝对像素值
    :param w: 宽 可以使用相对坐标(0-1)或绝对像素值
    :param h: 高 可以使用相对坐标(0-1)或绝对像素值
    :param threshold: 图片预处理参数
    """

    # 如果脚本在电脑上运行, 需要将图片文件提交到手机指定目录
    if not ProjectEnvironment.IMPORT_JAVA_SUCCESS:
        imgs_ = []
        if img[0] is tuple:
            img = img[0]
        for pi in img:
            imgs_.append(os.path.join(ProjectEnvironment.current_project_dir(), pi))
            if not post_file(pi, os.path.join(ProjectEnvironment.current_project_dir(), os.path.dirname(pi))):
                raise RuntimeError(f"提交文件到手机: {pi} -> {ProjectEnvironment.current_project_dir()}/{pi} 失败☹️")
            if ProjectEnvironment.DEBUG_MODE:
                log_d(f"调试:提交文件到手机: {pi} -> {ProjectEnvironment.current_project_dir()}/{pi} 成功")
    else:
        imgs_ = [__handle_image_path(i) for i in img]
    args = {"templates": ";".join(imgs_), "threshold": threshold}
    __handle_screen_rect_args(args, x, y, w, h)
    data = engine_call("/find_image", args)
    return data if isinstance(data, list) else []


def ui_dump_xml(path=None, all_window=False) -> str:
    """
    扫描控件布局xml到本地

    :param path: 保存到本地路径
    :param all_window: 是否查询所有窗口
    :returns: 保存的路径
    """
    if path is None:
        path = ProjectEnvironment.DEFAULT_UI_DUMP_PATH
    return engine_call("/uia_dump", {"path": path, "all_window": "true" if all_window else "false"})


def match_images(template_image: str, prob: float, threshold=0, image=None, x=None, y=None, w=None, h=None) -> list:
    """
    对图片在指定范围内进行多次匹配

    :param image: 如image == None, 则进行截图作为识别图片
    :param template_image: 匹配模版
    :param prob: 限制最低相似度, 数字越大, 匹配精确度越高, 数值范围0-1.0
    :param threshold: 图片预处理, 请参考 `screen_find_image`
    """
    args = dict()
    __handle_screen_rect_args(args, x, y, w, h)
    args["threshold"] = threshold
    args["prob"] = prob
    args["template"] = __handle_image_path(template_image)
    if image is not None:
        args["image"] = __handle_image_path(image)
    data = engine_call("/match_image", args)
    if isinstance(data, list):
        return [MatchImageResult(item["x"], item["y"], item["w"], item["h"], item["prob"]) for item in data]
    return []


def find_color(base_rgb: str, bias_points: [str] = [], max_fuzzy: int = 3, step_x: int = 5, step_y: int = 5, image=None,
               x=None, y=None, w=None, h=None, max_counts: int = 1) -> [Point]:
    """
    单(多)点找色, 返回匹配颜色的到的坐标

    :param base_rgb: 基点RGB字符串, 格式为R,G,B
    :param bias_points: 偏移点的偏移坐标与RGB字符串
    :param max_fuzzy: 找色时颜色相似度的临界值，范围为0 ~ 255
    :param step_x: 横向偏离像素
    :param step_y: 竖向偏离像素
    :param image: 如image == None, 则进行截图作为识别图片
    :param max_counts: 最大成功匹配坐标点数
    :return: 找到符合颜色的屏幕坐标点
    """
    _check_str("find_color", "base_rgb", base_rgb)
    parts = base_rgb.split(",")
    if len(parts) != 3 or not all(p.strip().isdigit() for p in parts):
        raise YydsParamError(
            f'find_color(): base_rgb 格式应为 "R,G,B" (如 "255,0,128"), 收到 {base_rgb!r}'
        )
    args = dict()
    __handle_screen_rect_args(args, x, y, w, h)
    args["rgb"] = base_rgb
    args["prob"] = max_fuzzy
    args["max_counts"] = max_counts
    args["step_x"] = step_x
    args["step_y"] = step_y
    args["points"] = "\n".join(bias_points)
    if image is not None:
        args["image"] = __handle_image_path(image)
    data = engine_call("/find_color", args)
    if isinstance(data, dict):
        if data.get("found"):
            return [Point(data["x"], data["y"])]
        return []
    if isinstance(data, list):
        return [Point(item["x"], item["y"]) for item in data if item.get("found", True)]
    return []


def get_color(x: int, y: int, image=None) -> Color:
    """
    获取图片指定坐标的颜色

    :param x: 整数坐标
    :param y: 整数坐标
    :param image: 如image == None, 则进行截图作为识别图片
    :returns: RGB颜色
    """
    args = dict()
    if image is not None:
        args["image"] = __handle_image_path(image)
    args["x"] = x
    args["y"] = y
    data = engine_call("/get_color", args)
    if isinstance(data, dict):
        return Color(data["r"], data["g"], data["b"])
    return EngineResultParser.parse_color(str(data))


def get_multi_color(points: [(int, int)], image=None) -> (Color,):
    """
    获取图片多个坐标的颜色

    :param points: 如[(100, 255), [45, 588]]
    :param image: 如image == None, 则进行截图作为识别图片
    :returns: RGB颜色数组
    """
    args = dict()
    if image is not None:
        args["image"] = __handle_image_path(image)
    args["points"] = " ".join([f"{x},{y}" for x, y in points])
    data = engine_call("/get_color", args)
    if isinstance(data, list):
        return tuple(Color(c["r"], c["g"], c["b"]) for c in data)
    return EngineResultParser.parse_multi_color(str(data))


def set_touch_mode(mode: str) -> str:
    """
    设置触控模式

    :param mode: 触控模式, 可选值: java / kernel / uinput / auto
    :returns: 当前触控模式
    """
    return engine_call("/set_touch_mode", {"mode": mode})


def get_touch_mode() -> str:
    """
    获取当前触控模式

    :returns: 当前触控模式字符串
    """
    data = engine_call("/get_touch_mode")
    if isinstance(data, dict):
        return data.get("mode", "unknown")
    return str(data)


def ensure_kernel_click():
    """
    设置引擎使用内核点击 (等同于 set_touch_mode("kernel"))
    """
    return set_touch_mode("kernel")


def cancel_kernel_click():
    """
    取消内核点击, 使用Java层点击 (等同于 set_touch_mode("java"))
    """
    return set_touch_mode("java")


def pull_file(remote: str, local: str) -> bool:
    """
    从手机拉取文件到本地

    :param remote: 远程手机文件路径
    :param local: 本地文件路径
    :returns: 是否操作成功
    """
    # 优先尝试 HTTP 接口
    try:
        r = requests.get(f"http://{ProjectEnvironment.DEBUG_IP}:61140/pull-file?path={remote}",
                         stream=True, timeout=10)
        if r.status_code == 200 and int(r.headers.get("content-length", 0)) > 0:
            with open(local, 'wb+') as f:
                r.raw.decode_content = True
                shutil.copyfileobj(r.raw, f)
            return True
    except Exception:
        pass
    # fallback: 通过 adb pull
    try:
        import subprocess
        ret = subprocess.run(["adb", "pull", remote, local],
                             capture_output=True, timeout=30)
        return ret.returncode == 0
    except Exception:
        return False


def post_file(local: str, remote_dir: str = "/sdcard") -> bool:
    """
    从本地推送文件到手机

    :param local: 本地文件绝对路径
    :param remote_dir: 远程手机目录, 默认为/sdcard
    :returns: 是否操作成功
    """
    # 优先尝试 HTTP multipart 接口
    try:
        fname = os.path.basename(local)
        with open(local, mode="rb") as f:
            r = requests.post(
                f"http://{ProjectEnvironment.DEBUG_IP}:61140/post-file",
                data={"path": remote_dir},
                files={fname: f.read()},
                timeout=30,
            )
        if r.status_code < 300:
            return True
    except Exception:
        pass
    # fallback: 通过 adb push
    try:
        import subprocess
        remote_path = remote_dir.rstrip("/") + "/" + os.path.basename(local)
        ret = subprocess.run(["adb", "push", local, remote_path],
                             capture_output=True, timeout=30)
        return ret.returncode == 0
    except Exception:
        return False
