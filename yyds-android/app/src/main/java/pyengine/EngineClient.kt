package pyengine

import android.os.SystemClock
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.tencent.yyds.ShizukuUtil
import uiautomator.ExtSystem
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * App进程与工作进程的通信客户端
 * 架构: JSON + HTTP REST（控制命令） + WebSocket（仅日志流）
 */
object EngineClient {
    private val gson = Gson()
    private val baseUrl get() = "http://127.0.0.1:${PyEngine.enginePort}"

    private val logQueue: LinkedBlockingQueue<String> = LinkedBlockingQueue()
    private val isLogConnect: AtomicBoolean = AtomicBoolean(false)
    private var mLogClient: HttpClient? = null

    // 日志广播: 支持多个消费者同时接收日志（FloatingLogService、LogcatActivity等）
    private val logListeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun addLogListener(listener: (String) -> Unit) {
        logListeners.add(listener)
        // 新listener注册时，投递缓冲的消息（控制台 + Agent悬浮窗）
        deliverPending(pendingConsoleMessages, listener)
        deliverPending(pendingOverlayMessages, listener)
    }

    private fun deliverPending(buffer: CopyOnWriteArrayList<String>, listener: (String) -> Unit) {
        if (buffer.isNotEmpty()) {
            val pending = ArrayList(buffer)
            buffer.clear()
            for (msg in pending) {
                try { listener.invoke(msg) } catch (_: Exception) {}
            }
        }
    }

    fun removeLogListener(listener: (String) -> Unit) {
        logListeners.remove(listener)
    }

    // ================================================================
    // 命令拦截（解决Python端无法直接启动Android Service的问题）
    // ================================================================
    const val CONSOLE_CMD_PREFIX = "##YYDS_CONSOLE##"
    const val AGENT_OVERLAY_PREFIX = "##YYDS_AGENT_OVERLAY##"

    /** App层注册的回调: 当检测到console show命令且Service未运行时触发 */
    @Volatile
    var onConsoleShowRequest: (() -> Unit)? = null

    /** App层注册的回调: 当检测到agent overlay命令且Service未运行时触发 */
    @Volatile
    var onAgentOverlayRequest: (() -> Unit)? = null

    /** 缓冲区: Service尚未启动时暂存消息，待Service注册listener后投递 */
    private val pendingConsoleMessages = CopyOnWriteArrayList<String>()
    private val pendingOverlayMessages = CopyOnWriteArrayList<String>()

    /** 剥离 out:/err: 前缀，供外部解析使用 */
    fun stripLogPrefix(raw: String): String = when {
        raw.startsWith("out:") -> raw.substring(4)
        raw.startsWith("err:") -> raw.substring(4)
        else -> raw
    }.trimStart()

    /** 检查日志消息是否为控制台命令，若是则拦截处理 */
    private fun interceptConsoleCommand(raw: String): Boolean {
        val msg = stripLogPrefix(raw)
        if (!msg.startsWith(CONSOLE_CMD_PREFIX)) return false

        val cmd = msg.substringAfter(CONSOLE_CMD_PREFIX).trim()
        val hasListener = logListeners.isNotEmpty()
        if (!hasListener) {
            pendingConsoleMessages.add(raw)
        }
        if (cmd == "show" || cmd.startsWith("log:")) {
            if (!hasListener) onConsoleShowRequest?.invoke()
        }
        return true
    }

    /** 检查日志消息是否为Agent悬浮窗命令，若是则拦截处理 */
    private fun interceptAgentOverlayCommand(raw: String): Boolean {
        val msg = stripLogPrefix(raw)
        if (!msg.startsWith(AGENT_OVERLAY_PREFIX)) return false

        val cmd = msg.substringAfter(AGENT_OVERLAY_PREFIX)
        // Service尚未启动 → 缓冲消息 + 通知App层启动
        if (cmd.startsWith("show:") || cmd.startsWith("think:") || cmd.startsWith("done:")) {
            if (!com.tencent.yyds.AgentOverlayService.isRunning()) {
                pendingOverlayMessages.add(raw)
                onAgentOverlayRequest?.invoke()
            }
        }
        return true
    }

