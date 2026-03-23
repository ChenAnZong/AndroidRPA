"""脚本项目管理 Mixin"""

from __future__ import annotations

from typing import TYPE_CHECKING, Dict, List, Optional

from ..types import ProjectInfo

if TYPE_CHECKING:
    from ..device import Device


class ProjectMixin:
    """脚本项目管理（yyds.auto 独有能力）"""

    _client: ...

    def list_projects(self) -> List[ProjectInfo]:
        """列出设备上的脚本项目"""
        result = self._client.get_json("/project/list")
        projects: List[ProjectInfo] = []
        if isinstance(result, list):
            for item in result:
                if isinstance(item, dict):
                    projects.append(ProjectInfo(
                        name=item.get("name", ""),
                        path=item.get("path", ""),
                        running=item.get("running", False),
                    ))
        return projects

    def start_project(self, name: str) -> None:
        """启动脚本项目

        Args:
            name: 项目名称
        """
        self._client.get_text("/project/start", params={"name": name})

    def stop_project(self) -> None:
        """停止当前运行的脚本项目"""
        self._client.get_text("/project/stop")

    def project_status(self) -> Dict:
        """获取当前项目运行状态"""
        return self._client.get_json("/project/status")

    def run_code(self, code: str, timeout: float = 30) -> str:
        """在设备上执行 Python 代码片段

        Args:
            code: Python 代码
            timeout: 超时秒数

        Returns:
            执行输出
        """
        result = self._client.post_text("/engine/run-code", {"code": code}, timeout=timeout)
        return result
