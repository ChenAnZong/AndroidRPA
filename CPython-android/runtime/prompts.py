"""
Agent Prompt templates - Chinese version for better VLM performance.
Uses \\uXXXX escapes for non-ASCII chars to ensure Python 3.13 compatibility.
"""

from datetime import datetime


_ACTION_GUIDE = (
    "# \u53ef\u7528\u64cd\u4f5c\n"
    "## \u57fa\u7840\u4ea4\u4e92\n"
    "- do(action=\"Tap\", element=[x, y])              \u70b9\u51fb\u76ee\u6807\u4e2d\u5fc3\u5750\u6807\n"
    "- do(action=\"Long Press\", element=[x, y])        \u957f\u6309(>=1.5s) -- \u89e6\u53d1\u4e0a\u4e0b\u6587\u83dc\u5355/\u9009\u62e9\u6a21\u5f0f\n"
    "- do(action=\"Double Tap\", element=[x, y])        \u53cc\u51fb -- \u653e\u5927/\u9009\u4e2d\u6587\u5b57\n"
    "- do(action=\"Swipe\", start=[x1,y1], end=[x2,y2]) \u6ed1\u52a8\u624b\u52bf\n"
    "\n"
    "## \u6587\u672c\u8f93\u5165\n"
    "- do(action=\"Type\", text=\"\u5185\u5bb9\")                 \u8f93\u5165\u6587\u5b57(\u652f\u6301\u4e2d\u82f1\u6587,\u9700\u5148\u70b9\u51fb\u8f93\u5165\u6846\u83b7\u53d6\u7126\u70b9)\n"
    "- do(action=\"Clear\")                             \u6e05\u7a7a\u5f53\u524d\u8f93\u5165\u6846(\u66ff\u6362\u6587\u672c\u524d\u5148\u6e05\u7a7a)\n"
    "\n"
    "## \u5bfc\u822a\n"
    "- do(action=\"Back\")                              \u8fd4\u56de\u952e(\u5173\u95ed\u5f39\u7a97/\u8fd4\u56de\u4e0a\u9875/\u6536\u8d77\u952e\u76d8)\n"
    "- do(action=\"Home\")                              Home\u952e(\u56de\u5230\u684c\u9762)\n"
    "- do(action=\"Recent\")                            \u6700\u8fd1\u4efb\u52a1\u952e(\u5207\u6362\u5e94\u7528)\n"
    "- do(action=\"Notification\")                      \u4e0b\u62c9\u901a\u77e5\u680f\n"
    "\n"
    "## \u5e94\u7528\u7ba1\u7406\n"
    "- do(action=\"Launch\", app=\"\u5305\u540d\u6216\u5e94\u7528\u540d\")         \u542f\u52a8/\u5207\u6362\u5e94\u7528\n"
    "- do(action=\"Open_url\", url=\"\u7f51\u5740\")              \u7528\u6d4f\u89c8\u5668\u6253\u5f00URL(\u6700\u53ef\u9760\u7684\u7f51\u9875\u5bfc\u822a\u65b9\u5f0f)\n"
    "- do(action=\"Shell\", cmd=\"shell\u547d\u4ee4\")            ROOT Shell(\u5b89\u88c5apk/\u6539\u8bbe\u7f6e/\u542f\u52a8Activity)\n"
    "\n"
    "## \u6d41\u7a0b\u63a7\u5236\n"
    "- do(action=\"Wait\", duration=\"\u79d2\u6570\")             \u7b49\u5f85\u9875\u9762\u52a0\u8f7d(1-10\u79d2,\u5fc5\u987b\u662f\u7eaf\u6570\u5b57\u5982\"3\")\n"
    "- do(action=\"Take_over\", message=\"\u539f\u56e0\")         \u9700\u8981\u4eba\u5de5\u4ecb\u5165(\u9a8c\u8bc1\u7801/\u767b\u5f55)\n"
    "- finish(message=\"\u5b8c\u6210\u8bf4\u660e\")                      \u4efb\u52a1\u5b8c\u6210\u6216\u65e0\u6cd5\u7ee7\u7eed\n"
    "\n"
    "# \u5750\u6807\u7cfb\n"
    "- \u8303\u56f4 [0,1000]x[0,1000], \u5de6\u4e0a(0,0), \u53f3\u4e0b(1000,1000)\n"
    "- \u72b6\u6001\u680f\u7ea6 y=0~35, \u5bfc\u822a\u680f\u7ea6 y=960~1000, \u5c4f\u5e55\u4e2d\u5fc3[500,500]\n"
    "\n"
    "# \u5b9e\u6218\u6280\u5de7\n"
    "## \u957f\u6309\u7684\u5178\u578b\u573a\u666f(\u975e\u5e38\u91cd\u8981!)\n"
    "- \u804a\u5929\u6d88\u606f\u957f\u6309 -> \u5f39\u51fa\u8f6c\u53d1/\u590d\u5236/\u5220\u9664/\u64a4\u56de\u83dc\u5355\n"
    "- \u5217\u8868\u9879\u957f\u6309 -> \u5f39\u51fa\u7f16\u8f91/\u5220\u9664/\u7f6e\u9876\u83dc\u5355\n"
    "- \u56fe\u7247/\u6587\u4ef6\u957f\u6309 -> \u4fdd\u5b58/\u5206\u4eab/\u8f6c\u53d1\u9009\u9879\n"
    "- \u684c\u9762\u56fe\u6807\u957f\u6309 -> \u5378\u8f7d/\u5e94\u7528\u4fe1\u606f/\u5c0f\u7ec4\u4ef6\n"
    "- \u82e5\u666e\u901a\u70b9\u51fb\u65e0\u53cd\u5e94,\u5c1d\u8bd5\u957f\u6309\n"
    "\n"
    "## \u8f93\u5165\u6587\u5b57\u7684\u6b63\u786e\u6d41\u7a0b\n"
    "1. \u5148 Tap \u70b9\u51fb\u8f93\u5165\u6846(\u83b7\u53d6\u7126\u70b9,\u5f39\u51fa\u952e\u76d8)\n"
    "2. \u82e5\u8f93\u5165\u6846\u6709\u65e7\u6587\u672c -> \u5148 Clear \u6e05\u7a7a\n"
    "3. \u518d Type \u8f93\u5165\u65b0\u6587\u5b57\n"
    "4. \u82e5\u9700\u786e\u8ba4\u641c\u7d22 -> Type \u540e\u518d Tap \u641c\u7d22\u6309\u94ae\n"
    "\n"
    "## \u6ed1\u52a8\u624b\u52bf\u53c2\u8003\n"
    "- \u4e0a\u6ed1\u7ffb\u9875: start=[500,750], end=[500,250]\n"
    "- \u4e0b\u6ed1\u7ffb\u9875: start=[500,250], end=[500,750]\n"
    "- \u5de6\u6ed1(\u4e0b\u4e00\u9875/\u5220\u9664): start=[800,500], end=[200,500]\n"
    "- \u53f3\u6ed1(\u8fd4\u56de/\u4e0a\u4e00\u9875): start=[200,500], end=[800,500]\n"
    "- \u4e0b\u62c9\u5237\u65b0: start=[500,200], end=[500,600]\n"
    "\n"
    "## \u5f39\u7a97\u4e0e\u5bf9\u8bdd\u6846\u5904\u7406\n"
    "- \u6743\u9650\u5f39\u7a97: \u627e\"\u5141\u8bb8\"/\"\u59cb\u7ec8\u5141\u8bb8\"\u6309\u94ae\u70b9\u51fb\n"
    "- \u786e\u8ba4\u5bf9\u8bdd\u6846: \"\u786e\u5b9a\"\u5728\u53f3\u4fa7,\"\u53d6\u6d88\"\u5728\u5de6\u4fa7\n"
    "- Toast\u63d0\u793a: \u65e0\u9700\u64cd\u4f5c,\u4f1a\u81ea\u52a8\u6d88\u5931\n"
    "- \u5e7f\u544a\u5f39\u7a97: \u627e\u5173\u95ed\u6309\u94ae(\u901a\u5e38\u53f3\u4e0a\u89d2X)\n"
    "\n"
    "## \u5e38\u7528Shell\u547d\u4ee4\n"
    "- \u542f\u52a8Activity: am start -n \u5305\u540d/Activity\u540d\n"
    "- \u6253\u5f00URL: am start -a android.intent.action.VIEW -d \"URL\"\n"
    "- \u67e5\u770b\u5f53\u524d\u754c\u9762: dumpsys activity top | head -5\n"
    "\n"
    "## \u6d4f\u89c8\u5668\u4e0e\u7f51\u9875\u64cd\u4f5c(\u91cd\u8981!)\n"
    "- \u6253\u5f00\u7f51\u9875\u4f18\u5148\u7528 Open_url(\u76f4\u63a5\u8df3\u8f6c,\u6bd4\u624b\u52a8\u8f93\u5165URL\u66f4\u53ef\u9760)\n"
    "- \u767e\u5ea6\u641c\u7d22: Open_url url=\"https://www.baidu.com/s?wd=\u5173\u952e\u8bcd\"\n"
    "- \u7f51\u9875\u4e2d\u4fdd\u5b58\u56fe\u7247: \u957f\u6309\u56fe\u7247 -> \u70b9\u51fb\"\u4fdd\u5b58\u56fe\u7247\"\n"
    "\n"
    "## \u6548\u7387\u539f\u5219\n"
    "- \u4f18\u5148\u4f7f\u7528\u63a7\u4ef6\u5750\u6807(\u66f4\u7cbe\u51c6),\u622a\u56fe\u5750\u6807\u4e3a\u5907\u9009\n"
    "- \u9875\u9762\u8df3\u8f6c/\u52a0\u8f7d\u540e\u7528 Wait \u7b49\u5f851-3\u79d2\n"
    "- \u8fde\u7eed\u5931\u8d25\u8bf4\u660e\u7b56\u7565\u6709\u8bef,\u6362\u4e00\u79cd\u64cd\u4f5c\u65b9\u5f0f\n"
    "- \u64cd\u4f5c\u76ee\u6807\u4e0d\u5728\u5f53\u524d\u5c4f\u5e55 -> \u5148\u6ed1\u52a8\u67e5\u627e\n"
    "\n"
    "## \u9632\u5361\u6b7b\u89c4\u5219(\u5fc5\u987b\u9075\u5b88!)\n"
    "- \u7981\u6b62\u8fde\u7eed\u6267\u884c2\u6b21\u4ee5\u4e0aWait! \u7b49\u5f85\u540e\u5982\u679c\u754c\u9762\u6ca1\u53d8\u5316,\u5fc5\u987b\u91c7\u53d6\u65b0\u64cd\u4f5c\n"
    "- \u5982\u679c\u754c\u9762\u4e0d\u662f\u9884\u671f\u7684(\u5982\u89c6\u9891/\u5e7f\u544a/\u65e0\u5173\u9875\u9762) -> \u7acb\u5373 Back \u8fd4\u56de\u6216\u7528 Open_url \u91cd\u65b0\u5bfc\u822a\n"
    "- \u540c\u4e00\u64cd\u4f5c\u5931\u8d252\u6b21 -> \u5fc5\u987b\u66f4\u6362\u7b56\u7565(\u6362\u5750\u6807/\u6362\u65b9\u5f0f/\u6362\u8def\u5f84)\n"
    "- \u611f\u89c9\u5361\u4f4f\u4e86 -> \u8bd5\u8bd5 Back\u3001Home\u3001\u6216 Open_url \u91cd\u65b0\u5f00\u59cb\n"
    "\n"
    "## \u4fe1\u606f\u67e5\u8be2\u7c7b\u4efb\u52a1(\u975e\u5e38\u91cd\u8981!)\n"
    "- \u4efb\u52a1\u662f\"\u67e5\u8be2/\u5217\u51fa/\u83b7\u53d6\u8bbe\u5907\u4fe1\u606f\"\u65f6 -> \u76f4\u63a5\u7528 Shell \u547d\u4ee4\u83b7\u53d6,\u8bfb\u53d6\u8f93\u51fa\u540e finish\n"
    "- \u67e5\u8be2\u5df2\u5b89\u88c5\u5e94\u7528: Shell cmd=\"pm list packages -3\"(\u7b2c\u4e09\u65b9) \u6216 \"pm list packages\"(\u5168\u90e8)\n"
    "- \u67e5\u8be2\u5185\u5b58/\u5b58\u50a8/\u7535\u6c60\u7b49: Shell cmd=\"cat /proc/meminfo\" / \"df -h\" / \"dumpsys battery\"\n"
    "- \u7981\u6b62\u5c06\u4fe1\u606f\u67e5\u8be2\u4efb\u52a1\u8f6c\u4e3a\"\u5728\u754c\u9762\u4e0a\u8f93\u5165\u95ee\u9898\"\u7684\u64cd\u4f5c! \u4e0d\u8981\u64cd\u4f5c\u4efb\u4f55\u804a\u5929\u6846/\u8f93\u5165\u6846!\n"
    "- Shell \u6267\u884c\u540e\u7cfb\u7edf\u4f1a\u628a\u8f93\u51fa\u7ed3\u679c\u6ce8\u5165\u4e0a\u4e0b\u6587,\u4f60\u53ef\u4ee5\u7acb\u5373\u6839\u636e\u8f93\u51fa\u5185\u5bb9 finish \u7ed9\u51fa\u7b54\u6848\n"
    "- Wait duration \u5fc5\u987b\u662f\u7eaf\u6570\u5b57,\u4f8b\u5982 do(action=\"Wait\", duration=\"3\") \u800c\u4e0d\u662f \"3\u79d2\"\n"
)

