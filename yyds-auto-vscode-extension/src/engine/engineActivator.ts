/**
 * 引擎激活器 — 通过 ADB 在非ROOT设备上启动工作进程
 *
 * 流程:
 *   1. 确保 ADB 可用（自动探测或引导下载）
 *   2. 选择目标设备
 *   3. 通过 adb shell 执行: 提取SO → chmod → 启动 yyds.keep
 *   4. yyds.keep 自动派生启动 yyds.auto 和 yyds.py
 *   5. 验证端口 61140 可达
 */

import * as vscode from 'vscode';
import { AdbManager, AdbDevice } from './adbManager';
import { log, logError } from './logger';

const TAG = 'EngineActivator';

/** 激活结果 */
export interface ActivationResult {
    success: boolean;
    message: string;
    deviceIp?: string;
}

export class EngineActivator {
    private adbManager: AdbManager;

    constructor(adbManager: AdbManager) {
        this.adbManager = adbManager;
    }

    // ================================================================
    // 引擎激活
    // ================================================================

    /**
     * 激活引擎（完整流程）
     * @param deviceSerial 指定设备序列号，null 则自动选择
     */
    async activateEngine(deviceSerial?: string): Promise<ActivationResult> {
        // 1. 确保 ADB 可用
        const adbPath = await this.adbManager.ensureAdb();
        if (!adbPath) {
            return { success: false, message: '未找到 ADB 工具，请安装 Android SDK 或允许自动下载' };
        }

        return vscode.window.withProgress({
            location: vscode.ProgressLocation.Notification,
            title: 'Yyds.Auto: 引擎激活',
            cancellable: false,
        }, async (progress) => {
            try {
                // 2. 选择目标设备
                progress.report({ message: '检测设备...' });
                const serial = deviceSerial || await this.selectDevice();
                if (!serial) {
                    return { success: false, message: '未选择设备或无设备连接' };
                }
                log(TAG, `目标设备: ${serial}`);

                // 3. 检查 Yyds.Auto 是否已安装
                progress.report({ message: '检查应用安装状态...' });
                const appCheck = await this.adbManager.runAdbShell(serial, 'pm path com.yyds.auto');
                if (!appCheck.stdout.includes('package:')) {
                    return { success: false, message: '设备上未安装 Yyds.Auto 应用 (com.yyds.auto)' };
                }

                // 4. 检查是否已在运行
                progress.report({ message: '检查引擎状态...' });
                const pidCheck = await this.adbManager.runAdbShell(serial, 'pidof yyds.keep');
                if (pidCheck.stdout.trim().length > 0) {
                    log(TAG, `引擎已在运行 (yyds.keep PID=${pidCheck.stdout.trim()})`);
                    const deviceIp = this.extractIpFromSerial(serial);
                    return { success: true, message: `引擎已在运行 (PID: ${pidCheck.stdout.trim()})`, deviceIp };
                }

                // 5. 获取设备 ABI
                progress.report({ message: '获取设备信息...' });
                const abiResult = await this.adbManager.runAdbShell(serial, 'getprop ro.product.cpu.abi');
                const abi = abiResult.stdout.trim() || 'arm64-v8a';
                log(TAG, `设备ABI: ${abi}`);

                // 6. 获取 APK 路径
                const apkPath = appCheck.stdout.trim().replace('package:', '');
                log(TAG, `APK路径: ${apkPath}`);

                // 7. 提取 SO 文件
                progress.report({ message: '提取引擎文件...' });
                const extractCmds = [
                    `mkdir -p /data/local/tmp/cache/lib/${abi}`,
                    `unzip -o ${apkPath} "lib/${abi}/*.so" -d /data/local/tmp/cache 2>/dev/null`,
                    `chmod +x /data/local/tmp/cache/lib/${abi}/libyyds_keep.so`,
                ].join(' && ');

                const extractResult = await this.adbManager.runAdbShell(serial, extractCmds);
                log(TAG, `SO提取结果: exit=${extractResult.exitCode}, stdout=${extractResult.stdout.substring(0, 200)}`);

                // 验证 keeper 二进制存在
                const checkKeeper = await this.adbManager.runAdbShell(
                    serial,
                    `ls -la /data/local/tmp/cache/lib/${abi}/libyyds_keep.so`
                );
                if (!checkKeeper.stdout.includes('libyyds_keep')) {
                    return { success: false, message: 'SO文件提取失败，请确认 Yyds.Auto 应用已正确安装' };
                }

                // 8. 启动 yyds.keep 守护进程
                progress.report({ message: '启动引擎守护进程...' });
                const keeperPath = `/data/local/tmp/cache/lib/${abi}/libyyds_keep.so`;
                const ldPath = `/data/local/tmp/cache/lib/${abi}`;
                // 注意：adb shell 不是 pipe，nohup 可正常工作
                const launchCmd = `LD_LIBRARY_PATH=${ldPath} nohup ${keeperPath} ${apkPath} LD_LIBRARY_PATH=${ldPath} >/dev/null 2>&1 &`;
                const launchResult = await this.adbManager.runAdbShell(serial, launchCmd);
                log(TAG, `启动命令结果: exit=${launchResult.exitCode}`);

                // 9. 等待并验证
                progress.report({ message: '等待引擎启动...' });
                await this.sleep(2000);

                const verifyPid = await this.adbManager.runAdbShell(serial, 'pidof yyds.keep');
                const keeperPid = verifyPid.stdout.trim();
                if (!keeperPid) {
                    // 重试一次：可能需要更长时间
                    await this.sleep(3000);
                    const retry = await this.adbManager.runAdbShell(serial, 'pidof yyds.keep');
                    if (!retry.stdout.trim()) {
                        return { success: false, message: '引擎启动失败，yyds.keep 进程未启动。请检查设备日志。' };
                    }
                }

                log(TAG, `yyds.keep 已启动, PID=${verifyPid.stdout.trim()}`);

                // 10. 等待 yyds.py 启动（keeper 会自动派生）
                progress.report({ message: '等待脚本引擎启动...' });
                let pyReady = false;
                for (let i = 0; i < 10; i++) {
                    await this.sleep(2000);
                    const pyPid = await this.adbManager.runAdbShell(serial, 'pidof yyds.py');
                    if (pyPid.stdout.trim()) {
                        pyReady = true;
                        log(TAG, `yyds.py 已启动, PID=${pyPid.stdout.trim()}`);
                        break;
                    }
                }

                if (!pyReady) {
                    log(TAG, 'yyds.py 尚未启动，但 yyds.keep 已运行（可能需要更长时间）');
                }

                const deviceIp = this.extractIpFromSerial(serial);
                return {
                    success: true,
                    message: pyReady
                        ? `引擎激活成功！守护进程和脚本引擎均已启动。`
                        : `守护进程已启动，脚本引擎仍在启动中...`,
                    deviceIp,
                };

            } catch (e: any) {
                logError(TAG, '引擎激活异常', e);
                return { success: false, message: `激活失败: ${e.message || e}` };
            }
        });
    }

