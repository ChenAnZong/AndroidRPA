"""
设备信息查询快速路径 — 零 VLM 调用、零 token 消耗

对于纯信息类问题（内存、应用列表、存储、电池等），直接执行 shell 命令返回答案，
不进入 VLM 主循环。
"""

from __future__ import annotations

import re
import subprocess
from typing import Optional


def _sh(cmd: str, timeout: int = 10) -> str:
    """执行 shell 命令，返回 stdout（已去除首尾空白）"""
    try:
        ret = subprocess.run(
            ["sh", "-c", cmd],
            capture_output=True, text=True, timeout=timeout,
        )
        return ret.stdout.strip()
    except Exception as e:
        return f"(执行失败: {e})"


# ============================================================
# 格式化辅助函数
# ============================================================

def _fmt_bytes(n: int) -> str:
    """将字节数格式化为人类可读单位"""
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


def _fmt_meminfo() -> str:
    raw = _sh("cat /proc/meminfo")
    info = {}
    for line in raw.splitlines():
        parts = line.split(":")
        if len(parts) == 2:
            key = parts[0].strip()
            val_str = parts[1].strip().split()[0]
            try:
                info[key] = int(val_str) * 1024  # kB → B
            except ValueError:
                pass

    total = info.get("MemTotal", 0)
    avail = info.get("MemAvailable", 0)
    used = total - avail

    if total == 0:
        return "无法获取内存信息"

    pct = used / total * 100
    lines = [
        f"📊 手机内存使用情况",
        f"  总内存:   {_fmt_bytes(total)}",
        f"  已使用:   {_fmt_bytes(used)}  ({pct:.1f}%)",
        f"  可用内存: {_fmt_bytes(avail)}",
    ]

    # swap
    swap_total = info.get("SwapTotal", 0)
    if swap_total > 0:
        swap_free = info.get("SwapFree", 0)
        swap_used = swap_total - swap_free
        lines.append(f"  Swap:     {_fmt_bytes(swap_used)} / {_fmt_bytes(swap_total)}")

    return "\n".join(lines)


def _fmt_storage() -> str:
    raw = _sh("df -h /data /sdcard 2>/dev/null || df -h /")
    lines = ["💾 存储空间使用情况", ""]
    for line in raw.splitlines():
        if line.startswith("Filesystem") or not line.strip():
            continue
        parts = line.split()
        if len(parts) >= 6:
            fs, size, used, avail, pct, mount = parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]
            if mount in ("/data", "/sdcard", "/storage/emulated/0", "/"):
                name = {"/data": "内部存储(data)", "/sdcard": "SD卡/共享存储",
                        "/storage/emulated/0": "共享存储", "/": "系统"}.get(mount, mount)
                lines.append(f"  {name}: 共 {size}，已用 {used}({pct})，剩余 {avail}")
    if len(lines) <= 2:
        lines.append(raw or "无法获取存储信息")
    return "\n".join(lines)


def _fmt_battery() -> str:
    raw = _sh("dumpsys battery")
    info = {}
    for line in raw.splitlines():
        if ":" in line:
            k, _, v = line.partition(":")
            info[k.strip()] = v.strip()

    level = info.get("level", "?")
    status_map = {"1": "未知", "2": "充电中", "3": "放电中", "4": "未充电", "5": "满电"}
    status = status_map.get(info.get("status", ""), info.get("status", "?"))
    health_map = {"1": "未知", "2": "良好", "3": "过热", "4": "损坏", "5": "充电过压",
                  "6": "未知故障", "7": "温度过低"}
    health = health_map.get(info.get("health", ""), info.get("health", "?"))
    plugged_map = {"0": "未插电", "1": "AC充电", "2": "USB充电", "4": "无线充电"}
    plugged = plugged_map.get(info.get("plugged", "0"), info.get("plugged", "未插电"))
    temp_raw = info.get("temperature", "")
    temp = f"{int(temp_raw) / 10:.1f}°C" if temp_raw.isdigit() else "?"
    voltage_raw = info.get("voltage", "")
    voltage = f"{int(voltage_raw) / 1000:.2f}V" if voltage_raw.isdigit() else "?"

    lines = [
        f"🔋 电池信息",
        f"  电量:   {level}%",
        f"  状态:   {status}  ({plugged})",
        f"  温度:   {temp}",
        f"  电压:   {voltage}",
        f"  健康:   {health}",
    ]
    return "\n".join(lines)


