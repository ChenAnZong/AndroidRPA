/**
 * 通讯连接器 — JSON + HTTP REST + WebSocket(日志流)
 *
 * 架构:
 *   1. HTTP REST   — 所有控制命令（启动/停止/点击/截图/文件传输等）
 *   2. WebSocket /log — 实时日志流（唯一保留的WebSocket通道）
 *
 * 所有数据交换使用 JSON 或标准 HTTP 二进制。
 */

import WebSocket from 'ws';
import * as http from 'http';
import { ApiResponse } from './protocol';
import { EventEmitter } from 'events';
import { log, logError } from './logger';

const TAG = 'Connector';
const RECONNECT_INTERVAL = 3_000;
const HTTP_TIMEOUT = 15_000;
const PING_INTERVAL = 10_000;

export class EngineConnector extends EventEmitter {
    private deviceIp: string = '192.168.1.2';
    private port: number = 61140;

    private logWs: WebSocket | null = null;

    private isUserDisconnect: boolean = false;
    private logReconnectTimer: ReturnType<typeof setTimeout> | null = null;
    private pingTimer: ReturnType<typeof setInterval> | null = null;

    private _isApiConnected: boolean = false;
    private _isLogConnected: boolean = false;

    get isApiConnected(): boolean { return this._isApiConnected; }
    get isLogConnected(): boolean { return this._isLogConnected; }

    get baseUrl(): string { return `http://${this.deviceIp}:${this.port}`; }

    getDeviceIp(): string { return this.deviceIp; }
    setDeviceIp(ip: string): void { this.deviceIp = ip; }
    setPort(port: number): void { this.port = port; }

    // ================================================================
    // 连接管理
    // ================================================================

    ensureConnect(): void {
        this.isUserDisconnect = false;
        log(TAG, `开始连接设备 ${this.deviceIp}:${this.port}`);
        this.startPing();
        this.startLogConnection();
    }

    disconnect(): void {
        this.isUserDisconnect = true;
        log(TAG, '用户主动断开');

        if (this.logReconnectTimer) { clearTimeout(this.logReconnectTimer); this.logReconnectTimer = null; }
        if (this.pingTimer) { clearInterval(this.pingTimer); this.pingTimer = null; }

        this.closeWs(this.logWs); this.logWs = null;

        this._isApiConnected = false;
        this._isLogConnected = false;

        this.emit('statusChanged');
    }

    reConnect(ip: string): void {
        log(TAG, `重连到 ${ip}`);
        this.setDeviceIp(ip);
        this.disconnect();
        setTimeout(() => this.ensureConnect(), 500);
    }

    private closeWs(ws: WebSocket | null): void {
        if (!ws) { return; }
        try { ws.removeAllListeners(); ws.close(); } catch {}
    }

    // ================================================================
    // HTTP Ping（检测API可用性）
    // ================================================================

    private startPing(): void {
        if (this.pingTimer) { clearInterval(this.pingTimer); }
        // 立即检测一次
        this.checkApiAlive();
        this.pingTimer = setInterval(() => this.checkApiAlive(), PING_INTERVAL);
    }

    private checkApiAlive(): void {
        if (this.isUserDisconnect) { return; }
        this.httpGetText('/ping').then((res) => {
            const wasConnected = this._isApiConnected;
            this._isApiConnected = res !== null;
            if (wasConnected !== this._isApiConnected) {
                log(TAG, `[API] ${this._isApiConnected ? '已连接' : '已断开'}`);
                this.emit('statusChanged');
            }
        });
    }

    // ================================================================
    // WebSocket 日志通道（唯一保留的WebSocket）
    // ================================================================

    private startLogConnection(): void {
        if (this.isUserDisconnect || this._isLogConnected) { return; }

        const url = `ws://${this.deviceIp}:${this.port}/log`;
        log(TAG, `[LOG] 正在连接 ${url}`);

        try {
            this.logWs = new WebSocket(url, { handshakeTimeout: 5000 });

            this.logWs.on('open', () => {
                this._isLogConnected = true;
                log(TAG, '[LOG] 已连接');
                this.emit('statusChanged');
            });

            this.logWs.on('message', (data: WebSocket.Data) => {
                this.emit('log', data.toString());
            });

            this.logWs.on('close', () => {
                this._isLogConnected = false;
                this.emit('statusChanged');
                this.scheduleLogReconnect();
            });

            this.logWs.on('error', () => {
                this._isLogConnected = false;
                this.emit('statusChanged');
            });

        } catch (e) {
            this.scheduleLogReconnect();
        }
    }

