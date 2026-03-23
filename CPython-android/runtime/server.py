"""
HTTP + WebSocket 服务器 - 替代 WebSocketAsServer.kt (Ktor CIO)
使用 aiohttp 实现所有 REST API 端点和 WebSocket 端点
端口: 61140
"""

import asyncio
import json
import os
import subprocess
import sys
import time
import threading
import traceback
from typing import Optional

from aiohttp import web, WSMsgType

from log_manager import log_manager
from project_manager import project_manager
from auto_engine_proxy import auto_engine
from screen_capture import screen_capture
from file_ops import file_ops
from agent_config import AgentConfig, get_config, set_config
from agent import get_agent, reset_agent, MobileAgent, get_agent_run_list, get_agent_run_detail, clear_agent_logs

ENGINE_PORT = 61140
VERSION_CODE = "CPython"
BUILD_DATE = time.strftime("%Y-%m-%d")

# ── Path safety ──
_SAFE_PREFIXES = ("/sdcard/", "/data/local/tmp/", "/storage/")
_MAX_UPLOAD_SIZE = 100 * 1024 * 1024  # 100 MB

def _is_path_safe(path: str) -> bool:
    """Restrict file operations to safe directories only."""
    if not path:
        return False
    try:
        canonical = os.path.realpath(path)
    except Exception:
        canonical = path
    return any(canonical.startswith(p) or canonical == p.rstrip("/") for p in _SAFE_PREFIXES)


def _json_response(data, status=200):
    return web.json_response(data, status=status)


def _json_error(msg, status=500):
    return web.json_response({"error": msg}, status=status)


def _shell(cmd: str) -> str:
    try:
        ret = subprocess.run(["sh", "-c", cmd], capture_output=True, text=True, timeout=30)
        return ret.stdout.strip()
    except Exception as e:
        return str(e)


# ============================================================
# 路由: 基础
# ============================================================

async def handle_root(request):
    pid = os.getpid()
    uid = os.getuid()
    return web.Response(
        text=f"Yyds.Auto({VERSION_CODE}|{BUILD_DATE}) py engine [CPython], pid={pid}, uid={uid}\n"
    )


async def handle_ping(request):
    try:
        res = auto_engine.ping()
        return web.Response(text=res or "")
    except Exception as e:
        return web.Response(text=f"执行错误:{traceback.format_exc()}")


# ============================================================
# 路由: 项目管理
# ============================================================

async def handle_project_list(request):
    try:
        projects = project_manager.scan_projects()
        return _json_response(projects)
    except Exception as e:
        return _json_error(str(e))


async def handle_project_status(request):
    try:
        running, name = project_manager.get_running_status()
        return _json_response({"running": running, "project": name})
    except Exception as e:
        return _json_error(str(e))


async def handle_project_start(request):
    try:
        name = request.query.get("name")
        if not name:
            return _json_error("缺少name参数", 400)
        # 在后台线程运行项目
        threading.Thread(
            target=project_manager.start_project,
            args=(name,),
            daemon=True
        ).start()
        return _json_response({"success": True, "project": name})
    except Exception as e:
        return _json_error(str(e))


async def handle_project_stop(request):
    try:
        project_manager.abort_project()
        return _json_response({"success": True})
    except Exception as e:
        return _json_error(str(e))


# ============================================================
# 路由: 引擎控制
# ============================================================

async def handle_engine_run_code(request):
    try:
        body = await request.json()
        code = body.get("code")
        if not code:
            return _json_error("缺少code参数", 400)
        result = await asyncio.get_running_loop().run_in_executor(
            None, project_manager.run_code_snippet, code
        )
        return _json_response(result)
    except Exception as e:
        return _json_error(str(e))


async def handle_log_diag(request):
    """诊断端点：验证 log_manager 链路"""
    import io
    diag = io.StringIO()

    diag.write("=== Log Manager 诊断 ===\n")
    diag.write(f"sys.stdout type: {type(sys.stdout).__name__}\n")
    diag.write(f"sys.stderr type: {type(sys.stderr).__name__}\n")
    diag.write(f"hooks installed: {hasattr(sys.stdout, '_log_mgr')}\n")

    # 测试 print → drain
    test_msg = f"__DIAG_{time.time()}"
    print(test_msg)
    text = log_manager.drain()
    has_msg = f"O:{test_msg}" in text
    diag.write(f"print→drain: {'PASS' if has_msg else 'FAIL'} (got: {text!r})\n")

    # 测试跨线程
    import threading as _th
    done = _th.Event()
    def _worker():
        print(test_msg + "_T")
        done.set()
    _th.Thread(target=_worker, daemon=True).start()
    done.wait(timeout=2)
    text2 = log_manager.drain()
    has_t = f"O:{test_msg}_T" in text2
    diag.write(f"跨线程print→drain: {'PASS' if has_t else 'FAIL'} (got: {text2!r})\n")

    return web.Response(text=diag.getvalue(), content_type="text/plain")


