"""
entry.py — Python 运行时入口模块

职责:
  1. 安装 ConsoleOutputStream，使 print() 输出通过 PyOut → WebSocket 到达 IDE 控制台
  2. 提供项目加载 / 运行 / 停止的生命周期管理
  3. 提供 run_code_snippet / run_single_py_file 等辅助 API
"""

import glob
import importlib
import importlib.util
import os
import sys
import gc
import threading
import traceback
from abc import ABC, abstractmethod
from io import TextIOBase

# ---------------------------------------------------------------------------
# 环境初始化
# ---------------------------------------------------------------------------
_home = os.environ.get("HOME", "")
os.environ["PYTHONPATH"] = os.environ.get("PYTHONPATH", "") + ":" + _home if _home else os.environ.get("PYTHONPATH", "")
threading.current_thread().setName("Py.Entry")

# ---------------------------------------------------------------------------
# 项目任务抽象
# ---------------------------------------------------------------------------

class ProjectTask(ABC):
    """项目运行任务的抽象基类"""

    @abstractmethod
    def start(self): ...

    @abstractmethod
    def stop(self): ...

    @abstractmethod
    def join(self): ...

    @abstractmethod
    def is_running(self) -> bool: ...


class ProjectThread(ProjectTask):
    """在守护线程中运行项目 main() 的任务实现"""

    def __init__(self, target):
        self._thread = threading.Thread(target=target, daemon=True)
        self._running = True

    def start(self):
        self._thread.start()

    def join(self):
        self._thread.join()

    def is_running(self) -> bool:
        return self._thread.is_alive() and self._running

    def stop(self):
        self._running = False
        # 子进程隔离模式: 由父进程 SIGTERM/SIGKILL 终止
        try:
            sys.exit(0)
        except SystemExit:
            pass


# ---------------------------------------------------------------------------
# 全局运行状态
# ---------------------------------------------------------------------------

class _RunState:
    """当前运行中的项目状态（模块级单例）"""
    project_name: str = ""
    task: ProjectTask = None


# ---------------------------------------------------------------------------
# ConsoleOutputStream — 拦截 print() 输出并转发到 IDE
# ---------------------------------------------------------------------------

from pyengine import PyOut

class ConsoleOutputStream(TextIOBase):
    """
    拦截 sys.stdout / sys.stderr，将输出转发到 PyOut → WebSocket → IDE 控制台。
    同时写入原始 fd，供 C 层管道线程捕获到 logcat（C 层不再调 PyOut，不会重复）。
    """

    def __init__(self, stream, is_stderr=False):
        self._stream = stream          # 原始 sys.stdout / sys.stderr
        self._is_stderr = is_stderr

    def write(self, text):
        if not text:
            return 0
        # 1) 转发到 PyOut → WebSocket（唯一路径）
        #    去掉尾部换行符，避免前端显示时多出空行
        forwarded = text.rstrip('\n')
        if forwarded:
            if self._is_stderr:
                PyOut.err(forwarded)
            else:
                PyOut.out(forwarded)
        # 2) 写入原始 fd → C 管道 → logcat（C 层只写 logcat，不调 PyOut）
        try:
            self._stream.write(text)
            self._stream.flush()
        except Exception:
            pass
        return len(text)

    def flush(self):
        try:
            self._stream.flush()
        except Exception:
            pass

    @property
    def encoding(self):
        return getattr(self._stream, 'encoding', 'utf-8')

sys.stdout = ConsoleOutputStream(sys.stdout, is_stderr=False)
sys.stderr = ConsoleOutputStream(sys.stderr, is_stderr=True)

# ---------------------------------------------------------------------------
# 线程异常处理
# ---------------------------------------------------------------------------

def _thread_exception_handler(exc_info):
    if "exc_value=SystemExit()" in str(exc_info):
        print("@exit() 退出!")
    else:
        print("执行错误:", exc_info, file=sys.stderr)
        print("错误堆栈:", traceback.format_exc(), file=sys.stderr)


