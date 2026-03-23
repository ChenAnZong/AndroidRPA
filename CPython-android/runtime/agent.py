"""
Mobile Agent — 单循环高效 Android 自动化 Agent

架构: screenshot + observation → VLM 单次调用 → action 解析 → 执行 → 等待

特性:
1. 每步仅 1 次 VLM 调用
2. AutoGLM-Phone 兼容的 pseudo-code 动作格式
3. 智能观测层（按场景选择 UI 控件树 / OCR）
4. Image stripping 节省历史 token
5. 动态等待策略
6. Skills 快速路径
"""

from __future__ import annotations

import asyncio
import base64
import json
import os
import re
import subprocess
import time
import urllib.parse
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

from action_parser import ParsedAction, parse_response, parse_all_actions
from observation import Observation, ObservationProvider, coords_to_pixels
from prompts import build_system_prompt, build_user_prompt, build_history_summary, build_plan_prompt
from vlm_client import VLMClient, VLMResponse, PRESETS
from agent_config import AgentConfig, get_config
from agent_skills import match_skill
import device_query as _device_query_mod
from auto_engine_proxy import auto_engine
from console import console


# ============================================================
# 常量
# ============================================================

VLM_COORD_RANGE = 1000

# Agent 悬浮窗协议前缀（通过 stdout 传递到 Android 端）
AGENT_OVERLAY_PREFIX = "##YYDS_AGENT_OVERLAY##"

# 动作执行后的等待时间（秒）
ACTION_WAIT = {
    "Tap": 1.0,
    "Type": 0.5,
    "Swipe": 1.5,
    "Long Press": 1.0,
    "Double Tap": 0.8,
    "Launch": 3.0,
    "Back": 1.0,
    "Home": 1.5,
    "Wait": 0.0,      # Wait 动作内部自己 sleep
    "Shell": 1.0,
    "Take_over": 0.0,
    "Clear": 0.3,
    "Notification": 1.0,
    "Recent": 1.5,
    "Open_url": 3.0,
}

# 连续相同动作的最大允许次数（超过则注入警告）
MAX_REPEAT_SAME_ACTION = 2
# 硬性覆盖阈值：超过此次数强制替换动作（VLM 忽略警告时生效）
HARD_OVERRIDE_THRESHOLD = 3
# 豁免卡死检测的动作（合理的重复行为，不代表真正卡死）
STUCK_EXEMPT_ACTIONS = {"Wait", "Take_over", "Shell"}

# P2-2: 模块级常量，避免 _emit_log 每次调用重建 set
_LOG_SKIP_TITLES = frozenset({"获取屏幕观测...", "VLM 推理中...", "等待人工操作"})


# ============================================================
# 数据结构
# ============================================================

@dataclass
class AgentStatus:
    """Agent 运行状态"""
    running: bool = False
    instruction: str = ""
    current_step: int = 0
    max_steps: int = 25
    message: str = ""
    phase: str = "idle"   # idle / planning / confirm_pending / running / done
    plan: str = ""        # 自然语言执行计划（供用户确认）


@dataclass
class AgentResult:
    """Agent 运行结果"""
    success: bool = False
    message: str = ""
    answer: str = ""
    total_steps: int = 0
    elapsed_ms: int = 0
    screenshot_path: str = ""  # ② 任务完成截图路径


@dataclass
class TokenUsage:
    """Token 使用统计"""
    total_prompt: int = 0
    total_completion: int = 0
    total_calls: int = 0

    def add(self, prompt: int, completion: int):
        self.total_prompt += prompt
        self.total_completion += completion
        self.total_calls += 1

    @property
    def total(self) -> int:
        return self.total_prompt + self.total_completion

    def to_dict(self) -> dict:
        return {
            "prompt_tokens": self.total_prompt,
            "completion_tokens": self.total_completion,
            "total_tokens": self.total,
            "call_count": self.total_calls,  # P2-1: 与前端 AgentFragment 字段名对齐
        }


@dataclass
class StepHistory:
    """单步历史记录"""
    step: int = 0
    action_desc: str = ""
    success: bool = False
    thinking: str = ""
    action_name: str = ""


# ============================================================
# MobileAgent
# ============================================================

