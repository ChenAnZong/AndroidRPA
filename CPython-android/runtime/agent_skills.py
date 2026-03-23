"""
Skills 层 — 意图匹配 + 快速路径
借鉴肉包项目的 Skill + SkillManager 设计

两种执行模式：
- Delegation：通过 Intent/DeepLink 直接跳转（快速路径）
- GUI Automation：提供步骤指导，由 Agent 主循环执行

Skills 从 skills.json 加载，支持关键词匹配
"""

import json
import os

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional


# ==========================================================
# 数据结构
# ==========================================================

class ExecutionType(Enum):
    DELEGATION = "delegation"       # DeepLink / Intent 直达
    GUI_AUTOMATION = "gui_automation"  # GUI 自动化循环


@dataclass
class RelatedApp:
    """关联应用"""
    package: str
    name: str
    exec_type: ExecutionType
    priority: int = 100
    deep_link: str = ""           # delegation 模式的 DeepLink 模板
    steps: List[str] = field(default_factory=list)  # gui_automation 模式的步骤指导
    python_snippet: str = ""      # 可选的 Python 代码片段（我们的独特优势）


@dataclass
class SkillParam:
    """Skill 参数"""
    name: str
    type: str  # string / int / float
    description: str
    required: bool = False
    examples: List[str] = field(default_factory=list)


@dataclass
class Skill:
    """技能定义"""
    id: str
    name: str
    description: str
    category: str = ""
    keywords: List[str] = field(default_factory=list)
    params: List[SkillParam] = field(default_factory=list)
    related_apps: List[RelatedApp] = field(default_factory=list)

    def match_score(self, query: str) -> float:
        """关键词匹配得分（0~1）"""
        q = query.lower()
        score = 0.0
        # 精确匹配 name
        if self.name in q:
            score = max(score, 0.9)
        # 关键词匹配 — 匹配到任意一个即有基础分
        matched = sum(1 for kw in self.keywords if kw.lower() in q)
        if self.keywords and matched > 0:
            # 基础分 0.4，每多匹配一个加 0.15，上限 1.0
            kw_score = min(1.0, 0.4 + (matched - 1) * 0.15)
            score = max(score, kw_score)
        # 类别匹配
        if self.category and self.category.lower() in q:
            score = max(score, 0.3)
        return score

    def extract_params(self, query: str) -> Dict[str, str]:
        """从用户指令中提取参数 — 先精确匹配 examples，再模糊提取"""
        extracted = {}
        for p in self.params:
            # 1. 精确匹配 examples
            for ex in p.examples:
                if ex in query:
                    extracted[p.name] = ex
                    break
            if p.name in extracted:
                continue
            # 2. 模糊提取：去掉已知关键词后，剩余部分作为参数值
            if p.required or len(self.params) == 1:
                remaining = query
                for kw in self.keywords:
                    remaining = remaining.replace(kw, "")
                for other_p in self.params:
                    if other_p.name != p.name:
                        for ex in other_p.examples:
                            remaining = remaining.replace(ex, "")
                remaining = remaining.strip("，。！？ \t,!?")
                if remaining:
                    extracted[p.name] = remaining
        return extracted

    def best_app(self, installed_packages: Optional[List[str]] = None) -> Optional[RelatedApp]:
        """选择最佳关联应用"""
        candidates = self.related_apps
        if installed_packages:
            candidates = [a for a in candidates if a.package in installed_packages]
        if not candidates:
            candidates = self.related_apps
        if not candidates:
            return None
        # 按优先级排序，delegation 优先
        candidates.sort(key=lambda a: (-1 if a.exec_type == ExecutionType.DELEGATION else 0, -a.priority))
        return candidates[0]


# ==========================================================
# SkillRegistry — 加载、匹配、执行
# ==========================================================

