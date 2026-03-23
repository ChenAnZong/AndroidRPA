"""ADB 管理 — 查找 ADB、设备列表、端口转发、Shell 执行"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Tuple

from .exceptions import AdbError, AdbNotFoundError, DeviceNotFoundError

_ADB_TIMEOUT = 15

# ---------- 数据模型 ----------

@dataclass
class AdbDevice:
    """ADB 设备"""
    serial: str
    state: str  # device / offline / unauthorized

    @property
    def is_ready(self) -> bool:
        return self.state == "device"


# ---------- ADB 路径查找 ----------

def _candidate_paths() -> List[str]:
    """返回 ADB 可能存在的路径列表"""
    candidates: List[str] = []

    # 1. 环境变量
    env = os.environ.get("YYDS_ADB_PATH")
    if env:
        candidates.append(env)

    # 2. ANDROID_HOME / ANDROID_SDK_ROOT
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(var)
        if sdk:
            candidates.append(os.path.join(sdk, "platform-tools", "adb"))

    # 3. 常见安装路径
    home = Path.home()
    if sys.platform == "win32":
        candidates += [
            str(home / "AppData" / "Local" / "Android" / "Sdk" / "platform-tools" / "adb.exe"),
            r"C:\platform-tools\adb.exe",
        ]
    elif sys.platform == "darwin":
        candidates += [
            str(home / "Library" / "Android" / "sdk" / "platform-tools" / "adb"),
            "/opt/homebrew/bin/adb",
        ]
    else:
        candidates += [
            str(home / "Android" / "Sdk" / "platform-tools" / "adb"),
            "/usr/bin/adb",
            "/usr/local/bin/adb",
        ]

    return candidates


def find_adb() -> str:
    """查找 ADB 可执行文件路径，找不到则抛出 AdbNotFoundError"""
    # shutil.which 优先（PATH 中）
    which = shutil.which("adb")
    if which:
        return which

    for p in _candidate_paths():
        if p and os.path.isfile(p) and os.access(p, os.X_OK):
            return p

    raise AdbNotFoundError(
        "找不到 ADB，请安装 Android SDK Platform-Tools 或设置 YYDS_ADB_PATH 环境变量\n"
        "下载地址: https://developer.android.com/tools/releases/platform-tools"
    )


# ---------- ADB 命令执行 ----------

class AdbClient:
    """ADB 客户端封装"""

    def __init__(self, adb_path: Optional[str] = None):
        self._adb = adb_path or find_adb()

    @property
    def adb_path(self) -> str:
        return self._adb

    def raw(self, *args: str, timeout: float = _ADB_TIMEOUT) -> Tuple[int, str, str]:
        """执行原始 ADB 命令，返回 (exit_code, stdout, stderr)"""
        cmd = [self._adb, *args]
        try:
            proc = subprocess.run(
                cmd, capture_output=True, text=True, timeout=timeout,
            )
            return proc.returncode, proc.stdout, proc.stderr
        except FileNotFoundError:
            raise AdbNotFoundError(f"ADB 不存在: {self._adb}")
        except subprocess.TimeoutExpired:
            raise AdbError(f"ADB 命令超时 ({timeout}s): {' '.join(cmd)}")

    def run(self, *args: str, timeout: float = _ADB_TIMEOUT) -> str:
        """执行 ADB 命令，返回 stdout，失败抛异常"""
        code, out, err = self.raw(*args, timeout=timeout)
        if code != 0:
            raise AdbError(f"adb {' '.join(args)} 失败 (exit={code}): {err.strip() or out.strip()}")
        return out

    # ---------- 设备管理 ----------

    def devices(self) -> List[AdbDevice]:
        """列出已连接设备"""
        out = self.run("devices")
        result: List[AdbDevice] = []
        for line in out.strip().splitlines()[1:]:
            line = line.strip()
            if not line or line.startswith("*"):
                continue
            parts = line.split("\t")
            if len(parts) >= 2:
                result.append(AdbDevice(serial=parts[0], state=parts[1]))
        return result

    def get_device(self, serial: Optional[str] = None) -> AdbDevice:
        """获取指定设备，serial 为 None 时返回唯一设备"""
        devs = self.devices()
        ready = [d for d in devs if d.is_ready]

        if not ready:
            if devs:
                states = ", ".join(f"{d.serial}({d.state})" for d in devs)
                raise DeviceNotFoundError(f"设备未就绪: {states}")
            raise DeviceNotFoundError("未检测到 ADB 设备，请检查 USB 连接或开启 USB 调试")

        if serial:
            for d in ready:
                if d.serial == serial:
                    return d
            raise DeviceNotFoundError(f"未找到设备 {serial}，可用设备: {', '.join(d.serial for d in ready)}")

        if len(ready) == 1:
            return ready[0]

        serials = ", ".join(d.serial for d in ready)
        raise DeviceNotFoundError(f"检测到多台设备 ({serials})，请指定 serial 参数")

    # ---------- 设备操作 ----------

    def shell(self, serial: str, cmd: str, timeout: float = _ADB_TIMEOUT) -> Tuple[int, str]:
        """执行 adb shell 命令，返回 (exit_code, output)"""
        code, out, err = self.raw("-s", serial, "shell", cmd, timeout=timeout)
        return code, out

    def forward(self, serial: str, local_port: int, remote_port: int) -> bool:
        """设置 TCP 端口转发"""
        try:
            self.run("-s", serial, "forward", f"tcp:{local_port}", f"tcp:{remote_port}")
            return True
        except AdbError:
            return False

    def forward_remove(self, serial: str, local_port: int) -> None:
        """移除端口转发"""
        try:
            self.run("-s", serial, "forward", "--remove", f"tcp:{local_port}")
        except AdbError:
            pass

    def install(self, serial: str, apk_path: str, replace: bool = True) -> str:
        """安装 APK"""
        args = ["-s", serial, "install"]
        if replace:
            args.append("-r")
        args.append(apk_path)
        return self.run(*args, timeout=120)

    def push(self, serial: str, local: str, remote: str) -> str:
        """推送文件到设备"""
        return self.run("-s", serial, "push", local, remote, timeout=120)

    def pull(self, serial: str, remote: str, local: str) -> str:
        """从设备拉取文件"""
        return self.run("-s", serial, "pull", remote, local, timeout=120)

    def get_prop(self, serial: str, prop: str) -> str:
        """获取设备属性"""
        _, out = self.shell(serial, f"getprop {prop}")
        return out.strip()

    def is_installed(self, serial: str, package: str) -> bool:
        """检查应用是否已安装"""
        _, out = self.shell(serial, f"pm path {package}")
        return "package:" in out

    def start_activity(self, serial: str, component: str) -> None:
        """启动 Activity"""
        self.shell(serial, f"am start -n {component}")

    def broadcast(self, serial: str, action: str, extras: str = "") -> None:
        """发送广播"""
        self.shell(serial, f"am broadcast -a {action} {extras}".strip())