class MobileAgent:
    """单循环、高执行率、低 token 的移动端自动化 Agent"""

    def __init__(self, config: Optional[AgentConfig] = None):
        self._config = config or get_config()
        self._status = AgentStatus(max_steps=self._config.max_steps)
        self._token_usage = TokenUsage()
        self._vlm: Optional[VLMClient] = None
        self._obs_provider = ObservationProvider(auto_engine)
        self._log_callback: Optional[Callable] = None
        self._stop_flag = False
        self._takeover_flag = False   # 用户请求人工接管，暂停自动执行
        self._takeover_resume = False  # 接管后用户发出恢复信号
        self._plan_event = asyncio.Event()  # P0-3: 计划确认通知事件
        self._plan_confirmed = False          # 用户已确认计划，继续执行
        self._plan_rejected = False           # 用户拒绝计划，取消任务

        # 对话上下文（多轮 messages）
        self._context: List[Dict[str, Any]] = []
        # 历史步骤记录
        self._history: List[StepHistory] = []
        # 完整日志
        self._full_logs: List[Dict] = []

    @property
    def status(self) -> AgentStatus:
        return self._status

    @property
    def is_running(self) -> bool:
        return self._status.running

    def set_log_callback(self, callback: Callable):
        self._log_callback = callback

    def stop(self):
        self._stop_flag = True
        self._overlay("hide")

    def request_takeover(self):
        """用户请求人工接管：暂停自动执行，等待 resume 或 stop"""
        self._takeover_flag = True
        self._takeover_resume = False

    def resume_from_takeover(self):
        """用户完成人工操作后恢复 Agent 自动执行"""
        self._takeover_flag = False
        self._takeover_resume = True

    def confirm_plan(self):
        """用户确认执行计划，Agent 继续"""
        self._plan_confirmed = True
        self._plan_rejected = False
        self._plan_event.set()  # P0-3: 唤醒等待协程

    def reject_plan(self):
        """用户拒绝执行计划，Agent 取消"""
        self._plan_rejected = True
        self._plan_confirmed = False
        self._plan_event.set()  # P0-3: 唤醒等待协程

    def _overlay(self, cmd: str):
        """发送悬浮窗控制指令（通过 stdout 传递到 Android 端）"""
        if self._config.show_floating_window:
            print(f"{AGENT_OVERLAY_PREFIX}{cmd}", flush=True)

    # ----------------------------------------------------------
    # VLM 客户端初始化
    # ----------------------------------------------------------

    def _init_vlm(self):
        """根据配置初始化 VLM 客户端"""
        cfg = self._config
        provider = cfg.provider or "autoglm"

        if provider == "custom":
            self._vlm = VLMClient(
                api_key=cfg.api_key,
                base_url=cfg.base_url,
                model=cfg.model,
                temperature=cfg.temperature,
                max_tokens=cfg.max_tokens,
            )
        else:
            kwargs = {}
            if cfg.model:
                kwargs["model"] = cfg.model
            kwargs["temperature"] = cfg.temperature
            kwargs["max_tokens"] = cfg.max_tokens
            self._vlm = VLMClient.from_preset(provider, cfg.api_key, **kwargs)

    @property
    def _is_autoglm(self) -> bool:
        """是否使用 AutoGLM-Phone 模型"""
        provider = self._config.provider or "autoglm"
        model = self._config.model or ""
        return provider == "autoglm" or "autoglm" in model.lower()

    # ----------------------------------------------------------
    # 主循环
    # ----------------------------------------------------------

    async def run(self, instruction: str) -> AgentResult:
        """运行 Agent 完成任务"""
        t0 = time.time()
        # P0-1: 每次运行重载最新配置，防止配置变更后使用旧快照
        self._config = get_config()
        self._status = AgentStatus(
            running=True,
            instruction=instruction,
            max_steps=self._config.max_steps,
        )
        self._stop_flag = False
        self._token_usage = TokenUsage()
        self._context = []
        self._history = []
        self._full_logs = []
        # P0-3: 重置计划确认状态
        self._plan_event = asyncio.Event()
        self._plan_confirmed = False
        self._plan_rejected = False

        # 设备信息查询快速路径（零 token）
        import importlib; importlib.reload(_device_query_mod)
        device_answer = _device_query_mod.try_device_query(instruction)
        if device_answer is not None:
            elapsed = int((time.time() - t0) * 1000)
            result = AgentResult(
                success=True,
                message=device_answer,
                answer=device_answer,
                total_steps=0,
                elapsed_ms=elapsed,
            )
            self._status.running = False
            self._full_logs.append({
                "step": 0,
                "timestamp": int(time.time() * 1000),
                "type": "success",
                "title": "设备查询",
                "detail": device_answer[:200],
                "answer": device_answer,
                "token_total": self._token_usage.to_dict(),
            })
            if self._log_callback:
                try:
                    self._log_callback(self._full_logs[-1])
                except Exception:
                    pass
            self._persist_run_log(instruction, result)
            return result

        # 初始化 VLM
        try:
            self._init_vlm()
        except Exception as e:
            result = AgentResult(
                success=False,
                message=f"VLM 初始化失败: {e}",
                elapsed_ms=int((time.time() - t0) * 1000),
            )
            self._status.running = False
            return result

        # 任务计划生成 + 用户确认
        if self._config.enable_plan_confirm:
            plan_result = await self._generate_and_confirm_plan(instruction)
            if plan_result is not None:
                # 用户拒绝或超时取消
                self._status.running = False
                self._status.phase = "done"
                self._persist_run_log(instruction, plan_result)
                return plan_result

        self._emit_log("info", "Agent 启动",
                        f"指令: {instruction}\n模型: {self._vlm.model}")
        self._overlay(f"show:\U0001f680 Agent 启动\n{instruction[:80]}")
        # 初始化悬浮日志控制台
        console.show()
        console.set_title(f"\U0001f916 Agent 执行中")
        console.divider(label="Agent 启动")
        console.log(f"\U0001f4cb 任务: {instruction[:100]}")
        console.log(f"\U0001f916 模型: {self._vlm.model}")

        # Skills 快速路径
        skill_context = ""
        skill_match_result = match_skill(instruction)
        if skill_match_result:
            self._emit_log("info", "Skill 匹配",
                            f"匹配到: {skill_match_result['skill'].name}")
            fast_result = self._try_skill_fast_path(skill_match_result)
            if fast_result is not None:
                # Skill 直接完成了任务（DeepLink / Python 片段）
                elapsed = int((time.time() - t0) * 1000)
                result = AgentResult(
                    success=True,
                    message=fast_result,
                    total_steps=0,
                    elapsed_ms=elapsed,
                )
                self._emit_log("success", "Skill 快速完成", fast_result)
                self._status.running = False
                self._persist_run_log(instruction, result)
                return result
            # GUI Skill：提取上下文指引注入首步 prompt
            skill_context = skill_match_result.get("context", "")

        # 构建 system message
        system_prompt = build_system_prompt(is_autoglm=self._is_autoglm)
        self._context.append({"role": "system", "content": system_prompt})

        # 主循环
        result = await self._main_loop(instruction, skill_context)

        # 清理
        elapsed = int((time.time() - t0) * 1000)
        result.elapsed_ms = elapsed
        result.total_steps = self._status.current_step
        self._status.running = False
        self._status.phase = "done"

        # ② 任务完成时自动截图留档
        if result.success and self._config.save_result_screenshot:
            try:
                from screen_capture import screen_capture as _sc
                screenshot_dir = _AGENT_LOG_DIR
                os.makedirs(screenshot_dir, exist_ok=True)
                ts = int(time.time() * 1000)
                shot_path = os.path.join(screenshot_dir, f"{ts}_result.png")
                if _sc.write_to(shot_path):
                    result.screenshot_path = shot_path
            except Exception as _e:
                print(f"[Agent] 结果截图失败: {_e}")

        self._emit_log(
            "success" if result.success else "error",
            "✅ 任务完成" if result.success else "❌ 任务结束",
            f"{result.message}\n步数: {result.total_steps}, 耗时: {elapsed}ms, "
            f"Token: {self._token_usage.total}",
            screenshot_path=getattr(result, 'screenshot_path', ''),
        )
        # 悬浮日志控制台最终结果
        console.divider(label="任务完成" if result.success else "任务结束")
        if result.success:
            console.success(result.message[:120])
        else:
            console.error(result.message[:120])
        console.log(f"\U0001f4ca 步数: {result.total_steps} | 耗时: {elapsed//1000}s | Token: {self._token_usage.total}")
        self._persist_run_log(instruction, result)
        return result

    async def _main_loop(self, instruction: str, skill_context: str) -> AgentResult:
        """核心执行循环"""
        consecutive_failures = 0
        _last_action_name = ""
        _last_action_desc = ""  # 完整动作描述（含坐标），用于精确卡死检测
        _same_action_count = 0
        _override_count = 0  # 累计覆盖次数，用于循环不同恢复策略

        while self._status.current_step < self._status.max_steps:
            if self._stop_flag:
                return AgentResult(success=False, message="用户手动停止")

            # 人工接管：暂停循环，等待用户 resume 或 stop
            if self._takeover_flag:
                self._emit_log("warning", "人工接管中",
                               "Agent 已暂停，等待用户操作完成后点击「恢复」继续...")
                self._overlay("show:⏸ 人工接管中\n点击「恢复 Agent」继续自动执行")
                console.warn("⏸ Agent 已暂停 — 等待人工接管完成，请在控制台点击「恢复」")
                while self._takeover_flag and not self._stop_flag:
                    await asyncio.sleep(1)
                if self._stop_flag:
                    return AgentResult(success=False, message="用户手动停止")
                # 注入上下文：告知 VLM 用户已操作，屏幕已变化
                msg = "用户已完成人工接管操作，屏幕状态可能已发生变化，请根据最新屏幕继续完成任务。"
                self._context.append({
                    "role": "user",
                    "content": [{"type": "text", "text": f"[系统提示] {msg}"}],
                })
                self._emit_log("info", "恢复自动执行", msg)
                console.log("▶ 人工接管完成，Agent 恢复自动执行")

            self._status.current_step += 1
            step = self._status.current_step

            self._emit_log("info", f"步骤 {step}", "获取屏幕观测...")
            self._overlay("hide")
            await asyncio.sleep(0.15)  # 等待悬浮窗隐藏，避免 screencap 拍到

            # 1. 获取观测
            try:
                obs = self._obs_provider.capture(
                    use_ui=self._config.use_ui_dump,
                    use_ocr_fallback=True,
                )
            except Exception as e:
                self._emit_log("error", f"步骤 {step}", f"观测失败: {e}")
                consecutive_failures += 1
                if consecutive_failures >= 3:
                    return AgentResult(success=False, message=f"连续观测失败: {e}")
                await asyncio.sleep(2)
                continue

            if not obs.screenshot_bytes:
                self._emit_log("warning", f"步骤 {step}", "截图获取失败，重试...")
                consecutive_failures += 1
                if consecutive_failures >= 3:
                    return AgentResult(success=False, message="无法获取截图")
                await asyncio.sleep(2)
                continue

            # 屏幕尺寸兜底（常见手机分辨率 1080x2400）
            if not obs.screen_width or not obs.screen_height:
                obs.screen_width = obs.screen_width or 1080
                obs.screen_height = obs.screen_height or 2400
                self._emit_log("warning", f"步骤 {step}",
                                f"屏幕尺寸回退: {obs.screen_width}x{obs.screen_height}")

            # 2. 构建 user prompt
            # 检测卡死状态，注入警告（Wait/Take_over 豁免）
            stuck_warning = ""
            if (_same_action_count >= MAX_REPEAT_SAME_ACTION
                    and _last_action_name not in STUCK_EXEMPT_ACTIONS):
                stuck_warning = (
                    f"\n⚠️ 警告: 你已经连续执行了 {_same_action_count} 次 '{_last_action_name}'，"
                    f"说明当前策略无效。你必须立即改变操作方式！"
                    f"\n建议: 尝试 Back 返回、Open_url 直接导航、或更换其他操作。禁止再次执行 {_last_action_name}！"
                )
                self._emit_log("warning", "卡死检测",
                                f"连续 {_same_action_count} 次 {_last_action_name}，强制换策略")

            history_text = build_history_summary(
                [{"step": h.step, "action_desc": h.action_desc,
                  "success": h.success, "thinking": h.thinking}
                 for h in self._history],
                max_recent=5,
            )
            if stuck_warning:
                history_text = (history_text + stuck_warning) if history_text else stuck_warning
            user_text = build_user_prompt(
                instruction=instruction,
                step=step,
                max_steps=self._status.max_steps,
                foreground_app=obs.foreground_app,
                foreground_activity=obs.foreground_activity,
                ui_elements=obs.ui_elements,
                ocr_texts=obs.ocr_texts,
                history_summary=history_text,
                skill_context=skill_context if step == 1 else "",
            )

            # 3. 构建消息（截图 + 文本）
            img_b64 = base64.b64encode(obs.screenshot_bytes).decode("utf-8")
            mime = "image/png" if obs.screenshot_bytes[:4] == b'\x89PNG' else "image/jpeg"
            user_message = {
                "role": "user",
                "content": [
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:{mime};base64,{img_b64}"},
                    },
                    {"type": "text", "text": user_text},
                ],
            }
            self._context.append(user_message)

            # 4. 调用 VLM
            self._emit_log("info", f"步骤 {step}", "VLM 推理中...")
            self._overlay(f"show:\U0001f4ad 步骤 {step}/{self._status.max_steps} · AI 思考中...")
            try:
                vlm_resp = await self._vlm.predict_with_context(self._context)
            except Exception as e:
                self._emit_log("error", f"步骤 {step}", f"VLM 调用失败: {e}")
                # 移除当前 user message 避免上下文损坏
                self._context.pop()
                consecutive_failures += 1
                if consecutive_failures >= 3:
                    return AgentResult(success=False, message=f"VLM 连续失败: {e}")
                await asyncio.sleep(3)
                continue

            if vlm_resp.error:
                self._emit_log("error", f"步骤 {step}", f"VLM 错误: {vlm_resp.error}")
                self._context.pop()
                consecutive_failures += 1
                if consecutive_failures >= 3:
                    return AgentResult(success=False, message=f"VLM 错误: {vlm_resp.error}")
                await asyncio.sleep(3)
                continue

            # 记录 token
            self._token_usage.add(vlm_resp.usage_prompt, vlm_resp.usage_completion)
            self._emit_log(
                "info", f"步骤 {step} VLM 响应",
                f"模型: {vlm_resp.model}, "
                f"tokens: {vlm_resp.usage_prompt}+{vlm_resp.usage_completion}, "
                f"延迟: {vlm_resp.latency_ms}ms",
                prompt_tokens=vlm_resp.usage_prompt,
                completion_tokens=vlm_resp.usage_completion,
                latency_ms=vlm_resp.latency_ms,
            )

            # 5. Image stripping — 移除当前 user message 中的图片
            self._context[-1] = _strip_images(self._context[-1])

            # 添加 assistant 回复到上下文
            self._context.append({
                "role": "assistant",
                "content": vlm_resp.content,
            })

            # 6. 解析动作（支持多动作响应）
            all_parsed = parse_all_actions(vlm_resp.content)
            parsed = all_parsed[0]
            extra_actions = all_parsed[1:]  # 多动作响应中的后续动作

            # ③ 从 thinking 提取人话化消息 — 取第一句有意义的自然语言
            human_msg = _extract_human_msg(parsed.thinking, parsed.action_name)

            self._emit_log("info", f"步骤 {step} 解析",
                            f"原始: {vlm_resp.content[:200]}\n"
                            f"思考: {parsed.thinking[:80]}...\n"
                            f"动作: {parsed.describe()}"
                            + (f" + {len(extra_actions)} 个后续动作" if extra_actions else ""),
                            human_msg=human_msg)
            # 显示思考内容到悬浮窗
            thinking_display = parsed.thinking[:120].replace("\n", " ") if parsed.thinking else ""
            self._overlay(f"think:\U0001f4a1 {thinking_display}\n\u25b6 {parsed.describe()[:60]}")

            # 7. 检查是否完成
            if parsed.is_finish:
                # 解析错误导致的 finish
                if parsed.parse_error:
                    thinking_content = parsed.thinking or parsed.params.get("message", "")
                    # VLM 给出了实质性答案（>50字）但格式不对 → 直接作为 finish
                    if len(thinking_content) > 10:
                        self._emit_log("warning", f"步骤 {step} 解析失败",
                                        f"VLM 输出格式不规范但内容有效，直接 finish")
                        history_item = StepHistory(
                            step=step, action_desc="finish(格式回退)",
                            success=True, thinking=thinking_content,
                            action_name="finish",
                        )
                        self._history.append(history_item)
                        self._overlay(f"done:\u2705 任务完成\n{thinking_content[:80]}")
                        return AgentResult(
                            success=True,
                            message=thinking_content,
                            answer=thinking_content,
                        )
                    # 内容太短，说明真的解析失败 → 注入 Back 回退
                    self._emit_log("warning", f"步骤 {step} 解析失败",
                                    f"VLM 输出无法解析为动作，注入回退操作")
                    parsed = ParsedAction(
                        action_type="do", action_name="Back", params={},
                        thinking="[系统回退] VLM 输出解析失败，尝试返回",
                    )
                else:
                    history_item = StepHistory(
                        step=step, action_desc=parsed.describe(),
                        success=True, thinking=parsed.thinking,
                        action_name="finish",
                    )
                    self._history.append(history_item)
                    self._overlay(f"done:\u2705 任务完成\n{(parsed.message or '任务完成')[:80]}")
                    return AgentResult(
                        success=True,
                        message=parsed.message or "任务完成",
                        answer=parsed.message,
                    )

            # 7.5 硬性卡死覆盖：VLM 忽略警告时强制替换动作（Wait/Take_over 豁免）
            if (parsed.action_name == _last_action_name
                    and _same_action_count >= HARD_OVERRIDE_THRESHOLD
                    and parsed.action_name not in STUCK_EXEMPT_ACTIONS):
                override_cycle = _override_count
                _override_count += 1
                if parsed.action_name in ("Tap", "Long Press", "Double Tap"):
                    # 位置类动作卡死 → 强制点击屏幕不同区域（网格采样）
                    _grid_positions = [
                        [250, 400], [750, 400],
                        [250, 650], [750, 650],
                        [500, 500],
                    ]
                    pos = _grid_positions[override_cycle % len(_grid_positions)]
                    parsed = ParsedAction(
                        action_type="do", action_name="Tap",
                        params={"element": pos},
                        thinking=f"[系统强制覆盖] 强制点击区域 {pos}",
                    )
                else:
                    # 非位置类卡死 → 循环: Back → Home → Back → Home ...
                    if override_cycle % 2 == 0:
                        parsed = ParsedAction(
                            action_type="do", action_name="Back", params={},
                            thinking="[系统强制覆盖] 尝试返回",
                        )
                    else:
                        parsed = ParsedAction(
                            action_type="do", action_name="Home", params={},
                            thinking="[系统强制覆盖] 回到桌面重新开始",
                        )
                self._emit_log("warning", "强制覆盖动作",
                                f"连续 {_same_action_count} 次相同动作，"
                                f"强制执行: {parsed.describe()}")
                # 重置计数
                _last_action_name = parsed.action_name
                _last_action_desc = parsed.describe()
                _same_action_count = 0

            # 8. 执行动作 — 隐藏悬浮窗避免遮挡
            self._overlay("hide")
            await asyncio.sleep(0.1)  # 等待悬浮窗隐藏
            action_success = await self._execute_action(
                parsed, obs.screen_width, obs.screen_height, step
            )

            # 执行多动作响应中的后续动作
            for extra in extra_actions:
                extra_wait = ACTION_WAIT.get(extra.action_name, 0.5)
                if extra_wait > 0:
                    await asyncio.sleep(extra_wait)
                extra_ok = await self._execute_action(
                    extra, obs.screen_width, obs.screen_height, step
                )
                self._emit_log("info", f"步骤 {step} 附加动作",
                                f"{extra.describe()} → {'成功' if extra_ok else '失败'}")
                history_item_extra = StepHistory(
                    step=step, action_desc=f"[附加] {extra.describe()}",
                    success=extra_ok, thinking="",
                    action_name=extra.action_name,
                )
                self._history.append(history_item_extra)

            # 记录历史
            history_item = StepHistory(
                step=step, action_desc=parsed.describe(),
                success=action_success, thinking=parsed.thinking,
                action_name=parsed.action_name,
            )
            self._history.append(history_item)

            if action_success:
                consecutive_failures = 0
            else:
                consecutive_failures += 1

            # 卡死检测：跟踪连续相同动作（Wait/Take_over 豁免，不计入连续计数）
            current_desc = parsed.describe()
            if parsed.action_name not in STUCK_EXEMPT_ACTIONS:
                if current_desc == _last_action_desc:
                    _same_action_count += 1
                else:
                    _last_action_name = parsed.action_name
                    _last_action_desc = current_desc
                    _same_action_count = 1

            # 9. 动态等待
            wait_time = ACTION_WAIT.get(parsed.action_name, 1.0)
            if not action_success:
                wait_time = min(wait_time * 1.5, 5.0)
            if wait_time > 0:
                await asyncio.sleep(wait_time)

            # 10. 上下文窗口管理 — 防止 context 无限增长
            self._trim_context()

        # 超出最大步数
        self._overlay(f"done:\u26a0\ufe0f 达到最大步数限制 ({self._status.max_steps})")
        return AgentResult(
            success=False,
            message=f"达到最大步数限制 ({self._status.max_steps})",
        )

    # ----------------------------------------------------------
    # 动作执行
    # ----------------------------------------------------------

    async def _execute_action(
        self,
        parsed: ParsedAction,
        screen_w: int,
        screen_h: int,
        step: int,
    ) -> bool:
        """执行解析后的动作"""
        name = parsed.action_name
        params = parsed.params

        try:
            if name == "Tap":
                return self._do_tap(params, screen_w, screen_h)
            elif name == "Type":
                return self._do_type(params)
            elif name == "Swipe":
                return self._do_swipe(params, screen_w, screen_h)
            elif name == "Long Press":
                return await self._do_long_press(params, screen_w, screen_h)
            elif name == "Double Tap":
                return await self._do_double_tap(params, screen_w, screen_h)
            elif name == "Launch":
                return self._do_launch(params)
            elif name == "Back":
                return auto_engine.key_code("4")
            elif name == "Home":
                return auto_engine.key_code("3")
            elif name == "Wait":
                raw_dur = str(params.get("duration", "2")).strip()
                # 剥离中文/英文单位: "3秒" "3s" "3S" "3m" "2分" 等
                raw_dur = re.sub(r'[\u79d2\u5206sSmM\s]+$', '', raw_dur)
                try:
                    duration = float(raw_dur) if raw_dur else 2.0
                except ValueError:
                    duration = 2.0
                await asyncio.sleep(min(duration, 30))
                return True
            elif name == "Take_over":
                msg = params.get("message", "需要人工介入")
                self._emit_log("warning", "需要人工介入", msg)
                # 等待用户操作（30秒）
                for i in range(6):
                    await asyncio.sleep(5)
                    self._emit_log("info", "等待人工操作",
                                    f"还有 {30 - (i+1)*5} 秒继续自动执行...")
                # 注入上下文，告知 VLM 用户已经介入操作，屏幕状态可能已改变
                self._context.append({
                    "role": "user",
                    "content": [{"type": "text",
                                 "text": f"[系统提示] 用户已完成人工介入操作（原因: {msg}），"
                                         "屏幕状态可能已发生变化，请根据最新屏幕状态继续完成剩余任务。"}]
                })
                return True
            elif name == "Clear":
                try:
                    return auto_engine.x_input_clear()
                except AttributeError:
                    # 回退: Ctrl+A 全选 + Delete 删除
                    auto_engine.key_code("277")  # KEYCODE_CTRL_A
                    auto_engine.key_code("67")   # KEYCODE_DEL
                    return True
            elif name == "Notification":
                # 从顶部下拉打开通知栏
                return auto_engine.swipe(
                    screen_w // 2, 0,
                    screen_w // 2, screen_h * 2 // 3,
                    duration=300,
                )
            elif name == "Recent":
                return auto_engine.key_code("187")  # KEYCODE_APP_SWITCH
            elif name == "Open_url":
                url = params.get("url", "")
                if url:
                    # P1-2: 不拼接URL到shell字符串，改用参数列表避免注入
                    ret = subprocess.run(
                        ["am", "start", "-a", "android.intent.action.VIEW", "-d", url],
                        capture_output=True, text=True, timeout=10,
                    )
                    self._emit_log("info", "打开网址", url)
                    return ret.returncode == 0
                return False
            elif name == "Shell":
                cmd = params.get("cmd", "")
                if cmd:
                    ret = subprocess.run(
                        ["sh", "-c", cmd],
                        capture_output=True, text=True, timeout=15,
                    )
                    shell_out = (ret.stdout or ret.stderr or "").strip()
                    self._emit_log("info", "Shell 输出", shell_out[:500])
                    # 将 Shell 结果注入上下文，让 VLM 直接读取输出内容
                    if shell_out:
                        inject_text = f"[Shell 执行结果]\n命令: {cmd}\n输出:\n{shell_out[:2000]}"
                        self._context.append({
                            "role": "user",
                            "content": [{"type": "text", "text": inject_text}],
                        })
                    return ret.returncode == 0
                return False
            else:
                self._emit_log("warning", f"未知动作: {name}")
                return False

        except Exception as e:
            self._emit_log("error", f"执行失败: {name}", str(e))
            return False

    def _do_tap(self, params: dict, sw: int, sh: int) -> bool:
        """点击"""
        element = params.get("element", [])
        if len(element) < 2:
            return False
        px, py = coords_to_pixels(element[0], element[1], sw, sh)
        return auto_engine.touch(px, py)

    def _do_type(self, params: dict) -> bool:
        """输入文字 — 默认通过 YY 输入法（更稳定，兼容中文）"""
        text = params.get("text", "")
        if not text:
            return False

        # 中文/非ASCII 走剪贴板粘贴，ASCII 走 input_text
        has_non_ascii = any(ord(c) > 127 for c in text)
        if has_non_ascii:
            ok = auto_engine.set_clipboard(text)
            # P1-1: 剪贴板失败时回落到 input_text，不能返回 True
            if ok:
                auto_engine.key_code("279")  # KEYCODE_PASTE
                return True
        return auto_engine.input_text(text)

    def _do_swipe(self, params: dict, sw: int, sh: int) -> bool:
        """滑动"""
        start = params.get("start", [])
        end = params.get("end", [])
        if len(start) < 2 or len(end) < 2:
            return False
        x1, y1 = coords_to_pixels(start[0], start[1], sw, sh)
        x2, y2 = coords_to_pixels(end[0], end[1], sw, sh)
        return auto_engine.swipe(x1, y1, x2, y2, duration=300)

    async def _do_long_press(self, params: dict, sw: int, sh: int) -> bool:
        """长按"""
        element = params.get("element", [])
        if len(element) < 2:
            return False
        px, py = coords_to_pixels(element[0], element[1], sw, sh)
        return auto_engine.long_click(px, py)

    async def _do_double_tap(self, params: dict, sw: int, sh: int) -> bool:
        """双击（异步，不阻塞事件循环）"""
        element = params.get("element", [])
        if len(element) < 2:
            return False
        px, py = coords_to_pixels(element[0], element[1], sw, sh)
        ok1 = auto_engine.touch(px, py)
        await asyncio.sleep(0.15)
        ok2 = auto_engine.touch(px, py)
        return ok1 and ok2

    def _do_launch(self, params: dict) -> bool:
        """启动应用（智能解析包名/应用名）"""
        app = params.get("app", "")
        if not app:
            return False
        return auto_engine.open_app(app)

    # ----------------------------------------------------------
    # 任务计划确认
    # ----------------------------------------------------------

    async def _generate_and_confirm_plan(self, instruction: str) -> Optional[AgentResult]:
        """
        生成任务执行计划并等待用户确认。
        Returns:
            None         — 用户确认，继续执行
            AgentResult  — 用户拒绝或超时取消，终止任务
        """
        self._status.phase = "planning"
        self._plan_confirmed = False
        self._plan_rejected = False

        self._emit_log("info", "📋 生成任务计划", f"正在分析任务：{instruction[:60]}...")
        self._overlay(f"show:\U0001f4cb AI 正在制定计划\n{instruction[:60]}")

        plan_text = ""
        try:
            plan_prompt = build_plan_prompt(instruction)
            resp = await self._vlm.predict(plan_prompt)
            if not resp.error and resp.content:
                content = resp.content
                content = re.sub(r"<think>.*?</think>", "", content, flags=re.DOTALL).strip()
                content = re.sub(r"<answer>(.*?)</answer>", r"\1", content, flags=re.DOTALL).strip()
                plan_text = content.strip()
                self._token_usage.add(resp.usage_prompt, resp.usage_completion)
        except Exception as e:
            self._emit_log("warning", "计划生成失败", f"跳过确认步骤: {e}")
            return None  # 失败时直接继续执行，不阻断

        if not plan_text:
            return None

        # 更新状态，推送给前端等待确认
        self._status.plan = plan_text
        self._status.phase = "confirm_pending"
        self._emit_log("info", "📋 请确认执行计划", plan_text)
        self._overlay(f"show:\U0001f4cb 执行计划已生成\n请在手机上确认或取消")
        console.log(f"\U0001f4cb 任务计划:\n{plan_text}")

        # P0-3: 用 asyncio.Event 替代 busy-wait，最多等待 60 秒
        try:
            await asyncio.wait_for(self._plan_event.wait(), timeout=60.0)
        except asyncio.TimeoutError:
            self._emit_log("warning", "计划确认超时", "60 秒无响应，自动继续执行")
            self._status.phase = "running"
            return None

        if self._stop_flag or self._plan_rejected:
            self._status.phase = "done"
            return AgentResult(success=False, message="用户取消执行")

        self._status.phase = "running"
        self._emit_log("success", "✅ 计划已确认", "开始执行任务")
        self._overlay("hide")
        return None

    # ----------------------------------------------------------
    # Skills 快速路径
    # ----------------------------------------------------------

    def _try_skill_fast_path(self, skill_match: Dict[str, Any]) -> Optional[str]:
        """
        尝试 Skill 快速路径（DeepLink/Python 片段直接执行）

        Args:
            skill_match: match_skill() 的返回值
        Returns:
            完成消息 (str) 表示快速路径成功, None 表示需要走 GUI 自动化
        """
        app = skill_match.get("app")
        if not app:
            return None

        from agent_skills import ExecutionType

        if app.exec_type == ExecutionType.DELEGATION:
            # DeepLink 直达
            if app.deep_link:
                params = skill_match.get("params", {})
                deep_link = app.deep_link
                for k, v in params.items():
                    deep_link = deep_link.replace(f"{{{k}}}", v)
                subprocess.run(
                    ["sh", "-c",
                     f"am start -a android.intent.action.VIEW -d '{deep_link}'"],
                    capture_output=True, timeout=10,
                )
                return f"已通过 DeepLink 打开: {app.name}"

            # Python 片段
            if app.python_snippet:
                try:
                    from agent_executor import execute_python
                    params = skill_match.get("params", {})
                    code = app.python_snippet
                    for k, v in params.items():
                        code = code.replace(f"{{{k}}}", v)
                    result = execute_python(code, timeout=15)
                    if result.success:
                        return result.data or f"已执行: {app.name}"
                except Exception as e:
                    self._emit_log("warning", "Skill Python 执行失败", str(e))

        return None

    # ----------------------------------------------------------
    # 上下文管理
    # ----------------------------------------------------------

    def _trim_context(self, max_messages: int = 15):
        """修剪上下文，防止无限增长"""
        # 保留: system(1) + 最近 N 轮对话
        if len(self._context) <= max_messages:
            return

        # 保留 system message + 最近的消息
        system = self._context[0] if self._context[0]["role"] == "system" else None
        recent = self._context[-(max_messages - 1):]

        self._context = []
        if system:
            self._context.append(system)
        self._context.extend(recent)

    # ----------------------------------------------------------
    # 日志
    # ----------------------------------------------------------

    def _emit_log(self, log_type: str, title: str, detail: str = "",
                  prompt_tokens: int = 0, completion_tokens: int = 0,
                  latency_ms: int = 0, human_msg: str = "",
                  screenshot_path: str = ""):
        """发送日志，同时推送到悬浮日志控制台"""
        entry = {
            "step": self._status.current_step,
            "timestamp": int(time.time() * 1000),
            "type": log_type,
            "title": title,
            "detail": detail,
        }
        # ③ 人话化消息（来自 VLM thinking 的自然语言描述）
        if human_msg:
            entry["human_msg"] = human_msg
        # ② 结果截图路径
        if screenshot_path:
            entry["screenshot_path"] = screenshot_path
        if prompt_tokens or completion_tokens:
            entry["token"] = {
                "prompt_tokens": prompt_tokens,
                "completion_tokens": completion_tokens,
                "total_tokens": prompt_tokens + completion_tokens,
                "latency_ms": latency_ms,
            }
        entry["token_total"] = self._token_usage.to_dict()
        self._full_logs.append(entry)
        if self._log_callback:
            try:
                self._log_callback(entry)
            except Exception:
                pass
        # 实时推送到悬浮窗 Agent Tab（专用步骤日志）
        step = self._status.current_step
        max_s = self._status.max_steps
        prefix = f"[{step}/{max_s}] "
        if title not in _LOG_SKIP_TITLES:
            short_detail = detail.split("\n")[0][:80] if detail else ""
            msg = f"{prefix}{title}" + (f": {short_detail}" if short_detail else "")
            # 同时推送到：① 脚本日志 Tab（console.log），② Agent Tab（agent_log 命令）
            level_map = {"error": "E", "warning": "W", "success": "I", "info": "I"}
            level_tag = level_map.get(log_type, "I")
            console.agent_log(msg, level_tag)  # → Agent Tab
            if log_type == "error":
                console.error(msg)    # → 日志 Tab
            elif log_type == "warning":
                console.warn(msg)
            elif log_type == "success":
                console.success(msg)
            else:
                console.log(msg)

    def _persist_run_log(self, instruction: str, result: AgentResult):
        """持久化运行日志"""
        os.makedirs(_AGENT_LOG_DIR, exist_ok=True)
        run_id = f"{int(time.time() * 1000)}"
        log_data = {
            "run_id": run_id,
            "instruction": instruction,
            "success": result.success,
            "message": result.message,
            "answer": result.answer,
            "total_steps": result.total_steps,
            "elapsed_ms": result.elapsed_ms,
            "token_usage": self._token_usage.to_dict(),
            "model": self._vlm.model if self._vlm else "",
            "provider": self._config.provider or "",
            "started_at": self._full_logs[0]["timestamp"] if self._full_logs else 0,
            "finished_at": int(time.time() * 1000),
            "logs": self._full_logs,
        }
        path = os.path.join(_AGENT_LOG_DIR, f"{run_id}.json")
        try:
            with open(path, "w", encoding="utf-8") as f:
                json.dump(log_data, f, ensure_ascii=False, separators=(",", ":"))
        except Exception as e:
            print(f"[Agent] 日志持久化失败: {e}")