    // ================================================================
    // HTTP JSON 工具方法
    // ================================================================

    internal fun httpGet(path: String, timeoutMs: Int = 5000): String? {
        try {
            val url = URL("$baseUrl$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText()
            conn.disconnect()
            return body
        } catch (e: Exception) {
            ExtSystem.printDebugError("HTTP GET $path 失败", e)
            return null
        }
    }

    internal fun httpPostJson(path: String, json: String, timeoutMs: Int = 5000): String? {
        try {
            val url = URL("$baseUrl$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(json.toByteArray()) }
            // 非2xx时用errorStream读取响应体（服务端可能返回500但body仍含有效JSON）
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText()
            conn.disconnect()
            return body
        } catch (e: Exception) {
            ExtSystem.printDebugError("HTTP POST $path 失败", e)
            return null
        }
    }

    // ================================================================
    // 项目控制（HTTP REST）
    // ================================================================

    fun sendStopProject() {
        thread { httpGet("/project/stop") }
    }

    fun sendStartProject(name: String) {
        thread { httpGet("/project/start?name=$name") }
    }

    // <is_running, project_name>
    fun getProjectRunningStatus(): Pair<Boolean, String?>? {
        val body = httpGet("/project/status") ?: return null
        try {
            val map = gson.fromJson(body, Map::class.java)
            val running = map["running"] == true
            val project = map["project"]?.toString()
            return running to project
        } catch (e: Exception) {
            ExtSystem.printDebugError("解析项目状态失败", e)
            return null
        }
    }

    // ================================================================
    // WebSocket 日志流（保留）
    // ================================================================

    fun ensureLogConnect() {
        if (!isLogConnect.get()) {
            thread {
                ExtSystem.printDebugLog("# 开启日志流")
                keepLogConnect()
            }
        }
    }

    fun keepLogConnect() {
        val handler = CoroutineExceptionHandler { _, e ->
            ExtSystem.printDebugLog("==========CoroutineExceptionHandler==========", e)
        }

        val client = HttpClient(OkHttp) {
            install(WebSockets)
        }
        mLogClient = client

        runBlocking {
            launch(handler) {
                isLogConnect.set(true)
                client.webSocket(
                    method = HttpMethod.Get,
                    host = "127.0.0.1",
                    port = PyEngine.enginePort,
                    path = "/log"
                ) {
                    launch(handler) {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    // 拦截控制台命令: 确保Service能被Python端启动
                                    val isConsoleCmd = interceptConsoleCommand(text)
                                    val isOverlayCmd = interceptAgentOverlayCommand(text)
                                    // 控制台/悬浮窗命令不放入logQueue（LogcatActivity不需要看到）
                                    if (!isConsoleCmd && !isOverlayCmd) {
                                        logQueue.offer(text)
                                    }
                                    for (l in logListeners) {
                                        try { l.invoke(text) } catch (_: Exception) {}
                                    }
                                }
                                else -> {}
                            }
                        }
                    }.join()
                    isLogConnect.set(false)
                    client.close()
                }
            }
        }