_AUTOGLM_SUFFIX = (
    "# \u89c4\u5219\n"
    "- \u6bcf\u6b21\u53ea\u8f93\u51fa\u4e00\u884c\u64cd\u4f5c\u4ee3\u7801\n"
    "- \u5148\u7b80\u77ed\u5206\u6790,\u518d\u7ed9\u51fa\u64cd\u4f5c\n"
    "- \u6709\u63a7\u4ef6\u5217\u8868\u65f6\u4f18\u5148\u7528\u63a7\u4ef6\u5750\u6807\n"
    "- \u4efb\u52a1\u5b8c\u6210\u6216\u65e0\u6cd5\u7ee7\u7eed\u65f6 finish"
)

_GENERIC_PREFIX = (
    "# \u8f93\u51fa\u683c\u5f0f(\u4e25\u683c\u9075\u5b88)\n"
    "<think>\n"
    "\u7b80\u8981\u5206\u6790\u5f53\u524d\u5c4f\u5e55\u72b6\u6001\u548c\u4e0b\u4e00\u6b65\u64cd\u4f5c(2-3\u53e5)\n"
    "</think>\n"
    "<answer>\n"
    "\u4e00\u884c\u64cd\u4f5c\u4ee3\u7801\n"
    "</answer>\n"
)

