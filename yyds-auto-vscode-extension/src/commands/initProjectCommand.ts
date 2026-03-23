/**
 * 新建 Yyds.Auto 脚本工程命令
 * 在指定目录生成标准项目脚手架，支持多模板选择
 */

import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { log } from '../engine/logger';

interface ProjectTemplate {
    id: string;
    label: string;
    description: string;
    detail: string;
    mainPyContent: (name: string) => string;
    uiYmlContent: (name: string) => string;
    requirementsTxtContent: string;
    extraDirs?: string[];
}

const TEMPLATES: ProjectTemplate[] = [
    {
        id: 'empty',
        label: '$(file) 空白工程',
        description: '最小起步，只有入口函数',
        detail: '适合有经验的开发者，从零开始编写自动化逻辑',
        requirementsTxtContent: 'yyds-auto\nrequests\npyyaml\npillow\n',
        mainPyContent: (name) => `"""
Yyds.Auto 脚本工程 — ${name}
官方文档: https://yydsxx.com/docs/yyds-auto/script
"""
import os
import yyds_auto

# 区分两套运行方式
if os.path.exists("/sdcard"):
    # 安卓侧运行: 直连本地引擎
    d = yyds_auto.connect("127.0.0.1:61140")
else:
    # PC 侧运行: 连接手机
    d = yyds_auto.connect()

def main():
    print("脚本启动!")

    # === 在此编写你的自动化逻辑 ===

    print("脚本执行完毕!")

if __name__ == "__main__":
    main()
`,
        uiYmlContent: (name) => `text-notice:
  value: "${name}"
  color: "#008305"
  size: 16

div-1:

check-enable:
  title: "启用功能"
  value: true
`,
    },
    {
        id: 'quickstart',
        label: '$(rocket) 快速入门',
        description: '演示截图、点击、滑动、OCR 等核心 API',
        detail: '推荐新手选择，包含常用 API 示例代码和详细注释',
        requirementsTxtContent: 'yyds-auto\nrequests\npyyaml\npillow\n',
        mainPyContent: (name) => `"""
Yyds.Auto 脚本工程 — ${name}
官方文档: https://yydsxx.com/docs/yyds-auto/script

快速入门模板 — 演示截图、点击、滑动、OCR 等核心 API
"""
import os
import time
import yyds_auto

# 区分两套运行方式
if os.path.exists("/sdcard"):
    # 安卓侧运行: 直连本地引擎
    d = yyds_auto.connect("127.0.0.1:61140")
else:
    # PC 侧运行: 连接手机
    d = yyds_auto.connect()

def main():
    print("脚本启动!")

    # --- 截图 ---
    img = d.screenshot()
    print(f"截图尺寸: {img.size}")

    # --- 点击 (基于 1080x2400 参考分辨率) ---
    # TODO: 修改为你需要点击的坐标
    d.click(540, 1200)
    time.sleep(1)

    # --- 长按 ---
    # d.long_click(540, 1200)

    # --- 滑动 ---
    d.swipe_ext("up")       # 上滑
    time.sleep(0.5)

    # --- OCR 文字识别 ---
    results = d.ocr()
    for item in results:
        center = item.center
        print(f"识别到: {item.text} @ ({center[0]}, {center[1]})")

    # --- 查找并点击文字 ---
    if d.ocr_click("设置", timeout=2):
        print("已点击「设置」")
    else:
        print("未找到「设置」")
    time.sleep(1)

    print("脚本执行完毕!")

if __name__ == "__main__":
    main()
`,
        uiYmlContent: (name) => `text-notice:
  value: "${name} — 快速入门"
  color: "#008305"
  size: 16

div-1:

check-enable:
  title: "启用功能"
  value: true
`,
    },
    {
        id: 'app_auto',
        label: '$(device-mobile) App自动化',
        description: '打开App → 查找控件 → 交互 → 退出',
        detail: '适合自动化操作特定 App，含 retry 装饰器和控件查找示例',
        requirementsTxtContent: 'yyds-auto\nrequests\npyyaml\npillow\n',
        mainPyContent: (name) => `"""
Yyds.Auto 脚本工程 — ${name}
官方文档: https://yydsxx.com/docs/yyds-auto/script

App自动化模板 — 打开App → 查找控件 → 交互 → 退出
"""
import os
import time
import functools
import yyds_auto

# 区分两套运行方式
if os.path.exists("/sdcard"):
    d = yyds_auto.connect("127.0.0.1:61140")
else:
    d = yyds_auto.connect()


def retry(max_retries=3, delay=1):
    """重试装饰器: 操作失败时自动重试"""
    def decorator(func):
        @functools.wraps(func)
        def wrapper(*args, **kwargs):
            for attempt in range(1, max_retries + 1):
                try:
                    return func(*args, **kwargs)
                except Exception as e:
                    print(f"[重试 {attempt}/{max_retries}] {func.__name__} 失败: {e}")
                    if attempt < max_retries:
                        time.sleep(delay)
                    else:
                        raise
        return wrapper
    return decorator


# TODO: 修改为目标 App 的包名
APP_PACKAGE = "com.example.app"


def open_app():
    """启动目标 App"""
    d.app_start(APP_PACKAGE)
    print(f"正在启动 {APP_PACKAGE} ...")
    time.sleep(3)


@retry(max_retries=3, delay=2)
def do_task():
    """执行自动化任务"""
    # TODO: 根据实际 App 修改以下控件查找和操作逻辑

    # 方式1: 通过文字查找并点击
    if d.ocr_click("登录"):
        print("已点击「登录」按钮")
        time.sleep(1)

    # 方式2: 通过控件 ID 查找
    # if d(resourceId="com.example.app:id/btn_login").exists:
    #     d(resourceId="com.example.app:id/btn_login").click()

    # 方式3: 通过坐标点击 (基于 1080x2400)
    # d.click(540, 1800)

    print("任务执行完成")


def close_app():
    """关闭目标 App"""
    d.app_stop(APP_PACKAGE)
    print(f"已关闭 {APP_PACKAGE}")


def main():
    print("脚本启动!")

    try:
        open_app()
        do_task()
    except Exception as e:
        print(f"执行出错: {e}")
    finally:
        close_app()

    print("脚本执行完毕!")


if __name__ == "__main__":
    main()
`,
        uiYmlContent: (name) => `text-notice:
  value: "${name} — App自动化"
  color: "#008305"
  size: 16

div-1:

edit-package:
  title: "目标App包名"
  hint: "com.example.app"
  input: text

div-2:

check-auto-close:
  title: "执行完毕后关闭App"
  value: true
`,
    },
    {
        id: 'ocr',
        label: '$(eye) OCR文字识别',
        description: '文字识别 + 等待文字 + 滑动查找文字',
        detail: '适合需要大量文字识别和基于文字定位的自动化场景',
        requirementsTxtContent: 'yyds-auto\nrequests\npyyaml\npillow\n',
        mainPyContent: (name) => `"""
Yyds.Auto 脚本工程 — ${name}
官方文档: https://yydsxx.com/docs/yyds-auto/script

OCR文字识别模板 — 文字识别 + 等待文字 + 滑动查找文字
"""
import os
import time
import yyds_auto

if os.path.exists("/sdcard"):
    d = yyds_auto.connect("127.0.0.1:61140")
else:
    d = yyds_auto.connect()


def ocr_full_screen():
    """全屏 OCR 识别并打印结果"""
    results = d.ocr()
    print(f"共识别到 {len(results)} 条文字:")
    for item in results:
        center = item.center
        print(f"  [{center[0]},{center[1]}] {item.text}")
    return results


def find_and_click_text(target, timeout=10):
    """等待目标文字出现并点击"""
    print(f"等待文字「{target}」出现 (超时 {timeout}s) ...")
    if d.ocr_wait(target, timeout=timeout):
        d.ocr_click(target)
        print(f"已点击「{target}」")
        return True
    else:
        print(f"超时未找到「{target}」")
        return False


def scroll_find_text(target, max_scrolls=5):
    """向下滑动查找文字，找到后点击"""
    for i in range(max_scrolls):
        if d.ocr_click(target):
            print(f"第 {i+1} 屏找到并点击「{target}」")
            return True
        print(f"第 {i+1} 屏未找到「{target}」，继续下滑...")
        d.swipe_ext("up")
        time.sleep(1)
    print(f"滑动 {max_scrolls} 屏仍未找到「{target}」")
    return False


def main():
    print("脚本启动!")

    # --- 全屏 OCR ---
    ocr_full_screen()
    time.sleep(1)

    # --- 等待并点击文字 ---
    # TODO: 修改为你需要查找的文字
    find_and_click_text("设置")
    time.sleep(1)

    # --- 滑动查找文字 ---
    # TODO: 修改为你需要滑动查找的文字
    # scroll_find_text("关于手机")

    print("脚本执行完毕!")


if __name__ == "__main__":
    main()
`,
        uiYmlContent: (name) => `text-notice:
  value: "${name} — OCR文字识别"
  color: "#008305"
  size: 16

div-1:

edit-keyword:
  title: "目标关键词"
  hint: "输入要查找的文字"
  input: text

div-2:

select-ocr-mode:
  title: "识别模式"
  options:
    - "全屏识别"
    - "区域识别"
  value: "全屏识别"
`,
    },
    {
        id: 'image',
        label: '$(file-media) 图像识别',
        description: '模板匹配 + YOLO检测 + 屏幕变化检测',
        detail: '适合基于图像定位的自动化，含模板匹配和 YOLO 目标检测示例',
        requirementsTxtContent: 'yyds-auto\nrequests\npyyaml\npillow\nnumpy\n',
        extraDirs: ['models'],
        mainPyContent: (name) => `"""
Yyds.Auto 脚本工程 — ${name}
官方文档: https://yydsxx.com/docs/yyds-auto/script

图像识别模板 — 模板匹配 + YOLO检测 + 屏幕变化检测
"""
import os
import time
import yyds_auto

if os.path.exists("/sdcard"):
    d = yyds_auto.connect("127.0.0.1:61140")
else:
    d = yyds_auto.connect()


def template_match_demo():
    """模板匹配: 在屏幕中查找小图并点击"""
    # TODO: 将目标截图放入 img/ 目录，修改文件名
    img_path = "img/target.png"
    result = d.find_image(img_path)
    if result:
        print(f"模板匹配成功: {result}")
        d.click(*result)
        time.sleep(1)
    else:
        print("模板匹配: 未找到目标图像")


def screen_change_demo():
    """屏幕变化检测: 等待画面发生变化"""
    print("等待屏幕变化...")
    img_before = d.screenshot()
    # TODO: 修改等待时间
    time.sleep(3)
    img_after = d.screenshot()

    # 简单比较: 截图前后是否有变化
    if img_before.tobytes() != img_after.tobytes():
        print("检测到屏幕变化!")
    else:
        print("屏幕无变化")


def main():
    print("脚本启动!")

    # --- 模板匹配 ---
    template_match_demo()

    # --- 屏幕变化检测 ---
    # screen_change_demo()

    print("脚本执行完毕!")


if __name__ == "__main__":
    main()
`,
        uiYmlContent: (name) => `text-notice:
  value: "${name} — 图像识别"
  color: "#008305"
  size: 16

div-1:

select-match-mode:
  title: "识别方式"
  options:
    - "模板匹配"
  value: "模板匹配"

div-2:

edit-threshold:
  title: "匹配阈值"
  hint: "0.8"
  input: number
`,
    },
];

