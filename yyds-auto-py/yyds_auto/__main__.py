"""yyds-auto CLI — python -m yyds_auto"""

from __future__ import annotations

import argparse
import json
import sys


def cmd_version(args):
    from .version import __version__
    print(f"yyds-auto {__version__}")


def cmd_devices(args):
    from .adb import AdbClient
    try:
        adb = AdbClient()
        devs = adb.devices()
        if not devs:
            print("未检测到设备")
            return
        print(f"{'序列号':<30} {'状态':<15}")
        print("-" * 45)
        for d in devs:
            print(f"{d.serial:<30} {d.state:<15}")
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        sys.exit(1)


def cmd_discover(args):
    from .discover import discover
    subnet = args.subnet if hasattr(args, "subnet") else None
    print("正在扫描局域网设备 ...")
    devices = discover(subnet=subnet, timeout=args.timeout)
    if not devices:
        print("未发现设备")
        return
    print(f"\n发现 {len(devices)} 台设备:\n")
    print(f"{'IP':<18} {'型号':<20} {'Android':<10} {'引擎版本':<10}")
    print("-" * 58)
    for d in devices:
        print(f"{d.ip:<18} {d.model:<20} {d.android_version:<10} {d.engine_version:<10}")


def cmd_init(args):
    from .adb import AdbClient
    from .client import HttpClient
    from .setup import check_app_installed, ensure_engine, install_app

    try:
        adb = AdbClient()
        print(f"✓ ADB: {adb.adb_path}")

        dev = adb.get_device(args.serial if hasattr(args, "serial") else None)
        print(f"✓ 设备: {dev.serial}")

        installed = check_app_installed(adb, dev.serial)
        print(f"{'✓' if installed else '✗'} yyds.auto {'已安装' if installed else '未安装'}")

        if not installed and hasattr(args, "apk") and args.apk:
            install_app(adb, dev.serial, args.apk)
            print("✓ APK 安装完成")

        # 端口转发 + 启动引擎
        adb.forward(dev.serial, 61140, 61140)
        http = HttpClient()
        ensure_engine(adb, dev.serial, http)
        print("✓ 引擎运行中")

    except Exception as e:
        print(f"✗ {e}", file=sys.stderr)
        sys.exit(1)


def cmd_doctor(args):
    import shutil
    print("=== yyds-auto 环境诊断 ===\n")

    # ADB
    adb_path = shutil.which("adb")
    print(f"ADB: {'✓ ' + adb_path if adb_path else '✗ 未找到'}")

    # 设备
    if adb_path:
        from .adb import AdbClient
        try:
            adb = AdbClient(adb_path)
            devs = adb.devices()
            print(f"设备: {'✓ ' + str(len(devs)) + ' 台' if devs else '✗ 无设备'}")
            for d in devs:
                print(f"  - {d.serial} ({d.state})")
                if d.is_ready:
                    installed = adb.is_installed(d.serial, "com.yyds.auto")
                    print(f"    yyds.auto: {'✓ 已安装' if installed else '✗ 未安装'}")
        except Exception as e:
            print(f"设备: ✗ {e}")

    # 引擎
    from .client import HttpClient
    http = HttpClient()
    alive = http.ping()
    print(f"引擎: {'✓ 运行中' if alive else '✗ 未运行'}")

    # Python 依赖
    deps = ["requests", "PIL", "adbutils"]
    for dep in deps:
        try:
            __import__(dep)
            print(f"{dep}: ✓")
        except ImportError:
            print(f"{dep}: ✗ 未安装")


def cmd_screenshot(args):
    from . import connect
    d = connect(args.serial if hasattr(args, "serial") and args.serial else None)
    filename = args.output if hasattr(args, "output") and args.output else "screenshot.png"
    d.screenshot(filename)
    print(f"截图已保存: {filename}")


def cmd_shell(args):
    from . import connect
    d = connect(args.serial if hasattr(args, "serial") and args.serial else None)
    result = d.shell(args.command)
    print(result.output, end="")
    sys.exit(result.exit_code)


def main():
    parser = argparse.ArgumentParser(prog="yyds-auto", description="yyds-auto Android RPA 工具")
    sub = parser.add_subparsers(dest="cmd")

    # version
    sub.add_parser("version", help="显示版本")

    # devices
    sub.add_parser("devices", help="列出 ADB 设备")

    # discover
    p_disc = sub.add_parser("discover", help="扫描局域网设备")
    p_disc.add_argument("--subnet", help="子网范围 (CIDR)")
    p_disc.add_argument("--timeout", type=float, default=0.5, help="探测超时 (秒)")

    # init
    p_init = sub.add_parser("init", help="初始化设备 (安装APK+启动引擎)")
    p_init.add_argument("-s", "--serial", help="设备序列号")
    p_init.add_argument("--apk", help="APK 文件路径")

    # doctor
    sub.add_parser("doctor", help="环境诊断")

    # screenshot
    p_shot = sub.add_parser("screenshot", help="截图")
    p_shot.add_argument("-s", "--serial", help="设备序列号")
    p_shot.add_argument("-o", "--output", default="screenshot.png", help="输出文件名")

    # shell
    p_shell = sub.add_parser("shell", help="执行 Shell 命令")
    p_shell.add_argument("-s", "--serial", help="设备序列号")
    p_shell.add_argument("command", help="Shell 命令")

    args = parser.parse_args()

    handlers = {
        "version": cmd_version,
        "devices": cmd_devices,
        "discover": cmd_discover,
        "init": cmd_init,
        "doctor": cmd_doctor,
        "screenshot": cmd_screenshot,
        "shell": cmd_shell,
    }

    if args.cmd in handlers:
        handlers[args.cmd](args)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