async def handle_engine_reboot(request):
    try:
        # 延迟重启自身
        def _reboot():
            time.sleep(3)
            _shell("killall yyds.py")
        threading.Thread(target=_reboot, daemon=True).start()
        return _json_response({"success": True})
    except Exception as e:
        return _json_error(str(e))


async def handle_engine_shell(request):
    try:
        body = await request.json()
        command = body.get("command")
        if not command:
            return _json_error("缺少command参数", 400)
        ret = _shell(command)
        return _json_response({"success": True, "result": ret})
    except Exception as e:
        return _json_error(str(e))


async def handle_engine_click(request):
    try:
        body = await request.json()
        x = body.get("x")
        y = body.get("y")
        if x is None or y is None:
            return _json_error("缺少x或y参数", 400)
        if auto_engine.check_engine():
            ok = auto_engine.touch(x, y)
            return _json_response({"success": ok})
        else:
            return _json_error("连接设备自动引擎失败", 503)
    except Exception as e:
        return _json_error(str(e))


async def handle_engine_auto(request):
    try:
        body = await request.json()
        uri = body.get("uri")
        if not uri:
            return _json_error("缺少uri参数", 400)
        if auto_engine.check_engine():
            ret = auto_engine.http(uri, body)
            return _json_response({"success": True, "result": ret})
        else:
            return _json_error("连接设备自动引擎失败", 503)
    except Exception as e:
        return _json_error(str(e))


async def handle_engine_foreground(request):
    try:
        if auto_engine.check_engine():
            ret = auto_engine.foreground()
            return _json_response({"success": True, "result": ret})
        else:
            return _json_error("连接自动引擎失败", 503)
    except Exception as e:
        return _json_error(str(e))


# ============================================================
# 路由: 截图与 UI Dump
# ============================================================

_last_screen_size = 0


async def handle_screen(request):
    global _last_screen_size
    try:
        quality = 100
        q_param = request.match_info.get("quality")
        if q_param:
            quality = int(q_param)
        else:
            q_query = request.query.get("quality")
            if q_query:
                quality = int(q_query)

        is_force = "no-cache" in request.query

        data = screen_capture.get_bitmap_data(quality)
        if len(data) == _last_screen_size and not is_force:
            return web.Response(body=b"")
        elif data:
            _last_screen_size = len(data)
            content_type = "image/png" if data[:4] == b'\x89PNG' else "image/jpeg"
            return web.Response(body=data, content_type=content_type)
        else:
            return _json_error("截图数据为空")
    except Exception as e:
        return _json_error(str(e))


async def handle_screenshot(request):
    try:
        save_to = f"{project_manager.project_dir}/screenshot.png"
        if os.path.exists(save_to):
            os.remove(save_to)
        if screen_capture.write_to(save_to) and os.path.exists(save_to):
            return web.FileResponse(save_to)
        else:
            return _json_error("截图失败")
    except Exception as e:
        return _json_error(str(e))


async def handle_ui_dump(request):
    try:
        if auto_engine.check_engine():
            save_to = "/data/local/tmp/dump.xml"
            auto_engine.ui_dump(save_to)
            if os.path.exists(save_to):
                with open(save_to, 'r', encoding='utf-8', errors='replace') as f:
                    content = f.read()
                return web.Response(text=content, content_type="text/xml")
            else:
                return _json_error("dump文件不存在")
        else:
            return _json_error("auto engine未启动", 503)
    except Exception as e:
        return _json_error(str(e))


# ============================================================
# 路由: 文件操作
# ============================================================

async def handle_file_exists(request):
    path = request.query.get("path")
    if not path:
        return _json_error("缺少path参数", 400)
    return _json_response({"exists": file_ops.exists(path)})


async def handle_file_read_text(request):
    path = request.query.get("path")
    if not path:
        return _json_error("缺少path参数", 400)
    if not _is_path_safe(path):
        return _json_error(f"路径不允许: {path}", 403)
    content = file_ops.read_text(path)
    if content is not None:
        return web.Response(text=content)
    return _json_error(f"文件不存在: {path}", 404)


