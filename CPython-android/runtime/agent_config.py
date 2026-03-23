"""
Agent 配置管理 - 持久化到 /sdcard/Yyds.Auto/agent.json
支持预置服务商模板和自定义 OpenAI 兼容接口
"""

import json
import os
from dataclasses import dataclass, field, asdict
from typing import Optional

# 配置文件路径
AGENT_CONFIG_DIR = "/sdcard/Yyds.Auto"
AGENT_CONFIG_PATH = os.path.join(AGENT_CONFIG_DIR, "agent.json")


@dataclass
class AgentConfig:
    """Agent 配置"""
    # 服务商: autoglm / doubao / qwen / zhipu / deepseek / moonshot / baichuan / minimax /
    #         siliconflow / openai / anthropic / gemini / mistral / groq / xai /
    #         openrouter / custom
    provider: str = "autoglm"
    # API Key
    api_key: str = ""
    # 自定义 base_url（仅 custom 模式）
    base_url: str = ""
    # 自定义模型名（仅 custom 模式，或覆盖预置模型）
    model: str = ""
    # 最大执行步数
    max_steps: int = 25
    # 是否在 prompt 中包含 UI 控件树 XML
    use_ui_dump: bool = True
    # 使用 V2 Agent（单循环、AutoGLM-Phone 兼容）
    use_v2: bool = True
    # 悬浮窗口显示执行过程（思考时显示，操作时隐藏）
    show_floating_window: bool = True
    # VLM temperature
    temperature: float = 0.1
    # VLM max_tokens
    max_tokens: int = 1024
    # 执行前生成任务计划，等待用户确认后再执行
    enable_plan_confirm: bool = False
    # 任务完成时自动截图留档
    save_result_screenshot: bool = True

    @property
    def is_configured(self) -> bool:
        """是否已配置 API Key"""
        return bool(self.api_key.strip())

    def to_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict) -> "AgentConfig":
        known_fields = {f.name for f in cls.__dataclass_fields__.values()}
        filtered = {k: v for k, v in data.items() if k in known_fields}
        return cls(**filtered)

    def save(self, path: str = AGENT_CONFIG_PATH) -> bool:
        """保存配置到文件"""
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                json.dump(self.to_dict(), f, ensure_ascii=False, indent=2)
            return True
        except Exception as e:
            print(f"[AgentConfig] 保存失败: {e}")
            return False

    @classmethod
    def load(cls, path: str = AGENT_CONFIG_PATH) -> "AgentConfig":
        """从文件加载配置，不存在则返回默认"""
        try:
            if os.path.exists(path):
                with open(path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                return cls.from_dict(data)
        except Exception as e:
            print(f"[AgentConfig] 加载失败: {e}")
        return cls()


# 全局配置单例
_config: Optional[AgentConfig] = None


def get_config() -> AgentConfig:
    global _config
    _config = AgentConfig.load()
    return _config


def set_config(config: AgentConfig) -> bool:
    global _config
    _config = config
    return config.save()


def update_config(**kwargs) -> AgentConfig:
    """部分更新配置"""
    cfg = get_config()
    for k, v in kwargs.items():
        if hasattr(cfg, k):
            setattr(cfg, k, v)
    set_config(cfg)
    return cfg
