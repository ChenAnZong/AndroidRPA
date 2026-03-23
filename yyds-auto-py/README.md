# yyds-auto

[![PyPI version](https://badge.fury.io/py/yyds-auto.svg)](https://pypi.org/project/yyds-auto/)
[![Python](https://img.shields.io/pypi/pyversions/yyds-auto.svg)](https://pypi.org/project/yyds-auto/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Android RPA 自动化库 — 从 PC 端 Python 控制 Android 设备。

类似 [uiautomator2](https://github.com/openatx/uiautomator2)，但集成了 OCR、图像识别、脚本项目管理等更多能力。

## 特性

- **多种连接方式** — USB 自动连接 / 指定序列号 / WiFi IP 直连 / 局域网自动扫描
- **uiautomator2 风格 API** — `d.click()`, `d(text="X").click()`, `d.screenshot()` 零学习成本
- **元素选择器** — 支持 text / resourceId / className / description 等多种定位方式
- **OCR 识别** — 内置 OCR 能力，`d.ocr_click("确认")` 一行搞定文字点击
- **图像识别** — 模板匹配、像素取色
- **Shell 命令** — ROOT/SHELL 权限执行，`d.shell("ls /sdcard")`
- **文件管理** — push / pull / 读写 / 列目录 / 删除
- **应用管理** — 启动 / 停止 / 安装 / 卸载 / 列表
- **脚本项目** — 远程管理设备上的 Python 脚本项目
- **CLI 工具** — 命令行快速操作：截图、Shell、设备扫描、环境诊断
- **自动初始化** — 自动检测 APK 安装状态，自动启动引擎

## 安装

```bash
pip install yyds-auto
```

## 前置条件

1. Android 设备已开启 USB 调试（或 WiFi 调试）
2. 设备已安装 yyds.auto App
3. PC 已安装 [ADB](https://developer.android.com/tools/releases/platform-tools)（USB 连接时需要）

> WiFi 直连模式下只需设备已启动 yyds.auto 引擎，无需 ADB。

## 快速开始

### 连接设备

由于 \`yyds-auto\` 支持在 **PC 端调试** 以及 **直接在 Android 设备端运行**，推荐使用以下区分逻辑来初始化设备连接：

\`\`\`python
import os
import yyds_auto

# 区分两套运行方式
if os.path.exists("/sdcard"):
    # Android 端运行: 直连本地引擎
    d = yyds_auto.connect("127.0.0.1:61140")
else:
    # PC 端运行: 自动连接手机
    # 可直接使用 yyds_auto.connect() 自动查找 USB 或默认设备
    d = yyds_auto.connect()
    
    # 或者指定连接：
    # d = yyds_auto.connect("HJR5T19A28007194")          # 指定 USB 序列号
    # d = yyds_auto.connect("192.168.1.5")               # WiFi IP 直连
    # d = yyds_auto.connect("192.168.1.5:61140")         # WiFi IP + 自定义端口
    # 
    # 也可以使用局域网自动发现：
    # devices = yyds_auto.discover()
    # d = devices[0].connect()
\`\`\`

### 设备信息

```python
# 基础信息
print(d.info)

# 详细硬件信息
info = d.device_info
print(f"型号: {info.model}")
print(f"品牌: {info.brand}")
print(f"Android: {info.android_version}")
print(f"分辨率: {info.display_width}x{info.display_height}")
print(f"IP: {info.wlan_ip}")

# 屏幕尺寸
w, h = d.window_size()

# 连接状态
print(d.alive())
```

### 截图

```python
# 截图并保存
d.screenshot("screen.png")

# 获取 PIL Image 对象
img = d.screenshot()
img.show()

# 指定质量
img = d.screenshot(quality=50)
```

### 触控操作

```python
# 点击
d.click(500, 800)

# 双击
d.double_click(500, 800)

# 长按
d.long_click(500, 800, duration=1.0)

# 滑动
d.swipe(500, 1500, 500, 500, duration=0.5)

# 方向滑动
d.swipe_ext("up")      # 上滑
d.swipe_ext("down")    # 下滑
d.swipe_ext("left")    # 左滑
d.swipe_ext("right")   # 右滑

# 拖拽
d.drag(100, 200, 300, 400)

# 底层触控链
d.touch.down(500, 500).sleep(0.5).move(500, 300).up()
```

### 按键与输入

```python
# 按键
d.press("home")
d.press("back")
d.press("enter")
d.press("volume_up")
d.press("power")
d.press("recent")       # 最近任务

# 文本输入
d.send_keys("hello world")

# 清空后输入
d.send_keys("new text", clear=True)

# 剪贴板
d.set_clipboard("copied text")
print(d.clipboard)
```

### 屏幕控制

```python
# 亮屏 / 熄屏
d.screen_on()
d.screen_off()

# 屏幕方向
print(d.orientation)  # 0=竖屏, 1=横屏左, 2=倒屏, 3=横屏右

# 冻结旋转
d.freeze_rotation(True)
d.freeze_rotation(False)
```

### 元素选择器

yyds-auto 支持类似 uiautomator2 的元素选择器语法：

```python
# 通过文本查找
d(text="Settings").click()

# 通过 resourceId
d(resourceId="com.example:id/btn").click()

# 通过 className
d(className="android.widget.EditText").set_text("hello")

# 组合条件
d(text="OK", clickable=True).click()

# 等待元素出现
d(text="Loading").wait_gone(timeout=15)
d(text="Done").wait(timeout=10)

# 检查元素是否存在
if d(text="Error").exists:
    print("出错了")

# 获取元素信息
elem = d(resourceId="com.example:id/title")
print(elem.text)
print(elem.bounds)     # (left, top, right, bottom)
print(elem.center)     # (x, y)

# 文本操作
d(className="android.widget.EditText").set_text("hello")
d(className="android.widget.EditText").clear_text()

# 滚动查找
d(scrollable=True).scroll_to(text="Target Item")
```

支持的选择器属性：

| 属性 | 说明 | 示例 |
|------|------|------|
| `text` | 精确文本 | `d(text="OK")` |
| `textContains` | 包含文本 | `d(textContains="设置")` |
| `textStartsWith` | 文本前缀 | `d(textStartsWith="第")` |
| `textMatches` | 正则匹配 | `d(textMatches="\\d+")` |
| `resourceId` | 资源 ID | `d(resourceId="com.example:id/btn")` |
| `className` | 类名 | `d(className="android.widget.Button")` |
| `description` | 描述文本 | `d(description="返回")` |
| `clickable` | 可点击 | `d(clickable=True)` |
| `scrollable` | 可滚动 | `d(scrollable=True)` |
| `enabled` | 已启用 | `d(enabled=True)` |
| `checked` | 已选中 | `d(checked=True)` |
| `selected` | 已选择 | `d(selected=True)` |
| `packageName` | 包名 | `d(packageName="com.example")` |

### OCR 文字识别

```python
# 全屏 OCR
results = d.ocr()
for r in results:
    print(f"{r.text} (置信度: {r.confidence:.2f}, 位置: {r.bounds})")

# 区域 OCR
results = d.ocr(region=(0, 0, 500, 500))

# 查找文字
result = d.ocr_find("确认")
if result:
    print(f"找到: {result.text} at {result.center}")

# 查找并点击
d.ocr_click("确认", timeout=10)

# 等待文字出现
d.ocr_wait("加载完成", timeout=15)

# 像素取色
color = d.pixel_color(500, 800)
print(color)  # "#FF0000"

# 模板匹配
pos = d.find_image("/sdcard/template.png", threshold=0.8)
if pos:
    d.click(*pos)
```

### Shell 命令

```python
# 执行命令（通过引擎，ROOT/SHELL 权限）
result = d.shell("ls /sdcard")
print(result.output)
print(result.exit_code)

# 作为布尔值使用
if d.shell("test -f /sdcard/test.txt"):
    print("文件存在")

# 通过 ADB 执行（仅 USB 模式）
result = d.adb_shell("getprop ro.build.version.release")
```

### 应用管理

```python
# 启动应用
d.app_start("com.example.app")

# 先停止再启动
d.app_start("com.example.app", stop=True)

# 停止应用
d.app_stop("com.example.app")

# 当前前台应用
current = d.app_current()
print(f"包名: {current['package']}")

# 等待应用出现在前台
d.app_wait("com.example.app", timeout=10)

# 已安装应用列表
apps = d.app_list()
apps = d.app_list(filter="example")  # 过滤
```

### 文件操作

```python
# 推送文件到设备
d.push("local_file.txt", "/sdcard/remote_file.txt")

# 从设备拉取文件
d.pull("/sdcard/remote_file.txt", "local_file.txt")

# 列出目录
files = d.list_files("/sdcard")
for f in files:
    print(f"{f.name} {'[DIR]' if f.is_dir else f.size}")

# 读写文本文件
content = d.read_file("/sdcard/test.txt")
d.write_file("/sdcard/test.txt", "hello world")

# 文件操作
d.file_exists("/sdcard/test.txt")  # 检查存在
d.mkdir("/sdcard/new_folder")      # 创建目录
d.remove("/sdcard/test.txt")       # 删除
```

### 脚本项目管理

```python
# 列出设备上的脚本项目
projects = d.list_projects()
for p in projects:
    print(f"{p.name} {'[运行中]' if p.running else ''}")

# 启动 / 停止项目
d.start_project("my_script")
d.stop_project()

# 查看运行状态
status = d.project_status()

# 远程执行代码片段
output = d.run_code("print('hello from device')")
print(output)
```

## 局域网设备发现

自动扫描局域网中运行 yyds.auto 引擎的设备：

```python
import yyds_auto

# 自动检测本机网段并扫描
devices = yyds_auto.discover()
for dev in devices:
    print(f"{dev.ip} - {dev.model} (Android {dev.android_version})")

# 指定子网
devices = yyds_auto.discover(subnet="192.168.1.0/24")

# 扫描多个子网
devices = yyds_auto.discover(subnet=["192.168.1.0/24", "10.0.0.0/24"])

# 调整扫描参数
devices = yyds_auto.discover(
    timeout=0.5,       # 单 IP 探测超时（秒）
    max_workers=256,   # 并发线程数
)

# 连接发现的设备
d = devices[0].connect()
d.screenshot("screen.png")
```

## CLI 命令行工具

安装后可通过 `yyds-auto` 或 `python -m yyds_auto` 使用：

```bash
# 环境诊断（检查 ADB、设备、引擎状态）
yyds-auto doctor

# 列出 ADB 设备
yyds-auto devices

# 扫描局域网设备
yyds-auto discover
yyds-auto discover --subnet 192.168.1.0/24

# 初始化设备（安装 APK + 启动引擎）
yyds-auto init
yyds-auto init -s SERIAL --apk path/to/yyds-auto.apk

# 截图
yyds-auto screenshot
yyds-auto screenshot -o screen.png -s SERIAL

# 执行 Shell 命令
yyds-auto shell "ls /sdcard"
yyds-auto shell -s SERIAL "whoami"

# 查看版本
yyds-auto version
```

## 架构说明

```
PC (Python)                          Android 设备
┌──────────────┐                    ┌──────────────────┐
│  yyds-auto   │                    │   yyds.auto App   │
│              │   USB/ADB Forward  │                  │
│  Device ─────┼──── HTTP REST ────►│  yyds.py :61140  │
│              │   or WiFi Direct   │       │          │
│  discover()  │                    │       ▼          │
│  connect()   │                    │  yyds.auto :61100│
│              │                    │  (自动化引擎)     │
└──────────────┘                    └──────────────────┘
```

- **USB 模式**: 通过 ADB 端口转发 `localhost:61140 → device:61140`
- **WiFi 模式**: 直接连接设备 IP `http://<device-ip>:61140`
- **引擎自启动**: 连接时自动检测引擎状态，未运行则尝试三级启动策略

## 与 uiautomator2 的对比

| 功能 | yyds-auto | uiautomator2 |
|------|-----------|--------------|
| USB 连接 | ✅ | ✅ |
| WiFi 连接 | ✅ | ✅ |
| 局域网扫描 | ✅ | ❌ |
| 元素选择器 | ✅ | ✅ |
| 截图 | ✅ | ✅ |
| OCR 识别 | ✅ 内置 | ❌ 需第三方 |
| 图像匹配 | ✅ 内置 | ❌ 需第三方 |
| ROOT Shell | ✅ | ❌ |
| 文件管理 | ✅ | ❌ |
| 脚本项目管理 | ✅ | ❌ |
| 远程执行代码 | ✅ | ❌ |

## 依赖

- Python >= 3.8
- [requests](https://pypi.org/project/requests/) >= 2.28
- [Pillow](https://pypi.org/project/Pillow/) >= 9.0
- [adbutils](https://pypi.org/project/adbutils/) >= 2.0

## License

[MIT](LICENSE)

# 发布
pip install twine
twine upload dist/*