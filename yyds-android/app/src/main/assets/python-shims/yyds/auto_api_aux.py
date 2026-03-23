from .auto_api import *
from .util import _check_xy, _check_str, _check_positive, YydsParamError


def click_double(x: Union[str, int], y: Union[str, int]):
    """
    双击坐标点
    """
    _check_xy("click_double", x, y)
    click(x, y)
    time.sleep(0.29)
    click(x, y)


def swipe(x1, y1, x2, y2, duration, is_random: bool = False):
    """
    滑动

    :param x1: 起始坐标 x
    :param y1: 起始坐标 y
    :param x2: 目标坐标 x
    :param y2: 目标坐标 y
    :param duration: 滑动耗时（毫秒） 越小滑动越快
    :param is_random: 是否随机进行滑动
    """
    _check_xy("swipe", x1, y1)
    _check_xy("swipe", x2, y2)
    _check_positive("swipe", "duration(滑动时长)", duration)
    engine_call("/swipe", {"x1": int(x1), "x2": int(x2),
                          "y1": int(y1), "y2": int(y2), "duration": int(duration), "random": is_random})


def key_back():
    """
    注入返回键
    """
    engine_call('/key_code', {"code": "4"})


def key_home():
    """
    注入home键
    """
    engine_call('/key_code', {"code": "3"})


def key_menu():
    """
    注入菜单键
    """
    engine_call("/key_code", {"code": "82"})


def key_confirm():
    """
    确认键，一般用于编辑框确认搜索，确认提交
    """
    return engine_call("/key_confirm")


def key_code(code):
    """
    注入键值码

    :param code: 键值码
    """
    engine_call("/key_code", {"code": int(code)})


def device_get_screen_size() -> (int, int):
    """
    获取屏幕分辨率大小

    :returns: 宽, 高
    """
    data = engine_call("/screen_size")
    if isinstance(data, dict):
        # 兼容新旧两种字段名: width/height 和 w/h
        w = data.get("width", data.get("w", 0))
        h = data.get("height", data.get("h", 0))
        return int(w), int(h)
    # 兼容旧格式
    ret = str(data)
    x, y = ret.split(",")
    return int(x), int(y)


def stop_app(pkg):
    """
    停止应用运行

    :returns: 无
    """
    _check_str("stop_app", "pkg(包名)", pkg)
    return shell(f"am force-stop {pkg}")


def open_app(pkg):
    """
    根据包名打开应用

    :param pkg: 应用包名, 如 "com.tencent.mm"
    :returns: 无
    """
    _check_str("open_app", "pkg(包名)", pkg)
    return engine_call("/open_app", {"pkg": pkg})


def open_url(url):
    """
    通过匹配intent打开网页, 电话等页面

    :returns: intent 命令的输出
    """
    return engine_call("/open_url", {"url": url})


def device_foreground() -> Optional[DeviceForegroundResponse]:
    """
    获取当前设备前台运行信息

    :returns: 前台运行信息
    """
    data = engine_call('/foreground')
    if isinstance(data, dict):
        return DeviceForegroundResponse(data.get("pkg", ""), data.get("activity", ""), data.get("pid", ""))
    # 兼容旧格式
    s = str(data).split(" ")
    if len(s) < 2:
        return None
    return DeviceForegroundResponse(s[0], s[1], s[2])


def device_foreground_activity() -> str:
    """
    比device_foreground 更快

    :returns: 当前活动界面名
    """
    data = engine_call("/foreground_activity")
    return str(data) if data else ""


def device_foreground_package() -> str:
    """
    比device_foreground 更快

    :returns: 当前前台包名
    """
    data = engine_call("/foreground_package")
    return str(data) if data else ""


def is_app_running(pkg: str) -> bool:
    """
    :returns: app是否在后台运行
    """
    _check_str("is_app_running", "pkg(包名)", pkg)
    return bool(engine_call("/is_app_running", {"pkg": pkg}))