class SkillRegistry:
    """技能注册表"""

    def __init__(self):
        self._skills: Dict[str, Skill] = {}

    def load_from_json(self, path: str):
        """从 JSON 文件加载 Skills"""
        if not os.path.exists(path):
            return
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)

        for item in data:
            skill = Skill(
                id=item["id"],
                name=item["name"],
                description=item.get("description", ""),
                category=item.get("category", ""),
                keywords=item.get("keywords", []),
                params=[
                    SkillParam(
                        name=p["name"], type=p.get("type", "string"),
                        description=p.get("description", ""),
                        required=p.get("required", False),
                        examples=p.get("examples", []),
                    ) for p in item.get("params", [])
                ],
                related_apps=[
                    RelatedApp(
                        package=a["package"], name=a["name"],
                        exec_type=ExecutionType(a.get("type", "gui_automation")),
                        priority=a.get("priority", 100),
                        deep_link=a.get("deep_link", ""),
                        steps=a.get("steps", []),
                        python_snippet=a.get("python_snippet", ""),
                    ) for a in item.get("related_apps", [])
                ],
            )
            self._skills[skill.id] = skill

    def register(self, skill: Skill):
        self._skills[skill.id] = skill

    def match(self, query: str, threshold: float = 0.3) -> List[tuple]:
        """匹配用户指令，返回 [(skill, score)] 按得分降序"""
        results = []
        for skill in self._skills.values():
            score = skill.match_score(query)
            if score >= threshold:
                results.append((skill, score))
        results.sort(key=lambda x: -x[1])
        return results

    def best_match(self, query: str, threshold: float = 0.3) -> Optional[Skill]:
        """返回最佳匹配的 Skill"""
        matches = self.match(query, threshold)
        return matches[0][0] if matches else None

    def get(self, skill_id: str) -> Optional[Skill]:
        return self._skills.get(skill_id)

    def list_all(self) -> List[Skill]:
        return list(self._skills.values())

    def to_json_list(self) -> list:
        """序列化为 JSON 可传输的列表"""
        result = []
        for s in self._skills.values():
            result.append({
                "id": s.id, "name": s.name,
                "description": s.description,
                "category": s.category,
                "keywords": s.keywords,
            })
        return result


# ==========================================================
# Agent 上下文生成
# ==========================================================

def generate_skill_context(skill: Skill, app: Optional[RelatedApp], params: Dict[str, str]) -> str:
    """为 Agent 主循环生成 Skill 上下文（注入到 prompt 中）"""
    lines = [f"[Skill 匹配] {skill.name} — {skill.description}"]

    if params:
        lines.append(f"提取的参数: {json.dumps(params, ensure_ascii=False)}")

    if app:
        lines.append(f"目标应用: {app.name} ({app.package})")
        if app.exec_type == ExecutionType.DELEGATION and app.deep_link:
            lines.append(f"快速路径: 可通过 DeepLink 直达 → {app.deep_link}")
            lines.append("建议使用 shell 工具执行: am start -a android.intent.action.VIEW -d '<deep_link>'")
        elif app.steps:
            lines.append("操作步骤指导:")
            for i, step in enumerate(app.steps, 1):
                lines.append(f"  {i}. {step}")
        if app.python_snippet:
            lines.append(f"可用 run_python 执行预置代码:\n```python\n{app.python_snippet}\n```")

    return "\n".join(lines)


# ==========================================================
# 全局实例
# ==========================================================

_registry: Optional[SkillRegistry] = None
_SKILLS_JSON = os.path.join(os.path.dirname(__file__), "skills.json")


def get_skill_registry() -> SkillRegistry:
    """获取全局 Skill 注册表（单例）"""
    global _registry
    if _registry is None:
        _registry = SkillRegistry()
        _registry.load_from_json(_SKILLS_JSON)
    return _registry


def match_skill(instruction: str) -> Optional[Dict[str, Any]]:
    """
    匹配用户指令到 Skill，返回上下文信息
    Returns: {"skill": Skill, "app": RelatedApp, "params": dict, "context": str} or None
    """
    registry = get_skill_registry()
    skill = registry.best_match(instruction)
    if not skill:
        return None

    params = skill.extract_params(instruction)
    app = skill.best_app()
    context = generate_skill_context(skill, app, params)

    return {
        "skill": skill,
        "app": app,
        "params": params,
        "context": context,
    }