    // ================================================================
    // 设备选择
    // ================================================================

    /**
     * 选择目标设备
     * 单设备直接返回，多设备弹出 QuickPick
     */
    private async selectDevice(): Promise<string | null> {
        const devices = await this.adbManager.getDevices();

        if (devices.length === 0) {
            const action = await vscode.window.showWarningMessage(
                '未检测到 ADB 设备。请通过 USB 连接设备或使用无线调试。',
                '无线配对 (Android 11+)',
                '刷新'
            );
            if (action === '无线配对 (Android 11+)') {
                const paired = await this.pairWirelessDevice();
                if (paired) {
                    // 配对成功后重新获取设备列表
                    const newDevices = await this.adbManager.getDevices();
                    if (newDevices.length === 1) { return newDevices[0].serial; }
                    if (newDevices.length > 1) { return this.pickDevice(newDevices); }
                }
                return null;
            }
            if (action === '刷新') {
                const newDevices = await this.adbManager.getDevices();
                if (newDevices.length === 0) {
                    vscode.window.showWarningMessage('仍未检测到设备');
                    return null;
                }
                if (newDevices.length === 1) { return newDevices[0].serial; }
                return this.pickDevice(newDevices);
            }
            return null;
        }

        // 过滤可用设备
        const availableDevices = devices.filter(d => d.state === 'device');
        if (availableDevices.length === 0) {
            const stateList = devices.map(d => `${d.serial} (${d.state})`).join(', ');
            vscode.window.showWarningMessage(`设备不可用: ${stateList}。请检查 USB 调试授权。`);
            return null;
        }

        if (availableDevices.length === 1) {
            return availableDevices[0].serial;
        }

        return this.pickDevice(availableDevices);
    }

    /**
     * QuickPick 选择设备
     */
    private async pickDevice(devices: AdbDevice[]): Promise<string | null> {
        const items = devices.map(d => ({
            label: d.serial,
            description: d.state,
        }));

        const selected = await vscode.window.showQuickPick(items, {
            title: 'Yyds.Auto — 选择目标设备',
            placeHolder: '选择要激活引擎的设备',
        });

        return selected?.label || null;
    }

    // ================================================================
    // 无线配对
    // ================================================================