def _fmt_installed_apps(system: bool = False) -> str:
    if system:
        raw = _sh("pm list packages -f 2>/dev/null | head -80")
        title = "📦 已安装应用（含系统应用，前80条）"
    else:
        raw = _sh("pm list packages -3 2>/dev/null")
        title = "📦 已安装的第三方应用"

    pkgs = []
    for line in raw.splitlines():
        line = line.strip()
        if line.startswith("package:"):
            pkg = line.removeprefix("package:").strip()
            if "=" in pkg:
                pkg = pkg.split("=")[-1]
            pkgs.append(pkg)

    if not pkgs:
        return f"{title}\n  (未找到应用)"

    lines = [title, f"  共 {len(pkgs)} 个应用", ""]
    for p in sorted(pkgs):
        lines.append(f"  • {p}")
    return "\n".join(lines)


def _fmt_device_info() -> str:
    brand = _sh("getprop ro.product.brand")
    model = _sh("getprop ro.product.model")
    android = _sh("getprop ro.build.version.release")
    sdk = _sh("getprop ro.build.version.sdk")
    cpu = _sh("getprop ro.product.cpu.abi")
    serial = _sh("getprop ro.serialno 2>/dev/null || echo N/A")
    build = _sh("getprop ro.build.display.id")
    screen = _sh("wm size 2>/dev/null | grep -i 'physical\\|override' | tail -1")
    density = _sh("wm density 2>/dev/null | tail -1")

    lines = [
        f"📱 设备基本信息",
        f"  品牌/型号:   {brand} {model}",
        f"  Android:     {android}  (SDK {sdk})",
        f"  CPU架构:     {cpu}",
        f"  版本号:      {build}",
    ]
    if screen:
        lines.append(f"  屏幕分辨率:  {screen.replace('Physical size: ', '').replace('Override size: ', '')}")
    if density:
        lines.append(f"  屏幕密度:    {density.replace('Physical density: ', '').replace('Override density: ', '')}")
    return "\n".join(lines)


def _fmt_cpu_info() -> str:
    cores = _sh("nproc 2>/dev/null || cat /proc/cpuinfo | grep processor | wc -l")
    governor = _sh("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo N/A")
    max_freq_raw = _sh("cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq 2>/dev/null")
    cur_freq_raw = _sh("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq 2>/dev/null")
    chipset = _sh("getprop ro.board.platform")

    lines = [f"🔧 CPU 信息", f"  核心数:     {cores}", f"  芯片平台:   {chipset}"]
    if max_freq_raw.isdigit():
        lines.append(f"  最大频率:   {int(max_freq_raw) // 1000} MHz")
    if cur_freq_raw.isdigit():
        lines.append(f"  当前频率:   {int(cur_freq_raw) // 1000} MHz")
    lines.append(f"  调度策略:   {governor}")

    # CPU 使用率（快速读一次 /proc/stat）
    top_raw = _sh("top -bn1 2>/dev/null | grep -E 'Cpu|cpu' | head -2")
    if top_raw:
        lines.append(f"  实时负载:   {top_raw.splitlines()[0].strip()[:60]}")
    return "\n".join(lines)