    private scheduleLogReconnect(): void {
        if (this.isUserDisconnect) { return; }
        if (this.logReconnectTimer) { return; }
        this.logReconnectTimer = setTimeout(() => {
            this.logReconnectTimer = null;
            this.closeWs(this.logWs); this.logWs = null;
            this.startLogConnection();
        }, RECONNECT_INTERVAL);
    }

    // ================================================================
    // HTTP REST — JSON 请求
    // ================================================================

    /**
     * HTTP GET，返回解析后的 JSON 对象
     */
    httpGetJson<T = ApiResponse>(path: string, timeoutMs: number = HTTP_TIMEOUT): Promise<T | null> {
        return new Promise((resolve) => {
            const url = `${this.baseUrl}${path}`;
            log(TAG, `[HTTP] GET ${path}`);

            const req = http.get(url, { timeout: timeoutMs }, (res) => {
                const chunks: Buffer[] = [];
                res.on('data', (chunk: Buffer) => chunks.push(chunk));
                res.on('end', () => {
                    const body = Buffer.concat(chunks).toString('utf-8');
                    log(TAG, `[HTTP] GET ${path} ${res.statusCode} ${body.length}B`);
                    if (!body || body.trim().length === 0) {
                        resolve(null);
                        return;
                    }
                    try {
                        resolve(JSON.parse(body) as T);
                    } catch (e) {
                        logError(TAG, `[HTTP] GET ${path} JSON解析失败`, e);
                        resolve(null);
                    }
                });
                res.on('error', (err) => { logError(TAG, `[HTTP] GET ${path} 读取失败`, err); resolve(null); });
            });
            req.on('timeout', () => { req.destroy(); resolve(null); });
            req.on('error', (err) => { logError(TAG, `[HTTP] GET ${path} 失败`, err); resolve(null); });
        });
    }

    /**
     * HTTP POST JSON，返回解析后的 JSON 对象
     */
    httpPostJson<T = ApiResponse>(path: string, data: Record<string, any> = {}, timeoutMs: number = HTTP_TIMEOUT): Promise<T | null> {
        return new Promise((resolve) => {
            const url = new URL(`${this.baseUrl}${path}`);
            const jsonBody = JSON.stringify(data);
            log(TAG, `[HTTP] POST ${path} ${jsonBody.length}B`);

            const req = http.request({
                hostname: url.hostname,
                port: url.port,
                path: url.pathname,
                method: 'POST',
                timeout: timeoutMs,
                headers: {
                    'Content-Type': 'application/json',
                    'Content-Length': Buffer.byteLength(jsonBody),
                },
            }, (res) => {
                const chunks: Buffer[] = [];
                res.on('data', (chunk: Buffer) => chunks.push(chunk));
                res.on('end', () => {
                    const body = Buffer.concat(chunks).toString('utf-8');
                    log(TAG, `[HTTP] POST ${path} ${res.statusCode} ${body.length}B`);
                    if (!body || body.trim().length === 0) {
                        resolve(null);
                        return;
                    }
                    try {
                        resolve(JSON.parse(body) as T);
                    } catch (e) {
                        logError(TAG, `[HTTP] POST ${path} JSON解析失败`, e);
                        resolve(null);
                    }
                });
            });
            req.on('timeout', () => { req.destroy(); resolve(null); });
            req.on('error', (err) => { logError(TAG, `[HTTP] POST ${path} 失败`, err); resolve(null); });
            req.write(jsonBody);
            req.end();
        });
    }

