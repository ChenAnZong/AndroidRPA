"""
项目管理模块 - 替代 PyEngine.kt 中的项目管理 + YyProject.kt 的扫描逻辑 + entry.py 的执行逻辑
负责: 扫描项目列表、启动/停止项目、运行代码片段
"""

import ctypes
import gc
import glob
import importlib
import importlib.util
import os
import sys
import threading
import time
import traceback
from typing import Optional, List, Dict, Any, Tuple

from log_manager import log_manager

PROJECT_BASE_DIR = "/storage/emulated/0/Yyds.Py"


class ProjectTask:
    """项目运行任务（线程模式）"""

    def __init__(self, target):
        self.thread = threading.Thread(target=target, daemon=True)
        self.running = True

    def start(self):
        self.thread.start()

    def join(self):
        self.thread.join()

    def is_running(self):
        return self.thread.is_alive() and self.running

    def stop(self):
        self.running = False
        # 仅向本任务线程发送 SystemExit，不影响其他线程
        if self.thread.is_alive() and self.thread.ident:
            try:
                ret = ctypes.pythonapi.PyThreadState_SetAsyncExc(
                    ctypes.c_ulong(self.thread.ident),
                    ctypes.py_object(SystemExit)
                )
                if ret > 1:
                    # 异常设置失败，撤销
                    ctypes.pythonapi.PyThreadState_SetAsyncExc(
                        ctypes.c_ulong(self.thread.ident), None
                    )
                    print(f"[ProjectTask] 无法安全终止线程 {self.thread.ident}")
            except Exception as e:
                print(f"[ProjectTask] 终止线程异常: {e}")