def _fmt_network_info() -> str:
    ip = _sh("ip addr show wlan0 2>/dev/null | grep 'inet ' | awk '{print $2}'")
    wifi_ssid = _sh("dumpsys wifi 2>/dev/null | grep 'mWifiInfo' | grep -o 'SSID: [^,]*' | head -1")
    if not wifi_ssid:
        wifi_ssid = _sh("iwconfig wlan0 2>/dev/null | grep ESSID | awk -F'\"' '{print $2}'")
    dns = _sh("getprop net.dns1")
    gateway = _sh("ip route show default 2>/dev/null | awk '{print $3}' | head -1")
    mobile_state = _sh("dumpsys telephony.registry 2>/dev/null | grep 'mDataConnectionState' | head -1")

    lines = [f"🌐 网络信息"]
    if ip:
        lines.append(f"  IP地址(WiFi): {ip}")
    if wifi_ssid:
        lines.append(f"  WiFi SSID:    {wifi_ssid}")
    if gateway:
        lines.append(f"  网关:         {gateway}")
    if dns:
        lines.append(f"  DNS:          {dns}")
    if not ip and not wifi_ssid:
        lines.append("  WiFi 未连接")
    if mobile_state:
        lines.append(f"  移动数据:     {mobile_state.strip()[:60]}")
    return "\n".join(lines)


def _fmt_running_processes() -> str:
    raw = _sh("ps -A 2>/dev/null | head -30 || ps 2>/dev/null | head -30")
    lines = ["⚙️ 当前运行进程（前30条）", ""]
    for line in raw.splitlines():
        lines.append(f"  {line}")
    return "\n".join(lines)