threading.excepthook = _thread_exception_handler

# ---------------------------------------------------------------------------
# 公开 API
# ---------------------------------------------------------------------------

PROJECT_DIR = "/sdcard/Yyds.Py"


def kill():
    """终止当前运行的项目"""
    gc.collect()
    _cleanup_project_paths()
    if _RunState.task:
        print(f"@@结束工程:{_RunState.project_name}")
        _RunState.task.stop()
        try:
            sys.exit(0)
        except SystemExit:
            pass


def run_project(project_name: str) -> str:
    """
    项目运行入口（由 Java 层调用）。
    加载 /sdcard/Yyds.Py/<project_name>/ 下的 Python 模块并执行其 main() 函数。
    """
    if is_project_running():
        raise RuntimeError(f"当前正在运行工程: {_RunState.project_name}")

    _RunState.project_name = project_name.strip()
    _cleanup_project_paths()

    project_dir = f"{PROJECT_DIR}/{_RunState.project_name}"
    sys.path.append(project_dir)
    os.chdir(project_dir)

    _reload_project_modules()

    spec = _find_entry_spec(_RunState.project_name, project_dir)
    if spec is None:
        return ""

    module = importlib.util.module_from_spec(spec)
    sys.modules[_RunState.project_name] = module
    spec.loader.exec_module(module)

    if hasattr(module, "main"):
        _RunState.task = ProjectThread(module.main)
        _RunState.task.start()
        _RunState.task.join()
        _RunState.task.stop()
    else:
        print("运行工程错误, 找不到main函数", file=sys.stderr)

    del sys.modules[_RunState.project_name]
    gc.collect()
    print(f"@@@ 工程{_RunState.project_name}进程运行完毕")
    return "ok"


def is_project_running() -> bool:
    return _RunState.task is not None and _RunState.task.is_running()


def get_project_name() -> str:
    return _RunState.project_name


def run_code_snippet(code: str):
    """执行代码片段（IDE 交互式运行）"""
    try:
        exec(code)
    except Exception:
        print("运行代码异常:\n" + traceback.format_exc())


def run_single_py_file(path: str):
    """直接执行单个 .py 文件"""
    with open(path, "rb") as f:
        exec(compile(f.read(), path, "exec"))


# ---------------------------------------------------------------------------
# 内部辅助函数
# ---------------------------------------------------------------------------

def _cleanup_project_paths():
    """从 sys.path 中移除旧的项目路径"""
    for p in tuple(sys.path):
        if "/sdcard" in p:
            sys.path.remove(p)


def _reload_project_modules():
    """重新加载已缓存的项目模块（热更新）"""
    for module in list(sys.modules.values()):
        try:
            module_str = str(module)
            if f"{PROJECT_DIR}/" not in module_str:
                continue
            temp = module.__file__
            while os.path.dirname(temp) != PROJECT_DIR:
                temp = os.path.dirname(temp)
            new_path = module.__file__.replace(temp, os.getcwd())
            if new_path != module.__file__ and os.path.exists(new_path):
                module.__file__ = new_path
                module.__loader__.path = new_path
                module.__spec__.loader.path = new_path
                importlib.reload(module)
                print(f"Reload PY module {module.__name__}: {module} success")
        except Exception:
            print(f"Reload PY module {module.__name__}: {module} failed")


def _find_entry_spec(module_name: str, project_dir: str):
    """按优先级查找项目入口文件: entry.py > main.py > 其他 .py"""
    candidates = ["entry.py", "main.py"]
    candidates.extend(
        os.path.basename(x) for x in glob.glob(f"{project_dir}/*.py")
        if os.path.basename(x) not in candidates
    )
    for filename in candidates:
        filepath = os.path.join(project_dir, filename)
        if os.path.exists(filepath):
            return importlib.util.spec_from_file_location(module_name, filepath)
    return None