    /**
     * 无线配对流程 (Android 11+)
     */
    async pairWirelessDevice(): Promise<boolean> {
        // 确保 ADB 可用
        const adbPath = await this.adbManager.ensureAdb();
        if (!adbPath) { return false; }

        // 输入设备 IP
        const ip = await vscode.window.showInputBox({
            title: 'Yyds.Auto — 无线配对 (步骤 1/3)',
            prompt: '请输入设备 IP 地址（在设备的「无线调试」页面查看）',
            placeHolder: '例如: 192.168.1.100',
            validateInput: (v) => {
                if (!/^(\d{1,3}\.){3}\d{1,3}$/.test(v.trim())) {
                    return '请输入有效的 IP 地址';
                }
                return undefined;
            },
        });
        if (!ip) { return false; }

        // 输入配对端口
        const pairPort = await vscode.window.showInputBox({
            title: 'Yyds.Auto — 无线配对 (步骤 2/3)',
            prompt: '请输入配对端口（在设备「无线调试」→「使用配对码配对设备」中查看）',
            placeHolder: '例如: 37521',
            validateInput: (v) => {
                const port = parseInt(v.trim());
                if (isNaN(port) || port < 1 || port > 65535) {
                    return '请输入有效的端口号 (1-65535)';
                }
                return undefined;
            },
        });
        if (!pairPort) { return false; }

        // 输入配对码
        const code = await vscode.window.showInputBox({
            title: 'Yyds.Auto — 无线配对 (步骤 3/3)',
            prompt: '请输入 6 位配对码',
            placeHolder: '例如: 482604',
            validateInput: (v) => {
                if (!/^\d{6}$/.test(v.trim())) {
                    return '请输入 6 位数字配对码';
                }
                return undefined;
            },
        });
        if (!code) { return false; }

        // 执行配对
        const pairResult = await vscode.window.withProgress({
            location: vscode.ProgressLocation.Notification,
            title: 'Yyds.Auto: 正在配对...',
        }, async () => {
            return this.adbManager.pairWireless(ip.trim(), parseInt(pairPort.trim()), code.trim());
        });

        if (pairResult.stdout.toLowerCase().includes('success') ||
            pairResult.stderr.toLowerCase().includes('success')) {
            log(TAG, `配对成功: ${ip}:${pairPort}`);

            // 配对成功后连接
            // Android 无线调试的连接端口与配对端口不同
            const connectPort = await vscode.window.showInputBox({
                title: 'Yyds.Auto — 无线连接',
                prompt: '配对成功！请输入连接端口（在「无线调试」主页面显示的端口，不是配对端口）',
                placeHolder: '例如: 42839',
                validateInput: (v) => {
                    const port = parseInt(v.trim());
                    if (isNaN(port) || port < 1 || port > 65535) {
                        return '请输入有效的端口号';
                    }
                    return undefined;
                },
            });

            if (connectPort) {
                const connectResult = await this.adbManager.connectWireless(ip.trim(), parseInt(connectPort.trim()));
                if (connectResult.stdout.includes('connected')) {
                    vscode.window.showInformationMessage(`无线连接成功: ${ip}:${connectPort}`);
                    return true;
                } else {
                    vscode.window.showErrorMessage(`连接失败: ${connectResult.stdout} ${connectResult.stderr}`);
                    return false;
                }
            }

            return false;
        } else {
            vscode.window.showErrorMessage(`配对失败: ${pairResult.stdout} ${pairResult.stderr}`);
            return false;
        }
    }

    // ================================================================
    // 引擎状态检查
    // ================================================================

    /**
     * 通过 ADB 检查引擎进程状态
     */
    async checkEngineStatus(serial?: string): Promise<{
        keeperRunning: boolean;
        autoRunning: boolean;
        pyRunning: boolean;
    }> {
        const target = serial || null;
        const [keeper, auto, py] = await Promise.all([
            this.adbManager.runAdbShell(target, 'pidof yyds.keep'),
            this.adbManager.runAdbShell(target, 'pidof yyds.auto'),
            this.adbManager.runAdbShell(target, 'pidof yyds.py'),
        ]);

        return {
            keeperRunning: keeper.stdout.trim().length > 0,
            autoRunning: auto.stdout.trim().length > 0,
            pyRunning: py.stdout.trim().length > 0,
        };
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /**
     * 从设备 serial 中提取 IP 地址
     * USB 设备返回 null，无线设备返回 IP
     */
    private extractIpFromSerial(serial: string): string | undefined {
        // 无线设备格式: 192.168.1.100:5555
        const match = serial.match(/^(\d+\.\d+\.\d+\.\d+):\d+$/);
        return match ? match[1] : undefined;
    }

    private sleep(ms: number): Promise<void> {
        return new Promise(resolve => setTimeout(resolve, ms));
    }
}
