/**
 * Yyds.Auto VS Code 插件主入口
 * 激活条件: 工作区包含 project.config 文件
 */

import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { ProjectServer } from './engine/projectServer';
import { registerCommands } from './commands/projectCommands';
import { registerInitProjectCommand } from './commands/initProjectCommand';
import { StatusBarManager } from './statusBar';
import { LogViewProvider } from './views/logView';
import { DevToolPanel } from './views/devToolView';
import { UiDesignerPanel } from './views/uiDesignerView';
import { DeviceViewProvider, ActionsViewProvider } from './views/sidebarViews';
import { initLogger, log } from './engine/logger';
import { YydsCompletionProvider } from './language/completionProvider';
import { YydsHoverProvider } from './language/hoverProvider';
import { YydsSignatureHelpProvider } from './language/signatureHelpProvider';
import { AdbManager } from './engine/adbManager';
import { EngineActivator } from './engine/engineActivator';

let projectServer: ProjectServer | undefined;
let statusBarManager: StatusBarManager | undefined;
let logViewProvider: LogViewProvider | undefined;
let deviceViewProvider: DeviceViewProvider | undefined;

export function activate(context: vscode.ExtensionContext) {
    // 初始化调试日志 OutputChannel
    const debugChannel = initLogger();
    context.subscriptions.push(debugChannel);
    log('Extension', '插件已激活');

    // 查找 project.config 文件确定项目路径
    const projectPath = findProjectPath();

    // ========================================
    // 初始化状态栏（底部）
    // ========================================
    statusBarManager = new StatusBarManager();
    context.subscriptions.push({ dispose: () => statusBarManager?.dispose() });

    // ========================================
    // 注册左侧栏视图
    // ========================================
    // 设备连接视图
    deviceViewProvider = new DeviceViewProvider();
    context.subscriptions.push(
        vscode.window.registerTreeDataProvider('yyds.deviceView', deviceViewProvider)
    );

    // 操作视图
    const actionsViewProvider = new ActionsViewProvider();
    context.subscriptions.push(
        vscode.window.registerTreeDataProvider('yyds.actionsView', actionsViewProvider)
    );

    // 日志面板 WebView
    logViewProvider = new LogViewProvider(context.extensionUri);
    context.subscriptions.push(
        vscode.window.registerWebviewViewProvider(LogViewProvider.viewType, logViewProvider, {
            webviewOptions: { retainContextWhenHidden: true },
        })
    );

    // ========================================
    // 如果找到项目路径，初始化服务
    // ========================================
    if (projectPath) {
        initializeProject(context, projectPath);
    }

    // ========================================
    // 注册命令
    // ========================================
    registerCommands(context, () => projectServer);
    registerInitProjectCommand(context);

    // ========================================
    // 注册语言特性 (Python 智能提示)
    // ========================================
    const pythonSelector: vscode.DocumentSelector = { language: 'python', scheme: 'file' };

    context.subscriptions.push(
        vscode.languages.registerCompletionItemProvider(
            pythonSelector,
            new YydsCompletionProvider(),
            '.', '_'  // 触发字符: '.' 用于类方法, '_' 用于下划线命名函数
        )
    );

    context.subscriptions.push(
        vscode.languages.registerHoverProvider(
            pythonSelector,
            new YydsHoverProvider()
        )
    );

    context.subscriptions.push(
        vscode.languages.registerSignatureHelpProvider(
            pythonSelector,
            new YydsSignatureHelpProvider(),
            '(', ','  // 触发字符: '(' 开始参数, ',' 下一个参数
        )
    );

    // 连接设备命令 — 弹出 IP 输入对话框
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.connectDevice', async () => {
            // 获取当前 IP 作为默认值（优先级: 运行时 > globalState > project.config）
            let currentIp = '';
            if (projectServer) {
                currentIp = projectServer.getConnector().getDeviceIp();
            }
            if (!currentIp || currentIp === '192.168.1.2') {
                const savedIp = context.globalState.get<string>('lastDeviceIp');
                if (savedIp) { currentIp = savedIp; }
            }
            if (!currentIp) {
                // 尝试从 project.config 读取
                try {
                    const pp = findProjectPath();
                    if (pp) {
                        const configPath = path.join(pp, 'project.config');
                        const content = fs.readFileSync(configPath, 'utf-8');
                        const match = content.match(/DEBUG_DEVICE_IP\s*=\s*(.+)/);
                        if (match) { currentIp = match[1].trim(); }
                    }
                } catch {}
            }

            // 弹出输入框
            const ip = await vscode.window.showInputBox({
                title: 'Yyds.Auto — 连接设备',
                prompt: '请输入安卓设备的IP地址',
                value: currentIp,
                placeHolder: '例如: 192.168.1.2',
                validateInput: (value) => {
                    const trimmed = value.trim();
                    if (!trimmed) {
                        return '请输入IP地址';
                    }
                    // 简单的 IP 格式校验
                    const ipRegex = /^(\d{1,3}\.){3}\d{1,3}$/;
                    if (!ipRegex.test(trimmed)) {
                        return '请输入有效的IP地址，例如 192.168.1.2';
                    }
                    return undefined;
                },
            });

            if (!ip) { return; } // 用户取消

            const trimmedIp = ip.trim();

            // 如果 projectServer 尚未初始化，先初始化
            if (!projectServer) {
                const pp = findProjectPath();
                if (pp) {
                    initializeProject(context, pp);
                } else {
                    // 没有 project.config 也允许连接（创建临时 server）
                    const workspaceFolder = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
                    if (workspaceFolder) {
                        try {
                            projectServer = new ProjectServer(workspaceFolder);
                            statusBarManager?.setServer(projectServer);
                            logViewProvider?.setServer(projectServer);
                            deviceViewProvider?.setServer(projectServer);
                            context.subscriptions.push({
                                dispose: () => {
                                    projectServer?.dispose();
                                    projectServer = undefined;
                                },
                            });
                        } catch (e) {
                            vscode.window.showErrorMessage(`初始化失败: ${e}`);
                            return;
                        }
                    }
                }
            }

            if (projectServer) {
                projectServer.getConnector().reConnect(trimmedIp);
                deviceViewProvider?.refresh();
                // 同步 DevTool 面板的连接状态
                if (DevToolPanel.currentPanel) {
                    DevToolPanel.currentPanel.setServer(projectServer);
                }
                // 持久化 IP，下次启动 VSCode 自动填入
                context.globalState.update('lastDeviceIp', trimmedIp);
                vscode.window.showInformationMessage(`Yyds.Auto: 正在连接 ${trimmedIp} ...`);
            }
        })
    );

    // 开发助手面板命令
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.showDevTool', () => {
            DevToolPanel.createOrShow(context.extensionUri, projectServer);
        })
    );

    // 日志面板显示命令
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.showLog', () => {
            vscode.commands.executeCommand('yyds.logView.focus');
        })
    );

    // UI 配置设计器命令
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.showUiDesigner', (uri?: vscode.Uri) => {
            UiDesignerPanel.createOrShow(context.extensionUri, uri);
        })
    );

    // 打包APK命令
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.buildApk', () => {
            if (!projectServer) {
                vscode.window.showErrorMessage('请先打开一个Yyds.Auto项目');
                return;
            }
            projectServer.buildApk();
        })
    );

    // ========================================
    // ADB 引擎激活
    // ========================================
    const adbManager = new AdbManager(context);
    const engineActivator = new EngineActivator(adbManager);

    // ADB激活引擎命令
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.activateEngine', async () => {
            const result = await engineActivator.activateEngine();
            if (result.success) {
                vscode.window.showInformationMessage(`Yyds.Auto: ${result.message}`);
                // 激活成功后，如果获取到设备IP，自动连接
                if (result.deviceIp) {
                    ensureServerAndConnect(context, result.deviceIp);
                } else {
                    // USB设备：尝试通过 adb forward 或提示用户输入IP
                    const action = await vscode.window.showInformationMessage(
                        '引擎已激活！如需通过网络连接，请输入设备IP。USB设备可使用 adb forward 端口转发后连接 127.0.0.1。',
                        '输入设备IP',
                        '使用 127.0.0.1（需先 adb forward）'
                    );
                    if (action === '使用 127.0.0.1（需先 adb forward）') {
                        // 执行端口转发
                        await adbManager.runAdb('forward', 'tcp:61140', 'tcp:61140');
                        ensureServerAndConnect(context, '127.0.0.1');
                    } else if (action === '输入设备IP') {
                        vscode.commands.executeCommand('yyds.connectDevice');
                    }
                }
            } else {
                vscode.window.showErrorMessage(`Yyds.Auto: ${result.message}`);
            }
        })
    );

    // 无线配对设备命令
    context.subscriptions.push(
        vscode.commands.registerCommand('yyds.pairDevice', async () => {
            const success = await engineActivator.pairWirelessDevice();
            if (success) {
                // 配对成功后询问是否激活引擎
                const action = await vscode.window.showInformationMessage(
                    '设备配对成功！是否立即激活引擎？',
                    '激活引擎',
                    '稍后'
                );
                if (action === '激活引擎') {
                    vscode.commands.executeCommand('yyds.activateEngine');
                }
            }
        })
    );

    // ========================================
    // 监听配置变化
    // ========================================
    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration((e) => {
            if (e.affectsConfiguration('yyds')) {
                if (projectServer) {
                    const vsIp = vscode.workspace.getConfiguration('yyds').get<string>('deviceIp');
                    if (vsIp && vsIp.trim().length > 0) {
                        projectServer.getConnector().setDeviceIp(vsIp.trim());
                    }
                    const vsPort = vscode.workspace.getConfiguration('yyds').get<number>('port');
                    if (vsPort) {
                        projectServer.getConnector().setPort(vsPort);
                    }
                }
            }
        })
    );

    // 监听工作区变化
    context.subscriptions.push(
        vscode.workspace.onDidChangeWorkspaceFolders(() => {
            if (!projectServer) {
                const newPath = findProjectPath();
                if (newPath) {
                    initializeProject(context, newPath);
                }
            }
        })
    );

    /**
     * 确保 projectServer 已初始化并连接到指定 IP
     */
    function ensureServerAndConnect(ctx: vscode.ExtensionContext, ip: string) {
        if (!projectServer) {
            const pp = findProjectPath();
            if (pp) {
                initializeProject(ctx, pp);
            } else {
                const workspaceFolder = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
                if (workspaceFolder) {
                    try {
                        projectServer = new ProjectServer(workspaceFolder);
                        statusBarManager?.setServer(projectServer);
                        logViewProvider?.setServer(projectServer);
                        deviceViewProvider?.setServer(projectServer);
                        ctx.subscriptions.push({
                            dispose: () => { projectServer?.dispose(); projectServer = undefined; },
                        });
                    } catch (e) {
                        vscode.window.showErrorMessage(`初始化失败: ${e}`);
                        return;
                    }
                }
            }
        }
        if (projectServer) {
            projectServer.getConnector().reConnect(ip);
            deviceViewProvider?.refresh();
            if (DevToolPanel.currentPanel) {
                DevToolPanel.currentPanel.setServer(projectServer);
            }
            ctx.globalState.update('lastDeviceIp', ip);
            vscode.window.showInformationMessage(`Yyds.Auto: 正在连接 ${ip} ...`);
        }
    }
}