async def handle_file_write_text(request):
    try:
        body = await request.json()
        path = body.get("path")
        content = body.get("content", "")
        if not path:
            return _json_error("缺少path参数", 400)
        if not _is_path_safe(path):
            return _json_error(f"路径不允许: {path}", 403)
        ok = file_ops.write_text(path, content)
        return _json_response({"success": ok})
    except Exception as e:
        return _json_error(str(e))


async def handle_file_list(request):
    path = request.query.get("path")
    if not path:
        return _json_error("缺少path参数", 400)
    result = file_ops.list_dir(path)
    if result is None:
        return _json_error(f"目录不存在: {path}", 404)
    return _json_response(result)


async def handle_file_delete(request):
    path = request.query.get("path")
    if not path:
        return _json_error("缺少path参数", 400)
    if not _is_path_safe(path):
        return _json_error(f"路径不允许: {path}", 403)
    ok = file_ops.delete(path)
    return _json_response({"success": ok})


async def handle_file_rename(request):
    try:
        body = await request.json()
        old_path = body.get("oldPath")
        new_name = body.get("newName")
        if not old_path:
            return _json_error("缺少oldPath参数", 400)
        if not new_name:
            return _json_error("缺少newName参数", 400)
        if not _is_path_safe(old_path):
            return _json_error(f"路径不允许: {old_path}", 403)
        new_path = os.path.join(os.path.dirname(old_path), new_name)
        if not _is_path_safe(new_path):
            return _json_error(f"目标路径不允许: {new_path}", 403)
        new_path = file_ops.rename(old_path, new_name)
        if new_path:
            return _json_response({"success": True, "newPath": new_path})
        return _json_response({"success": False})
    except Exception as e:
        return _json_error(str(e))


async def handle_file_mkdir(request):
    path = request.query.get("path")
    if not path:
        return _json_error("缺少path参数", 400)
    if not _is_path_safe(path):
        return _json_error(f"路径不允许: {path}", 403)
    ok = file_ops.mkdir(path)
    return _json_response({"success": ok})


async def handle_file_last_modified(request):
    path = request.query.get("path")
    if not path:
        return _json_error("缺少path参数", 400)
    ts = file_ops.last_modified(path)
    return _json_response({"lastModified": ts})


# ============================================================
# 路由: 文件传输
# ============================================================

async def handle_pull_file(request):
    try:
        path = request.query.get("path")
        if not path or not os.path.isfile(path):
            return web.Response(text=f"path:{path} not exists", status=404)
        if not _is_path_safe(path):
            return _json_error(f"路径不允许: {path}", 403)
        return web.FileResponse(path)
    except Exception as e:
        return web.Response(text=traceback.format_exc())


async def handle_post_file(request):
    try:
        reader = await request.multipart()
        path = None
        filename = None
        data = None

        async for part in reader:
            if part.name == "path":
                path = (await part.text()).strip()
            elif part.filename:
                filename = part.filename
                # Read with size limit to prevent OOM
                chunks = []
                total = 0
                while True:
                    chunk = await part.read_chunk(65536)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > _MAX_UPLOAD_SIZE:
                        return _json_error(f"文件过大(>{_MAX_UPLOAD_SIZE // 1024 // 1024}MB)", 413)
                    chunks.append(chunk)
                data = b"".join(chunks)

        if path and filename and data:
            if not _is_path_safe(path):
                return _json_error(f"路径不允许: {path}", 403)
            os.makedirs(path, exist_ok=True)
            out_file = os.path.join(path, filename)
            if not _is_path_safe(out_file):
                return _json_error(f"路径不允许: {out_file}", 403)
            with open(out_file, 'wb') as f:
                f.write(data)
            return _json_response({"success": True, "path": out_file, "size": len(data)})
        else:
            return _json_error(f"缺少参数 path={path}, fileName={filename}", 400)
    except Exception as e:
        return _json_error(str(e))


async def handle_push_project(request):
    try:
        reader = await request.multipart()
        project_name = None
        data = None

        async for part in reader:
            if part.name == "name":
                project_name = (await part.text()).strip()
            elif part.filename:
                data = await part.read()

        if project_name and data:
            if "/" not in project_name:
                folder = os.path.join(project_manager.project_dir, project_name)
            else:
                folder = "/sdcard/Yyds.Auto"

            os.makedirs(folder, exist_ok=True)

            if "/" in project_name:
                zip_path = os.path.join(project_name, ".local.zip")
            else:
                zip_path = os.path.join(project_manager.project_dir, project_name, ".local.zip")

            with open(zip_path, 'wb') as f:
                f.write(data)

            file_ops.unzip(zip_path, os.path.dirname(zip_path))
            os.remove(zip_path)
            return _json_response({"success": True, "project": project_name})
        else:
            return _json_error(f"缺少参数 name={project_name}", 400)
    except Exception as e:
        return _json_error(str(e))