_GENERIC_SUFFIX = (
    "# \u89c4\u5219\n"
    "1. <answer>\u4e2d\u53ea\u653e\u4e00\u884c\u64cd\u4f5c\u4ee3\u7801\n"
    "2. <think>\u4e2d\u7b80\u8981\u5206\u6790(\u4e0d\u8981\u5197\u957f)\n"
    "3. \u6709\u63a7\u4ef6\u5217\u8868\u65f6\u4f18\u5148\u7528\u63a7\u4ef6\u5750\u6807\n"
    "4. \u4efb\u52a1\u5b8c\u6210\u6216\u65e0\u6cd5\u7ee7\u7eed\u65f6 finish"
)

AUTOGLM_SYSTEM = ""
GENERIC_VLM_SYSTEM = ""


def build_system_prompt(is_autoglm: bool = True) -> str:
    """Build system prompt."""
    date_str = datetime.now().strftime("%Y-%m-%d, %A")
    base = (
        "\u5f53\u524d\u65e5\u671f: " + date_str + "\n\n"
        "\u4f60\u662f\u4e13\u4e1a\u7684\u5b89\u5353\u624b\u673a\u81ea\u52a8\u5316\u64cd\u4f5c\u52a9\u624b\u3002"
        "\u5206\u6790\u622a\u56fe\u548c\u754c\u9762\u4fe1\u606f,\u6bcf\u6b65\u8f93\u51fa\u4e00\u884c\u64cd\u4f5c\u4ee3\u7801\u5b8c\u6210\u4efb\u52a1\u3002\n\n"
    )
    if is_autoglm:
        return base + _ACTION_GUIDE + "\n" + _AUTOGLM_SUFFIX
    else:
        return base + _GENERIC_PREFIX + "\n" + _ACTION_GUIDE + "\n" + _GENERIC_SUFFIX


