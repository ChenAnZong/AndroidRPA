/**
 * Yyds.Auto Python SDK API 数据定义
 * 为 CompletionProvider / HoverProvider / SignatureHelpProvider 提供数据源
 */

export interface YydsApiParam {
    name: string;
    type: string;
    doc: string;
    default?: string;
}

export interface YydsApiEntry {
    name: string;
    kind: 'function' | 'class' | 'method' | 'property' | 'decorator';
    module: string;
    doc: string;
    signature?: string;
    params?: YydsApiParam[];
    returnType?: string;
    detail?: string;
}

// ============================================================
// 完整 API 数据表
// ============================================================
export const YYDS_API_DATA: YydsApiEntry[] = [
    // === 触摸与点击 ===
    {
        name: 'click', kind: 'function', module: 'yyds',
        doc: '点击屏幕坐标',
        signature: 'click(x, y, click_time=1, interval=50)',
        params: [
            { name: 'x', type: 'int', doc: '屏幕绝对坐标 X' },
            { name: 'y', type: 'int', doc: '屏幕绝对坐标 Y' },
            { name: 'click_time', type: 'int', doc: '连续点击次数 (抢购/高速点赞)', default: '1' },
            { name: 'interval', type: 'int', doc: '连续点击间隔 (毫秒)', default: '50' },
        ],
        returnType: 'bool',
    },
    {
        name: 'click_double', kind: 'function', module: 'yyds',
        doc: '双击坐标',
        signature: 'click_double(x, y)',
        params: [
            { name: 'x', type: 'int', doc: 'X 坐标' },
            { name: 'y', type: 'int', doc: 'Y 坐标' },
        ],
    },
    {
        name: 'random_click', kind: 'function', module: 'yyds',
        doc: '在指定矩形区域内随机点击, 模拟人工操作',
        signature: 'random_click(x, y, w, h)',
        params: [
            { name: 'x', type: 'int', doc: '区域左上角 X' },
            { name: 'y', type: 'int', doc: '区域左上角 Y' },
            { name: 'w', type: 'int', doc: '区域宽度' },
            { name: 'h', type: 'int', doc: '区域高度' },
        ],
    },
    {
        name: 'click_target', kind: 'function', module: 'yyds',
        doc: '点击识别结果对象的中心点 (支持 ResOcr / ResYolo / ResFindImage)',
        signature: 'click_target(t)',
        params: [
            { name: 't', type: 'ResOcr | ResYolo | ResFindImage', doc: '识别结果对象' },
        ],
    },

    // === 滑动 ===
    {
        name: 'swipe', kind: 'function', module: 'yyds',
        doc: '屏幕滑动',
        signature: 'swipe(x1, y1, x2, y2, duration, is_random=False)',
        params: [
            { name: 'x1', type: 'int', doc: '起始 X' },
            { name: 'y1', type: 'int', doc: '起始 Y' },
            { name: 'x2', type: 'int', doc: '终点 X' },
            { name: 'y2', type: 'int', doc: '终点 Y' },
            { name: 'duration', type: 'int', doc: '滑动耗时 (毫秒), 越小越快' },
            { name: 'is_random', type: 'bool', doc: '是否随机锯齿滑动', default: 'False' },
        ],
    },
    {
        name: 'swipe_up', kind: 'function', module: 'yyds',
        doc: '往上滑动 (需先调用 DeviceScreen.init())',
        signature: 'swipe_up()',
    },
    {
        name: 'swipe_down', kind: 'function', module: 'yyds',
        doc: '往下滑动',
        signature: 'swipe_down()',
    },
    {
        name: 'swipe_left', kind: 'function', module: 'yyds',
        doc: '往左滑动',
        signature: 'swipe_left()',
    },
    {
        name: 'swipe_right', kind: 'function', module: 'yyds',
        doc: '往右滑动',
        signature: 'swipe_right()',
    },

    // === 按键 ===
    {
        name: 'key_back', kind: 'function', module: 'yyds',
        doc: '注入返回键',
        signature: 'key_back()',
    },
    {
        name: 'key_home', kind: 'function', module: 'yyds',
        doc: '注入 Home 键',
        signature: 'key_home()',
    },
    {
        name: 'key_menu', kind: 'function', module: 'yyds',
        doc: '注入菜单键',
        signature: 'key_menu()',
    },
    {
        name: 'key_confirm', kind: 'function', module: 'yyds',
        doc: '确认键 (搜索/提交)',
        signature: 'key_confirm()',
        returnType: 'str',
    },
    {
        name: 'key_code', kind: 'function', module: 'yyds',
        doc: '注入 Android KeyCode\n参考: http://yydsxx.com/blog/android%20keycode%20table',
        signature: 'key_code(code)',
        params: [
            { name: 'code', type: 'int', doc: '键值码' },
        ],
    },

    // === 截图 ===
    {
        name: 'screenshot', kind: 'function', module: 'yyds',
        doc: '屏幕截图, 无需申请权限',
        signature: 'screenshot(path=None)',
        params: [
            { name: 'path', type: 'str | None', doc: '保存路径, 默认 /sdcard/screenshot.png', default: 'None' },
        ],
        returnType: 'str',
    },

    // === OCR ===
    {
        name: 'ocr', kind: 'function', module: 'yyds',
        doc: '底层 OCR 接口, 识别屏幕或图片中的文字',
        signature: 'ocr(image=None, x=None, y=None, w=None, h=None, use_gpu=False)',
        params: [
            { name: 'image', type: 'str | None', doc: '图片路径, None 则截屏识别', default: 'None' },
            { name: 'x', type: 'float | None', doc: '识别区域起始 X (0~1 相对坐标)', default: 'None' },
            { name: 'y', type: 'float | None', doc: '识别区域起始 Y', default: 'None' },
            { name: 'w', type: 'float | None', doc: '识别区域宽', default: 'None' },
            { name: 'h', type: 'float | None', doc: '识别区域高', default: 'None' },
            { name: 'use_gpu', type: 'bool', doc: '是否使用 GPU 加速', default: 'False' },
        ],
        returnType: 'str',
    },
    {
        name: 'screen_ocr_x', kind: 'function', module: 'yyds',
        doc: '屏幕 OCR 识别, 返回匹配指定文字的结果 (支持正则)',
        signature: 'screen_ocr_x(specific_texts=None, x=None, y=None, w=None, h=None, use_gpu=False)',
        params: [
            { name: 'specific_texts', type: 'list | tuple | None', doc: '要查找的文字列表 (支持正则)', default: 'None' },
            { name: 'x', type: 'float | None', doc: '区域起始 X', default: 'None' },
            { name: 'y', type: 'float | None', doc: '区域起始 Y', default: 'None' },
            { name: 'w', type: 'float | None', doc: '区域宽', default: 'None' },
            { name: 'h', type: 'float | None', doc: '区域高', default: 'None' },
            { name: 'use_gpu', type: 'bool', doc: '是否 GPU 加速', default: 'False' },
        ],
        returnType: 'Tuple[ResOcr, ...]',
    },
    {
        name: 'screen_ocr_first_x', kind: 'function', module: 'yyds',
        doc: '屏幕 OCR, 仅返回第一个匹配结果',
        signature: 'screen_ocr_first_x(specific_texts=None, x=None, y=None, w=None, h=None, use_gpu=False)',
        params: [
            { name: 'specific_texts', type: 'list | tuple | None', doc: '要查找的文字列表', default: 'None' },
            { name: 'x', type: 'float | None', doc: '区域起始 X', default: 'None' },
            { name: 'y', type: 'float | None', doc: '区域起始 Y', default: 'None' },
            { name: 'w', type: 'float | None', doc: '区域宽', default: 'None' },
            { name: 'h', type: 'float | None', doc: '区域高', default: 'None' },
            { name: 'use_gpu', type: 'bool', doc: '是否 GPU 加速', default: 'False' },
        ],
        returnType: 'ResOcr | None',
    },
    {
        name: 'ocr_click_any', kind: 'function', module: 'yyds',
        doc: '搜索到任一指定文字时, 点击最后一个',
        signature: 'ocr_click_any(*text, x=None, y=None, w=None, h=None, offset_h=None, offset_w=None)',
        params: [
            { name: '*text', type: 'str', doc: '要搜索的文字 (可变参数, 支持正则)' },
            { name: 'x', type: 'float | None', doc: '区域起始 X', default: 'None' },
            { name: 'y', type: 'float | None', doc: '区域起始 Y', default: 'None' },
            { name: 'w', type: 'float | None', doc: '区域宽', default: 'None' },
            { name: 'h', type: 'float | None', doc: '区域高', default: 'None' },
        ],
        returnType: 'bool',
    },
    {
        name: 'ocr_click_if_found', kind: 'function', module: 'yyds',
        doc: '搜索到所有指定文字时, 点击最后一个',
        signature: 'ocr_click_if_found(*text, x=None, y=None, w=None, h=None, offset_h=None, offset_w=None)',
        params: [
            { name: '*text', type: 'str', doc: '要搜索的文字 (可变参数)' },
            { name: 'x', type: 'float | None', doc: '区域起始 X', default: 'None' },
            { name: 'y', type: 'float | None', doc: '区域起始 Y', default: 'None' },
            { name: 'w', type: 'float | None', doc: '区域宽', default: 'None' },
            { name: 'h', type: 'float | None', doc: '区域高', default: 'None' },
        ],
        returnType: 'bool',
    },
    {
        name: 'ocr_exists_all', kind: 'function', module: 'yyds',
        doc: '判断屏幕上是否所有指定文字都存在',
        signature: 'ocr_exists_all(*text, x=None, y=None, w=None, h=None)',
        params: [
            { name: '*text', type: 'str', doc: '要判断的文字' },
        ],
        returnType: 'bool',
    },
    {
        name: 'ocr_exists_any', kind: 'function', module: 'yyds',
        doc: '判断屏幕上是否存在任一指定文字',
        signature: 'ocr_exists_any(*text, x=None, y=None, w=None, h=None)',
        params: [
            { name: '*text', type: 'str', doc: '要判断的文字' },
        ],
        returnType: 'bool',
    },

    // === 找图 ===
    {
        name: 'screen_find_image_x', kind: 'function', module: 'yyds',
        doc: '高级找图, 同时查找多张图片并返回结构化结果',
        signature: 'screen_find_image_x(fd_images, min_prob=0.5, x=None, y=None, w=None, h=None, threshold=-1)',
        params: [
            { name: 'fd_images', type: 'tuple', doc: '图片路径元组' },
            { name: 'min_prob', type: 'float', doc: '最低置信率', default: '0.5' },
            { name: 'x', type: 'float | None', doc: '区域起始 X (0~1 相对坐标)', default: 'None' },
            { name: 'y', type: 'float | None', doc: '区域起始 Y', default: 'None' },
            { name: 'w', type: 'float | None', doc: '区域宽', default: 'None' },
            { name: 'h', type: 'float | None', doc: '区域高', default: 'None' },
            { name: 'threshold', type: 'int', doc: '图片预处理 (<0 彩色, ==0 灰度反相, >0 二值化)', default: '-1' },
        ],
        returnType: 'Tuple[ResFindImage, ...]',
    },
    {
        name: 'screen_find_image_first_x', kind: 'function', module: 'yyds',
        doc: '高级找图, 仅返回第一个结果',
        signature: 'screen_find_image_first_x(fd_images, min_prob=0.9, x=None, y=None, w=None, h=None, threshold=-1)',
        params: [
            { name: 'fd_images', type: 'tuple', doc: '图片路径元组' },
            { name: 'min_prob', type: 'float', doc: '最低置信率', default: '0.9' },
        ],
        returnType: 'ResFindImage | None',
    },
    {
        name: 'find_image_click', kind: 'function', module: 'yyds',
        doc: '找到所有图片后点击最后一张',
        signature: 'find_image_click(*img, min_prob=0.5, x=None, y=None, w=None, h=None, offset_x=None, offset_y=None, threshold=-1, wait=0)',
        params: [
            { name: '*img', type: 'str', doc: '图片路径' },
            { name: 'min_prob', type: 'float', doc: '最低置信率', default: '0.5' },
        ],
        returnType: 'bool',
    },
    {
        name: 'find_image_click_max_prob', kind: 'function', module: 'yyds',
        doc: '传入一组图片, 点击相似度最高的一张\n支持元组参数: ("路径", 是否必点, 等待秒数, 点击次数)',
        signature: 'find_image_click_max_prob(*img, min_prob=0.5, x=None, y=None, w=None, h=None, is_random_click=False, threshold=-1)',
        params: [
            { name: '*img', type: 'str | tuple', doc: '图片路径或 (路径, 必点, 等待, 次数)' },
            { name: 'min_prob', type: 'float', doc: '最低置信率', default: '0.5' },
        ],
        returnType: 'bool',
    },
    {
        name: 'match_images', kind: 'function', module: 'yyds',
        doc: '对图片进行多次匹配',
        signature: 'match_images(template_image, prob, threshold=0, image=None, x=None, y=None, w=None, h=None)',
        params: [
            { name: 'template_image', type: 'str', doc: '模板图片路径' },
            { name: 'prob', type: 'float', doc: '最低相似度 (0~1.0)' },
            { name: 'threshold', type: 'int', doc: '图片预处理方式', default: '0' },
        ],
        returnType: 'List[MatchImageResult]',
    },

    // === YOLO ===
    {
        name: 'screen_yolo_find_x', kind: 'function', module: 'yyds',
        doc: 'YOLO 目标检测, 识别当前屏幕内容',
        signature: 'screen_yolo_find_x(specify_labels=None, min_prob=0.9, x=None, y=None, w=None, h=None, use_gpu=False)',
        params: [
            { name: 'specify_labels', type: 'list | None', doc: '仅返回指定 label 的结果', default: 'None' },
            { name: 'min_prob', type: 'float', doc: '最低置信率', default: '0.9' },
        ],
        returnType: 'Tuple[ResYolo, ...]',
    },
    {
        name: 'screen_yolo_find_first_x', kind: 'function', module: 'yyds',
        doc: 'YOLO 检测, 仅返回第一个结果',
        signature: 'screen_yolo_find_first_x(labels=None, prob=0.9, x=None, y=None, w=None, h=None, use_gpu=False)',
        returnType: 'ResYolo | None',
    },

    // === 找色 ===
    {
        name: 'find_color', kind: 'function', module: 'yyds',
        doc: '单/多点找色, 返回匹配颜色的坐标',
        signature: 'find_color(base_rgb, bias_points=[], max_fuzzy=3, step_x=5, step_y=5, image=None, x=None, y=None, w=None, h=None, max_counts=1)',
        params: [
            { name: 'base_rgb', type: 'str', doc: '基点 RGB, 格式 "R,G,B"' },
            { name: 'bias_points', type: 'list', doc: '偏移点列表, 格式 "偏移X,偏移Y|R,G,B"', default: '[]' },
            { name: 'max_fuzzy', type: 'int', doc: '颜色相似度临界值 (0~255)', default: '3' },
            { name: 'max_counts', type: 'int', doc: '最大返回结果数', default: '1' },
        ],
        returnType: 'List[Point]',
    },
    {
        name: 'get_color', kind: 'function', module: 'yyds',
        doc: '获取指定坐标的 RGB 颜色',
        signature: 'get_color(x, y, image=None)',
        params: [
            { name: 'x', type: 'int', doc: 'X 坐标' },
            { name: 'y', type: 'int', doc: 'Y 坐标' },
        ],
        returnType: 'Color',
    },

    // === UI 控件 ===
    {
        name: 'ui_match', kind: 'function', module: 'yyds',
        doc: '扫描屏幕 UI 控件并匹配\n支持: text, resource_id, content_desc, class_ (正则)\n支持: width, height (">10" / "<50")\n支持: x, y, w, h (区域限定)\n支持: par_*, sib_*, chi_* (父/兄/子匹配)',
        signature: 'ui_match(all_window=False, match_from_cache=False, limit=9999, **match_params)',
        params: [
            { name: 'all_window', type: 'bool', doc: '是否查找所有窗口 (含悬浮窗)', default: 'False' },
            { name: 'match_from_cache', type: 'bool', doc: '从缓存匹配 (需界面未变)', default: 'False' },
            { name: 'limit', type: 'int', doc: '最大返回数量', default: '9999' },
            { name: '**match_params', type: 'str', doc: '匹配参数 (text, resource_id, content_desc...)' },
        ],
        returnType: 'List[Node]',
    },
    {
        name: 'ui_exist', kind: 'function', module: 'yyds',
        doc: '检查符合条件的控件是否存在',
        signature: 'ui_exist(all_window=False, match_from_cache=False, **match_params)',
        returnType: 'bool',
    },
    {
        name: 'ui_parent', kind: 'function', module: 'yyds',
        doc: '获取节点的所有父节点 (由近到远)',
        signature: 'ui_parent(node)',
        params: [{ name: 'node', type: 'Node', doc: '控件节点' }],
        returnType: 'List[Node]',
    },
    {
        name: 'ui_child', kind: 'function', module: 'yyds',
        doc: '获取节点的所有子节点',
        signature: 'ui_child(node)',
        params: [{ name: 'node', type: 'Node', doc: '控件节点' }],
        returnType: 'List[Node]',
    },
    {
        name: 'ui_sib', kind: 'function', module: 'yyds',
        doc: '获取节点的兄弟节点 (不含自身)',
        signature: 'ui_sib(node)',
        params: [{ name: 'node', type: 'Node', doc: '控件节点' }],
        returnType: 'List[Node]',
    },

    // === 设备信息 ===
    {
        name: 'device_foreground', kind: 'function', module: 'yyds',
        doc: '获取当前前台应用信息',
        signature: 'device_foreground()',
        returnType: 'DeviceForegroundResponse | None',
    },
    {
        name: 'device_foreground_package', kind: 'function', module: 'yyds',
        doc: '获取当前前台包名',
        signature: 'device_foreground_package()',
        returnType: 'str',
    },
    {
        name: 'device_foreground_activity', kind: 'function', module: 'yyds',
        doc: '获取当前活动界面名',
        signature: 'device_foreground_activity()',
        returnType: 'str',
    },
    {
        name: 'device_get_screen_size', kind: 'function', module: 'yyds',
        doc: '获取屏幕分辨率 (宽, 高), 横竖屏数值会交换',
        signature: 'device_get_screen_size()',
        returnType: '(int, int)',
    },
    {
        name: 'device_model', kind: 'function', module: 'yyds',
        doc: '获取手机型号',
        signature: 'device_model()',
        returnType: 'str',
    },
    {
        name: 'device_code', kind: 'function', module: 'yyds',
        doc: '获取设备唯一硬件码',
        signature: 'device_code()',
        returnType: 'str',
    },
    {
        name: 'is_net_online', kind: 'function', module: 'yyds',
        doc: '检测设备网络是否连通',
        signature: 'is_net_online()',
        returnType: 'bool',
    },
    {
        name: 'is_app_running', kind: 'function', module: 'yyds',
        doc: '判断应用是否在后台运行',
        signature: 'is_app_running(pkg)',
        params: [{ name: 'pkg', type: 'str', doc: '应用包名' }],
        returnType: 'bool',
    },
    {
        name: 'is_in_app', kind: 'function', module: 'yyds',
        doc: '判断当前是否在某应用界面内',
        signature: 'is_in_app(pkg)',
        params: [{ name: 'pkg', type: 'str', doc: '应用包名' }],
        returnType: 'bool',
    },

    // === 应用管理 ===
    {
        name: 'open_app', kind: 'function', module: 'yyds',
        doc: '根据包名打开应用',
        signature: 'open_app(pkg)',
        params: [{ name: 'pkg', type: 'str', doc: '应用包名, 如 "com.android.browser"' }],
        returnType: 'str',
    },
    {
        name: 'stop_app', kind: 'function', module: 'yyds',
        doc: '停止应用运行',
        signature: 'stop_app(pkg)',
        params: [{ name: 'pkg', type: 'str', doc: '应用包名' }],
    },
    {
        name: 'open_url', kind: 'function', module: 'yyds',
        doc: '通过 Intent 打开 URL',
        signature: 'open_url(url)',
        params: [{ name: 'url', type: 'str', doc: 'URL 链接' }],
    },
    {
        name: 'bring_app_to_top', kind: 'function', module: 'yyds',
        doc: '将后台应用带回前台',
        signature: 'bring_app_to_top(pkg)',
        params: [{ name: 'pkg', type: 'str', doc: '应用包名' }],
        returnType: 'bool',
    },

    // === Shell ===
    {
        name: 'shell', kind: 'function', module: 'yyds',
        doc: '执行 Shell 命令 (引擎内置 busybox)',
        signature: 'shell(*cmd)',
        params: [{ name: '*cmd', type: 'str', doc: 'Shell 命令 (可多条)' }],
        returnType: 'str',
    },

    // === 输入法 ===
    {
        name: 'x_input_text', kind: 'function', module: 'yyds',
        doc: '通过 YY 输入法输入文本 (支持中文)',
        signature: 'x_input_text(text)',
        params: [{ name: 'text', type: 'str', doc: '要输入的文本' }],
        returnType: 'bool',
    },
    {
        name: 'x_input_clear', kind: 'function', module: 'yyds',
        doc: '通过 YY 输入法清空编辑框',
        signature: 'x_input_clear()',
        returnType: 'bool',
    },
    {
        name: 'input_text', kind: 'function', module: 'yyds',
        doc: '注入文本 (仅 ASCII 英文数字符号)',
        signature: 'input_text(text)',
        params: [{ name: 'text', type: 'str', doc: 'ASCII 文本' }],
        returnType: 'int',
    },
    {
        name: 'set_text', kind: 'function', module: 'yyds',
        doc: '清空编辑框并输入新文本',
        signature: 'set_text(text)',
        params: [{ name: 'text', type: 'str', doc: '要输入的文本' }],
    },
    {
        name: 'set_yy_input_enable', kind: 'function', module: 'yyds',
        doc: '启用/禁用 YY 输入法',
        signature: 'set_yy_input_enable(enable)',
        params: [{ name: 'enable', type: 'bool', doc: '是否启用' }],
        returnType: 'bool',
    },

    // === 剪贴板 ===
    {
        name: 'set_clipboard', kind: 'function', module: 'yyds',
        doc: '复制文本到剪贴板',
        signature: 'set_clipboard(text)',
        params: [{ name: 'text', type: 'str', doc: '文本' }],
    },
    {
        name: 'get_clipboard', kind: 'function', module: 'yyds',
        doc: '获取剪贴板文本 (安卓 9+ 需启用 YY 输入法)',
        signature: 'get_clipboard()',
        returnType: 'str',
    },

    // === 提示与日志 ===
    {
        name: 'toast', kind: 'function', module: 'yyds',
        doc: '弹出 Toast 提示',
        signature: 'toast(content)',
        params: [{ name: 'content', type: 'str', doc: '提示内容' }],
    },
    {
        name: 'toast_print', kind: 'function', module: 'yyds',
        doc: '弹出 Toast 并打印日志',
        signature: 'toast_print(text)',
        params: [{ name: 'text', type: 'str', doc: '文本' }],
    },
    {
        name: 'log_d', kind: 'function', module: 'yyds',
        doc: '打印标准日志 (灰色)',
        signature: 'log_d(*objs)',
        params: [{ name: '*objs', type: 'object', doc: '要打印的对象' }],
    },
    {
        name: 'log_e', kind: 'function', module: 'yyds',
        doc: '打印错误日志 (红色)',
        signature: 'log_e(*objs)',
        params: [{ name: '*objs', type: 'object', doc: '要打印的对象' }],
    },

    // === 暂停 ===
    {
        name: 'sleep', kind: 'function', module: 'yyds',
        doc: '暂停指定秒数',
        signature: 'sleep(t)',
        params: [{ name: 't', type: 'float', doc: '暂停秒数 (支持小数)' }],
        returnType: 'bool',
    },
    {
        name: 'false_sleep', kind: 'function', module: 'yyds',
        doc: '暂停指定秒数, 返回 False',
        signature: 'false_sleep(t)',
        params: [{ name: 't', type: 'float', doc: '暂停秒数' }],
        returnType: 'bool',
    },
    {
        name: 'random_sleep', kind: 'function', module: 'yyds',
        doc: '暂停随机秒数 (a~b)',
        signature: 'random_sleep(a, b)',
        params: [
            { name: 'a', type: 'int', doc: '区间开始' },
            { name: 'b', type: 'int', doc: '区间结束' },
        ],
        returnType: 'bool',
    },

    // === 装饰器 ===
    {
        name: 'run_no_hurt', kind: 'decorator', module: 'yyds',
        doc: '装饰器: 立即执行函数, 异常不中断',
        signature: '@run_no_hurt',
    },
    {
        name: 'retry_until_true', kind: 'decorator', module: 'yyds',
        doc: '装饰器: 循环重试直到返回 True',
        signature: '@retry_until_true(retry_time=40, interval=1)',
        params: [
            { name: 'retry_time', type: 'int', doc: '最大重试次数', default: '40' },
            { name: 'interval', type: 'int', doc: '重试间隔 (秒)', default: '1' },
        ],
    },
    {
        name: 'register_task', kind: 'decorator', module: 'yyds',
        doc: '装饰器: 注册任务',
        signature: '@register_task(*task_name)',
        params: [{ name: '*task_name', type: 'str', doc: '任务名称' }],
    },
    {
        name: 'run_activity_handler', kind: 'decorator', module: 'yyds',
        doc: '装饰器: 注册界面处理器, 当前界面匹配时执行',
        signature: '@run_activity_handler(*names)',
        params: [{ name: '*names', type: 'str', doc: '活动名' }],
    },

    // === 工具 ===
    {
        name: 'engine_set_debug', kind: 'function', module: 'yyds',
        doc: '设置是否打印引擎通讯日志',
        signature: 'engine_set_debug(is_debug)',
        params: [{ name: 'is_debug', type: 'bool', doc: '是否打印' }],
    },
    {
        name: 'md5_file', kind: 'function', module: 'yyds',
        doc: '计算文件 MD5',
        signature: 'md5_file(path)',
        returnType: 'str',
    },
    {
        name: 'md5_str', kind: 'function', module: 'yyds',
        doc: '计算文本 MD5',
        signature: 'md5_str(text)',
        returnType: 'str',
    },
    {
        name: 'image_similarity', kind: 'function', module: 'yyds',
        doc: '计算两张图片的相似度 (需尺寸一致)',
        signature: 'image_similarity(img1, img2, flags=0)',
        params: [
            { name: 'img1', type: 'str', doc: '图片1路径' },
            { name: 'img2', type: 'str', doc: '图片2路径' },
        ],
        returnType: 'float (0~1.0)',
    },
    {
        name: 'download', kind: 'function', module: 'yyds',
        doc: 'HTTP 下载文件',
        signature: 'download(url, save_local_path)',
        params: [
            { name: 'url', type: 'str', doc: '下载 URL' },
            { name: 'save_local_path', type: 'str', doc: '本地保存路径' },
        ],
        returnType: 'bool',
    },
    {
        name: 'exit_go_home', kind: 'function', module: 'yyds',
        doc: '返回桌面并退出脚本',
        signature: 'exit_go_home()',
    },

    // === 等待类 API (auto_api_ext) ===
    {
        name: 'wait_for_text', kind: 'function', module: 'yyds',
        doc: '等待屏幕出现指定文字, 出现后立即返回\n支持传入多个文字 (正则), match_all 控制是否全部出现',
        signature: 'wait_for_text(*text, timeout=10, interval=0.5, x=None, y=None, w=None, h=None, match_all=False)',
        params: [
            { name: '*text', type: 'str', doc: '要等待的文字 (支持正则), 可传入多个' },
            { name: 'timeout', type: 'float', doc: '超时秒数', default: '10' },
            { name: 'interval', type: 'float', doc: '轮询间隔秒数', default: '0.5' },
            { name: 'match_all', type: 'bool', doc: 'True=所有文字都出现才返回', default: 'False' },
        ],
        returnType: 'Tuple[ResOcr, ...] | None',
    },
    {
        name: 'wait_for_text_gone', kind: 'function', module: 'yyds',
        doc: '等待屏幕上的指定文字消失',
        signature: 'wait_for_text_gone(*text, timeout=10, interval=0.5, x=None, y=None, w=None, h=None)',
        params: [
            { name: '*text', type: 'str', doc: '要等待消失的文字 (支持正则)' },
            { name: 'timeout', type: 'float', doc: '超时秒数', default: '10' },
            { name: 'interval', type: 'float', doc: '轮询间隔秒数', default: '0.5' },
        ],
        returnType: 'bool',
    },
    {
        name: 'wait_for_ui', kind: 'function', module: 'yyds',
        doc: '等待屏幕出现符合条件的 UI 控件\n\n示例: nodes = wait_for_ui(text="确定", timeout=5)',
        signature: 'wait_for_ui(timeout=10, interval=0.5, **match_params)',
        params: [
            { name: 'timeout', type: 'float', doc: '超时秒数', default: '10' },
            { name: 'interval', type: 'float', doc: '轮询间隔秒数', default: '0.5' },
            { name: '**match_params', type: 'str', doc: '控件匹配参数, 同 ui_match()' },
        ],
        returnType: 'List[Node] | None',
    },
    {
        name: 'wait_for_ui_gone', kind: 'function', module: 'yyds',
        doc: '等待指定 UI 控件消失',
        signature: 'wait_for_ui_gone(timeout=10, interval=0.5, **match_params)',
        params: [
            { name: 'timeout', type: 'float', doc: '超时秒数', default: '10' },
            { name: 'interval', type: 'float', doc: '轮询间隔秒数', default: '0.5' },
            { name: '**match_params', type: 'str', doc: '控件匹配参数, 同 ui_match()' },
        ],
        returnType: 'bool',
    },
    {
        name: 'wait_for_image', kind: 'function', module: 'yyds',
        doc: '等待屏幕出现指定图片',
        signature: 'wait_for_image(*img, timeout=10, interval=1.0, min_prob=0.8, x=None, y=None, w=None, h=None, threshold=-1)',
        params: [
            { name: '*img', type: 'str', doc: '图片路径 (相对工程目录)' },
            { name: 'timeout', type: 'float', doc: '超时秒数', default: '10' },
            { name: 'interval', type: 'float', doc: '轮询间隔秒数', default: '1.0' },
            { name: 'min_prob', type: 'float', doc: '最低置信率', default: '0.8' },
        ],
        returnType: 'Tuple[ResFindImage, ...] | None',
    },
    {
        name: 'wait_for_activity', kind: 'function', module: 'yyds',
        doc: '等待进入指定 Activity 界面 (支持部分匹配)',
        signature: 'wait_for_activity(activity, timeout=10, interval=0.5)',
        params: [
            { name: 'activity', type: 'str', doc: 'Activity 名 (支持部分匹配)' },
            { name: 'timeout', type: 'float', doc: '超时秒数', default: '10' },
            { name: 'interval', type: 'float', doc: '轮询间隔秒数', default: '0.5' },
        ],
        returnType: 'bool',
    },
    {
        name: 'wait_for_package', kind: 'function', module: 'yyds',
        doc: '等待进入指定应用 (精确匹配包名)',
        signature: 'wait_for_package(package, timeout=10, interval=0.5)',
        params: [
            { name: 'package', type: 'str', doc: '应用包名' },
            { name: 'timeout', type: 'float', doc: '超时秒数', default: '10' },
            { name: 'interval', type: 'float', doc: '轮询间隔秒数', default: '0.5' },
        ],
        returnType: 'bool',
    },
    {
        name: 'wait_and_click_text', kind: 'function', module: 'yyds',
        doc: '等待文字出现并点击 — 最常用的复合操作',
        signature: 'wait_and_click_text(*text, timeout=10, interval=0.5, x=None, y=None, w=None, h=None)',
        params: [
            { name: '*text', type: 'str', doc: '要等待并点击的文字' },
            { name: 'timeout', type: 'float', doc: '超时秒数', default: '10' },
            { name: 'interval', type: 'float', doc: '轮询间隔', default: '0.5' },
        ],
        returnType: 'bool',
    },
    {
        name: 'wait_and_click_ui', kind: 'function', module: 'yyds',
        doc: '等待控件出现并点击 — 最常用的复合操作',
        signature: 'wait_and_click_ui(timeout=10, interval=0.5, **match_params)',
        params: [
            { name: 'timeout', type: 'float', doc: '超时秒数', default: '10' },
            { name: 'interval', type: 'float', doc: '轮询间隔', default: '0.5' },
            { name: '**match_params', type: 'str', doc: '控件匹配参数, 同 ui_match()' },
        ],
        returnType: 'bool',
    },

    // === 手势操作 (auto_api_ext) ===
    {
        name: 'long_press', kind: 'function', module: 'yyds',
        doc: '长按指定坐标',
        signature: 'long_press(x, y, duration=500)',
        params: [
            { name: 'x', type: 'int', doc: '屏幕坐标 X' },
            { name: 'y', type: 'int', doc: '屏幕坐标 Y' },
            { name: 'duration', type: 'int', doc: '长按时长 (毫秒)', default: '500' },
        ],
    },
    {
        name: 'gesture', kind: 'function', module: 'yyds',
        doc: '沿路径点执行手势滑动 (模拟手势轨迹)',
        signature: 'gesture(points, duration=300)',
        params: [
            { name: 'points', type: 'List[Tuple[int, int]]', doc: '路径坐标点列表, 如 [(100,200), (300,400), (500,600)]' },
            { name: 'duration', type: 'int', doc: '总手势时长 (毫秒)', default: '300' },
        ],
    },
    {
        name: 'pinch_in', kind: 'function', module: 'yyds',
        doc: '双指捏合 (缩小) — 通过两次滑动模拟',
        signature: 'pinch_in(cx, cy, distance=300, duration=400)',
        params: [
            { name: 'cx', type: 'int', doc: '中心点 X' },
            { name: 'cy', type: 'int', doc: '中心点 Y' },
            { name: 'distance', type: 'int', doc: '起始两指间距 (像素)', default: '300' },
            { name: 'duration', type: 'int', doc: '手势时长 (毫秒)', default: '400' },
        ],
    },
    {
        name: 'pinch_out', kind: 'function', module: 'yyds',
        doc: '双指张开 (放大) — 通过两次滑动模拟',
        signature: 'pinch_out(cx, cy, distance=300, duration=400)',
        params: [
            { name: 'cx', type: 'int', doc: '中心点 X' },
            { name: 'cy', type: 'int', doc: '中心点 Y' },
            { name: 'distance', type: 'int', doc: '目标两指间距 (像素)', default: '300' },
            { name: 'duration', type: 'int', doc: '手势时长 (毫秒)', default: '400' },
        ],
    },

    // === 滑动查找 (auto_api_ext) ===
    {
        name: 'swipe_to_find_text', kind: 'function', module: 'yyds',
        doc: '反复滑动直到找到指定文字',
        signature: 'swipe_to_find_text(*text, direction="up", max_swipes=10, interval=0.8, x=None, y=None, w=None, h=None)',
        params: [
            { name: '*text', type: 'str', doc: '要查找的文字 (支持正则)' },
            { name: 'direction', type: 'str', doc: '滑动方向 "up" 或 "down"', default: '"up"' },
            { name: 'max_swipes', type: 'int', doc: '最大滑动次数', default: '10' },
            { name: 'interval', type: 'float', doc: '每次滑动后等待秒数', default: '0.8' },
        ],
        returnType: 'Tuple[ResOcr, ...] | None',
    },
    {
        name: 'swipe_to_find_ui', kind: 'function', module: 'yyds',
        doc: '反复滑动直到找到指定 UI 控件',
        signature: 'swipe_to_find_ui(direction="up", max_swipes=10, interval=0.8, **match_params)',
        params: [
            { name: 'direction', type: 'str', doc: '滑动方向 "up" 或 "down"', default: '"up"' },
            { name: 'max_swipes', type: 'int', doc: '最大滑动次数', default: '10' },
            { name: 'interval', type: 'float', doc: '每次滑动后等待秒数', default: '0.8' },
            { name: '**match_params', type: 'str', doc: '控件匹配参数, 同 ui_match()' },
        ],
        returnType: 'List[Node] | None',
    },
    {
        name: 'swipe_to_find_image', kind: 'function', module: 'yyds',
        doc: '反复滑动直到找到指定图片',
        signature: 'swipe_to_find_image(*img, direction="up", max_swipes=10, interval=1.0, min_prob=0.8, threshold=-1)',
        params: [
            { name: '*img', type: 'str', doc: '图片路径' },
            { name: 'direction', type: 'str', doc: '滑动方向', default: '"up"' },
            { name: 'max_swipes', type: 'int', doc: '最大滑动次数', default: '10' },
            { name: 'min_prob', type: 'float', doc: '最低置信率', default: '0.8' },
        ],
        returnType: 'Tuple[ResFindImage, ...] | None',
    },

    // === 屏幕变化检测 (auto_api_ext) ===
    {
        name: 'wait_screen_change', kind: 'function', module: 'yyds',
        doc: '等待屏幕发生变化 (页面跳转、加载完成等)',
        signature: 'wait_screen_change(timeout=10, interval=0.3, threshold=0.95)',
        params: [
            { name: 'timeout', type: 'float', doc: '超时秒数', default: '10' },
            { name: 'interval', type: 'float', doc: '检测间隔秒数', default: '0.3' },
            { name: 'threshold', type: 'float', doc: '相似度阈值 (低于此值认为变化), 0-1.0', default: '0.95' },
        ],
        returnType: 'bool',
    },
    {
        name: 'wait_screen_stable', kind: 'function', module: 'yyds',
        doc: '等待屏幕稳定 (不再变化), 常用于等待页面加载完成',
        signature: 'wait_screen_stable(stable_duration=1.0, timeout=10, interval=0.3, threshold=0.98)',
        params: [
            { name: 'stable_duration', type: 'float', doc: '连续稳定的时长 (秒)', default: '1.0' },
            { name: 'timeout', type: 'float', doc: '超时秒数', default: '10' },
            { name: 'interval', type: 'float', doc: '检测间隔秒数', default: '0.3' },
            { name: 'threshold', type: 'float', doc: '相似度阈值 (高于此值认为未变化), 0-1.0', default: '0.98' },
        ],
        returnType: 'bool',
    },

    // === 设备控制扩展 (auto_api_ext) ===
    {
        name: 'get_battery_info', kind: 'function', module: 'yyds',
        doc: '获取电池信息 (level, status, temperature, voltage)',
        signature: 'get_battery_info()',
        returnType: 'dict',
    },
    {
        name: 'get_wifi_info', kind: 'function', module: 'yyds',
        doc: '获取当前 WiFi 连接信息 (ssid, ip 等)',
        signature: 'get_wifi_info()',
        returnType: 'dict',
    },
    {
        name: 'set_wifi_enabled', kind: 'function', module: 'yyds',
        doc: '开关 WiFi',
        signature: 'set_wifi_enabled(enabled)',
        params: [{ name: 'enabled', type: 'bool', doc: 'True=打开, False=关闭' }],
    },
    {
        name: 'set_airplane_mode', kind: 'function', module: 'yyds',
        doc: '开关飞行模式',
        signature: 'set_airplane_mode(enabled)',
        params: [{ name: 'enabled', type: 'bool', doc: 'True=打开, False=关闭' }],
    },
    {
        name: 'get_screen_orientation', kind: 'function', module: 'yyds',
        doc: '获取当前屏幕旋转方向',
        signature: 'get_screen_orientation()',
        returnType: 'int',
        detail: '返回值: 0=竖屏, 1=横屏(左转), 2=反向竖屏, 3=横屏(右转)',
    },
    {
        name: 'set_screen_brightness', kind: 'function', module: 'yyds',
        doc: '设置屏幕亮度',
        signature: 'set_screen_brightness(level)',
        params: [{ name: 'level', type: 'int', doc: '亮度值 0-255' }],
    },
    {
        name: 'get_notifications', kind: 'function', module: 'yyds',
        doc: '读取当前通知栏内容 (需要 ROOT/SHELL 权限)',
        signature: 'get_notifications()',
        returnType: 'str',
    },

    // === 应用管理扩展 (auto_api_ext) ===
    {
        name: 'app_uninstall', kind: 'function', module: 'yyds',
        doc: '卸载应用',
        signature: 'app_uninstall(pkg)',
        params: [{ name: 'pkg', type: 'str', doc: '应用包名' }],
        returnType: 'bool',
    },
    {
        name: 'app_clear_data', kind: 'function', module: 'yyds',
        doc: '清除应用数据 (相当于 "清除存储")',
        signature: 'app_clear_data(pkg)',
        params: [{ name: 'pkg', type: 'str', doc: '应用包名' }],
        returnType: 'bool',
    },
    {
        name: 'grant_permission', kind: 'function', module: 'yyds',
        doc: '授予应用权限',
        signature: 'grant_permission(pkg, permission)',
        params: [
            { name: 'pkg', type: 'str', doc: '应用包名' },
            { name: 'permission', type: 'str', doc: '权限名, 如 android.permission.READ_EXTERNAL_STORAGE' },
        ],
        returnType: 'bool',
    },
    {
        name: 'revoke_permission', kind: 'function', module: 'yyds',
        doc: '撤销应用权限',
        signature: 'revoke_permission(pkg, permission)',
        params: [
            { name: 'pkg', type: 'str', doc: '应用包名' },
            { name: 'permission', type: 'str', doc: '权限名' },
        ],
        returnType: 'bool',
    },

    // === 分辨率适配 (auto_api_ext) ===
    {
        name: 'scale_pos', kind: 'function', module: 'yyds',
        doc: '通用分辨率适配 — 将基准分辨率坐标转换为当前设备坐标',
        signature: 'scale_pos(x, y, base_w=1080, base_h=2400)',
        params: [
            { name: 'x', type: 'int', doc: '基准分辨率下的 X 坐标' },
            { name: 'y', type: 'int', doc: '基准分辨率下的 Y 坐标' },
            { name: 'base_w', type: 'int', doc: '基准屏幕宽', default: '1080' },
            { name: 'base_h', type: 'int', doc: '基准屏幕高', default: '2400' },
        ],
        returnType: '(int, int)',
    },
    {
        name: 'click_scaled', kind: 'function', module: 'yyds',
        doc: '分辨率自适应点击 — 传入基准分辨率坐标, 自动换算到当前设备',
        signature: 'click_scaled(x, y, base_w=1080, base_h=2400)',
        params: [
            { name: 'x', type: 'int', doc: '基准分辨率下的 X 坐标' },
            { name: 'y', type: 'int', doc: '基准分辨率下的 Y 坐标' },
            { name: 'base_w', type: 'int', doc: '基准屏幕宽', default: '1080' },
            { name: 'base_h', type: 'int', doc: '基准屏幕高', default: '2400' },
        ],
    },

    // === 类 ===
    {
        name: 'DeviceScreen', kind: 'class', module: 'yyds',
        doc: '设备屏幕参数管理, 使用前需调用 DeviceScreen.init()',
        detail: '方法: init(), get_w(), get_h(), get_screen_wh()',
    },
    {
        name: 'DeviceScreen.init', kind: 'method', module: 'yyds',
        doc: '初始化屏幕宽高, 屏幕旋转后需重新调用',
        signature: 'DeviceScreen.init()',
    },
    {
        name: 'DeviceScreen.get_w', kind: 'method', module: 'yyds',
        doc: '获取屏幕宽度',
        signature: 'DeviceScreen.get_w()',
        returnType: 'int',
    },
    {
        name: 'DeviceScreen.get_h', kind: 'method', module: 'yyds',
        doc: '获取屏幕高度',
        signature: 'DeviceScreen.get_h()',
        returnType: 'int',
    },
    {
        name: 'Config', kind: 'class', module: 'yyds',
        doc: '配置存储类, 与设备 UI 配置共享同一存储',
        detail: '方法: read_config_value(), write_config_value(), read_ui_value()',
    },
    {
        name: 'Config.read_config_value', kind: 'method', module: 'yyds',
        doc: '读取配置值 (同时支持 UI 配置和自定义配置)',
        signature: 'Config.read_config_value(config_name, read_load=False)',
        params: [
            { name: 'config_name', type: 'str', doc: '配置键名, 如 "edit-user"' },
            { name: 'read_load', type: 'bool', doc: '是否强制重新读取', default: 'False' },
        ],
        returnType: 'bool | str | int | None',
    },
    {
        name: 'Config.write_config_value', kind: 'method', module: 'yyds',
        doc: '写入配置值',
        signature: 'Config.write_config_value(config_name, value)',
        params: [
            { name: 'config_name', type: 'str', doc: '配置键名' },
            { name: 'value', type: 'any', doc: '值' },
        ],
    },
    {
        name: 'Config.read_ui_value', kind: 'method', module: 'yyds',
        doc: '从 ui.yml 读取默认值',
        signature: 'Config.read_ui_value(config_name)',
        params: [
            { name: 'config_name', type: 'str', doc: 'UI 配置键名' },
        ],
        returnType: 'str | None',
    },

    // === Node 类 (auto_entity) ===
    {
        name: 'Node', kind: 'class', module: 'yyds',
        doc: 'UI 控件节点对象, 由 ui_match() 返回\n支持直接操作: click(), long_press(), set_text(), scroll_forward(), scroll_backward()',
        detail: '属性: text, desc, id, class_name, bound_str, cx, cy, bounds, width, height, is_clicked, is_check_able, is_enable, is_scroll_able, is_selected, is_long_click_able, is_visible, is_password, is_foucuable, is_foucesed, child_count, parent_count, pkg, index, hash_code',
    },
    {
        name: 'Node.click', kind: 'method', module: 'yyds',
        doc: '点击此控件的中心坐标\n\n示例: nodes[0].click()',
        signature: 'node.click()',
    },
    {
        name: 'Node.long_press', kind: 'method', module: 'yyds',
        doc: '长按此控件',
        signature: 'node.long_press(duration=500)',
        params: [
            { name: 'duration', type: 'int', doc: '长按时长 (毫秒)', default: '500' },
        ],
    },
    {
        name: 'Node.set_text', kind: 'method', module: 'yyds',
        doc: '点击此控件并输入文本 (先清空再通过 YY 输入法输入)',
        signature: 'node.set_text(text)',
        params: [
            { name: 'text', type: 'str', doc: '要输入的文本' },
        ],
    },
    {
        name: 'Node.scroll_forward', kind: 'method', module: 'yyds',
        doc: '向前滚动此控件 (适用于 ScrollView, RecyclerView)',
        signature: 'node.scroll_forward()',
    },
    {
        name: 'Node.scroll_backward', kind: 'method', module: 'yyds',
        doc: '向后滚动此控件',
        signature: 'node.scroll_backward()',
    },
    {
        name: 'Node.bounds', kind: 'property', module: 'yyds',
        doc: '返回节点的边界坐标 (x1, y1, x2, y2)',
        signature: 'node.bounds',
        returnType: '(int, int, int, int)',
    },
    {
        name: 'Node.width', kind: 'property', module: 'yyds',
        doc: '控件宽度 (像素)',
        signature: 'node.width',
        returnType: 'int',
    },
    {
        name: 'Node.height', kind: 'property', module: 'yyds',
        doc: '控件高度 (像素)',
        signature: 'node.height',
        returnType: 'int',
    },
    {
        name: 'Node.cx', kind: 'property', module: 'yyds',
        doc: '控件中心 X 坐标',
        signature: 'node.cx',
        returnType: 'int',
    },
    {
        name: 'Node.cy', kind: 'property', module: 'yyds',
        doc: '控件中心 Y 坐标',
        signature: 'node.cy',
        returnType: 'int',
    },

    // === 工具类实例 ===
    {
        name: 'Point', kind: 'class', module: 'yyds',
        doc: '坐标点对象',
        signature: 'Point(x, y)',
        params: [
            { name: 'x', type: 'int', doc: 'X 坐标' },
            { name: 'y', type: 'int', doc: 'Y 坐标' },
        ],
        detail: '属性: x, y',
    },
    {
        name: 'Color', kind: 'class', module: 'yyds',
        doc: '颜色对象',
        signature: 'Color(r, g, b)',
        params: [
            { name: 'r', type: 'int', doc: '红色分量 0-255' },
            { name: 'g', type: 'int', doc: '绿色分量 0-255' },
            { name: 'b', type: 'int', doc: '蓝色分量 0-255' },
        ],
        detail: '属性: r, g, b\n方法: similarity_to(other) → float',
    },
    {
        name: 'Color.similarity_to', kind: 'method', module: 'yyds',
        doc: '计算与另一颜色的相似度',
        signature: 'color.similarity_to(other)',
        params: [{ name: 'other', type: 'Color', doc: '另一个 Color 对象' }],
        returnType: 'float (0~1.0)',
    },
    {
        name: 'format_time', kind: 'function', module: 'yyds',
        doc: '获取当前格式化时间字符串',
        signature: 'format_time(fmt=None)',
        params: [
            { name: 'fmt', type: 'str | None', doc: '时间格式, 默认 "%Y-%m-%d %H:%M:%S"', default: 'None' },
        ],
        returnType: 'str',
    },
    {
        name: 'ui_dump_xml', kind: 'function', module: 'yyds',
        doc: '获取当前屏幕 UI 控件树 XML 字符串',
        signature: 'ui_dump_xml()',
        returnType: 'str',
    },

    // === ProjectEnvironment ===
    {
        name: 'ProjectEnvironment', kind: 'class', module: 'yyds',
        doc: '项目运行环境信息',
        detail: '方法: current_project(), project_dir()',
    },
    {
        name: 'ProjectEnvironment.current_project', kind: 'method', module: 'yyds',
        doc: '获取当前运行的项目名称',
        signature: 'ProjectEnvironment.current_project()',
        returnType: 'str',
    },
    {
        name: 'ProjectEnvironment.project_dir', kind: 'method', module: 'yyds',
        doc: '获取当前项目目录路径',
        signature: 'ProjectEnvironment.project_dir()',
        returnType: 'str',
    },

    // === EngineDebug ===
    {
        name: 'EngineDebug', kind: 'class', module: 'yyds',
        doc: '引擎调试工具 (内部使用)',
        detail: '方法: _ping(), _version(), _pid(), _uid()',
    },
    {
        name: 'EngineDebug._ping', kind: 'method', module: 'yyds',
        doc: '测试引擎通讯是否正常',
        signature: 'EngineDebug._ping()',
        returnType: 'bool',
    },
    {
        name: 'EngineDebug._version', kind: 'method', module: 'yyds',
        doc: '获取引擎版本号',
        signature: 'EngineDebug._version()',
        returnType: 'str',
    },
    {
        name: 'EngineDebug._pid', kind: 'method', module: 'yyds',
        doc: '获取引擎进程 PID',
        signature: 'EngineDebug._pid()',
        returnType: 'int',
    },
    {
        name: 'EngineDebug._uid', kind: 'method', module: 'yyds',
        doc: '获取引擎进程 UID (0=root, 2000=shell)',
        signature: 'EngineDebug._uid()',
        returnType: 'int',
    },

    // === console (悬浮日志控制台) ===
    {
        name: 'console', kind: 'class', module: 'yyds.console_shim',
        doc: '悬浮日志控制台, 需要 `from yyds.console_shim import console`',
        detail: '方法: show(), hide(), log(), warn(), error(), debug(), verbose(), success(), fail(), group(), group_end(), time(), time_end(), count(), count_reset(), table(), json(), assert_(), trace(), divider(), set_title(), set_alpha(), clear()',
    },
    {
        name: 'console.show', kind: 'method', module: 'yyds.console_shim',
        doc: '显示悬浮控制台窗口',
        signature: 'console.show()',
    },
    {
        name: 'console.hide', kind: 'method', module: 'yyds.console_shim',
        doc: '隐藏悬浮控制台窗口',
        signature: 'console.hide()',
    },
    {
        name: 'console.log', kind: 'method', module: 'yyds.console_shim',
        doc: '输出普通信息 (绿色)',
        signature: 'console.log(*args)',
        params: [{ name: '*args', type: 'object', doc: '要输出的对象' }],
    },
    {
        name: 'console.warn', kind: 'method', module: 'yyds.console_shim',
        doc: '输出警告信息 (黄色)',
        signature: 'console.warn(*args)',
        params: [{ name: '*args', type: 'object', doc: '要输出的对象' }],
    },
    {
        name: 'console.error', kind: 'method', module: 'yyds.console_shim',
        doc: '输出错误信息 (红色)',
        signature: 'console.error(*args)',
        params: [{ name: '*args', type: 'object', doc: '要输出的对象' }],
    },
    {
        name: 'console.debug', kind: 'method', module: 'yyds.console_shim',
        doc: '输出调试信息 (蓝色)',
        signature: 'console.debug(*args)',
        params: [{ name: '*args', type: 'object', doc: '要输出的对象' }],
    },
    {
        name: 'console.verbose', kind: 'method', module: 'yyds.console_shim',
        doc: '输出详细追踪信息 (灰色)',
        signature: 'console.verbose(*args)',
        params: [{ name: '*args', type: 'object', doc: '要输出的对象' }],
    },
    {
        name: 'console.success', kind: 'method', module: 'yyds.console_shim',
        doc: '输出成功标记 (绿色 ✓)',
        signature: 'console.success(msg)',
        params: [{ name: 'msg', type: 'str', doc: '成功消息' }],
    },
    {
        name: 'console.fail', kind: 'method', module: 'yyds.console_shim',
        doc: '输出失败标记 (红色 ✗)',
        signature: 'console.fail(msg)',
        params: [{ name: 'msg', type: 'str', doc: '失败消息' }],
    },
    {
        name: 'console.group', kind: 'method', module: 'yyds.console_shim',
        doc: '开始一个分组 (缩进)',
        signature: 'console.group(label="")',
        params: [{ name: 'label', type: 'str', doc: '分组标题', default: '""' }],
    },
    {
        name: 'console.group_end', kind: 'method', module: 'yyds.console_shim',
        doc: '结束当前分组 (取消缩进)',
        signature: 'console.group_end()',
    },
    {
        name: 'console.time', kind: 'method', module: 'yyds.console_shim',
        doc: '启动计时器',
        signature: 'console.time(label)',
        params: [{ name: 'label', type: 'str', doc: '计时器名称' }],
    },
    {
        name: 'console.time_end', kind: 'method', module: 'yyds.console_shim',
        doc: '结束计时器并输出耗时',
        signature: 'console.time_end(label)',
        params: [{ name: 'label', type: 'str', doc: '计时器名称' }],
    },
    {
        name: 'console.count', kind: 'method', module: 'yyds.console_shim',
        doc: '输出计数 (自动递增)',
        signature: 'console.count(label="default")',
        params: [{ name: 'label', type: 'str', doc: '计数器名称', default: '"default"' }],
    },
    {
        name: 'console.count_reset', kind: 'method', module: 'yyds.console_shim',
        doc: '重置计数器',
        signature: 'console.count_reset(label="default")',
        params: [{ name: 'label', type: 'str', doc: '计数器名称', default: '"default"' }],
    },
    {
        name: 'console.table', kind: 'method', module: 'yyds.console_shim',
        doc: '以表格形式输出数据',
        signature: 'console.table(data)',
        params: [{ name: 'data', type: 'list[dict]', doc: '字典列表' }],
    },
    {
        name: 'console.json', kind: 'method', module: 'yyds.console_shim',
        doc: '格式化输出 JSON 数据',
        signature: 'console.json(obj)',
        params: [{ name: 'obj', type: 'dict | list', doc: '要输出的对象' }],
    },
    {
        name: 'console.assert_', kind: 'method', module: 'yyds.console_shim',
        doc: '断言检查, 失败时输出错误',
        signature: 'console.assert_(condition, msg="")',
        params: [
            { name: 'condition', type: 'bool', doc: '断言条件' },
            { name: 'msg', type: 'str', doc: '断言失败消息', default: '""' },
        ],
    },
    {
        name: 'console.trace', kind: 'method', module: 'yyds.console_shim',
        doc: '输出调用栈追踪',
        signature: 'console.trace(msg="")',
        params: [{ name: 'msg', type: 'str', doc: '追踪消息', default: '""' }],
    },
    {
        name: 'console.divider', kind: 'method', module: 'yyds.console_shim',
        doc: '输出分隔线',
        signature: 'console.divider(label="", char="─", width=40)',
        params: [
            { name: 'label', type: 'str', doc: '分隔线标题', default: '""' },
            { name: 'char', type: 'str', doc: '分隔字符', default: '"─"' },
            { name: 'width', type: 'int', doc: '宽度', default: '40' },
        ],
    },
    {
        name: 'console.set_title', kind: 'method', module: 'yyds.console_shim',
        doc: '设置控制台标题',
        signature: 'console.set_title(title)',
        params: [{ name: 'title', type: 'str', doc: '标题文字' }],
    },
    {
        name: 'console.set_alpha', kind: 'method', module: 'yyds.console_shim',
        doc: '设置控制台透明度',
        signature: 'console.set_alpha(alpha)',
        params: [{ name: 'alpha', type: 'float', doc: '透明度 0.0~1.0' }],
    },
    {
        name: 'console.clear', kind: 'method', module: 'yyds.console_shim',
        doc: '清空控制台内容',
        signature: 'console.clear()',
    },
];

/** 按名称快速查找 */
export function findApi(name: string): YydsApiEntry | undefined {
    return YYDS_API_DATA.find(a => a.name === name);
}

/** 根据前缀模糊查找 */
export function searchApi(prefix: string): YydsApiEntry[] {
    const lower = prefix.toLowerCase();
    return YYDS_API_DATA.filter(a => a.name.toLowerCase().startsWith(lower));
}
