"""文件操作 Mixin"""

from __future__ import annotations

from typing import TYPE_CHECKING, List, Optional

from ..types import FileInfo

if TYPE_CHECKING:
    from ..device import Device


class FileMixin:
    """设备文件操作"""

    _client: ...

    def push(self, local: str, remote: str) -> None:
        """推送本地文件到设备

        Args:
            local: 本地文件路径
            remote: 设备目标路径
        """
        with open(local, "rb") as f:
            self._client.post(
                "/post-file",
                data=None,
                files={"file": (remote.split("/")[-1], f)},
                timeout=120,
            )

    def pull(self, remote: str, local: str) -> None:
        """从设备拉取文件到本地

        Args:
            remote: 设备文件路径
            local: 本地保存路径
        """
        data = self._client.get_bytes("/pull-file", params={"path": remote})
        with open(local, "wb") as f:
            f.write(data)

    def list_files(self, path: str = "/sdcard") -> List[FileInfo]:
        """列出目录内容

        Args:
            path: 设备目录路径

        Returns:
            文件信息列表
        """
        result = self._client.get_json("/file/list", params={"path": path})
        files = []
        if isinstance(result, list):
            for item in result:
                if isinstance(item, dict):
                    files.append(FileInfo(
                        name=item.get("name", ""),
                        path=item.get("path", ""),
                        is_dir=item.get("isDir", item.get("is_dir", False)),
                        size=int(item.get("size", 0)),
                        modified=item.get("modified", ""),
                    ))
        return files

    def read_file(self, path: str) -> str:
        """读取设备上的文本文件"""
        return self._client.get_text("/file/read-text", params={"path": path})

    def write_file(self, path: str, content: str) -> None:
        """写入文本文件到设备"""
        self._client.post_json("/file/write-text", {"path": path, "content": content})

    def file_exists(self, path: str) -> bool:
        """检查文件是否存在"""
        result = self._client.get_text("/file/exists", params={"path": path})
        return result.strip().lower() in ("true", "1", "yes")

    def mkdir(self, path: str) -> None:
        """创建目录"""
        self._client.get_text("/file/mkdir", params={"path": path})

    def remove(self, path: str) -> None:
        """删除文件或目录"""
        self._client.get_text("/file/delete", params={"path": path})
