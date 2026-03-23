"""
推送工程到手机并运行, 收集日志
"""
import requests
import time
import sys

BASE = "http://127.0.0.1:61140"
PROJECT_NAME = "我的脚本"

def api(path, **kwargs):
    try:
        r = requests.get(f"{BASE}{path}", timeout=10, **kwargs)
        return r.text
    except Exception as e:
        return f"ERROR: {e}"

def api_post(path, data=None, **kwargs):
    try:
        r = requests.post(f"{BASE}{path}", json=data or {}, timeout=10, **kwargs)
        return r.text
    except Exception as e:
        return f"ERROR: {e}"

print("=" * 60)
print("Yyds.Auto 设备运行诊断")
print("=" * 60)

# 1. 检查引擎
print("\n[1] 引擎 Ping:", api_post("/api/ping"))

# 2. 项目列表
print("[2] 项目列表:", api("/project/list"))

# 3. 项目状态
print("[3] 项目状态:", api("/project/status"))

# 4. 启动项目
print(f"\n[4] 启动项目: {PROJECT_NAME}")
result = api(f"/project/start?name={PROJECT_NAME}")
print(f"    启动响应: {result}")

# 5. 等待执行
print("\n[5] 等待脚本执行 (5秒)...")
for i in range(5):
    time.sleep(1)
    status = api("/project/status")
    print(f"    {i+1}s 状态: {status}")

# 6. 收集日志 (通过 WebSocket 或 logcat)
print("\n[6] 收集 logcat 日志...")
import subprocess
p = subprocess.run(
    ["adb", "shell", "logcat", "-d", "-t", "200"],
    capture_output=True, text=True, timeout=10
)
lines = p.stdout.split("\n")
# 过滤 Python 引擎相关日志
keywords = ["PyProcess", "PyOut", "python", "Traceback", "Error", "Exception", 
            "ImportError", "ModuleNotFound", "main()", "脚本", "YYDS_PY",
            "chaquopy", "已启动", "已停止", "运行", "start", "stop"]
relevant = [l for l in lines if any(k.lower() in l.lower() for k in keywords)]
if relevant:
    print(f"    找到 {len(relevant)} 条相关日志:")
    for l in relevant[-40:]:
        print(f"    {l.strip()}")
else:
    print("    未找到 Python 引擎相关日志")
    # 打印最近的 YYDS 日志
    yyds_lines = [l for l in lines if "YYDS" in l or "yyds" in l.lower()]
    print(f"\n    YYDS 相关日志 ({len(yyds_lines)} 条, 显示最后 30 条):")
    for l in yyds_lines[-30:]:
        print(f"    {l.strip()}")

print("\n" + "=" * 60)
print("诊断完成")