# ============================================================
# 工具函数
# ============================================================

# ③ 动作名称 → 自然语言映射表
_ACTION_HUMAN_NAMES: Dict[str, str] = {
    "Tap":        "点击界面元素",
    "Long Press": "长按界面元素",
    "Double Tap": "双击界面元素",
    "Swipe":      "滑动屏幕",
    "Type":       "输入文字",
    "Clear":      "清空输入框",
    "Back":       "返回上一页",
    "Home":       "回到桌面",
    "Recent":     "打开最近任务",
    "Notification": "下拉通知栏",
    "Launch":     "启动应用",
    "Open_url":   "打开网页",
    "Shell":      "执行系统命令",
    "Wait":       "等待页面加载",
    "Take_over":  "需要人工操作",
}


# P2-3: 无意义开头短语过滤词组（这类词出现在开头时说明 thinking 没有实际内容）
_THINKING_NOISE_PREFIXES = (
    "当前屏幕", "当前界面", "根据屏幕", "根据截图",
    "current screen", "based on the", "the screen shows",
    "我需要", "现在我", "接下来", "首先我",
)


def _extract_human_msg(thinking: str, action_name: str) -> str:
    """从 VLM thinking 中提取最有意义的自然语言句，用于人话化播报"""
    if thinking:
        # 清除系统标签
        text = re.sub(r"<[^>]+>", "", thinking).strip()
        # 分句
        sentences = re.split(r"[。！？.!?\n]", text)
        for s in sentences:
            s = s.strip()
            if len(s) < 5:
                continue
            # P2-3: 过滤掇述性无意义开头
            s_lower = s.lower()
            if any(s_lower.startswith(p) for p in _THINKING_NOISE_PREFIXES):
                continue
            return s[:60]
    # 回退：动作名自然语言化
    return _ACTION_HUMAN_NAMES.get(action_name, f"执行 {action_name}")