# ============================================================
# 路由: 自动化引擎代理
# ============================================================

async def handle_api_proxy(request):
    try:
        api = request.match_info.get("api")
        post_text = await request.text()
        params = {}
        if post_text:
            try:
                params = json.loads(post_text)
            except json.JSONDecodeError:
                pass
        ret = auto_engine.http(f"/{api}", params if params else None)
        return web.Response(text=ret or "")
    except Exception as e:
        return web.Response(text=f"执行错误:{traceback.format_exc()}")


# ============================================================
# WebSocket: 日志流
# ============================================================

async def handle_log_poll(request):
    """HTTP 轮询日志端点 - 返回队列中所有待发送日志"""
    text = log_manager.drain()
    return web.Response(text=text, content_type="text/plain; charset=utf-8")


# ============================================================
# WebSocket: 截图流
# ============================================================

async def handle_ws_shot(request):
    ws = web.WebSocketResponse()
    await ws.prepare(request)

    try:
        quality = int(request.match_info.get("quality", 100))
        count = int(request.match_info.get("count", 1))
        interval = int(request.match_info.get("interval", 500))

        last_size = 0
        for i in range(count):
            data = screen_capture.get_bitmap_data(quality)
            if data and len(data) != last_size:
                last_size = len(data)
                await ws.send_bytes(data)
                await asyncio.sleep(interval / 1000.0)
    except Exception:
        pass
    finally:
        if not ws.closed:
            await ws.close()

    return ws


# ============================================================
# 应用初始化
# ============================================================

def create_app() -> web.Application:
    app = web.Application(client_max_size=100 * 1024 * 1024)  # 100MB max upload

    app.router.add_get("/", handle_root)
    app.router.add_get("/ping", handle_ping)

    # 项目管理
    app.router.add_get("/project/list", handle_project_list)
    app.router.add_get("/project/status", handle_project_status)
    app.router.add_get("/project/start", handle_project_start)
    app.router.add_get("/project/stop", handle_project_stop)

    # 引擎控制
    app.router.add_post("/engine/run-code", handle_engine_run_code)
    app.router.add_get("/engine/log-diag", handle_log_diag)
    app.router.add_post("/engine/reboot", handle_engine_reboot)
    app.router.add_post("/engine/shell", handle_engine_shell)
    app.router.add_post("/engine/click", handle_engine_click)
    app.router.add_post("/engine/auto", handle_engine_auto)
    app.router.add_get("/engine/foreground", handle_engine_foreground)

    # 截图与UI
    app.router.add_get("/screen/{quality}", handle_screen)
    app.router.add_get("/screen", handle_screen)
    app.router.add_get("/screenshot", handle_screenshot)
    app.router.add_get("/ui-dump", handle_ui_dump)

    # 文件操作
    app.router.add_get("/file/exists", handle_file_exists)
    app.router.add_get("/file/read-text", handle_file_read_text)
    app.router.add_post("/file/write-text", handle_file_write_text)
    app.router.add_get("/file/list", handle_file_list)
    app.router.add_get("/file/delete", handle_file_delete)
    app.router.add_post("/file/rename", handle_file_rename)
    app.router.add_get("/file/mkdir", handle_file_mkdir)
    app.router.add_get("/file/last-modified", handle_file_last_modified)

    # 文件传输
    app.router.add_get("/pull-file", handle_pull_file)
    app.router.add_post("/post-file", handle_post_file)
    app.router.add_post("/push-project", handle_push_project)

    # 自动化引擎代理
    app.router.add_post("/api/{api}", handle_api_proxy)

    # 日志轮询
    app.router.add_get("/log/poll", handle_log_poll)
    app.router.add_get("/shot/{quality}/{count}/{interval}", handle_ws_shot)

    # 悬浮窗控制台（来自 console.py HTTP 路径）
    app.router.add_post("/console/log", handle_console_log)
    app.router.add_post("/console/agent-log", handle_console_agent_log)
    app.router.add_post("/console/switch-tab", handle_console_switch_tab)
    app.router.add_post("/console/show", handle_console_show)
    app.router.add_post("/console/hide", handle_console_hide)
    app.router.add_post("/console/close", handle_console_close)
    app.router.add_post("/console/clear", handle_console_clear)
    app.router.add_post("/console/set-alpha", handle_console_set_alpha)
    app.router.add_post("/console/set-title", handle_console_set_title)
    app.router.add_post("/console/set-size", handle_console_set_size)
    app.router.add_post("/console/set-position", handle_console_set_position)

    # AI Agent
    app.router.add_get("/agent/config", handle_agent_config_get)
    app.router.add_post("/agent/config", handle_agent_config_set)
    app.router.add_get("/agent/providers", handle_agent_providers)
    app.router.add_get("/agent/models", handle_agent_models)
    app.router.add_post("/agent/run", handle_agent_run)
    app.router.add_get("/agent/stop", handle_agent_stop)
    app.router.add_post("/agent/takeover", handle_agent_takeover)
    app.router.add_post("/agent/resume", handle_agent_resume)
    app.router.add_post("/agent/plan-confirm", handle_agent_plan_confirm)
    app.router.add_post("/agent/plan-reject", handle_agent_plan_reject)
    app.router.add_get("/agent/status", handle_agent_status)
    app.router.add_get("/agent/logs", handle_agent_logs_ws)
    app.router.add_post("/agent/test-connection", handle_agent_test_connection)
    app.router.add_get("/agent/skills", handle_agent_skills)
    app.router.add_get("/agent/tools", handle_agent_tools)
    app.router.add_post("/agent/run-python", handle_agent_run_python)
    app.router.add_get("/agent/history", handle_agent_history)
    app.router.add_get("/agent/history/{run_id}", handle_agent_history_detail)
    app.router.add_delete("/agent/history", handle_agent_history_clear)
    # ④ 快捷指令
    app.router.add_get("/agent/shortcuts", handle_shortcuts_list)
    app.router.add_post("/agent/shortcuts", handle_shortcuts_add)
    app.router.add_delete("/agent/shortcuts/{id}", handle_shortcuts_delete)
    # ② 结果截图
    app.router.add_get("/agent/result-screenshot", handle_result_screenshot)

    return app


