"""
VLM 客户端 - OpenAI Chat Completions 兼容
支持 16 个主流 AI 服务商及任意 OpenAI 兼容接口
使用 aiohttp 异步请求，内置重试和超时
"""
from __future__ import annotations

import asyncio
import base64
import json
import time
import traceback
from dataclasses import dataclass, field
from typing import Optional, List, Dict, Any

import urllib.request
import urllib.error
import ssl


# ============================================================
# 预置服务商模板
# ============================================================

PRESETS: Dict[str, Dict[str, Any]] = {
    # ---- 国内服务商 ----
    # 智谱 AutoGLM-Phone：手机 UI 专用模型，执行率更高
    # 文档: https://docs.bigmodel.cn/cn/guide/models/vlm/autoglm-phone
    "autoglm": {
        "base_url": "https://open.bigmodel.cn/api/paas/v4",
        "model": "autoglm-phone",
        "name": "智谱 AutoGLM-Phone",
        "console_url": "https://open.bigmodel.cn/",
        "models": ["autoglm-phone"],
        "vision": True,
    },
    "doubao": {
        "base_url": "https://ark.cn-beijing.volces.com/api/v3",
        "model": "doubao-1-5-vision-pro-32k",
        "name": "火山引擎（豆包）",
        "console_url": "https://console.volcengine.com/ark",
        "models": ["doubao-1-5-vision-pro-32k", "doubao-1-5-vision-pro-256k",
                    "doubao-1-5-thinking-pro-250415", "doubao-1-5-pro-32k",
                    "doubao-seed-2-0-lite-260215", "doubao-seed-2-0-pro-260215"],
        "vision": True,
    },
    "qwen": {
        "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "model": "qwen-vl-max",
        "name": "阿里云（通义千问）",
        "console_url": "https://dashscope.console.aliyun.com/",
        "models": ["qwen-vl-max", "qwen-vl-plus", "qwen-max", "qwen-plus",
                    "qwen-turbo", "qwen2.5-vl-72b-instruct"],
        "vision": True,
    },
    "zhipu": {
        "base_url": "https://open.bigmodel.cn/api/paas/v4",
        "model": "glm-4v-flash",
        "name": "智谱AI（GLM）",
        "console_url": "https://open.bigmodel.cn/",
        "models": ["glm-4v-flash", "glm-4v-plus", "glm-4v",
                    "glm-4-plus", "glm-4-flash", "glm-4-long",
                    "glm-z1-flash", "glm-z1-air"],
        "vision": True,
    },
    "deepseek": {
        "base_url": "https://api.deepseek.com/v1",
        "model": "deepseek-chat",
        "name": "DeepSeek",
        "console_url": "https://platform.deepseek.com/api_keys",
        "models": ["deepseek-chat", "deepseek-reasoner"],
        "vision": False,
    },
    "moonshot": {
        "base_url": "https://api.moonshot.ai/v1",
        "model": "kimi-k2-turbo-preview",
        "name": "月之暗面（Kimi）",
        "console_url": "https://platform.moonshot.ai/",
        "models": ["kimi-k2-turbo-preview", "moonshot-v1-128k",
                    "moonshot-v1-32k", "moonshot-v1-8k"],
        "vision": False,
    },
    "baichuan": {
        "base_url": "https://api.baichuan-ai.com/v1",
        "model": "Baichuan4-Turbo",
        "name": "百川智能",
        "console_url": "https://platform.baichuan-ai.com/",
        "models": ["Baichuan4-Turbo", "Baichuan4-Air", "Baichuan3-Turbo"],
        "vision": False,
    },
    "minimax": {
        "base_url": "https://api.minimax.chat/v1",
        "model": "MiniMax-Text-01",
        "name": "MiniMax（海螺）",
        "console_url": "https://platform.minimaxi.com/",
        "models": ["MiniMax-Text-01", "MiniMax-VL-01", "abab6.5s-chat"],
        "vision": True,
    },
    "siliconflow": {
        "base_url": "https://api.siliconflow.cn/v1",
        "model": "deepseek-ai/DeepSeek-V3",
        "name": "硅基流动（SiliconFlow）",
        "console_url": "https://cloud.siliconflow.cn/",
        "models": ["deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1",
                    "Qwen/Qwen2.5-72B-Instruct", "THUDM/GLM-Z1-32B-0414",
                    "Pro/Qwen/Qwen2.5-VL-7B-Instruct"],
        "vision": True,
    },
    # ---- 海外服务商 ----
    "openai": {
        "base_url": "https://api.openai.com/v1",
        "model": "gpt-4o",
        "name": "OpenAI",
        "console_url": "https://platform.openai.com/api-keys",
        "models": ["gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini",
                    "gpt-4.1-nano", "o3-mini", "o4-mini"],
        "vision": True,
    },
    "anthropic": {
        "base_url": "https://api.anthropic.com/v1",
        "model": "claude-sonnet-4-20250514",
        "name": "Anthropic（Claude）",
        "console_url": "https://console.anthropic.com/",
        "models": ["claude-sonnet-4-20250514", "claude-opus-4-20250514",
                    "claude-3-5-haiku-20241022"],
        "vision": True,
        "extra_headers": {"anthropic-version": "2023-06-01"},
    },
    "gemini": {
        "base_url": "https://generativelanguage.googleapis.com/v1beta/openai",
        "model": "gemini-2.5-flash",
        "name": "Google（Gemini）",
        "console_url": "https://aistudio.google.com/apikey",
        "models": ["gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash"],
        "vision": True,
    },
    "mistral": {
        "base_url": "https://api.mistral.ai/v1",
        "model": "mistral-large-latest",
        "name": "Mistral AI",
        "console_url": "https://console.mistral.ai/",
        "models": ["mistral-large-latest", "mistral-small-latest",
                    "pixtral-large-latest", "codestral-latest"],
        "vision": True,
    },
    "groq": {
        "base_url": "https://api.groq.com/openai/v1",
        "model": "llama-3.3-70b-versatile",
        "name": "Groq",
        "console_url": "https://console.groq.com/keys",
        "models": ["llama-3.3-70b-versatile", "llama-3.1-8b-instant",
                    "gemma2-9b-it", "mixtral-8x7b-32768"],
        "vision": False,
    },
    "xai": {
        "base_url": "https://api.x.ai/v1",
        "model": "grok-4-fast-reasoning",
        "name": "xAI（Grok）",
        "console_url": "https://console.x.ai/",
        "models": ["grok-4-fast-reasoning", "grok-4-fast-non-reasoning",
                    "grok-4", "grok-2-image-1212"],
        "vision": True,
    },
    # ---- 聚合平台 ----
    "openrouter": {
        "base_url": "https://openrouter.ai/api/v1",
        "model": "anthropic/claude-sonnet-4",
        "name": "OpenRouter",
        "console_url": "https://openrouter.ai/keys",
        "models": ["anthropic/claude-sonnet-4", "openai/gpt-4o",
                    "google/gemini-2.5-flash", "deepseek/deepseek-r1",
                    "meta-llama/llama-3.3-70b-instruct"],
        "vision": True,
    },
}


