# Yyds.Auto Python 脚本工程模板

**安卓 RPA 自动化脚本开发框架** — 不依赖无障碍, 支持安卓 7~14, 支持云机与模拟器

[官方文档](https://yydsxx.com/docs/yyds-auto/script) · [APK 下载](https://yydsxx.com/download) · [视频教程](https://space.bilibili.com/413090517) · [联系作者](https://yydsxx.com/contact)

## 快速开始

1. 安装 [Yyds.Auto APK](https://yydsxx.com/download) 到安卓设备, 启动引擎
2. 在 VSCode 中安装 **Yyds.Auto Dev Plugin** 插件
3. 使用插件命令 **Yyds.Auto: 新建脚本工程** 或克隆本模板
4. 修改 `project.config` 中的 `DEBUG_DEVICE_IP` 为设备 IP
5. 按 `Ctrl+Alt+1` 推送并运行

## 工程结构

| 文件/目录           | 必要性 | 说明                                    |
| ------------------- | ------ | --------------------------------------- |
| `main.py`           | 必须   | 脚本入口, `main()` 函数由引擎自动调用   |
| `project.config`    | 必须   | 工程配置 (名称、版本、设备 IP)          |
| `ui.yml`            | 可选   | App 端用户配置界面定义                  |
| `img/`              | 可选   | 找图用的截图素材目录                    |
| `requirements.txt`  | 可选   | Python 额外依赖                         |

> **注意**: `yyds` SDK 包已内置于引擎中, 无需项目自带 `yyds/` 目录。
> `os`, `time`, `json`, `requests`, `yaml` 等常用库也已预加载。
> 如需自定义扩展, 可在项目目录下创建 `yyds/` 覆盖内置版本。

## API 速查表

### 触摸与点击

| 函数 | 说明 |
| ---- | ---- |
| `click(x, y)` | 点击坐标 |
| `click_double(x, y)` | 双击 |
| `random_click(x, y, w, h)` | 区域内随机点击 |
| `click_target(result)` | 点击识别结果对象的中心 |

### 滑动

| 函数 | 说明 |
| ---- | ---- |
| `swipe(x1, y1, x2, y2, duration)` | 自定义滑动 |
| `swipe_up()` / `swipe_down()` | 上/下滑 |
| `swipe_left()` / `swipe_right()` | 左/右滑 |

### 按键

| 函数 | 说明 |
| ---- | ---- |
| `key_back()` / `key_home()` / `key_menu()` | 系统按键 |
| `key_confirm()` | 确认/搜索键 |
| `key_code(code)` | 注入任意 KeyCode |

### OCR 文字识别

| 函数 | 说明 |
| ---- | ---- |
| `ocr(image, x, y, w, h)` | 底层 OCR |
| `screen_ocr_x(texts, x, y, w, h)` | 识别并匹配文字 (支持正则) |
| `ocr_click_any(*text)` | 发现任一文字则点击 |
| `ocr_click_if_found(*text)` | 发现所有文字则点击 |
| `ocr_exists_all(*text)` / `ocr_exists_any(*text)` | 判断文字是否存在 |

### 找图 (模板匹配)

| 函数 | 说明 |
| ---- | ---- |
| `screen_find_image_x(images, min_prob)` | 查找多张图片 |
| `find_image_click(*img, min_prob)` | 找到图片后点击 |
| `find_image_click_max_prob(*img)` | 找到后点击最高相似度的 |
| `match_images(template, prob)` | 多次匹配 |

### YOLO 目标检测

| 函数 | 说明 |
| ---- | ---- |
| `screen_yolo_find_x(labels, min_prob)` | YOLO 检测 |
| `model_yolo_reload(bin, param)` | 加载自定义模型 |

### 找色

| 函数 | 说明 |
| ---- | ---- |
| `find_color(rgb, bias_points, max_fuzzy)` | 单/多点找色 |
| `get_color(x, y)` | 获取坐标颜色 |

### UI 控件

| 函数 | 说明 |
| ---- | ---- |
| `ui_match(**params)` | 控件匹配 (支持正则, 区域限定) |
| `ui_exist(**params)` | 检查控件是否存在 |
| `ui_parent(node)` / `ui_child(node)` / `ui_sib(node)` | 节点关系查询 |

### 设备与应用

| 函数 | 说明 |
| ---- | ---- |
| `device_foreground_package()` | 获取前台包名 |
| `open_app(pkg)` / `stop_app(pkg)` | 打开/停止应用 |
| `shell(*cmd)` | 执行 Shell 命令 |
| `device_model()` / `device_code()` | 设备信息 |

### 输入法

| 函数 | 说明 |
| ---- | ---- |
| `x_input_text(text)` | YY 输入法输入 (支持中文) |
| `x_input_clear()` | 清空编辑框 |
| `set_text(text)` | 清空并输入 |

### 配置读写

```python
# 读取 ui.yml 中定义的用户配置
value = Config.read_config_value("edit-user")

# 写入配置
Config.write_config_value("edit-user", "新值")
```

### 流程控制装饰器

```python
@run_no_hurt          # 执行函数, 异常不中断
def risky(): ...

@retry_until_true(10, 3)  # 最多重试 10 次, 间隔 3 秒
def wait_page():
    return screen_find_image_x(("img/target.jpg",))
```

## 运行方式

- **手机端**: 在 Yyds.Auto App 中选择工程, 点击运行
- **电脑端**: 直接运行 `main.py`, 通过 HTTP 调用手机引擎
- **VSCode**: 使用插件 `Ctrl+Alt+1` 推送并运行, `Ctrl+Alt+2` 停止

## 版本变更

[更新历史](https://yydsxx.com/docs/yyds-auto/update_history)

## 支持

- [Shizuku 免 ROOT 激活](https://shizuku.rikka.app/)
- [QQ 交流群](https://yydsxx.com/contact)