/** 注册 yyds.initProject 命令 */
export function registerInitProjectCommand(context: vscode.ExtensionContext): void {
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.initProject', () => initProject(context))
    );
}

async function initProject(context: vscode.ExtensionContext): Promise<void> {
    // 1. 选择目标目录
    const targetUri = await vscode.window.showOpenDialog({
        canSelectFiles: false,
        canSelectFolders: true,
        canSelectMany: false,
        openLabel: '选择工程目录',
        title: 'Yyds.Auto — 选择新建工程的父目录',
    });

    if (!targetUri || targetUri.length === 0) { return; }
    const parentDir = targetUri[0].fsPath;

    // 2. 输入工程名称
    const projectName = await vscode.window.showInputBox({
        prompt: '请输入工程名称 (支持中文英文数字)',
        placeHolder: '如: 我的脚本',
        validateInput: (value) => {
            if (!value || value.trim().length === 0) {
                return '工程名称不能为空';
            }
            if (/[\\/:*?"<>|]/.test(value)) {
                return '工程名称不能包含特殊字符 \\ / : * ? " < > |';
            }
            return undefined;
        },
    });

    if (!projectName) { return; }

    // 3. 选择项目模板
    const picked = await vscode.window.showQuickPick(
        TEMPLATES.map(t => ({
            label: t.label,
            description: t.description,
            detail: t.detail,
            templateId: t.id,
        })),
        {
            placeHolder: '选择项目模板',
            title: 'Yyds.Auto — 选择项目模板',
            matchOnDescription: true,
            matchOnDetail: true,
        },
    );

    if (!picked) { return; }
    const template = TEMPLATES.find(t => t.id === picked.templateId)!;

    // 4. 输入设备 IP
    const deviceIp = await vscode.window.showInputBox({
        prompt: '请输入调试设备 IP (在 Yyds.Auto App 中查看)',
        placeHolder: '如: 192.168.1.100 (USB 连接填 127.0.0.1)',
        value: '192.168.1.2',
        validateInput: (value) => {
            if (!value || value.trim().length === 0) {
                return '设备 IP 不能为空';
            }
            return undefined;
        },
    });

    if (!deviceIp) { return; }

    const projectDir = path.join(parentDir, projectName);

    if (fs.existsSync(projectDir)) {
        const overwrite = await vscode.window.showWarningMessage(
            `目录 "${projectName}" 已存在, 是否覆盖生成项目文件?`,
            { modal: true },
            '覆盖'
        );
        if (overwrite !== '覆盖') { return; }
    }

    try {
        generateProject(projectDir, projectName, deviceIp, template, context);
        log('InitProject', `工程已创建: ${projectDir} (模板: ${template.id})`);

        const hasWorkspace = vscode.workspace.workspaceFolders && vscode.workspace.workspaceFolders.length > 0;
        await vscode.commands.executeCommand('vscode.openFolder', vscode.Uri.file(projectDir), hasWorkspace);
    } catch (e) {
        vscode.window.showErrorMessage(`创建工程失败: ${e}`);
    }
}

function generateProject(
    projectDir: string,
    projectName: string,
    deviceIp: string,
    template: ProjectTemplate,
    context: vscode.ExtensionContext,
): void {
    // 创建目录结构
    mkdirSafe(projectDir);
    mkdirSafe(path.join(projectDir, 'img'));
    mkdirSafe(path.join(projectDir, 'yyds'));

    // 创建模板额外目录
    if (template.extraDirs) {
        for (const dir of template.extraDirs) {
            mkdirSafe(path.join(projectDir, dir));
        }
    }

    // === project.config ===
    writeFile(path.join(projectDir, 'project.config'), `[default]
# Yyds.Auto 工程配置文件 — 此文件为工程必需文件
# 格式: INI, '#' 开头为注释

# [必填] 工程名称 (支持中文英文数字, 勿加特殊符号)
PROJECT_NAME=${projectName}

# [必填] 工程版本号
PROJECT_VERSION=1.0

# [必填] 调试设备 IP 地址 (在 Yyds.Auto App 中查看)
# USB 连接请先执行: adb forward tcp:61140 tcp:61140, 然后填写 127.0.0.1
DEBUG_DEVICE_IP=${deviceIp}

# [选填] 打包时文件名是否包含版本号
PACK_KEY_WITH_VERSION=false
`);

    // === main.py ===
    writeFile(path.join(projectDir, 'main.py'), template.mainPyContent(projectName));

    // === ui.yml ===
    writeFile(path.join(projectDir, 'ui.yml'), template.uiYmlContent(projectName));

    // === requirements.txt ===
    writeFile(path.join(projectDir, 'requirements.txt'), template.requirementsTxtContent);

}

function mkdirSafe(dir: string): void {
    if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
    }
}

function writeFile(filePath: string, content: string): void {
    const dir = path.dirname(filePath);
    mkdirSafe(dir);
    fs.writeFileSync(filePath, content, 'utf-8');
}