class ProjectManager:
    """项目管理器"""

    def __init__(self, project_dir: str = PROJECT_BASE_DIR):
        self.project_dir = project_dir
        self._current_task: Optional[ProjectTask] = None
        self._current_project: str = ""
        self._start_time: float = 0
        self._lock = threading.Lock()

    def scan_projects(self) -> List[Dict[str, Any]]:
        """扫描项目目录，返回项目列表（替代 YyProject.scanProject()）"""
        projects = []
        base = self.project_dir

        if not os.path.isdir(base):
            return projects

        try:
            entries = os.listdir(base)
        except OSError:
            return projects

        for name in entries:
            folder = os.path.join(base, name)
            if not os.path.isdir(folder):
                continue
            if name == "config" or name.startswith("."):
                continue

            config_file = os.path.join(folder, "project.config")
            if not os.path.isfile(config_file):
                continue

            props = self._load_properties(config_file)
            if props is None:
                continue

            pn = props.get("PROJECT_NAME", "").strip()
            pv = props.get("PROJECT_VERSION", "").strip()
            dl = props.get("DOWNLOAD_URL")

            if not pn or not pv:
                continue

            try:
                mtime = os.path.getmtime(folder)
                last_date = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(mtime))
            except OSError:
                last_date = ""

            projects.append({
                "name": pn,
                "version": pv,
                "folderPath": folder,
                "folderName": name,
                "lastDate": last_date,
                "downloadUrl": dl,
            })

        return projects

    def start_project(self, project_name: str):
        """启动项目（替代 PyEngine.startProject() + entry.run_project()）"""
        with self._lock:
            if self._current_task and self._current_task.is_running():
                print(f"正在停止当前项目: {self._current_project}")
                self.abort_project_internal()

        self._current_project = project_name.strip()
        self._start_time = time.time()
        print(f"@@启动工程: {self._current_project}")

        try:
            self._run_project(self._current_project)
        except SystemExit:
            print(f"{time.strftime('%Y-%m-%d %H:%M:%S')} 项目({self._current_project})已正常退出")
        except Exception as e:
            print(f"运行项目({self._current_project})出错: {e}", file=sys.stderr)
            traceback.print_exc(file=sys.stderr)
        finally:
            elapsed = int(time.time() - self._start_time)
            print(f"@@@ 工程{self._current_project}运行完毕，用时{elapsed}S")
            with self._lock:
                self._current_task = None

    def _run_project(self, project_name: str):
        """加载并执行项目（替代 entry.run_project()）"""
        project_dir = os.path.join(self.project_dir, project_name)

        # 清理旧的 sys.path 条目
        for sp in list(sys.path):
            if "/sdcard/Yyds.Py/" in sp or "/storage/emulated/0/Yyds.Py/" in sp:
                sys.path.remove(sp)

        sys.path.append(project_dir)
        os.chdir(project_dir)

        # 重新加载已修改的模块
        for module in list(sys.modules.values()):
            try:
                module_str = str(module)
                if "/Yyds.Py/" in module_str and hasattr(module, '__file__') and module.__file__:
                    old_file = module.__file__
                    # 尝试重新定位到当前项目目录
                    base = old_file
                    while os.path.dirname(base) not in (self.project_dir, "/sdcard/Yyds.Py"):
                        parent = os.path.dirname(base)
                        if parent == base:
                            break
                        base = parent
                    new_path = os.path.join(module.__file__.replace(base, os.getcwd()))
                    if new_path != module.__file__ and os.path.exists(new_path):
                        module.__file__ = new_path
                        if hasattr(module, '__loader__') and hasattr(module.__loader__, 'path'):
                            module.__loader__.path = new_path
                        if hasattr(module, '__spec__') and module.__spec__ and hasattr(module.__spec__, 'loader'):
                            if hasattr(module.__spec__.loader, 'path'):
                                module.__spec__.loader.path = new_path
                        importlib.reload(module)
                        print(f"Reload PY module {module.__name__} success")
            except Exception:
                pass

        # 查找入口文件
        try_files = ["entry.py", "main.py"]
        try_files.extend([
            os.path.basename(x)
            for x in glob.glob(os.path.join(project_dir, "*.py"))
            if os.path.basename(x) not in try_files
        ])

        spec = None
        for py_file in try_files:
            full_path = os.path.join(project_dir, py_file)
            if os.path.exists(full_path):
                spec = importlib.util.spec_from_file_location(project_name, full_path)
                break

        if spec is None:
            print(f"找不到项目入口文件: {project_dir}")
            return

        module = importlib.util.module_from_spec(spec)
        sys.modules[project_name] = module
        spec.loader.exec_module(module)

        if hasattr(module, 'main'):
            task = ProjectTask(module.main)
            with self._lock:
                self._current_task = task
            task.start()
            task.join()
            task.stop()
        else:
            print("运行工程错误, 找不到main函数", file=sys.stderr)

        if project_name in sys.modules:
            del sys.modules[project_name]
        gc.collect()

    def abort_project(self):
        """停止当前运行的项目"""
        with self._lock:
            self.abort_project_internal()

    def abort_project_internal(self):
        """内部停止方法（需持有 _lock）"""
        if self._current_task and self._current_task.is_running():
            print(f"@@结束工程: {self._current_project}")
            self._current_task.stop()
            self._current_task = None

    def get_running_status(self) -> Tuple[bool, Optional[str]]:
        """获取当前运行状态"""
        with self._lock:
            if self._current_task and self._current_task.is_running():
                return True, self._current_project
            return False, None

    def run_code_snippet(self, code: str) -> dict:
        """运行代码片段，捕获 stdout/stderr 并返回结果"""
        import io
        stdout_buf = io.StringIO()
        stderr_buf = io.StringIO()
        old_stdout, old_stderr = sys.stdout, sys.stderr
        try:
            sys.stdout = stdout_buf
            sys.stderr = stderr_buf
            exec(code)
            return {
                "success": True,
                "stdout": stdout_buf.getvalue(),
                "stderr": stderr_buf.getvalue(),
            }
        except Exception:
            stderr_buf.write(traceback.format_exc())
            return {
                "success": False,
                "stdout": stdout_buf.getvalue(),
                "stderr": stderr_buf.getvalue(),
            }
        finally:
            sys.stdout = old_stdout
            sys.stderr = old_stderr
            # 同时输出到原始流，保持日志链路
            out = stdout_buf.getvalue()
            err = stderr_buf.getvalue()
            if out:
                print(out, end="")
            if err:
                print(err, end="", file=sys.stderr)

    @staticmethod
    def _load_properties(config_file: str) -> Optional[Dict[str, str]]:
        """加载 Java Properties 格式的配置文件"""
        try:
            props = {}
            with open(config_file, 'r', encoding='utf-8') as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith('#') or line.startswith('!'):
                        continue
                    # 支持 = 和 : 分隔符
                    for sep in ('=', ':'):
                        idx = line.find(sep)
                        if idx >= 0:
                            key = line[:idx].strip()
                            val = line[idx + 1:].strip()
                            props[key] = val
                            break
            return props
        except Exception:
            return None


# 全局单例
project_manager = ProjectManager()
