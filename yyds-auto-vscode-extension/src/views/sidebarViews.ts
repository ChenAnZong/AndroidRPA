/**
 * 左侧栏视图 — 设备连接信息 + 操作按钮
 * 显示在 Activity Bar 的 Yyds.Auto 图标下
 */

import * as vscode from 'vscode';
import { ProjectServer } from '../engine/projectServer';

// ================================================================
// 设备连接视图 — 显示设备状态信息
// ================================================================
export class DeviceViewProvider implements vscode.TreeDataProvider<DeviceInfoItem> {
    private _onDidChangeTreeData = new vscode.EventEmitter<DeviceInfoItem | undefined>();
    readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

    private server: ProjectServer | undefined;

    setServer(server: ProjectServer): void {
        this.server = server;
        server.getConnector().on('statusChanged', () => {
            this._onDidChangeTreeData.fire(undefined);
        });
        this._onDidChangeTreeData.fire(undefined);
    }

    refresh(): void {
        this._onDidChangeTreeData.fire(undefined);
    }

    getTreeItem(element: DeviceInfoItem): vscode.TreeItem {
        return element;
    }

    getChildren(): DeviceInfoItem[] {
        if (!this.server) {
            return []; // viewsWelcome 会显示欢迎内容
        }

        const connector = this.server.getConnector();
        const ip = connector.getDeviceIp();
        const isApi = connector.isApiConnected;
        const isLog = connector.isLogConnected;
        const items: DeviceInfoItem[] = [];

        // 设备IP
        items.push(new DeviceInfoItem(
            `设备: ${ip}`,
            '',
            isApi && isLog ? 'pass' : 'error',
            { command: 'yyds.connectDevice', title: '修改设备IP' }
        ));

        // API 通道状态
        items.push(new DeviceInfoItem(
            `控制通道: ${isApi ? '已连接' : '断开'}`,
            '',
            isApi ? 'pass' : 'error'
        ));

        // 日志通道状态
        items.push(new DeviceInfoItem(
            `日志通道: ${isLog ? '已连接' : '断开'}`,
            '',
            isLog ? 'pass' : 'error'
        ));

        // 项目信息
        try {
            const config = this.server.loadProjectProperties();
            const projectName = config['PROJECT_NAME'];
            if (projectName) {
                items.push(new DeviceInfoItem(
                    `项目: ${projectName}`,
                    config['PROJECT_VERSION'] || '',
                    'info'
                ));
            }
        } catch {}

        return items;
    }
}

class DeviceInfoItem extends vscode.TreeItem {
    constructor(
        label: string,
        description: string,
        iconType: 'pass' | 'error' | 'info' | 'warning',
        command?: vscode.Command
    ) {
        super(label, vscode.TreeItemCollapsibleState.None);
        this.description = description;
        this.command = command;

        switch (iconType) {
            case 'pass':
                this.iconPath = new vscode.ThemeIcon('pass-filled', new vscode.ThemeColor('testing.iconPassed'));
                break;
            case 'error':
                this.iconPath = new vscode.ThemeIcon('error', new vscode.ThemeColor('testing.iconFailed'));
                break;
            case 'warning':
                this.iconPath = new vscode.ThemeIcon('warning', new vscode.ThemeColor('problemsWarningIcon.foreground'));
                break;
            case 'info':
                this.iconPath = new vscode.ThemeIcon('info');
                break;
        }
    }
}

// ================================================================
// 操作视图 — 常用操作快捷入口
// ================================================================

interface ActionNode {
    isCategory: boolean;
    label: string;
    icon?: string;
    commandId?: string;
    shortcut?: string;
    children?: ActionNode[];
}

const ACTION_TREE: ActionNode[] = [
    {
        isCategory: true,
        label: '项目管理',
        children: [
            { isCategory: false, label: '新建脚本工程...', icon: '$(file-directory-create)', commandId: 'yyds.initProject' },
            { isCategory: false, label: '推送工程', icon: '$(cloud-upload)', commandId: 'yyds.push' },
            { isCategory: false, label: '打包独立APK', icon: '$(archive)', commandId: 'yyds.buildApk' },
            { isCategory: false, label: '打包ZIP源码', icon: '$(package)', commandId: 'yyds.zip' },
        ]
    },
    {
        isCategory: true,
        label: '运行调试',
        children: [
            { isCategory: false, label: '推送并运行', icon: '$(run-all)', commandId: 'yyds.pushAndRun', shortcut: 'Ctrl+Alt+1' },
            { isCategory: false, label: '运行工程', icon: '$(debug-start)', commandId: 'yyds.run' },
            { isCategory: false, label: '停止工程', icon: '$(debug-stop)', commandId: 'yyds.stop', shortcut: 'Ctrl+Alt+2' },
            { isCategory: false, label: '运行选中代码 / 当前行', icon: '$(play)', commandId: 'yyds.runSelectedCode' },
            { isCategory: false, label: '运行当前文件', icon: '$(play-circle)', commandId: 'yyds.runCurrentFile' },
        ]
    },
    {
        isCategory: true,
        label: '开发工具',
        children: [
            { isCategory: false, label: '开发助手', icon: '$(tools)', commandId: 'yyds.showDevTool' },
            { isCategory: false, label: 'UI 配置设计器', icon: '$(symbol-interface)', commandId: 'yyds.showUiDesigner' },
            { isCategory: false, label: 'ADB激活引擎 (非ROOT)', icon: '$(zap)', commandId: 'yyds.activateEngine' },
            { isCategory: false, label: '无线配对设备 (Android 11+)', icon: '$(radio-tower)', commandId: 'yyds.pairDevice' },
        ]
    }
];

export class ActionsViewProvider implements vscode.TreeDataProvider<ActionItem> {
    private _onDidChangeTreeData = new vscode.EventEmitter<ActionItem | undefined | void>();
    readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

    getTreeItem(element: ActionItem): vscode.TreeItem {
        return element;
    }

    getChildren(element?: ActionItem): ActionItem[] {
        if (!element) {
            // Root elements are categories
            return ACTION_TREE.map(cat => new ActionItem(cat));
        } else if (element.node.isCategory && element.node.children) {
            // Children of categories
            return element.node.children.map(child => new ActionItem(child));
        }
        return [];
    }
}

class ActionItem extends vscode.TreeItem {
    public readonly node: ActionNode;

    constructor(node: ActionNode) {
        super(
            node.label,
            node.isCategory ? vscode.TreeItemCollapsibleState.Expanded : vscode.TreeItemCollapsibleState.None
        );
        this.node = node;

        if (!node.isCategory && node.icon) {
            this.iconPath = new vscode.ThemeIcon(node.icon.replace('$(', '').replace(')', ''));
        }
        if (!node.isCategory && node.commandId) {
            this.command = { command: node.commandId, title: node.label };
        }
        if (!node.isCategory && node.shortcut) {
            this.description = node.shortcut;
        }
    }
}
