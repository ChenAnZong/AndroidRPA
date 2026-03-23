import glob
import importlib
import importlib.util
import os
import sys
import gc
import threading
import traceback
from abc import ABCMeta, abstractmethod, ABC
from io import TextIOBase

from pyengine import PyOut

# CPython 嵌入式模式: PYTHONPATH 可能未设置，安全处理
if "PYTHONPATH" in os.environ:
    os.environ["PYTHONPATH"] = os.environ["PYTHONPATH"] + ":" + os.environ.get("HOME", "")
else:
    os.environ["PYTHONPATH"] = os.environ.get("HOME", "")
threading.current_thread().setName("Py.Entry")


class ProjectTask(metaclass=ABCMeta):
    @abstractmethod
    def start(self):
        pass

    @abstractmethod
    def stop(self):
        pass

    @abstractmethod
    def join(self):
        pass

    @abstractmethod
    def is_running(self):
        pass


class ProjectThread(ABC, ProjectTask):
    def __init__(self, t):
        self.thread = threading.Thread(target=t, daemon=True)
        self.running = True

    def start(self):
        self.thread.start()

    def join(self):
        self.thread.join()

    def is_running(self):
        return self.thread.is_alive() and self.running

    def stop(self):
        self.running = False
        # 子进程隔离模式: 由父进程 SIGTERM/SIGKILL 终止，无需线程级注入
        # 调用 sys.exit 让当前进程干净退出
        try:
            sys.exit(0)
        except SystemExit:
            pass


class Current:
    PROJECT_NAME: str = ""
    CUR_TASK: ProjectTask = None


class ConsoleOutputStream(TextIOBase):
    def __init__(self, stream, is_error):
        self.stream = stream
        self.is_error = is_error

    def __getattribute__(self, name):
        if name in [
            "close", "closed", "flush", "writable",  # IOBase
            "encoding", "errors", "newlines", "buffer", "detach",  # TextIOBase
            "line_buffering", "write_through", "reconfigure",  # TextIOWrapper
        ]:
            return getattr(self.stream, name)
        else:
            return super().__getattribute__(name)

    def write(self, s):
        result = self.stream.write(s)
        if self.is_error:
            PyOut.err(s)
        else:
            PyOut.out(s)
        return result


sys.stdout = ConsoleOutputStream(sys.stdout, False)
sys.stderr = ConsoleOutputStream(sys.stderr, True)


def thread_execption_handle(exec_type):
    if "exc_value=SystemExit()" in str(exec_type):
        print("@exit() 退出!")
    else:
        print("执行错误:", exec_type, file=sys.stderr)
        print("错误堆栈:", traceback.format_exc(), file=sys.stderr)


threading.excepthook = thread_execption_handle


def kill():
    gc.collect()
    for name in tuple(sys.path):
        if "/sdcard" in name:
            sys.path.remove(name)
    if Current.CUR_TASK:
        print(f"@@结束工程:{Current.PROJECT_NAME}")
        Current.CUR_TASK.stop()
        try:
            exit(0)
        except:
            pass


def run_project(pn_):
    if is_project_running():
        raise RuntimeError(f"当前正在运行工程: {Current.PROJECT_NAME}")
    """
    从java进来的py程序入口，接下来运行工程文件
    :param pn_:
    :return:
    """
    Current.PROJECT_NAME = pn_.replace("\r", "").replace("\n", "")

    for sp in tuple(sys.path):
        if "/sdcard" in sp:
            sys.path.remove(sp)

    sys.path.append(f"/sdcard/Yyds.Py/{Current.PROJECT_NAME}")
    cd = f"/sdcard/Yyds.Py/{Current.PROJECT_NAME}"
    os.chdir(cd)
    module_name = Current.PROJECT_NAME

    # print("sys.path", sys.path)
    # 刷新模块
    for module in list(sys.modules.values()):
        try:
            module_str = str(module)
            if "/sdcard/Yyds.Py/" in module_str:
                temp = module.__file__
                while os.path.dirname(temp) != "/sdcard/Yyds.Py":
                    temp = os.path.dirname(temp)
                nmp = os.path.join(module.__file__.replace(temp, os.getcwd()))
                if nmp != module.__file__ and os.path.exists(nmp):
                    module.__file__ = nmp
                    module.__loader__.path = nmp
                    module.__spec__.loader.path = nmp
                    importlib.reload(module)
                    print(f"Reload PY module {module.__name__}: {module} success")
        except:
            print(f"Reload PY module {module.__name__}: {module} failed")

    # 加载入口
    try_first_load_py = ["entry.py", "main.py"]
    try_first_load_py.extend([os.path.basename(x) for x in glob.glob(f'/sdcard/Yyds.Py/{Current.PROJECT_NAME}/*.py')])
    spec = None
    for py_file_name in try_first_load_py:
        main_path = f"/sdcard/Yyds.Py/{Current.PROJECT_NAME}/{py_file_name}"
        if os.path.exists(main_path):
            spec = importlib.util.spec_from_file_location(module_name, main_path)
            break
    if spec is None:
        return ""

    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)

    # import main as module
    if hasattr(module, 'main'):
        # if os.getuid() > 2000:
        Current.CUR_TASK = ProjectThread(module.main)
        # else:
        #     Current.CUR_TASK = ProjectProcess(module.main)
        Current.CUR_TASK.start()
        Current.CUR_TASK.join()
        Current.CUR_TASK.stop()
    else:
        print("运行工程错误, 找不到main函数", file=sys.stderr)
    del sys.modules[module_name]
    gc.collect()
    print(f"@@@ 工程{Current.PROJECT_NAME}进程运行完毕")
    return "ok"


def is_project_running():
    if Current.CUR_TASK is not None and Current.CUR_TASK.is_running():
        return True
    else:
        return False


def get_project_name():
    return Current.PROJECT_NAME


def run_code_snippet(code):
    try:
        exec(code)
    except:
        print("运行代码异常:\n" + traceback.format_exc())


def run_single_py_file(path):
    def execfile(filename, globals=None, locals=None):
        # http://stackoverflow.com/a/6357418/195651
        exec(compile(open(filename, "rb").read(), filename, 'exec'), globals, locals)

    execfile(path)

# print("""
# { @˙ꈊ˙@ }
# 帮助教程 官方网站: yyydsxx.com
# """)
# print(dir())