def bring_app_to_top(pkg: str) -> bool:
    """
    将后台运行的应用带回前台

    :returns: 是否操作成功
    """
    try:
        return bool(engine_call("/bring_app_to_top", {"pkg": pkg}))
    except RuntimeError:
        # 引擎未实现该端点时, 通过 monkey 命令将应用拉到前台
        try:
            shell(f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1")
            return True
        except Exception:
            return False


def is_in_app(pkg: str) -> bool:
    """
    当前是否在某应用界面内

    :param pkg: 应用包名, 如 "com.tencent.mm"
    """
    _check_str("is_in_app", "pkg(包名)", pkg)
    return pkg == device_foreground_package()


def device_code() -> str:
    """
    设备唯一硬件码

    :returns: 唯一设备硬件码
    """
    return str(engine_call("/device_code"))


def device_model() -> str:
    """
    获取当前手机型号
    """
    return str(engine_call("/device_model"))


def is_net_online() -> bool:
    """
    测试当前网络是否畅通

    :returns: 当前设备网络是否连通
    """
    return bool(engine_call("/is_net_online"))


def model_yolo_reload(model_dir=None, ncnn_bin_path=None, ncnn_param_path=None):
    """
    加载自定义 YOLO 模型

    方式一（推荐）: model_yolo_reload("/sdcard/my_model/")
      自动查找目录下的 .param, .bin, classes.txt

    方式二（兼容旧版）: model_yolo_reload(ncnn_bin_path="...", ncnn_param_path="...")

    :param model_dir: 模型目录路径（包含 .param + .bin + 可选 classes.txt）
    :param ncnn_bin_path: bin文件路径（兼容旧版）
    :param ncnn_param_path: param文件路径（兼容旧版）
    """
    # 向后兼容: 旧版调用 model_yolo_reload("/path/a.bin", "/path/a.param")
    # 此时 model_dir 实际是 bin 路径, ncnn_bin_path 实际是 param 路径
    if model_dir and ncnn_bin_path and ncnn_param_path is None:
        if model_dir.endswith(".bin") and ncnn_bin_path.endswith(".param"):
            ncnn_param_path = ncnn_bin_path
            ncnn_bin_path = model_dir
            model_dir = None
        elif model_dir.endswith(".param") and ncnn_bin_path.endswith(".bin"):
            ncnn_param_path = model_dir
            ncnn_bin_path = ncnn_bin_path
            model_dir = None

    if model_dir:
        engine_call("/set_yolo_model", {"dir": model_dir})
    elif ncnn_bin_path and ncnn_param_path:
        engine_call("/set_yolo_model", {
            "dir": "",
            "bin_file_path": ncnn_bin_path,
            "param_file_path": ncnn_param_path
        })
    else:
        # 重置为默认模型
        engine_call("/set_yolo_model", {"dir": ""})


def model_yolo_info() -> dict:
    """
    获取当前 YOLO 模型信息

    :returns: 包含 loaded, user_model_dir, has_user_model, has_builtin_model 等字段
    """
    return engine_call("/yolo_model_info")


def model_ocr_reload(ncnn_bin_path, ncnn_param_path):
    """
    自定义pp ocr模型

    :param ncnn_bin_path: bin文件路径
    :param ncnn_param_path: param文件路径
    """
    engine_call("/set_ocr_model", {
        "bin_file_path": ncnn_bin_path,
        "param_file_path": ncnn_param_path
    })


def ui_match(all_window=False, match_from_cache=False, limit=9999, **match_params) -> List[Node]:
    """
    扫描当前屏幕所有ui控件并进行匹配

    :param all_window: 是否查找所有窗口
    :param match_params: 匹配参数
    :param limit: 最多查找多少个就返回
    :param match_from_cache: 是否从引擎缓存中拉取控件
    :returns: 匹配到的节点数组
    """
    if not match_params:
        import warnings
        warnings.warn(
            "ui_match(): 未传入任何匹配条件, 将返回屏幕上所有控件。"
            "常用参数: text='文字', res='资源ID', cls='类名', desc='描述'",
            stacklevel=2
        )
    params_ = {"match_from_cache": "true" if match_from_cache else "false"}
    for k in match_params.keys():
        value = match_params[k]
        if isinstance(value, bool):
            value = str(value).lower()
        if k == "class_":
            params_["class"] = value
        else:
            params_[str(k).replace("_", "-")] = value
    if not match_from_cache and all_window:
        params_["all_window"] = "true"
    data = engine_call("/uia_match", params_)
    if isinstance(data, list):
        return [Node(i) for i in data]
    return []


def ui_parent(node: Node) -> List[Node]:
    """
    获取一个节点的父节点

    :returns: 匹配到的节点数组
    """
    params_ = {
        "hashcode": node.hash_code,
        "dump_time_ms": node.dump_time_ms,
        "type": "parent"
    }
    data = engine_call("/uia_relation", params_)
    if isinstance(data, list):
        return [Node(i) for i in data]
    return []


def ui_child(node: Node) -> List[Node]:
    """
    获取一个节点的子节点

    :returns: 匹配到的节点数组
    """
    params_ = {
        "hashcode": node.hash_code,
        "dump_time_ms": node.dump_time_ms,
        "type": "child"
    }
    data = engine_call("/uia_relation", params_)
    if isinstance(data, list):
        return [Node(i) for i in data]
    return []


def ui_sib(node: Node) -> List[Node]:
    """
    获取一个节点的兄弟节点, 结果不包含自己

    :returns: 匹配到的节点数组
    """
    params_ = {
        "hashcode": node.hash_code,
        "dump_time_ms": node.dump_time_ms,
        "type": "sib"
    }
    data = engine_call("/uia_relation", params_)
    if isinstance(data, list):
        return [Node(i) for i in data]
    return []


def ui_sib_offset(node: Node, next_count: int = 1) -> Union[Node, None]:
    """
    获取指定偏移量的兄弟节点

    :param node: 当前节点
    :param next_count: 偏移个兄弟节点, 默认是下一个, 可以为负数则向上找
    :returns: 匹配到的节点, 未找到返回 None
    """
    sibs = ui_sib(node)
    if sibs:
        for n in sibs:
            if n.index == node.index + next_count:
                return n
        return None
    else:
        return None


# 向后兼容别名 (旧拼写, 已废弃)
ui_sid_offset = ui_sib_offset


def ui_exist(all_window=False, match_from_cache=False, **match_params) -> bool:
    """
    检查符合条件的控件是否存在, 更多细节参考`ui_match`
    :param all_window: 是否查找所有窗口
    :param match_from_cache: 是否从引擎缓存中拉取控件, 而不是从系统从新获取控件; 适合于确保当前画面没有变化的界面, 提高运行效率
    :param 从xml匹配的key-value, 支持java正则, 所有value为字符串形式如visible_to_user="true"
    :returns: ui是否存在
    """
    return len(ui_match(all_window, match_from_cache, limit=1, **match_params)) >= 1


def shell(*cmd):
    """
    执行shell脚本
    :returns: shell执行输出
    """
    return engine_call("/shell", {"cmd": ";".join(cmd)})


def screen_find_image_x(fd_images: Union[Tuple[str, ...], Tuple[RequestFindImage, ...]],
                        min_prob: float = 0.5, x=None, y=None, w=None, h=None, threshold: int = -1) \
        -> Tuple[ResFindImage]:
    """
    对于同时查找多张图片的封装

    :param fd_images: 需要查找的图片
    :param min_prob: 最低置信率
    :param x: 识别起始点
    :param y: 识别起始点
    :param w: 宽
    :param h: 高
    :param threshold: 图片预处理方式
    :returns: 找图结果
    """
    in_list: List[RequestFindImage] = list()
    for fd in fd_images:
        if type(fd) is str:
            in_list.append(RequestFindImage(fd, fd, min_prob))
        elif type(fd) is RequestFindImage:
            if fd.prob == 0:
                fd.prob = min_prob
            in_list.append(fd)

    fd_paths: List[str] = list()
    for it in in_list:
        fd_paths.append(it.path)
    data = screen_find_image(*fd_paths, x=x, y=y, w=w, h=h, threshold=threshold)
    results: List[ResFindImage] = list()
    if isinstance(data, list):
        for idx, item in enumerate(data):
            if idx < len(in_list):
                it = in_list[idx]
                fd_image = ResFindImage(
                    it.name,
                    item.get("path", it.path),
                    float(item.get("prob", 0)),
                    int(item.get("x", 0)),
                    int(item.get("y", 0)),
                    int(item.get("x", 0)) + int(item.get("w", 0)),
                    int(item.get("y", 0)) + int(item.get("h", 0))
                )
                if fd_image.prob >= it.min_prob:
                    results.append(fd_image)
    return tuple(results)


def screen_find_image_first_x(fd_images: Tuple[Union[str, RequestFindImage]], min_prob: float = 0.9,
                              x=None, y=None, w=None, h=None, threshold: int = -1) -> Union[ResFindImage, None]:
    """
    屏幕查找图片, 仅返回第一张查找结果

    :param fd_images: 需要查找的图片
    :param min_prob:  最低置信率
    :param x: 识别起始点 可以使用相对坐标(0-1)
    :param y: 识别起始点 可以使用相对坐标(0-1)
    :param w: 宽 可以使用相对坐标(0-1)
    :param h: 高 可以使用相对坐标(0-1)
    :param threshold: 图片预处理方式 参考`screen_find_image()`
    :return: 第一张查找到到图片
    """
    find_images = screen_find_image_x(*fd_images, min_prob=min_prob, x=x, y=y, w=w, h=h, threshold=threshold)
    if len(find_images) > 0:
        return find_images[0]
    return None


def screen_yolo_find_x(specify_labels=None, min_prob: float = 0.9, x=None, y=None, w=None, h=None, use_gpu=False) \
        -> Tuple[ResYolo]:
    """
    通过yolo算法识别当前屏幕内容

    :param specify_labels: 指定label
    :param min_prob: 最低置信率
    :param use_gpu: 是否使用Gpu运算
    :returns: 识别结果列表
    """
    if specify_labels is None:
        specify_labels = []
    data = screen_yolo_locate(use_gpu=use_gpu, x=x, y=y, w=w, h=h)
    results: List[ResYolo] = list()
    if isinstance(data, list):
        for item in data:
            res_yolo = ResYolo(
                item.get("label", ""),
                int(item.get("cx", 0)),
                int(item.get("cy", 0)),
                float(item.get("x", 0)),
                float(item.get("y", 0)),
                float(item.get("w", 0)),
                float(item.get("h", 0)),
                float(item.get("confidence", item.get("prob", 0))),
            )
            if res_yolo.prob >= min_prob:
                if len(specify_labels) > 0:
                    for it in specify_labels:
                        if re.match(it, res_yolo.label):
                            results.append(res_yolo)
                else:
                    results.append(res_yolo)
    return tuple(results)


def screen_yolo_find_first_x(labels=None, prob: float = 0.9, x=None, y=None, w=None, h=None, use_gpu=False) \
        -> Union[ResYolo, None]:
    """
    :returns: 请参考yolo_find_x, 返回第一个结果
    """
    if labels is None:
        labels = []
    if isinstance(labels, str):
        labels = (labels,)
    find_yolo_results = screen_yolo_find_x(labels, prob, x=x, y=y, w=w, h=h, use_gpu=use_gpu)
    if len(find_yolo_results) > 0:
        return find_yolo_results[0]
    return None


def screen_ocr_x(specific_texts: Union[list, tuple] = None, x=None, y=None, w=None, h=None, use_gpu=False) \
        -> Tuple[ResOcr]:
    """
    使用内置ocr模型 对当前屏幕进行 OCR 识别

    :param specific_texts: 指定查找文本, 支持正则
    :param x: 识别起始点
    :param y: 识别起始点
    :param w: 宽
    :param h: 高
    :param use_gpu: 是否使用Gpu运算
    :returns: OCR识别结果列表
    """
    if isinstance(specific_texts, str):
        specific_texts = (specific_texts,)
    if specific_texts is None:
        specific_texts = []
    data = ocr(x=x, y=y, w=w, h=h, use_gpu=use_gpu)
    results: List[ResOcr] = list()
    if isinstance(data, list):
        for item in data:
            text = item.get("text", "")
            prob = item.get("confidence", item.get("prob", 0))
            box = item.get("box", {})
            # box 格式: {x1,y1, x2,y2, x3,y3, x4,y4} 四个角点
            x1 = int(box.get("x1", 0))
            y1 = int(box.get("y1", 0))
            x2 = int(box.get("x2", 0))
            y2 = int(box.get("y2", 0))
            x3 = int(box.get("x3", 0))
            y3 = int(box.get("y3", 0))
            x4 = int(box.get("x4", 0))
            y4 = int(box.get("y4", 0))
            res = ResOcr(prob, text, x1, y1, x2, y2, x3, y3, x4, y4)
            for find_text in specific_texts:
                if re.match(find_text, res.text):
                    results.append(res)
                    break
    return tuple(results)


def screen_ocr_first_x(specific_texts=Union[list, tuple], x=None, y=None, w=None, h=None, use_gpu=False) \
        -> Union[ResOcr, None]:
    """
    OCR 识别, 只获取第一个返回结果
    """
    if specific_texts is None:
        specific_texts = []
    if isinstance(specific_texts, str):
        specific_texts = (specific_texts,)
    find_ocr_results = screen_ocr_x(specific_texts, x=x, y=y, w=w, h=h, use_gpu=use_gpu)
    if len(find_ocr_results) > 0:
        return find_ocr_results[0]
    return None


def image_similarity(img1: str, img2: str, flags: int = 0) -> float:
    """
    计算两张图片相似度

    :param img1: 图片1的路径
    :param img2: 图片2的路径
    :param flags: 对比算法
    :returns: 0-1.0的浮点数
    """
    _check_str("image_similarity", "img1(图片路径)", img1)
    _check_str("image_similarity", "img2(图片路径)", img2)
    data = engine_call("/image_similarity", {"image1": img1, "image2": img2, "flags": flags})
    return float(data) if data is not None else 0.0


def input_text(text: str) -> int:
    """
    注入文本, 不支持中文

    :param text: 文本 (ascii)
    :returns: 注入成功的字符个数
    """
    _check_str("input_text", "text", str(text), allow_empty=True)
    if any(ord(c) > 127 for c in str(text)):
        import warnings
        warnings.warn(
            f"input_text(): 检测到非 ASCII 字符, 此接口不支持中文输入。"
            f"如需输入中文, 请使用 set_clipboard() + 粘贴 的方式",
            stacklevel=2
        )
    data = engine_call("/inject_text", {"text": str(text)})
    return int(data) if data is not None else 0


def x_input_text(text: str) -> bool:
    """
    通过YY输入法输入文本

    :returns: 是否发送成功
    """
    return bool(engine_call("/xinput_text", {"text": str(text)}))


def x_input_clear() -> bool:
    """
    通过YY输入法清空编辑框文本

    :returns: 是否发送成功
    """
    return bool(engine_call("/xinput_clear"))


def set_yy_input_enable(enable: bool) -> bool:
    """
    启用或禁用YY输入法

    :param enable: 是否启用
    :returns: 是否已启用
    """
    return bool(engine_call("/set_yy_input", {"enable": "true" if enable else "false"}))


def app_data_backup(pkg: str, path: str) -> bool:
    """
    备份应用数据

    :param pkg: 应用包名
    :param path: 备份路径 (tar格式)
    :returns: 是否成功
    """
    return bool(engine_call("/backup_app_data", {"package": pkg, "path": path}))


def app_data_recovery(pkg: str, path: str) -> bool:
    """
    还原应用数据

    :param pkg: 应用包名
    :param path: 备份文件路径
    :returns: 是否成功
    """
    return bool(engine_call("/recovery_app_data", {"package": pkg, "path": path}))


def app_apk_backup(pkg: str, path: str) -> bool:
    """
    提取备份应用安装包(apk), 保存到设备指定位置

    :param pkg: 应用包名
    :param path: 备份到手机路径
    :returns: 是否成功
    """
    apk_path = shell(f"pm path {pkg}").replace("package:", "")
    if "data" in apk_path:
        shell(f"cat {apk_path} > {path}")
        return True
    else:
        return False


def app_apk_install(path: str) -> bool:
    """
    进行 apk 安装

    :param path: APK文件路径
    :returns: 是否安装成功
    """

    # 如果apk文件在外置存储目录, 我们需要移动到其它可以安装到位置, 否则会报错!
    if "/sdcard/" in path or "/storage/emulated/0/" in path:
        return "success" in shell(
            f"mv {path} /data/local/tmp/temp.apk && pm install -r /data/local/tmp/temp.apk && echo success")
    return "success" in shell(f"pm install -r {path} && echo success")


def set_clipboard(text: str):
    """
    复制文本到粘贴板

    :param text: 要复制的文本
    """
    engine_call("/set_clipboard", {"text": text})


def get_clipboard() -> str:
    """
    获取粘贴板文本

    :returns: 粘贴板文本
    """
    data = engine_call("/get_clipboard", {})
    return str(data) if data else ""