        ExtSystem.printDebugLog("Disconnect from local engine, ${PyEngine.enginePort}")
    }

    fun closeLogConnect() {
        mLogClient?.close()
        mLogClient = null
        isLogConnect.set(false)
        ExtSystem.printDebugLog("# 关闭日志流")
    }

    fun logHasnext(): Boolean {
        return !logQueue.isEmpty()
    }

    fun nextLog(): String? {
        return logQueue.poll()
    }

    // ================================================================
    // 文件操作（通过守护进程，避免App进程权限问题）
    // ================================================================

    /**
     * 通过守护进程检查文件是否存在（守护进程有ROOT/SHELL权限）
     */
    fun fileExists(path: String): Boolean {
        try {
            val body = httpGet("/file/exists?path=${URLEncoder.encode(path, "UTF-8")}") ?: return false
            val map = gson.fromJson(body, Map::class.java)
            return map["exists"] == true
        } catch (e: Exception) {
            ExtSystem.printDebugError("检查文件存在失败: $path", e)
            return false
        }
    }

    /**
     * 通过守护进程读取文件文本内容（守护进程有ROOT/SHELL权限）
     * @return 文件内容，文件不存在或读取失败返回null
     */
    fun readFileText(path: String): String? {
        try {
            val url = URL("$baseUrl/file/read-text?path=${URLEncoder.encode(path, "UTF-8")}")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                return body
            }
            conn.disconnect()
            return null
        } catch (e: Exception) {
            ExtSystem.printDebugError("读取文件失败: $path", e)
            return null
        }
    }

    /**
     * 通过守护进程写入文本文件（守护进程有ROOT/SHELL权限，自动创建父目录）
     */
    fun writeFileText(path: String, content: String): Boolean {
        try {
            val body = httpPostJson("/file/write-text", gson.toJson(mapOf("path" to path, "content" to content))) ?: return false
            val map = gson.fromJson(body, Map::class.java)
            return map["success"] == true
        } catch (e: Exception) {
            ExtSystem.printDebugError("写入文件失败: $path", e)
            return false
        }
    }

    /**
     * 通过守护进程删除文件/目录（守护进程有ROOT/SHELL权限，支持递归删除）
     */
    fun fileDelete(path: String): Boolean {
        try {
            val body = httpGet("/file/delete?path=${URLEncoder.encode(path, "UTF-8")}") ?: return false
            val map = gson.fromJson(body, Map::class.java)
            return map["success"] == true
        } catch (e: Exception) {
            ExtSystem.printDebugError("删除文件失败: $path", e)
            return false
        }
    }

    /**
     * 通过守护进程获取文件最后修改时间（守护进程有ROOT/SHELL权限）
     */
    fun fileLastModified(path: String): Long {
        try {
            val body = httpGet("/file/last-modified?path=${URLEncoder.encode(path, "UTF-8")}") ?: return 0
            val map = gson.fromJson(body, Map::class.java)
            return (map["lastModified"] as? Number)?.toLong() ?: 0
        } catch (e: Exception) {
            ExtSystem.printDebugError("获取文件修改时间失败: $path", e)
            return 0
        }
    }

    /**
     * 通过守护进程列出目录内容（守护进程有ROOT/SHELL权限）
     * @return JSON字符串包含 files 数组和 parent 路径，失败返回null
     */
    fun listDir(path: String): String? {
        return httpGet("/file/list?path=${URLEncoder.encode(path, "UTF-8")}", 10000)
    }

    /**
     * 通过守护进程重命名文件/目录（守护进程有ROOT/SHELL权限）
     */
    fun renameFile(oldPath: String, newName: String): Boolean {
        try {
            val body = httpPostJson("/file/rename", gson.toJson(mapOf("oldPath" to oldPath, "newName" to newName))) ?: return false
            val map = gson.fromJson(body, Map::class.java)
            return map["success"] == true
        } catch (e: Exception) {
            ExtSystem.printDebugError("重命名文件失败: $oldPath -> $newName", e)
            return false
        }
    }

    /**
     * 通过守护进程创建目录（守护进程有ROOT/SHELL权限）
     */
    fun mkDir(path: String): Boolean {
        try {
            val body = httpGet("/file/mkdir?path=${URLEncoder.encode(path, "UTF-8")}") ?: return false
            val map = gson.fromJson(body, Map::class.java)
            return map["success"] == true
        } catch (e: Exception) {
            ExtSystem.printDebugError("创建目录失败: $path", e)
            return false
        }
    }

    // ================================================================
    // Shell 命令
    // ================================================================

    /**
     * 通过工作进程执行Shell命令（工作进程有ROOT/SHELL权限）
     * @return 命令输出文本，失败返回空字符串
     */
    fun runShell(command: String): String {
        try {
            val body = httpPostJson("/engine/shell", gson.toJson(mapOf("command" to command)), 15000) ?: return ""
            val map = gson.fromJson(body, Map::class.java)
            return map["result"]?.toString() ?: ""
        } catch (e: Exception) {
            ExtSystem.printDebugError("工作进程执行Shell失败", e)
            return ""
        }
    }

    // ================================================================
    // Logcat 日志收集
    // ================================================================

    /**
     * 通过工作进程dump logcat日志（一次性获取）
     * @param level 最低日志级别 V/D/I/W/E/F
     * @param pid 过滤进程ID（可选）
     * @param tag 过滤Tag（可选）
     * @param format 输出格式 threadtime/brief
     * @param lines 只获取最近N行，0=全部
     * @return JSON字符串包含 data(日志文本), lineCount, command
     */
    fun logcatDump(level: String = "V", pid: String? = null, tag: String? = null,
                   format: String = "threadtime", lines: Int = 0): String? {
        val params = mutableListOf("level=$level", "format=$format")
        if (!pid.isNullOrBlank()) params.add("pid=$pid")
        if (!tag.isNullOrBlank()) params.add("tag=${URLEncoder.encode(tag, "UTF-8")}")
        if (lines > 0) params.add("lines=$lines")
        return httpGet("/logcat/dump?${params.joinToString("&")}", 15000)
    }

    /**
     * 清空设备logcat缓冲区
     * @param buffer 要清空的缓冲区: all/main/system/crash/events
     */
    fun logcatClear(buffer: String = "all"): Boolean {
        try {
            val body = httpPostJson("/logcat/clear", gson.toJson(mapOf("buffer" to buffer)), 5000) ?: return false
            val map = gson.fromJson(body, Map::class.java)
            return map["success"] == true
        } catch (e: Exception) {
            ExtSystem.printDebugError("清空logcat失败", e)
            return false
        }
    }

    /**
     * 获取logcat缓冲区信息
     */
    fun logcatBufferInfo(): String? {
        return httpGet("/logcat/buffers", 5000)
    }

    /**
     * 获取logcat WebSocket流地址（用于实时流式连接）
     */
    fun getLogcatStreamUrl(level: String = "V", pid: String? = null, tag: String? = null,
                           format: String = "threadtime"): String {
        val params = mutableListOf("level=$level", "format=$format")
        if (!pid.isNullOrBlank()) params.add("pid=$pid")
        if (!tag.isNullOrBlank()) params.add("tag=$tag")
        return "ws://127.0.0.1:${PyEngine.enginePort}/logcat/stream?${params.joinToString("&")}"
    }

    // ================================================================
    // 项目列表
    // ================================================================

    /**
     * 快速探测引擎是否可达（轻量级，仅TCP连接+简单GET）
     */
    fun isEngineReachable(): Boolean {
        return try {
            val url = URL("$baseUrl/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 通过HTTP请求从工作进程获取项目列表（工作进程拥有ROOT/SHELL权限可访问文件系统）
     */
    fun getProjectList(): List<YyProject> {
        try {
            val body = httpGet("/project/list", 3000) ?: return emptyList()
            val type = object : TypeToken<List<YyProject>>() {}.type
            return gson.fromJson(body, type) ?: emptyList()
        } catch (e: Exception) {
            ExtSystem.printDebugError("从工作进程获取项目列表失败", e)
            return emptyList()
        }
    }

    // ================================================================
    // APK打包（通过工作进程HTTP API）
    // ================================================================

    /**
     * 通过工作进程打包APK（工作进程有ROOT/SHELL权限，可访问项目文件和APK模板）
     * 超时120秒，适合大APK
     */
    fun buildApkViaEngine(configJson: String): ApkPackageHelper.BuildResult? {
        try {
            val body = httpPostJson("/package/build", configJson, 120_000) ?: return null
            return gson.fromJson(body, ApkPackageHelper.BuildResult::class.java)
        } catch (e: Exception) {
            ExtSystem.printDebugError("打包APK请求失败", e)
            return null
        }
    }

    /**
     * 通过工作进程获取打包状态（异步打包时轮询用）
     */
    fun getPackageBuildStatus(): String? {
        return httpGet("/package/status", 5000)
    }

    /**
     * 获取设备上已安装的非系统应用列表
     * 返回 [{packageName, appName}, ...]
     */
    fun getInstalledApps(): List<Map<String, String>> {
        try {
            val body = httpGet("/package/installed-apps", 10_000) ?: return emptyList()
            val type = object : com.google.gson.reflect.TypeToken<List<Map<String, String>>>() {}.type
            return gson.fromJson(body, type) ?: emptyList()
        } catch (e: Exception) {
            ExtSystem.printDebugError("获取已安装应用列表失败", e)
            return emptyList()
        }
    }

    /**
     * 获取指定应用的图标PNG字节（通过工作进程HTTP API）
     */
    fun getAppIconBytes(packageName: String): ByteArray? {
        try {
            val url = java.net.URL("$baseUrl/package/app-icon?pkg=$packageName")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode == 200) {
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                return bytes
            }
            conn.disconnect()
            return null
        } catch (e: Exception) {
            ExtSystem.printDebugError("获取应用图标失败: $packageName", e)
            return null
        }
    }

    /**
     * 通过工作进程提取Runner内嵌脚本
     */
    fun extractBundledProjectViaEngine(projectName: String): Boolean {
        try {
            val body = httpPostJson("/package/extract",
                gson.toJson(mapOf("projectName" to projectName)), 30_000) ?: return false
            val map = gson.fromJson(body, Map::class.java)
            return map["success"] == true
        } catch (e: Exception) {
            ExtSystem.printDebugError("提取内嵌脚本失败", e)
            return false
        }
    }

    // ================================================================
    // Pip包管理（通过工作进程HTTP API）
    // ================================================================

    fun pipList(): String? {
        return httpGet("/pip/list", 30_000)
    }

    fun pipOutdated(): String? {
        return httpGet("/pip/outdated", 60_000)
    }

    fun pipShow(name: String): String? {
        return httpGet("/pip/show?name=${URLEncoder.encode(name, "UTF-8")}", 10_000)
    }

    fun pipInstall(name: String, mirror: String? = null): String? {
        val params = mutableMapOf<String, Any?>("name" to name)
        if (!mirror.isNullOrBlank()) params["mirror"] = mirror
        return httpPostJson("/pip/install", gson.toJson(params), 120_000)
    }

    fun pipUninstall(name: String): String? {
        return httpPostJson("/pip/uninstall", gson.toJson(mapOf("name" to name)), 30_000)
    }

    fun pipUpgrade(name: String, mirror: String? = null): String? {
        val params = mutableMapOf<String, Any?>("name" to name)
        if (!mirror.isNullOrBlank()) params["mirror"] = mirror
        return httpPostJson("/pip/upgrade", gson.toJson(params), 120_000)
    }

    fun pipSearch(name: String, mirror: String? = null): String? {
        val mirrorParam = if (!mirror.isNullOrBlank()) "&mirror=${URLEncoder.encode(mirror, "UTF-8")}" else ""
        return httpGet("/pip/search?name=${URLEncoder.encode(name, "UTF-8")}$mirrorParam", 30_000)
    }

    // ================================================================
    // UI分析（控件分析）
    // ================================================================

    fun getUiDumpXml(): String? {
        return httpGet("/uia_dump", 15000)
    }

    fun getScreenshotBytes(): ByteArray? {
        try {
            val url = URL("$baseUrl/screen/80")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            val bytes = conn.inputStream.readBytes()
            conn.disconnect()
            return if (bytes.isNotEmpty()) bytes else null
        } catch (e: Exception) {
            ExtSystem.printDebugError("获取截图失败", e)
            return null
        }
    }

    // ================================================================
    // 引擎启动管理
    // ================================================================

    /**
     * 确保工作进程已启动且版本与当前App一致
     * 如果工作进程运行的是旧版本代码（APK更新后未重启），则强制重启
     * 启动顺序: ROOT → Shizuku → 失败
     * @return true 如果Python引擎正在运行
     */
    fun ensureEngineRunning(): Boolean {
        if (PyEngine.isEngineOpen()) {
            // 端口可连接，但需要检查工作进程是否运行的是最新代码
            if (isEngineVersionCurrent()) {
                return true
            }
            // 版本不一致，工作进程运行的是旧代码，需要重启
            ExtSystem.printDebugLog("工作进程版本不一致，强制重启所有引擎...")
            forceRestartEngines()
            // 等待新引擎启动
            for (i in 1..25) {
                SystemClock.sleep(1000)
                if (PyEngine.isEngineOpen() && isEngineVersionCurrent()) {
                    ExtSystem.printDebugLog("引擎已用新版本重启成功")
                    return true
                }
            }
            return PyEngine.isEngineOpen()
        }
        // 端口不可连接，正常启动流程
        // 优先尝试 ROOT 启动
        try {
            ExtSystem.printDebugLog("工作进程未启动，正在尝试启动所有引擎...")
            PyEngine.startAllEngines()
            for (i in 1..40) {
                SystemClock.sleep(1000)
                if (PyEngine.isEngineOpen()) {
                    ExtSystem.printDebugLog("所有引擎已成功启动(ROOT)")
                    return true
                }
            }
        } catch (e: Exception) {
            ExtSystem.printDebugError("ROOT启动引擎失败，尝试Shizuku...", e)
        }

        // ROOT 失败，回退到 Shizuku
        if (!PyEngine.isEngineOpen() && ShizukuUtil.isAvailable) {
            ExtSystem.printDebugLog("尝试通过Shizuku启动引擎...")
            var shizukuSuccess = false
            val latch = java.util.concurrent.CountDownLatch(1)
            ShizukuUtil.startEngines { success, msg ->
                ExtSystem.printDebugLog("Shizuku启动结果: $success - $msg")
                shizukuSuccess = success
                latch.countDown()
            }
            latch.await(35, java.util.concurrent.TimeUnit.SECONDS)
            if (shizukuSuccess) return true
        }

        return PyEngine.isEngineOpen()
    }

    /**
     * 检查工作进程的版本是否与当前App一致
     * 通过 GET / 返回的字符串中的 BUILD_DATE 判断
     */
    private fun isEngineVersionCurrent(): Boolean {
        try {
            val body = httpGet("/", 3000) ?: return true // 无法获取时不阻塞，假设一致
            // 响应格式: "Yyds.Auto(107|2026-02-14#00:19) py engine, pid=xxx, uid=xxx"
            val appBuildDate = com.tencent.yyds.BuildConfig.BUILD_DATE
            return body.contains(appBuildDate)
        } catch (e: Exception) {
            return true // 异常时不阻塞
        }
    }

    /**
     * 强制杀掉所有旧工作进程并重新启动
     */
    private fun forceRestartEngines() {
        try {
            ExtSystem.shell("killall yyds.py 2>/dev/null")
            ExtSystem.shell("killall yyds.auto 2>/dev/null")
            ExtSystem.shell("killall yyds.keep 2>/dev/null")
            SystemClock.sleep(2000)
            PyEngine.startAllEngines()
        } catch (e: Exception) {
            ExtSystem.printDebugError("强制重启引擎失败", e)
        }
    }

    // ================================================================
    // Agent 公开 HTTP 方法（供 UI 层调用）
    // ================================================================

    fun httpGetPublic(path: String, timeoutMs: Int = 10000): String? = httpGet(path, timeoutMs)

    fun httpPostJsonPublic(path: String, json: String, timeoutMs: Int = 30000): String? = httpPostJson(path, json, timeoutMs)

    fun httpDeletePublic(path: String, timeoutMs: Int = 10000): String? {
        return try {
            val url = java.net.URL("$baseUrl$path")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.connect()
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else null
        } catch (_: Exception) { null }
    }

    val instance: EngineClient get() = this
}