def _fmt_uptime() -> str:
    raw = _sh("uptime 2>/dev/null")
    proc_uptime = _sh("cat /proc/uptime")
    seconds = 0
    if proc_uptime:
        try:
            seconds = float(proc_uptime.split()[0])
        except Exception:
            pass

    if seconds > 0:
        days = int(seconds // 86400)
        hours = int((seconds % 86400) // 3600)
        minutes = int((seconds % 3600) // 60)
        parts = []
        if days:
            parts.append(f"{days}天")
        if hours:
            parts.append(f"{hours}小时")
        parts.append(f"{minutes}分钟")
        uptime_str = "".join(parts)
        return f"⏱️ 设备运行时长: {uptime_str}\n  {raw}"
    return f"⏱️ 运行时间: {raw or '无法获取'}"


def _fmt_screen_info() -> str:
    size = _sh("wm size 2>/dev/null")
    density = _sh("wm density 2>/dev/null")
    brightness = _sh("settings get system screen_brightness 2>/dev/null")
    timeout = _sh("settings get system screen_off_timeout 2>/dev/null")

    lines = [f"🖥️ 屏幕信息"]
    for line in size.splitlines():
        lines.append(f"  分辨率: {line.replace('Physical size: ', '').replace('Override size: ', '')}")
    for line in density.splitlines():
        lines.append(f"  密度:   {line.replace('Physical density: ', '').replace('Override density: ', '')}")
    if brightness.isdigit():
        lines.append(f"  亮度:   {int(brightness) * 100 // 255}% ({brightness}/255)")
    if timeout.isdigit():
        secs = int(timeout) // 1000
        lines.append(f"  熄屏时间: {secs}秒")
    return "\n".join(lines)


def _fmt_locale_time() -> str:
    date = _sh("date '+%Y-%m-%d %H:%M:%S %Z'")
    tz = _sh("getprop persist.sys.timezone")
    locale = _sh("getprop persist.sys.locale || getprop ro.product.locale")
    return "\n".join([
        f"🕐 时间与语言",
        f"  当前时间: {date}",
        f"  时区:     {tz}",
        f"  语言:     {locale}",
    ])


# ============================================================
# 查询规则定义
# ============================================================

# 每条规则: (关键词列表, handler函数或lambda)
# 关键词匹配使用 OR 逻辑，只要出现任意一个关键词即触发
_QUERY_RULES: list[tuple[list[str], callable]] = [
    # 内存
    (["内存", "ram", "运行内存", "memory", "内存有多少", "内存使用", "可用内存", "剩余内存"],
     lambda q: _fmt_meminfo()),

    # 存储
    (["存储", "空间", "storage", "disk", "磁盘", "storage空间", "存储空间", "剩余空间",
      "可用空间", "硬盘"],
     lambda q: _fmt_storage()),

    # 电池
    (["电池", "battery", "电量", "充电", "电池状态", "剩余电量"],
     lambda q: _fmt_battery()),

    # 已安装应用
    (["安装了哪些应用", "装了哪些app", "已安装应用", "app列表", "应用列表", "装了什么应用",
      "安装的应用", "第三方应用", "安装了什么",
      "有哪些app", "有哪些应用", "装了哪些", "哪些app", "哪些应用",
      "系统里有什么app", "手机里有什么app", "手机有什么应用",
      "列出app", "列出应用", "查看应用", "查看app"],
     lambda q: _fmt_installed_apps(system="系统" in q or "all" in q.lower() or "所有" in q)),

    # 设备信息
    (["设备信息", "手机信息", "手机型号", "设备型号", "手机品牌", "android版本",
      "系统版本", "手机是什么", "什么型号", "是什么手机"],
     lambda q: _fmt_device_info()),

    # CPU
    (["cpu", "处理器", "核心数", "cpu使用率", "cpu信息", "处理器信息"],
     lambda q: _fmt_cpu_info()),

    # 网络
    (["ip地址", "wifi", "网络信息", "网络状态", "dns", "网关", "ip是什么", "ssid",
      "连的什么wifi", "网络连接"],
     lambda q: _fmt_network_info()),

    # 屏幕
    (["屏幕分辨率", "分辨率", "屏幕信息", "屏幕尺寸", "屏幕亮度", "屏幕密度"],
     lambda q: _fmt_screen_info()),

    # 运行时长
    (["运行时间", "开机时间", "运行了多久", "开机多久", "uptime"],
     lambda q: _fmt_uptime()),

    # 进程
    (["进程列表", "运行中的进程", "后台进程", "进程"],
     lambda q: _fmt_running_processes()),

    # 时间/语言
    (["当前时间", "现在几点", "时区", "语言设置", "系统时间"],
     lambda q: _fmt_locale_time()),
]


# ============================================================
# 公共 API
# ============================================================

def try_device_query(instruction: str) -> Optional[str]:
    """
    尝试将用户指令识别为设备信息查询并直接返回答案。

    Returns:
        str — 已格式化的答案字符串（直接展示给用户）
        None — 不是设备信息查询，需要走 VLM Agent 流程
    """
    q = instruction.lower().strip()

    # P0-4: 排除明显的操作类指令，仅匹配独立操作动词短语，避免误杀查询性问题
    # 策略: 动词必须紧跟宾语（非查询性组合），或以动词开头的命令式句子
    _COMMAND_VERB_PATTERNS = [
        # 以操作动词开头的命令式 —— 最可靠的判断
        r"^(帮我|请|去|能|可以)?(打开|启动|关闭|安装|卸载|发送|点击|截图|录制|修改|更改|下载)",
        r"^(open|launch|click|install|send|type|navigate|start|close|download)\b",
        # 明确的操作宾语组合
        r"(打开|启动)(app|应用|软件|微信|抖音|支付宝)",
        r"(关闭|卸载)(app|应用|软件|进程)",
        r"(发送|转发|分享).*(消息|文件|图片|链接)",
        r"(输入|填写|输入文字)",
        r"(搜索|查找|找).*(app|应用|文件|联系人)",
        r"(导航到|导航至)",
    ]
    import re as _re
    for pattern in _COMMAND_VERB_PATTERNS:
        if _re.search(pattern, q):
            return None

    for keywords, handler in _QUERY_RULES:
        for kw in keywords:
            if kw in q:
                try:
                    return handler(q)
                except Exception as e:
                    return f"查询失败: {e}"

    return None