def _strip_images(message: dict) -> dict:
    """从消息中移除图片内容，保留文本（节省 token）"""
    content = message.get("content")
    if isinstance(content, list):
        message = dict(message)  # shallow copy
        message["content"] = [
            item for item in content if item.get("type") == "text"
        ]
    return message


# ============================================================
# 全局实例管理
# ============================================================

_agent: Optional[MobileAgent] = None


def get_agent() -> MobileAgent:
    global _agent
    if _agent is None:
        _agent = MobileAgent()
    return _agent


def reset_agent(config: Optional[AgentConfig] = None) -> MobileAgent:
    global _agent
    _agent = MobileAgent(config)
    return _agent


# ============================================================
# Agent 日志历史查询
# ============================================================

_AGENT_LOG_DIR = "/sdcard/Yyds.Auto/agent_logs"


def get_agent_run_list(limit: int = 20, offset: int = 0) -> dict:
    """获取历史运行列表（按时间倒序）"""
    if not os.path.isdir(_AGENT_LOG_DIR):
        return {"total": 0, "runs": []}
    files = sorted(
        [f for f in os.listdir(_AGENT_LOG_DIR) if f.endswith(".json")],
        reverse=True,
    )
    total = len(files)
    page = files[offset:offset + limit]
    runs = []
    for fname in page:
        path = os.path.join(_AGENT_LOG_DIR, fname)
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            runs.append({
                "run_id": data.get("run_id", ""),
                "instruction": data.get("instruction", ""),
                "success": data.get("success", False),
                "total_steps": data.get("total_steps", 0),
                "elapsed_ms": data.get("elapsed_ms", 0),
                "token_usage": data.get("token_usage", {}),
                "model": data.get("model", ""),
                "provider": data.get("provider", ""),
                "started_at": data.get("started_at", 0),
                "finished_at": data.get("finished_at", 0),
            })
        except Exception:
            continue
    return {"total": total, "runs": runs}


def get_agent_run_detail(run_id: str) -> Optional[dict]:
    """获取某次运行的完整日志"""
    path = os.path.join(_AGENT_LOG_DIR, f"{run_id}.json")
    if not os.path.isfile(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def clear_agent_logs() -> int:
    """清空所有历史日志，返回删除数量"""
    if not os.path.isdir(_AGENT_LOG_DIR):
        return 0
    count = 0
    for fname in os.listdir(_AGENT_LOG_DIR):
        if fname.endswith(".json"):
            try:
                os.remove(os.path.join(_AGENT_LOG_DIR, fname))
                count += 1
            except Exception:
                pass
    return count