@dataclass
class VLMResponse:
    """VLM 响应"""
    content: str = ""
    usage_prompt: int = 0
    usage_completion: int = 0
    model: str = ""
    finish_reason: str = ""
    error: Optional[str] = None
    latency_ms: int = 0


class VLMClient:
    """OpenAI Chat Completions 兼容的 VLM 客户端"""

    def __init__(
        self,
        api_key: str,
        base_url: str,
        model: str,
        max_retries: int = 3,
        connect_timeout: float = 30.0,
        read_timeout: float = 120.0,
        temperature: float = 0.1,
        max_tokens: int = 1024,
        extra_headers: Optional[Dict[str, str]] = None,
    ):
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.max_retries = max_retries
        self.connect_timeout = connect_timeout
        self.read_timeout = read_timeout
        self.temperature = temperature
        self.max_tokens = max_tokens
        self.extra_headers = extra_headers or {}
        self._ssl_ctx = ssl.create_default_context()

    @classmethod
    def from_preset(cls, provider: str, api_key: str, **kwargs) -> "VLMClient":
        """从预置模板创建客户端"""
        preset = PRESETS.get(provider)
        if not preset:
            raise ValueError(f"未知的服务商: {provider}, 可选: {list(PRESETS.keys())}")
        return cls(
            api_key=api_key,
            base_url=preset["base_url"],
            model=kwargs.pop("model", None) or preset["model"],
            extra_headers=preset.get("extra_headers"),
            **kwargs,
        )

    async def close(self):
        """兼容接口，urllib 无需关闭"""
        pass

    # ----------------------------------------------------------
    # 核心调用
    # ----------------------------------------------------------

    async def predict(
        self,
        prompt: str,
        images: Optional[List[bytes]] = None,
    ) -> VLMResponse:
        """单轮预测：文本 + 可选图片列表"""
        content_parts: List[Dict[str, Any]] = []

        # 图片部分
        if images:
            for img_bytes in images:
                b64 = base64.b64encode(img_bytes).decode("utf-8")
                # 自动检测图片格式
                mime = "image/png" if img_bytes[:4] == b'\x89PNG' else "image/jpeg"
                content_parts.append({
                    "type": "image_url",
                    "image_url": {"url": f"data:{mime};base64,{b64}"},
                })

        # 文本部分
        content_parts.append({"type": "text", "text": prompt})

        messages = [{"role": "user", "content": content_parts}]
        return await self._chat_completions(messages)

    async def predict_with_context(
        self,
        messages: List[Dict[str, Any]],
    ) -> VLMResponse:
        """多轮对话预测，messages 格式同 OpenAI"""
        return await self._chat_completions(messages)

    async def test_connection(self) -> VLMResponse:
        """测试连接：发送简单请求验证 API Key 有效性"""
        return await self.predict("回复OK两个字母即可。")

    # ----------------------------------------------------------
    # HTTP 请求（带重试）
    # ----------------------------------------------------------

    async def _chat_completions(self, messages: List[Dict]) -> VLMResponse:
        """异步调用 VLM — 在线程中执行阻塞 HTTP，不冻结事件循环"""
        url = f"{self.base_url}/chat/completions"
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.api_key}",
        }
        if self.extra_headers:
            headers.update(self.extra_headers)
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": self.temperature,
            "max_tokens": self.max_tokens,
        }
        payload_bytes = json.dumps(payload).encode("utf-8")
        timeout = self.connect_timeout + self.read_timeout

        last_error = None
        for attempt in range(1, self.max_retries + 1):
            # 在线程池中执行阻塞 HTTP 请求，释放事件循环
            resp_or_err = await asyncio.to_thread(
                self._do_http_request, url, headers, payload_bytes, timeout
            )
            if isinstance(resp_or_err, VLMResponse):
                return resp_or_err

            # resp_or_err is (error_msg, is_client_error)
            last_error, is_client_error = resp_or_err
            if is_client_error:
                return VLMResponse(error=last_error)

            if attempt < self.max_retries:
                wait = min(2 ** attempt, 10)
                print(f"[VLM] 第 {attempt} 次请求失败: {last_error}, {wait}s 后重试")
                await asyncio.sleep(wait)

        return VLMResponse(error=f"重试 {self.max_retries} 次后仍失败: {last_error}")

    def _do_http_request(
        self, url: str, headers: dict, payload_bytes: bytes, timeout: float
    ):
        """同步 HTTP 请求 — 在线程池中被调用，不阻塞事件循环"""
        t0 = time.monotonic()
        try:
            req = urllib.request.Request(
                url, data=payload_bytes, headers=headers, method="POST"
            )
            with urllib.request.urlopen(
                req, timeout=timeout, context=self._ssl_ctx
            ) as resp:
                latency = int((time.monotonic() - t0) * 1000)
                body = resp.read().decode("utf-8")
                data = json.loads(body)
                choice = data.get("choices", [{}])[0]
                message = choice.get("message", {})
                usage = data.get("usage", {})
                # content 可能是 str 或 list（思维链模型返回 list of parts）
                raw_content = message.get("content", "")
                if isinstance(raw_content, list):
                    # 拼接所有 text 类型的 part
                    text_parts = [p.get("text", "") for p in raw_content
                                  if isinstance(p, dict) and p.get("type") == "text"]
                    content_str = "".join(text_parts).strip()
                else:
                    content_str = (raw_content or "").strip()
                # 如果 content 为空，尝试 reasoning_content（部分思维链模型）
                if not content_str:
                    content_str = (message.get("reasoning_content", "") or "").strip()
                return VLMResponse(
                    content=content_str,
                    usage_prompt=usage.get("prompt_tokens", 0),
                    usage_completion=usage.get("completion_tokens", 0),
                    model=data.get("model", self.model),
                    finish_reason=choice.get("finish_reason", ""),
                    latency_ms=latency,
                )
        except urllib.error.HTTPError as e:
            latency = int((time.monotonic() - t0) * 1000)
            err_body = ""
            try:
                err_body = e.read().decode("utf-8")[:500]
            except Exception:
                pass
            error_msg = f"HTTP {e.code}: {err_body}"
            is_client_error = 400 <= e.code < 500
            if is_client_error:
                return VLMResponse(error=error_msg, latency_ms=latency)
            return (error_msg, False)
        except Exception as e:
            return (f"{type(e).__name__}: {e}", False)

    # ----------------------------------------------------------
    # 工具方法
    # ----------------------------------------------------------

    @staticmethod
    def image_to_base64_url(img_bytes: bytes) -> str:
        """图片 bytes 转 data URL"""
        b64 = base64.b64encode(img_bytes).decode("utf-8")
        mime = "image/png" if img_bytes[:4] == b'\x89PNG' else "image/jpeg"
        return f"data:{mime};base64,{b64}"

    def __repr__(self):
        return f"VLMClient(model={self.model}, base_url={self.base_url})"


# ============================================================
# 辅助函数 — 供 server.py / MCP / App UI 调用
# ============================================================

def get_provider_list() -> List[Dict[str, Any]]:
    """返回所有预置服务商列表（供下拉选择）"""
    result = []
    for key, preset in PRESETS.items():
        result.append({
            "id": key,
            "name": preset["name"],
            "default_model": preset["model"],
            "models": preset.get("models", [preset["model"]]),
            "vision": preset.get("vision", False),
            "console_url": preset.get("console_url", ""),
        })
    # 末尾追加 custom 选项
    result.append({
        "id": "custom",
        "name": "自定义（OpenAI 兼容接口）",
        "default_model": "",
        "models": [],
        "vision": True,
        "console_url": "",
    })
    return result


def get_provider_models(provider: str) -> List[str]:
    """获取指定服务商的可用模型列表"""
    preset = PRESETS.get(provider)
    if not preset:
        return []
    return preset.get("models", [preset["model"]])
