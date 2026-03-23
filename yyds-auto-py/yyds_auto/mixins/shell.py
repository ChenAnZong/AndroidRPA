"""Shell 命令 Mixin"""

from __future__ import annotations

from typing import TYPE_CHECKING

from ..types import ShellResponse

if TYPE_CHECKING:
    from ..device import Device


class ShellMixin:
    """Shell 命令执行"""

    _client: ...

    def shell(self, cmd: str, timeout: float = 15) -> ShellResponse:
        """在设备上执行 Shell 命令（通过引擎，ROOT/SHELL 权限）

        Args:
            cmd: Shell 命令
            timeout: 超时秒数

        Returns:
            ShellResponse(output, exit_code)

        Example:
            >>> d.shell("ls /sdcard").output
            >>> d.shell("whoami")  # 通常返回 root 或 shell
        """
        result = self._client.post_json("/engine/shell", {"cmd": cmd}, timeout=timeout)
        if isinstance(result, dict):
            return ShellResponse(
                output=result.get("output", result.get("result", "")),
                exit_code=int(result.get("exitCode", result.get("exit_code", 0))),
            )
        return ShellResponse(output=str(result), exit_code=0)

    def adb_shell(self, cmd: str) -> ShellResponse:
        """通过 ADB 执行 Shell 命令（不需要引擎运行）

        仅在 USB 连接模式下可用。
        """
        if not hasattr(self, "_adb") or not hasattr(self, "_serial"):
            raise RuntimeError("adb_shell 仅在 USB 连接模式下可用")
        code, out = self._adb.shell(self._serial, cmd)  # type: ignore
        return ShellResponse(output=out, exit_code=code)