function initializeProject(context: vscode.ExtensionContext, projectPath: string) {
    try {
        projectServer = new ProjectServer(projectPath);

        // 用 globalState 中记忆的 IP 覆盖（优先级: VS Code设置 > globalState > project.config > 默认）
        const vsIpCfg = vscode.workspace.getConfiguration('yyds').get<string>('deviceIp');
        if (!vsIpCfg || vsIpCfg.trim().length === 0) {
            const savedIp = context.globalState.get<string>('lastDeviceIp');
            if (savedIp) {
                projectServer.getConnector().setDeviceIp(savedIp);
            }
        }

        // 设置状态栏
        statusBarManager?.setServer(projectServer);

        // 设置日志面板
        logViewProvider?.setServer(projectServer);

        // 设置侧边栏设备视图
        deviceViewProvider?.setServer(projectServer);

        // 同步 DevTool 面板
        if (DevToolPanel.currentPanel) {
            DevToolPanel.currentPanel.setServer(projectServer);
        }

        // 连接设备
        const autoConnect = vscode.workspace.getConfiguration('yyds').get<boolean>('autoConnect', true);
        if (autoConnect) {
            projectServer.connect();
        }

        // 销毁时断开
        context.subscriptions.push({
            dispose: () => {
                projectServer?.dispose();
                projectServer = undefined;
            },
        });

        const ip = projectServer.getConnector().getDeviceIp();
        vscode.window.showInformationMessage(`Yyds.Auto: 已加载项目, 设备IP: ${ip}`);

    } catch (e) {
        vscode.window.showWarningMessage(`Yyds.Auto: 初始化失败 — ${e}`);
    }
}

/**
 * 在工作区中查找包含 project.config 的目录
 */
function findProjectPath(): string | undefined {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders) { return undefined; }

    for (const folder of workspaceFolders) {
        const configPath = path.join(folder.uri.fsPath, 'project.config');
        if (fs.existsSync(configPath)) {
            return folder.uri.fsPath;
        }
    }

    return undefined;
}

export function deactivate() {
    projectServer?.dispose();
    statusBarManager?.dispose();
    console.log('[Yyds.Auto] 插件已停用');
}
