"""
守护线程 - 替代 Main.java 中的 startGuardThread()
监控 yyds.auto 和 yyds.keep 进程，异常时自动重启
"""

import os
import subprocess
import threading
import time

# 常量
AUTO_PROCESS_NAME = "yyds.auto"
KEEP_PROCESS_NAME = "yyds.keep"
GUARD_CHECK_INTERVAL = 30  # 秒
GUARD_INITIAL_DELAY = 45   # 秒


def _shell(cmd: str) -> str:
    """执行 shell 命令并返回输出"""
    try:
        ret = subprocess.run(
            ["sh", "-c", cmd],
            capture_output=True, text=True, timeout=10
        )
        return ret.stdout.strip()
    except Exception:
        return ""


def _pidof(name: str) -> str:
    """获取进程 PID"""
    return _shell(f"pidof {name}")


def _is_alive(name: str) -> bool:
    """检查进程是否存活"""
    return len(_pidof(name)) > 0


def _restart_auto_engine(auto_cmd: str):
    """重启 yyds.auto"""
    print(f"[yyds.py守护] 重启 {AUTO_PROCESS_NAME}...")
    _shell(auto_cmd)


def _restart_keeper(keeper_cmd: str):
    """重启 yyds.keep"""
    print(f"[yyds.py守护] 重启 {KEEP_PROCESS_NAME}...")
    _shell(keeper_cmd)


def start_guard_thread(auto_engine_cmd: str, keeper_cmd: str):
    """
    启动守护线程

    Args:
        auto_engine_cmd: 重启 yyds.auto 的 shell 命令
        keeper_cmd: 重启 yyds.keep 的 shell 命令
    """
    def _guard_loop():
        print(f"[yyds.py守护] 守护线程启动，初始等待{GUARD_INITIAL_DELAY}秒...")
        time.sleep(GUARD_INITIAL_DELAY)
        print(f"[yyds.py守护] 开始监控 {AUTO_PROCESS_NAME} 和 {KEEP_PROCESS_NAME} 进程")

        while True:
            try:
                # 检查 yyds.auto
                if not _is_alive(AUTO_PROCESS_NAME):
                    print(f"[yyds.py守护] 检测到{AUTO_PROCESS_NAME}未运行，正在重启...")
                    _restart_auto_engine(auto_engine_cmd)

                # 检查 yyds.keep
                if not _is_alive(KEEP_PROCESS_NAME):
                    print(f"[yyds.py守护] 检测到{KEEP_PROCESS_NAME}未运行，正在重启...")
                    _restart_keeper(keeper_cmd)

            except Exception as e:
                print(f"[yyds.py守护] 检查进程状态异常: {e}")

            time.sleep(GUARD_CHECK_INTERVAL)

    t = threading.Thread(target=_guard_loop, name="py-guard", daemon=True)
    t.start()
    return t