# ============================================================
# 路由: 悬浮窗控制台代理
# console.py 发来的 HTTP 命令 → 通过 stdout 标签转发给 Java 层
# ============================================================

_CONSOLE_CMD_PREFIX = "##YYDS_CONSOLE##"

def _console_cmd(cmd: str):
    """通过 stdout 管道将控制台命令发送给 Java 层"""
    print(f"{_CONSOLE_CMD_PREFIX}{cmd}", flush=True)

async def handle_console_log(request):
    try:
        body = await request.json()
        text  = body.get("text", "")
        level = body.get("level", "I")
        _console_cmd(f"log:{level}:{text}")
        return _json_response({"success": True})
    except Exception as e:
        return _json_error(str(e))

async def handle_console_agent_log(request):
    """将 Agent 步骤日志写入悬浮窗 Agent Tab（不影响脚本日志 Tab）"""
    try:
        body = await request.json()
        text  = body.get("text", "")
        level = body.get("level", "I")
        _console_cmd(f"agent_log:{level}:{text}")
        return _json_response({"success": True})
    except Exception as e:
        return _json_error(str(e))

async def handle_console_switch_tab(request):
    """切换悬浮窗显示的 Tab: scripts / log / agent"""
    try:
        body = await request.json()
        tab = body.get("tab", "log")
        _console_cmd(f"switch_tab:{tab}")
        return _json_response({"success": True})
    except Exception as e:
        return _json_error(str(e))

async def handle_console_show(request):
    _console_cmd("show")
    return _json_response({"success": True})

async def handle_console_hide(request):
    _console_cmd("hide")
    return _json_response({"success": True})

async def handle_console_close(request):
    _console_cmd("close")
    return _json_response({"success": True})

async def handle_console_clear(request):
    _console_cmd("clear")
    return _json_response({"success": True})

async def handle_console_set_alpha(request):
    try:
        body = await request.json()
        alpha = float(body.get("alpha", 0.9))
        _console_cmd(f"alpha:{alpha}")
        return _json_response({"success": True})
    except Exception as e:
        return _json_error(str(e))

async def handle_console_set_title(request):
    try:
        body = await request.json()
        title = body.get("title", "")
        _console_cmd(f"title:{title}")
        return _json_response({"success": True})
    except Exception as e:
        return _json_error(str(e))

async def handle_console_set_size(request):
    try:
        body = await request.json()
        w = int(body.get("width", 320))
        h = int(body.get("height", 480))
        _console_cmd(f"size:{w},{h}")
        return _json_response({"success": True})
    except Exception as e:
        return _json_error(str(e))

async def handle_console_set_position(request):
    try:
        body = await request.json()
        x = int(body.get("x", 0))
        y = int(body.get("y", 200))
        _console_cmd(f"pos:{x},{y}")
        return _json_response({"success": True})
    except Exception as e:
        return _json_error(str(e))