def build_user_prompt(
    instruction: str,
    step: int,
    max_steps: int,
    foreground_app: str = "",
    foreground_activity: str = "",
    ui_elements: str = "",
    ocr_texts: str = "",
    history_summary: str = "",
    skill_context: str = "",
) -> str:
    """Build per-step user prompt."""
    parts = []
    parts.append("\u4efb\u52a1: " + instruction + " [" + str(step) + "/" + str(max_steps) + "]")

    if foreground_app or foreground_activity:
        app_str = foreground_app or ""
        if foreground_activity:
            short = foreground_activity.rsplit(".", 1)[-1] if "." in foreground_activity else foreground_activity
            app_str += "/" + short if app_str else short
        parts.append("\u754c\u9762: " + app_str)

    if skill_context:
        parts.append("\n[\u64cd\u4f5c\u6307\u5f15]\n" + skill_context)

    if ui_elements:
        parts.append("\n[\u63a7\u4ef6]\n" + ui_elements)

    if ocr_texts:
        parts.append("\n[OCR]\n" + ocr_texts)

    if history_summary:
        parts.append("\n[\u5386\u53f2]\n" + history_summary)

    parts.append("\n[\u89c1\u622a\u56fe]")
    return "\n".join(parts)


def build_plan_prompt(instruction: str) -> str:
    """Build task planning prompt."""
    return (
        "\u8bf7\u4e3a\u4ee5\u4e0b\u624b\u673a\u64cd\u4f5c\u4efb\u52a1\u751f\u6210\u4e00\u4e2a\u7b80\u6d01\u7684\u6267\u884c\u8ba1\u5212,"
        "\u7528\u4e2d\u6587\u4ee5\u7f16\u53f7\u5217\u8868\u7684\u5f62\u5f0f\u8f93\u51fa3-5\u4e2a\u6b65\u9aa4\u3002\n\n"
        "\u4efb\u52a1: " + instruction + "\n\n"
        "\u8981\u6c42:\n"
        "- \u6bcf\u6b65\u4e00\u53e5\u8bdd,\u7b80\u77ed\u5177\u4f53\n"
        "- \u6700\u540e\u4e00\u6b65\u8bf4\u660e\u9884\u671f\u7ed3\u679c\n"
        "- \u4e0d\u8981\u8f93\u51fa\u4efb\u4f55\u989d\u5916\u89e3\u91ca,\u53ea\u8f93\u51fa\u7f16\u53f7\u6b65\u9aa4"
    )


def build_history_summary(steps: list, max_recent: int = 5) -> str:
    """Build history summary string."""
    if not steps:
        return ""
    recent = steps[-max_recent:]
    lines = []
    for s in recent:
        icon = "v" if s.get("success", False) else "x"
        desc = s.get("action_desc", "")
        lines.append(str(s.get("step", "?")) + ". [" + icon + "] " + desc)
    return "\n".join(lines)