    /**
     * HTTP GET，返回原始 Buffer（用于截图等二进制数据）
     */
    httpGetBuffer(path: string, timeoutMs: number = HTTP_TIMEOUT): Promise<Buffer | null> {
        return new Promise((resolve) => {
            const url = `${this.baseUrl}${path}`;
            log(TAG, `[HTTP] GET(bin) ${path}`);

            const req = http.get(url, { timeout: timeoutMs }, (res) => {
                if (res.statusCode !== 200) {
                    logError(TAG, `[HTTP] GET(bin) ${path} 返回 ${res.statusCode}`);
                    resolve(null);
                    return;
                }
                const chunks: Buffer[] = [];
                res.on('data', (chunk: Buffer) => chunks.push(chunk));
                res.on('end', () => {
                    const buf = Buffer.concat(chunks);
                    log(TAG, `[HTTP] GET(bin) ${path} ${buf.length}B`);
                    resolve(buf);
                });
                res.on('error', (err) => { logError(TAG, `[HTTP] GET(bin) ${path} 读取失败`, err); resolve(null); });
            });
            req.on('timeout', () => { req.destroy(); resolve(null); });
            req.on('error', (err) => { logError(TAG, `[HTTP] GET(bin) ${path} 失败`, err); resolve(null); });
        });
    }

    /**
     * HTTP GET，返回原始文本（用于UI Dump XML等）
     */
    httpGetText(path: string, timeoutMs: number = HTTP_TIMEOUT): Promise<string | null> {
        return new Promise((resolve) => {
            const url = `${this.baseUrl}${path}`;

            const req = http.get(url, { timeout: timeoutMs }, (res) => {
                if (res.statusCode !== 200) {
                    resolve(null);
                    return;
                }
                const chunks: Buffer[] = [];
                res.on('data', (chunk: Buffer) => chunks.push(chunk));
                res.on('end', () => resolve(Buffer.concat(chunks).toString('utf-8')));
                res.on('error', () => resolve(null));
            });
            req.on('timeout', () => { req.destroy(); resolve(null); });
            req.on('error', () => resolve(null));
        });
    }

    /**
     * HTTP POST multipart（用于文件上传）
     */
    httpPostMultipart(path: string, fields: Record<string, string>, fileData: Buffer, fileName: string = 'file.zip', timeoutMs: number = 120_000): Promise<ApiResponse | null> {
        return new Promise((resolve) => {
            const boundary = '----YydsAutoFormBoundary' + Date.now().toString(36);
            const url = new URL(`${this.baseUrl}${path}`);
            log(TAG, `[HTTP] POST(multipart) ${path} fileSize=${fileData.length}`);

            // 构建 multipart body
            const parts: Buffer[] = [];
            for (const [key, value] of Object.entries(fields)) {
                parts.push(Buffer.from(
                    `--${boundary}\r\nContent-Disposition: form-data; name="${key}"\r\n\r\n${value}\r\n`
                ));
            }
            parts.push(Buffer.from(
                `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${fileName}"\r\nContent-Type: application/octet-stream\r\n\r\n`
            ));
            parts.push(fileData);
            parts.push(Buffer.from(`\r\n--${boundary}--\r\n`));
            const body = Buffer.concat(parts);

            const req = http.request({
                hostname: url.hostname,
                port: url.port,
                path: url.pathname,
                method: 'POST',
                timeout: timeoutMs,
                headers: {
                    'Content-Type': `multipart/form-data; boundary=${boundary}`,
                    'Content-Length': body.length,
                },
            }, (res) => {
                const chunks: Buffer[] = [];
                res.on('data', (chunk: Buffer) => chunks.push(chunk));
                res.on('end', () => {
                    try {
                        const text = Buffer.concat(chunks).toString('utf-8');
                        log(TAG, `[HTTP] POST(multipart) ${path} ${res.statusCode} ${text.length}B`);
                        resolve(JSON.parse(text) as ApiResponse);
                    } catch {
                        resolve(null);
                    }
                });
            });
            req.on('timeout', () => { req.destroy(); resolve(null); });
            req.on('error', (err) => { logError(TAG, `[HTTP] POST(multipart) ${path} 失败`, err); resolve(null); });
            req.write(body);
            req.end();
        });
    }

    // ================================================================
    // 便捷方法
    // ================================================================

    addDebugLog(msg: string): void {
        log(TAG, msg);
        this.emit('log', `来自插件:${msg}`);
    }

    getConnectDescStatus(): string {
        const apiStatus = this._isApiConnected ? 'API:连接' : 'API:断开';
        const logStatus = this._isLogConnected ? '日志:连接' : '日志:断开';
        return `${apiStatus} ${logStatus} ${this.deviceIp}`;
    }
}