# ============================================================
# 路由: AI Agent
# ============================================================

# Agent 日志 WebSocket 订阅者
_agent_ws_clients: list = []


async def handle_agent_config_get(request):
    """获取 Agent 配置"""
    try:
        cfg = get_config()
        data = cfg.to_dict()
        # 脱敏：API Key 只返回前4位 + 掩码
        if data.get("api_key"):
            key = data["api_key"]
            data["api_key_masked"] = key[:4] + "****" + key[-4:] if len(key) > 8 else "****"
            data["api_key"] = key  # 完整 key 也返回（本地通信安全）
        data["is_configured"] = cfg.is_configured
        return _json_response(data)
    except Exception as e:
        return _json_error(str(e))


async def handle_agent_providers(request):
    """获取所有预置 AI 服务商列表（供下拉选择）"""
    try:
        from vlm_client import get_provider_list
        return _json_response({"providers": get_provider_list()})
    except Exception as e:
        return _json_error(str(e))


async def handle_agent_models(request):
    """获取指定服务商的可用模型列表"""
    try:
        provider = request.query.get("provider", "")
        from vlm_client import get_provider_models
        models = get_provider_models(provider)
        return _json_response({"provider": provider, "models": models})
    except Exception as e:
        return _json_error(str(e))


async def handle_agent_config_set(request):
    """设置 Agent 配置"""
    try:
        body = await request.json()
        cfg = AgentConfig.from_dict(body)
        ok = set_config(cfg)
        # 强制清除模块缓存的_config，确保下次get_config()重新从磁盘加载
        import agent_config as _ac_mod
        _ac_mod._config = None
        reset_agent(cfg)
        return _json_response({"success": ok})
    except Exception as e:
        return _json_error(str(e))


async def handle_agent_run(request):
    """启动 Agent 任务"""
    try:
        body = await request.json()
        instruction = body.get("instruction", "").strip()
        if not instruction:
            return _json_error("缺少 instruction 参数", 400)

        # 设备信息查询快速路径（零 VLM 调用）
        try:
            import importlib
            import device_query as _dq
            importlib.reload(_dq)
            _fast_ans = _dq.try_device_query(instruction)
            if _fast_ans is not None:
                return _json_response({
                    "success": True, "instruction": instruction,
                    "fast_answer": _fast_ans,
                })
        except Exception as _dq_err:
            print(f"[server] device_query fast path error: {_dq_err}")

        cfg = get_config()
        agent = get_agent()
        if agent.is_running:
            return _json_error("Agent 正在运行中", 409)

        if not cfg.is_configured:
            return _json_error("未配置 API Key，请先前往设置", 400)

        # 设置日志回调 → 推送给所有 WebSocket 订阅者
        loop = asyncio.get_running_loop()

        def _log_callback(entry):
            msg = json.dumps(entry, ensure_ascii=False)
            for ws in list(_agent_ws_clients):
                try:
                    loop.call_soon_threadsafe(
                        asyncio.ensure_future,
                        _ws_send_safe(ws, msg),
                    )
                except Exception:
                    pass

        agent.set_log_callback(_log_callback)

        # 在后台协程中运行 Agent
        async def _run_agent():
            try:
                result = await agent.run(instruction)
                # 推送最终结果
                final = {
                    "type": "result",
                    "success": result.success,
                    "message": result.message,
                    "answer": result.answer,
                    "total_steps": result.total_steps,
                    "elapsed_ms": result.elapsed_ms,
                }
                for ws in list(_agent_ws_clients):
                    try:
                        await _ws_send_safe(ws, json.dumps(final, ensure_ascii=False))
                    except Exception:
                        pass
            except Exception as e:
                print(f"[Agent] 运行异常: {e}")
                traceback.print_exc()

        asyncio.ensure_future(_run_agent())
        return _json_response({"success": True, "instruction": instruction})

    except Exception as e:
        return _json_error(str(e))


async def handle_agent_stop(request):
    """停止 Agent"""
    try:
        agent = get_agent()
        agent.stop()
        return _json_response({"success": True})
    except Exception as e:
        return _json_error(str(e))


async def handle_agent_takeover(request):
    """请求人工接管：暂停 Agent 自动执行，等待用户操作"""
    try:
        agent = get_agent()
        if not agent.is_running:
            return _json_error("Agent 未在运行中", 400)
        agent.request_takeover()
        return _json_response({"success": True, "message": "Agent 已暂停，等待人工接管"})
    except Exception as e:
        return _json_error(str(e))


