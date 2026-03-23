"""
文件操作模块 - 替代 WebSocketAsServer.kt 中的文件相关 HTTP 端点
在 ROOT/SHELL 权限进程中直接操作文件系统
"""

import os
import shutil
import zipfile
from typing import List, Dict, Any, Optional


class FileOps:
    """文件操作工具"""

    @staticmethod
    def exists(path: str) -> bool:
        return os.path.exists(path)

    @staticmethod
    def read_text(path: str) -> Optional[str]:
        try:
            if os.path.isfile(path):
                with open(path, 'r', encoding='utf-8', errors='replace') as f:
                    return f.read()
        except PermissionError:
            print(f"[FileOps] 无读取权限: {path}")
        except OSError as e:
            print(f"[FileOps] 读取失败: {path} - {e}")
        return None

    @staticmethod
    def write_text(path: str, content: str) -> bool:
        try:
            parent = os.path.dirname(path)
            if parent and not os.path.exists(parent):
                os.makedirs(parent, exist_ok=True)
            with open(path, 'w', encoding='utf-8') as f:
                f.write(content)
            return True
        except Exception:
            return False

    @staticmethod
    def list_dir(path: str) -> Optional[Dict[str, Any]]:
        if not os.path.isdir(path):
            return None
        files = []
        for name in os.listdir(path):
            full = os.path.join(path, name)
            try:
                st = os.stat(full)
                files.append({
                    "name": name,
                    "path": full,
                    "isDir": os.path.isdir(full),
                    "size": st.st_size,
                    "lastModified": int(st.st_mtime * 1000),
                    "readable": os.access(full, os.R_OK),
                    "writable": os.access(full, os.W_OK),
                })
            except OSError:
                files.append({
                    "name": name,
                    "path": full,
                    "isDir": False,
                    "size": 0,
                    "lastModified": 0,
                    "readable": False,
                    "writable": False,
                })
        # 排序：目录优先，然后按名称
        files.sort(key=lambda x: (not x["isDir"], x["name"].lower()))
        return {"files": files, "parent": os.path.dirname(path)}

    @staticmethod
    def delete(path: str) -> bool:
        try:
            if os.path.isdir(path):
                shutil.rmtree(path)
            elif os.path.exists(path):
                os.remove(path)
            else:
                return False
            return True
        except Exception:
            return False

    @staticmethod
    def rename(old_path: str, new_name: str) -> Optional[str]:
        try:
            parent = os.path.dirname(old_path)
            new_path = os.path.join(parent, new_name)
            os.rename(old_path, new_path)
            return new_path
        except Exception:
            return None

    @staticmethod
    def mkdir(path: str) -> bool:
        try:
            os.makedirs(path, exist_ok=True)
            return True
        except Exception:
            return False

    @staticmethod
    def last_modified(path: str) -> int:
        try:
            if os.path.exists(path):
                return int(os.path.getmtime(path) * 1000)
        except Exception:
            pass
        return 0

    @staticmethod
    def unzip(zip_path: str, dest_dir: str) -> bool:
        """解压 zip 文件"""
        try:
            with zipfile.ZipFile(zip_path, 'r') as zf:
                zf.extractall(dest_dir)
            return True
        except zipfile.BadZipFile:
            print(f"[FileOps] 无效的ZIP文件: {zip_path}")
            return False
        except PermissionError:
            print(f"[FileOps] 解压权限不足: {dest_dir}")
            return False
        except Exception as e:
            print(f"[FileOps] 解压失败: {zip_path} -> {dest_dir} - {e}")
            return False


file_ops = FileOps()
