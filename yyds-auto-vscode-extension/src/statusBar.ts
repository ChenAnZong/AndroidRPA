/**
 * 状态栏 — 移植自 YyConnectWidget.java + YyStatusPresentation.java
 * 在 VS Code 底部状态栏显示连接状态
 * 点击状态栏弹出 IP 输入对话框进行连接/重连
 */

import * as vscode from 'vscode';
import { ProjectServer } from './engine/projectServer';

export class StatusBarManager {
    private statusBarItem: vscode.StatusBarItem;
    private server: ProjectServer | undefined;
    private updateTimer: ReturnType<typeof setInterval> | null = null;

    constructor() {
        this.statusBarItem = vscode.window.createStatusBarItem(
            vscode.StatusBarAlignment.Left,
            -100 // 靠右放
        );
        // 点击时触发连接设备命令（弹出 IP 输入框）
        this.statusBarItem.command = 'yyds.connectDevice';
        this.updateDisplay();
        this.statusBarItem.show();
    }

    setServer(server: ProjectServer): void {
        this.server = server;

        // 监听状态变化
        server.getConnector().on('statusChanged', () => {
            this.updateDisplay();
        });

        // 定时刷新（每5秒）
        if (this.updateTimer) {
            clearInterval(this.updateTimer);
        }
        this.updateTimer = setInterval(() => {
            this.updateDisplay();
        }, 5000);

        this.updateDisplay();
    }

    private updateDisplay(): void {
        if (!this.server) {
            this.statusBarItem.text = '$(plug) Yyds.Auto: 点击连接设备';
            this.statusBarItem.tooltip = '点击输入设备IP地址进行连接';
            this.statusBarItem.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
            return;
        }

        const connector = this.server.getConnector();
        const isApi = connector.isApiConnected;
        const isLog = connector.isLogConnected;
        const ip = connector.getDeviceIp();

        if (isApi && isLog) {
            this.statusBarItem.text = `$(check) Yyds.Auto: 已连接 ${ip}`;
            this.statusBarItem.tooltip = `设备已连接: ${ip}\n控制通道: 已连接\n日志通道: 已连接\n\n点击可更换设备IP`;
            this.statusBarItem.backgroundColor = undefined;
        } else if (isApi || isLog) {
            const apiStr = isApi ? '已连接' : '断开';
            const logStr = isLog ? '已连接' : '断开';
            this.statusBarItem.text = `$(warning) Yyds.Auto: 部分连接 ${ip}`;
            this.statusBarItem.tooltip = `设备: ${ip}\n控制通道: ${apiStr}\n日志通道: ${logStr}\n\n点击可输入IP重新连接`;
            this.statusBarItem.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
        } else {
            this.statusBarItem.text = `$(error) Yyds.Auto: 未连接 ${ip}`;
            this.statusBarItem.tooltip = `设备断开: ${ip}\n\n点击输入IP地址连接设备`;
            this.statusBarItem.backgroundColor = new vscode.ThemeColor('statusBarItem.errorBackground');
        }
    }

    dispose(): void {
        if (this.updateTimer) {
            clearInterval(this.updateTimer);
        }
        this.statusBarItem.dispose();
    }
}