async def handle_agent_resume(request):
    """恢复 Agent：人工操作完成后恢复自动执行"""
    try:
        agent = get_agent()
        agent.resume_from_takeover()
        return _json_response({"success": True, "message": "Agent 已恢复自动执行"})
    except Exception as e:
        return _json_error(str(e))


async def handle_agent_status(request):
    """获取 Agent 状态
    P0-2: 支持 ?since=N 增量日志传输 — 只返回第 N 条之后的新日志，避免全量重传
    """
    try:
        agent = get_agent()
        s = agent.status
        full_logs = agent._full_logs
        total_logs = len(full_logs)

        # 增量模式：?since=N 只返回 full_logs[N:]
        since_str = request.query.get("since", "")
        if since_str.isdigit():
            since = int(since_str)
            new_logs = full_logs[since:]
        else:
            new_logs = full_logs  # 首次请求返回全量

        resp = {
            "running": s.running,
            "instruction": s.instruction,
            "current_step": s.current_step,
            "max_steps": s.max_steps,
            "message": s.message,
            "phase": s.phase,
            "plan": s.plan,
            "takeover": agent._takeover_flag,
            "logs": new_logs,
            "log_total": total_logs,  # 客户端下次请求用此值作 since
            "token_usage": agent._token_usage.to_dict(),
        }
        # 最近一次运行的答案（设备查询/任务完成）
        if full_logs:
            last = full_logs[-1]
            if last.get("type") == "success":
                resp["answer"] = last.get("answer") or last.get("detail", "")
            # 最新截图路径
            for log in reversed(full_logs):
                if log.get("screenshot_path"):
                    resp["result_screenshot_path"] = log["screenshot_path"]
                    break
        return _json_response(resp)
    except Exception as e:
        return _json_error(str(e))


async def handle_agent_test_connection(request):
    """测试 VLM 连接"""
    try:
        body = await request.json()
        cfg = AgentConfig.from_dict(body)
        if not cfg.api_key:
            return _json_error("缺少 API Key", 400)

        from vlm_client import VLMClient, PRESETS
        if cfg.provider == "custom":
            vlm = VLMClient(api_key=cfg.api_key, base_url=cfg.base_url, model=cfg.model)
        else:
            vlm = VLMClient.from_preset(cfg.provider, cfg.api_key)

        resp = await vlm.test_connection()
        await vlm.close()

        if resp.error:
            return _json_error(f"连接失败: {resp.error}")
        return _json_response({"message": "连接成功", "model": resp.model or ""})
    except Exception as e:
        return _json_error(str(e))


async def handle_agent_skills(request):
    """获取所有 Skills 列表"""
    try:
        from agent_skills import get_skill_registry
        registry = get_skill_registry()
        return _json_response({"skills": registry.to_json_list()})
    except Exception as e:
        return _json_error(str(e))


async def handle_agent_tools(request):
    """获取所有 Tools 列表"""
    try:
        from agent_tools import get_tool_registry
        registry = get_tool_registry()
        tools = []
        for name in registry.list_names():
            tool = registry.get(name)
            tools.append({
                "name": tool.name,
                "description": tool.description,
                "params": [{"name": p.name, "type": p.type, "description": p.description,
                            "required": p.required} for p in tool.params],
            })
        return _json_response({"tools": tools})
    except Exception as e:
        return _json_error(str(e))


async def handle_agent_run_python(request):
    """直接执行 Python 代码片段（调试/MCP 用）"""
    try:
        body = await request.json()
        code = body.get("code", "")
        if not code:
            return _json_error("缺少 code 参数", 400)
        timeout = body.get("timeout", 30)

        from agent_executor import execute_python
        result = execute_python(code, timeout=timeout)
        return _json_response({
            "success": result.success,
            "data": result.data,
            "error": result.error,
        })
    except Exception as e:
        return _json_error(str(e))


async def handle_agent_history(request):
    """获取 Agent 历史运行列表"""
    try:
        limit = int(request.query.get("limit", "20"))
        offset = int(request.query.get("offset", "0"))
        data = get_agent_run_list(limit=limit, offset=offset)
        return _json_response(data)
    except Exception as e:
        return _json_error(str(e))


async def handle_agent_history_detail(request):
    """获取某次运行的完整日志"""
    try:
        run_id = request.match_info["run_id"]
        data = get_agent_run_detail(run_id)
        if data is None:
            return _json_error("日志不存在", 404)
        return _json_response(data)
    except Exception as e:
        return _json_error(str(e))


async def handle_agent_history_clear(request):
    """清空所有历史日志"""
    try:
        count = clear_agent_logs()
        return _json_response({"deleted": count})
    except Exception as e:
        return _json_error(str(e))


async def handle_agent_logs_ws(request):
    """Agent 日志 WebSocket 流"""
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    _agent_ws_clients.append(ws)

    try:
        # 发送当前状态
        agent = get_agent()
        s = agent.status
        await ws.send_str(json.dumps({
            "type": "status",
            "running": s.running,
            "instruction": s.instruction,
            "current_step": s.current_step,
            "max_steps": s.max_steps,
            "phase": s.phase,
            "plan": s.plan,
            "logs": agent._full_logs,
            "token_usage": agent._token_usage.to_dict(),
        }, ensure_ascii=False))

        # 保持连接，等待客户端关闭
        async for msg in ws:
            if msg.type == WSMsgType.CLOSE:
                break
    except Exception:
        pass
    finally:
        if ws in _agent_ws_clients:
            _agent_ws_clients.remove(ws)
        if not ws.closed:
            await ws.close()
    return ws


async def handle_agent_plan_confirm(request):
    """① 用户确认执行计划"""
    try:
        agent = get_agent()
        agent.confirm_plan()
        return _json_response({"success": True})
    except Exception as e:
        return _json_error(str(e))


async def handle_agent_plan_reject(request):
    """① 用户拒绝执行计划"""
    try:
        agent = get_agent()
        agent.reject_plan()
        return _json_response({"success": True})
    except Exception as e:
        return _json_error(str(e))


async def handle_result_screenshot(request):
    """② 获取最近一次任务完成截图（base64）"""
    try:
        agent = get_agent()
        path = ""
        for log in reversed(agent._full_logs):
            if log.get("screenshot_path"):
                path = log["screenshot_path"]
                break
        if not path or not os.path.exists(path):
            return _json_error("暂无结果截图", 404)
        with open(path, "rb") as f:
            data = f.read()
        import base64 as _b64
        return _json_response({
            "path": path,
            "data": _b64.b64encode(data).decode("utf-8"),
            "mime": "image/png",
        })
    except Exception as e:
        return _json_error(str(e))


# ============================================================
# ④ 快捷指令（Shortcuts）
# ============================================================

_SHORTCUTS_PATH = "/sdcard/Yyds.Auto/shortcuts.json"


def _load_shortcuts() -> list:
    try:
        if os.path.exists(_SHORTCUTS_PATH):
            with open(_SHORTCUTS_PATH, "r", encoding="utf-8") as f:
                return json.load(f)
    except Exception:
        pass
    return []


def _save_shortcuts(items: list):
    try:
        os.makedirs(os.path.dirname(_SHORTCUTS_PATH), exist_ok=True)
        with open(_SHORTCUTS_PATH, "w", encoding="utf-8") as f:
            json.dump(items, f, ensure_ascii=False, indent=2)
    except Exception as e:
        print(f"[Shortcuts] 保存失败: {e}")


async def handle_shortcuts_list(request):
    """④ 获取所有快捷指令"""
    try:
        return _json_response({"shortcuts": _load_shortcuts()})
    except Exception as e:
        return _json_error(str(e))


async def handle_shortcuts_add(request):
    """④ 添加快捷指令"""
    try:
        body = await request.json()
        title = body.get("title", "").strip()
        instruction = body.get("instruction", "").strip()
        if not instruction:
            return _json_error("instruction 不能为空", 400)
        items = _load_shortcuts()
        new_id = str(int(time.time() * 1000))
        items.append({
            "id": new_id,
            "title": title or instruction[:20],
            "instruction": instruction,
            "created_at": int(time.time()),
        })
        _save_shortcuts(items)
        return _json_response({"success": True, "id": new_id})
    except Exception as e:
        return _json_error(str(e))


async def handle_shortcuts_delete(request):
    """④ 删除快捷指令"""
    try:
        sc_id = request.match_info["id"]
        items = _load_shortcuts()
        items = [x for x in items if x.get("id") != sc_id]
        _save_shortcuts(items)
        return _json_response({"success": True})
    except Exception as e:
        return _json_error(str(e))


async def _ws_send_safe(ws, text: str):
    """安全发送 WebSocket 消息"""
    try:
        if not ws.closed:
            await ws.send_str(text)
    except Exception:
        if ws in _agent_ws_clients:
            _agent_ws_clients.remove(ws)


def start_server(port=ENGINE_PORT):
    """启动 HTTP 服务器（阻塞）"""
    app = create_app()
    print(f"[CPython Server] 启动 HTTP 服务器 0.0.0.0:{port}")
    log_manager.install_stream_hooks()
    web.run_app(app, host="0.0.0.0", port=port, print=None)
