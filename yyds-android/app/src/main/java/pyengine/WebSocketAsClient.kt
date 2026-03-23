package pyengine

import android.os.Build
import android.os.SystemClock
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import uiautomator.AppProcess
import uiautomator.ExportHandle
import uiautomator.ExtSystem
import uiautomator.tool.Foreground
import uiautomator.tool.ScreenCapture
import kotlinx.coroutines.channels.Channel
import uiautomator.Const
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * WebSocket client connecting to yyds-con server.
 * Runs in yyds.py process (ROOT/SHELL privileges).
 *
 * Protocol: JSON text frames matching Rust backend DeviceMessage/ServerCommand types.
 * Binary frames: screenshot JPEG data (WS fallback streaming mode).
 */
object WebSocketAsClient {
    private val TAG = "WsClient"
    private val gson = Gson()

    // Bounded thread pool for command execution (prevents thread explosion under load)
    private val cmdExecutor = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "ws-cmd").apply { isDaemon = true }
    }

    // Connection config
    private var serverHost = ""
    private var serverPort = 8818
    private val configPath = "/sdcard/Yyds.Auto/server.conf"
    private val DEFAULT_HOST = "192.168.11.166" // TODO: dev default, remove for release
    private val reconnectDelaySec = 5L
    private var deviceToken = "" // JWT device token for authentication

    // State
    private val isConnected = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(false)
    private var streamQuality = AtomicInteger(70)
    @Volatile private var streamInterval = 33L  // 30fps target
    private var session: DefaultClientWebSocketSession? = null
    private var streamJob: Job? = null
    private var sendChannel: Channel<Frame>? = null

    // Intervals
    private val STATUS_REPORT_INTERVAL = 10_000L
    private val THUMBNAIL_QUALITY = 25
    private val THUMBNAIL_INTERVAL = 3_000L

    /**
     * Read server config from /sdcard/Yyds.Auto/server.conf
     * Format: {"host": "x.x.x.x", "port": 8818, "token": "jwt-device-token"}
     */
    private fun loadConfig(): Boolean {
        try {
            val file = File(configPath)
            if (!file.exists()) {
                // No config file — use dev default
                serverHost = DEFAULT_HOST
                serverPort = 8818
                deviceToken = ""
                ExtSystem.printInfo("[$TAG] 无配置文件，使用默认: $serverHost:$serverPort")
                return true
            }
            val json = gson.fromJson(file.readText(), JsonObject::class.java)
            serverHost = json.get("host")?.asString ?: ""
            serverPort = json.get("port")?.asInt ?: 8818
            deviceToken = json.get("token")?.asString ?: ""
            if (serverHost.isBlank()) {
                serverHost = DEFAULT_HOST
                ExtSystem.printInfo("[$TAG] 配置host为空，使用默认: $serverHost:$serverPort")
                return true
            }
            ExtSystem.printInfo("[$TAG] 加载配置: $serverHost:$serverPort token=${if (deviceToken.isNotEmpty()) "已配置" else "未配置"}")
            return true
        } catch (e: Exception) {
            ExtSystem.printDebugError("[$TAG] 读取配置失败", e)
            serverHost = DEFAULT_HOST
            serverPort = 8818
            deviceToken = ""
            return true
        }
    }

    /**
     * Get device IMEI. Falls back to serial number.
     */
    private fun getImei(): String {
        try {
            val imei = ExtSystem.shell("settings get secure android_id").trim()
            if (imei.isNotBlank() && imei != "null") return imei
        } catch (_: Exception) {}
        try {
            val serial = Build.SERIAL ?: ""
            if (serial.isNotBlank() && serial != "unknown") return serial
        } catch (_: Exception) {}
        return "unknown_${Build.MODEL.replace(" ", "_")}"
    }

    private fun getScreenSize(): Pair<Int, Int> {
        try {
            val wm = ExtSystem.shell("wm size").trim()
            // "Physical size: 1080x1920"
            val match = Regex("(\\d+)x(\\d+)").find(wm)
            if (match != null) {
                return Pair(match.groupValues[1].toInt(), match.groupValues[2].toInt())
            }
        } catch (_: Exception) {}
        return Pair(1080, 1920)
    }

    /**
     * Main connection loop. Call from a dedicated thread.
     * Automatically reconnects on disconnect.
     */
    fun keepConnected() {
        ExtSystem.printInfo("[$TAG] 启动远程连接服务")

        while (true) {
            if (!loadConfig()) {
                ExtSystem.printInfo("[$TAG] 无配置，${reconnectDelaySec}秒后重试...")
                SystemClock.sleep(reconnectDelaySec * 1000)
                continue
            }

            try {
                runBlocking {
                    connectAndServe()
                }
            } catch (e: Exception) {
                ExtSystem.printDebugError("[$TAG] 连接异常", e)
            }

            isConnected.set(false)
            isStreaming.set(false)
            streamJob?.cancel()
            streamJob = null
            session = null
            // Cleanup all WebRTC channels
            rtcChannels.values.forEach { it.close() }
            rtcChannels.clear()

            ExtSystem.printInfo("[$TAG] 断开连接，${reconnectDelaySec}秒后重连...")
            SystemClock.sleep(reconnectDelaySec * 1000)
        }
    }

    private suspend fun connectAndServe() {
        val imei = getImei()
        val model = Build.MODEL ?: "Unknown"
        val ver = try { AppProcess.appContext?.packageManager
            ?.getPackageInfo(AppProcess.appContext!!.packageName, 0)?.versionCode ?: 0
        } catch (_: Exception) { 0 }
        val (sw, sh) = getScreenSize()

        val client = HttpClient(OkHttp) {
            install(WebSockets) {
                pingInterval = 20_000
            }
            engine {
                config {
                    connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    readTimeout(0, java.util.concurrent.TimeUnit.SECONDS) // no read timeout for WS
                    writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        }

        try {
            val encodedModel = java.net.URLEncoder.encode(model, "UTF-8")
            val tokenParam = if (deviceToken.isNotEmpty()) "&token=${java.net.URLEncoder.encode(deviceToken, "UTF-8")}" else ""
            val wsPath = "/ws/device?imei=$imei&model=$encodedModel&ver=$ver&sw=$sw&sh=$sh$tokenParam"
            ExtSystem.printInfo("[$TAG] 连接 ws://$serverHost:$serverPort$wsPath")

            client.webSocket(
                method = HttpMethod.Get,
                host = serverHost,
                port = serverPort,
                path = wsPath
            ) {
                session = this
                isConnected.set(true)
                val outCh = Channel<Frame>(128)
                sendChannel = outCh
                ExtSystem.printInfo("[$TAG] 已连接到服务器")

                // Dedicated sender coroutine — only this touches session.send()
                val senderJob = launch {
                    for (frame in outCh) {
                        try { send(frame) } catch (_: Exception) { break }
                    }
                }

                // Send initial register message
                val registerMsg = JsonObject().apply {
                    addProperty("type", "register")
                    addProperty("imei", imei)
                    addProperty("model", model)
                    addProperty("sw", sw)
                    addProperty("sh", sh)
                    addProperty("ver", ver)
                }
                send(Frame.Text(gson.toJson(registerMsg)))

                // Start periodic status reporting
                val statusJob = launch {
                    while (isActive) {
                        delay(STATUS_REPORT_INTERVAL)
                        try {
                            sendStatusReport()
                        } catch (e: Exception) {
                            ExtSystem.printDebugError("[$TAG] 状态上报失败", e)
                        }
                    }
                }

                // Periodic thumbnail capture (low quality, every 3s) — paused during streaming
                val thumbnailJob = launch(Dispatchers.IO) {
                    delay(2000)
                    while (isActive && isConnected.get()) {
                        try {
                            if (!isStreaming.get()) {
                                val data = ScreenCapture.getBitmapData(THUMBNAIL_QUALITY)
                                if (data.isNotEmpty()) {
                                    outCh.trySend(Frame.Binary(true, data))
                                }
                            }
                        } catch (_: Exception) {}
                        delay(THUMBNAIL_INTERVAL)
                    }
                }

                // Local log relay: poll Python server's /log/poll and forward to yyds-con
                val logRelayJob = launch(Dispatchers.IO) {
                    delay(3000) // wait for Python server to start
                    ExtSystem.printInfo("[$TAG] 启动日志轮询")
                    while (isActive && isConnected.get()) {
                        try {
                            val text = httpGetLocal("/log/poll")
                            if (text.isNotBlank()) {
                                // 解析 O:/E: 前缀，转换为 out:/err:
                                val sb = StringBuilder()
                                for (chunk in text.split("(?=O:|E:)".toRegex())) {
                                    if (chunk.isEmpty()) continue
                                    val prefixed = when {
                                        chunk.startsWith("E:") -> "err:" + chunk.substring(2)
                                        chunk.startsWith("O:") -> "out:" + chunk.substring(2)
                                        else -> "out:$chunk"
                                    }
                                    forwardLog(prefixed)
                                }
                            }
                        } catch (_: Exception) {}
                        delay(300)
                    }
                }

                // Main message loop
                try {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                handleServerCommand(text)
                            }
                            is Frame.Ping -> {
                                // Ktor handles pong automatically
                            }
                            else -> {}
                        }
                    }
                } catch (e: Exception) {
                    ExtSystem.printDebugError("[$TAG] 消息循环异常", e)
                } finally {
                    logRelayJob.cancel()
                    statusJob.cancel()
                    thumbnailJob.cancel()
                    senderJob.cancel()
                    outCh.close()
                }
            }
        } finally {
            client.close()
        }
    }

    /**
     * Handle a JSON command from the server.
     * ServerCommand types: cmd, rtc_offer, rtc_ice
     */
    private suspend fun handleServerCommand(json: String) {
        try {
            val obj = gson.fromJson(json, JsonObject::class.java)
            val type = obj.get("type")?.asString ?: return

            when (type) {
                "cmd" -> handleCmd(obj)
                "rtc_offer" -> handleRtcOffer(obj)
                "rtc_ice" -> handleRtcIce(obj)
                else -> ExtSystem.printInfo("[$TAG] 未知命令类型: $type")
            }
        } catch (e: Exception) {
            ExtSystem.printDebugError("[$TAG] 处理命令异常: $json", e)
        }
    }

    /**
     * Handle a cmd message from server.
     * Format: {"type":"cmd", "id":"xxx", "action":"touch", "params":{"x":100,"y":200}}
     */
    private suspend fun handleCmd(obj: JsonObject) {
        val id = obj.get("id")?.asString ?: return
        val action = obj.get("action")?.asString ?: return
        val params = obj.getAsJsonObject("params") ?: JsonObject()

        ExtSystem.printInfo("[$TAG] 收到命令: $action ($id)")

        when (action) {
            "touch" -> {
                val x = params.get("x")?.asString ?: params.get("x")?.asInt?.toString() ?: "0"
                val y = params.get("y")?.asString ?: params.get("y")?.asInt?.toString() ?: "0"
                cmdExecutor.execute {
                    try {
                        if (ExportHandle.checkAutoEngine()) {
                            ExportHandle.getHandler().http("/touch", mapOf("x" to x, "y" to y))
                        }
                        sendResponse(id, true, "true")
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "touch failed")
                    }
                }
            }

            "swipe" -> {
                val x1 = params.get("x1")?.asString ?: "0"
                val y1 = params.get("y1")?.asString ?: "0"
                val x2 = params.get("x2")?.asString ?: "0"
                val y2 = params.get("y2")?.asString ?: "0"
                val dur = params.get("dur")?.asString ?: "300"
                cmdExecutor.execute {
                    try {
                        if (ExportHandle.checkAutoEngine()) {
                            ExportHandle.getHandler().http("/swipe", mapOf(
                                "x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "duration" to dur
                            ))
                        }
                        sendResponse(id, true, "true")
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "swipe failed")
                    }
                }
            }

            "shell" -> {
                cmdExecutor.execute {
                    try {
                        val cmd = params.get("cmd")?.asString ?: ""
                        val result = ExtSystem.shell(cmd)
                        sendResponse(id, true, result ?: "")
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "shell failed")
                    }
                }
            }

            "key" -> {
                cmdExecutor.execute {
                    try {
                        val keycode = params.get("keycode")?.asInt ?: 0
                        ExtSystem.shell("input keyevent $keycode")
                        sendResponse(id, true, "true")
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "key failed")
                    }
                }
            }

            "text" -> {
                cmdExecutor.execute {
                    try {
                        val text = params.get("text")?.asString ?: ""
                        ExtSystem.shell("input text '${text.replace("'", "\\'")}'")
                        sendResponse(id, true, "true")
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "text failed")
                    }
                }
            }

            "start_stream" -> {
                val quality = params.get("quality")?.asInt ?: 70
                val interval = params.get("interval")?.asLong ?: 33L
                val maxHeight = params.get("maxHeight")?.asInt ?: 1280
                streamQuality.set(quality)
                streamInterval = interval
                ScreenCapture.setStreamParams(maxHeight, quality)
                startStreaming()
                sendResponse(id, true, "streaming")
            }

            "stop_stream" -> {
                stopStreaming()
                sendResponse(id, true, "stopped")
            }

            "adjust_stream" -> {
                val quality = params.get("quality")?.asInt ?: -1
                val interval = params.get("interval")?.asLong ?: -1L
                val maxHeight = params.get("maxHeight")?.asInt ?: -1
                if (quality > 0) streamQuality.set(quality)
                if (interval > 0) streamInterval = interval
                // 仅传有效值给 ScreenCapture，避免 -1 覆盖当前参数
                val effectiveMaxH = if (maxHeight > 0) maxHeight else ScreenCapture.getStreamMaxHeight()
                val effectiveQ = if (quality > 0) quality else streamQuality.get()
                ScreenCapture.setStreamParams(effectiveMaxH, effectiveQ)
                ExtSystem.printInfo("[$TAG] 自适应调参: quality=$effectiveQ interval=$streamInterval maxHeight=$effectiveMaxH")
                sendResponse(id, true, """{"quality":${streamQuality.get()},"interval":$streamInterval,"maxHeight":${ScreenCapture.getStreamMaxHeight()}}""")
            }

            "start_project" -> {
                cmdExecutor.execute {
                    try {
                        val name = params.get("name")?.asString ?: ""
                        PyEngine.startProject(name)
                        sendResponse(id, true, "started")
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "start failed")
                    }
                }
            }

            "stop_project" -> {
                cmdExecutor.execute {
                    try {
                        PyEngine.abortProject()
                        sendResponse(id, true, "stopped")
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "stop failed")
                    }
                }
            }

            "project_list" -> {
                cmdExecutor.execute {
                    try {
                        val projects = YyProject.scanProject()
                        val list = projects.map { mapOf("name" to it.name, "path" to it.folderPath) }
                        sendResponse(id, true, gson.toJson(list))
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "list failed")
                    }
                }
            }

            "project_status" -> {
                cmdExecutor.execute {
                    try {
                        val status = PyEngine.getProjectRunningStatus()
                        val result = JsonObject().apply {
                            addProperty("running", status?.first ?: false)
                            addProperty("project", status?.second ?: "")
                        }
                        sendResponse(id, true, gson.toJson(result))
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "status failed")
                    }
                }
            }

            "file_list" -> {
                cmdExecutor.execute {
                    try {
                        val path = params.get("path")?.asString ?: "/sdcard"
                        if (!isPathSafe(path)) { sendResponseSync(id, false, "路径不允许: $path"); return@execute }
                        val dir = File(path)
                        val files = dir.listFiles()?.map { f ->
                            mapOf(
                                "name" to f.name,
                                "path" to f.absolutePath,
                                "is_dir" to f.isDirectory,
                                "size" to f.length(),
                                "modified" to f.lastModified()
                            )
                        } ?: emptyList()
                        sendResponse(id, true, gson.toJson(files))
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "file_list failed")
                    }
                }
            }

            "file_delete" -> {
                cmdExecutor.execute {
                    try {
                        val path = params.get("path")?.asString ?: ""
                        if (!isPathSafe(path)) { sendResponseSync(id, false, "路径不允许: $path"); return@execute }
                        val file = File(path)
                        if (!file.exists()) { sendResponseSync(id, false, "路径不存在: $path"); return@execute }
                        val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                        if (ok) sendResponse(id, true, "deleted")
                        else sendResponseSync(id, false, "删除失败: $path")
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "delete failed")
                    }
                }
            }

            "file_mkdir" -> {
                cmdExecutor.execute {
                    try {
                        val path = params.get("path")?.asString ?: ""
                        if (!isPathSafe(path)) { sendResponseSync(id, false, "路径不允许: $path"); return@execute }
                        val ok = File(path).mkdirs()
                        if (ok || File(path).isDirectory) sendResponse(id, true, "created")
                        else sendResponseSync(id, false, "创建目录失败: $path")
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "mkdir failed")
                    }
                }
            }

            "file_upload" -> {
                cmdExecutor.execute {
                    try {
                        val path = params.get("path")?.asString ?: ""
                        val contentBase64 = params.get("content")?.asString ?: ""
                        if (!isPathSafe(path)) { sendResponseSync(id, false, "路径不允许: $path"); return@execute }
                        if (contentBase64.isEmpty()) {
                            sendResponseSync(id, false, "Missing content")
                            return@execute
                        }
                        val bytes = android.util.Base64.decode(contentBase64, android.util.Base64.DEFAULT)
                        val f = File(path)
                        f.parentFile?.mkdirs()
                        f.writeBytes(bytes)
                        sendResponse(id, true, "ok")
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "file_upload failed")
                    }
                }
            }

            "file_download" -> {
                cmdExecutor.execute {
                    try {
                        val path = params.get("path")?.asString ?: ""
                        if (!isPathSafe(path)) { sendResponseSync(id, false, "路径不允许: $path"); return@execute }
                        val file = File(path)
                        if (!file.isFile) { sendResponseSync(id, false, "文件不存在: $path"); return@execute }
                        if (file.length() > 50 * 1024 * 1024) { sendResponseSync(id, false, "文件过大(>50MB)"); return@execute }
                        // Stream base64 in chunks to avoid 2x memory peak
                        val chunkSize = 3 * 1024 * 1024 // 3MB raw → 4MB base64 (must be multiple of 3)
                        val sb = StringBuilder((file.length() * 4 / 3 + 4).toInt())
                        file.inputStream().buffered().use { input ->
                            val buf = ByteArray(chunkSize)
                            var read: Int
                            while (input.read(buf).also { read = it } > 0) {
                                val chunk = if (read == buf.size) buf else buf.copyOf(read)
                                sb.append(android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP))
                            }
                        }
                        sendResponse(id, true, sb.toString())
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "file_download failed")
                    }
                }
            }

            "set_clipboard" -> {
                cmdExecutor.execute {
                    try {
                        val text = params.get("text")?.asString ?: ""
                        if (ExportHandle.checkAutoEngine()) {
                            ExportHandle.getHandler().http("/set_clipboard", mapOf("text" to text))
                        }
                        sendResponse(id, true, "ok")
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "set_clipboard failed")
                    }
                }
            }

            "get_clipboard" -> {
                cmdExecutor.execute {
                    try {
                        val result = if (ExportHandle.checkAutoEngine()) {
                            ExportHandle.getHandler().http("/get_clipboard", emptyMap())
                        } else ""
                        sendResponse(id, true, result ?: "")
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "get_clipboard failed")
                    }
                }
            }

            "paste" -> {
                cmdExecutor.execute {
                    try {
                        val text = params.get("text")?.asString ?: ""
                        if (ExportHandle.checkAutoEngine()) {
                            ExportHandle.getHandler().http("/set_clipboard", mapOf("text" to text))
                        }
                        Thread.sleep(100)
                        ExtSystem.shell("input keyevent 279")
                        sendResponse(id, true, "ok")
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "paste failed")
                    }
                }
            }

            "ime_list" -> {
                cmdExecutor.execute {
                    try {
                        ExtSystem.shell("ime enable ${Const.YY_INPUT_METHOD_ID}")
                        val output = ExtSystem.shell("ime list -s").trim()
                        val imes = output.split("\n").filter { it.isNotBlank() }
                        sendResponse(id, true, gson.toJson(imes))
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "ime_list failed")
                    }
                }
            }

            "ime_get" -> {
                cmdExecutor.execute {
                    try {
                        val current = ExtSystem.shell("settings get secure default_input_method").trim()
                        sendResponse(id, true, current)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "ime_get failed")
                    }
                }
            }

            "ime_set" -> {
                cmdExecutor.execute {
                    try {
                        val imeId = params.get("ime_id")?.asString ?: ""
                        if (imeId.isBlank()) { sendResponseSync(id, false, "ime_id is required"); return@execute }
                        // Sanitize: only allow valid IME component names (package/class)
                        if (!imeId.matches(Regex("^[a-zA-Z0-9_.]+/[a-zA-Z0-9_.]+$"))) {
                            sendResponseSync(id, false, "Invalid ime_id format"); return@execute
                        }
                        ExtSystem.shell("ime enable '$imeId'")
                        ExtSystem.shell("ime set '$imeId'")
                        sendResponse(id, true, "ok")
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "ime_set failed")
                    }
                }
            }

            "install_apk" -> {
                cmdExecutor.execute {
                    try {
                        val path = params.get("path")?.asString ?: ""
                        if (!isPathSafe(path)) { sendResponseSync(id, false, "路径不允许: $path"); return@execute }
                        val file = File(path)
                        if (!file.isFile) { sendResponseSync(id, false, "APK文件不存在: $path"); return@execute }
                        val result = if (ExportHandle.checkAutoEngine()) {
                            ExportHandle.getHandler().http("/install_apk", mapOf("path" to path))
                        } else {
                            ExtSystem.shell("pm install -r -t '${path.replace("'", "'\\''")}'")
                        }
                        val output = result ?: ""
                        if (output.contains("Success")) {
                            sendResponse(id, true, "ok")
                        } else {
                            sendResponseSync(id, false, "安装失败: $output")
                        }
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "install_apk failed")
                    }
                }
            }

            "reboot_engine" -> {
                sendResponse(id, true, "rebooting")
                cmdExecutor.execute {
                    SystemClock.sleep(1000)
                    AppProcess.rebootPyEngine("@yyds-con")
                }
            }

            // ── IDE proxy actions: forward to local yyds.py HTTP ──

            "run_code" -> {
                cmdExecutor.execute {
                    try {
                        val code = params.get("code")?.asString ?: ""
                        val body = gson.toJson(mapOf("code" to code))
                        val result = httpPostLocal("/engine/run-code", body)
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "run_code failed")
                    }
                }
            }

            "screenshot" -> {
                cmdExecutor.execute {
                    try {
                        val data = ScreenCapture.getBitmapData(80)
                        if (data.isNotEmpty()) {
                            val base64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
                            sendResponse(id, true, base64)
                        } else {
                            sendResponseSync(id, false, "screenshot empty")
                        }
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "screenshot failed")
                    }
                }
            }

            "ui_dump" -> {
                cmdExecutor.execute {
                    try {
                        val result = httpGetLocal("/uia_dump")
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "ui_dump failed")
                    }
                }
            }

            "foreground" -> {
                cmdExecutor.execute {
                    try {
                        val fg = if (ExportHandle.checkAutoEngine()) {
                            Foreground.getCurrentForegroundString() ?: ""
                        } else ""
                        sendResponse(id, true, fg)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "foreground failed")
                    }
                }
            }

            "file_read" -> {
                cmdExecutor.execute {
                    try {
                        val path = params.get("path")?.asString ?: ""
                        if (!isPathSafe(path)) { sendResponseSync(id, false, "路径不允许: $path"); return@execute }
                        val result = httpGetLocal("/file/read-text?path=${java.net.URLEncoder.encode(path, "UTF-8")}")
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "file_read failed")
                    }
                }
            }

            "file_write" -> {
                cmdExecutor.execute {
                    try {
                        val path = params.get("path")?.asString ?: ""
                        val content = params.get("content")?.asString ?: ""
                        if (!isPathSafe(path)) { sendResponseSync(id, false, "路径不允许: $path"); return@execute }
                        val body = gson.toJson(mapOf("path" to path, "content" to content))
                        val result = httpPostLocal("/file/write-text", body)
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "file_write failed")
                    }
                }
            }

            "file_exists" -> {
                cmdExecutor.execute {
                    try {
                        val path = params.get("path")?.asString ?: ""
                        if (!isPathSafe(path)) { sendResponseSync(id, false, "路径不允许: $path"); return@execute }
                        val result = httpGetLocal("/file/exists?path=${java.net.URLEncoder.encode(path, "UTF-8")}")
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "file_exists failed")
                    }
                }
            }

            "file_rename" -> {
                cmdExecutor.execute {
                    try {
                        val oldPath = params.get("old_path")?.asString ?: ""
                        val newPath = params.get("new_path")?.asString ?: ""
                        if (!isPathSafe(oldPath) || !isPathSafe(newPath)) {
                            sendResponseSync(id, false, "路径不允许"); return@execute
                        }
                        val body = gson.toJson(mapOf("old_path" to oldPath, "new_path" to newPath))
                        val result = httpPostLocal("/file/rename", body)
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "file_rename failed")
                    }
                }
            }

            "pip_list" -> {
                cmdExecutor.execute {
                    try {
                        val result = httpGetLocal("/pip/list")
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "pip_list failed")
                    }
                }
            }

            "pip_install" -> {
                cmdExecutor.execute {
                    try {
                        val name = params.get("name")?.asString ?: ""
                        val body = gson.toJson(mapOf("name" to name))
                        val result = httpPostLocal("/pip/install", body)
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "pip_install failed")
                    }
                }
            }

            "pip_uninstall" -> {
                cmdExecutor.execute {
                    try {
                        val name = params.get("name")?.asString ?: ""
                        val body = gson.toJson(mapOf("name" to name))
                        val result = httpPostLocal("/pip/uninstall", body)
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "pip_uninstall failed")
                    }
                }
            }

            // ── Phase 2: Pip extended + Package actions ──

            "pip_outdated" -> {
                cmdExecutor.execute {
                    try {
                        val result = httpGetLocal("/pip/outdated")
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "pip_outdated failed")
                    }
                }
            }

            "pip_show" -> {
                cmdExecutor.execute {
                    try {
                        val name = params.get("name")?.asString ?: ""
                        val result = httpGetLocal("/pip/show?name=${java.net.URLEncoder.encode(name, "UTF-8")}")
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "pip_show failed")
                    }
                }
            }

            "pip_upgrade" -> {
                cmdExecutor.execute {
                    try {
                        val name = params.get("name")?.asString ?: ""
                        val body = gson.toJson(mapOf("name" to name))
                        val result = httpPostLocal("/pip/upgrade", body)
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "pip_upgrade failed")
                    }
                }
            }

            "pip_search" -> {
                cmdExecutor.execute {
                    try {
                        val name = params.get("name")?.asString ?: ""
                        val result = httpGetLocal("/pip/search?name=${java.net.URLEncoder.encode(name, "UTF-8")}")
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "pip_search failed")
                    }
                }
            }

            "package_build" -> {
                cmdExecutor.execute {
                    try {
                        val body = gson.toJson(params)
                        val result = httpPostLocal("/package/build", body)
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "package_build failed")
                    }
                }
            }

            "package_list" -> {
                cmdExecutor.execute {
                    try {
                        val result = httpGetLocal("/package/list")
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "package_list failed")
                    }
                }
            }

            "package_installed_apps" -> {
                cmdExecutor.execute {
                    try {
                        val result = httpGetLocal("/package/installed-apps")
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "package_installed_apps failed")
                    }
                }
            }

            "package_app_icon" -> {
                cmdExecutor.execute {
                    try {
                        val pkg = params.get("pkg")?.asString ?: ""
                        val result = httpGetLocal("/package/app-icon?pkg=${java.net.URLEncoder.encode(pkg, "UTF-8")}")
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "package_app_icon failed")
                    }
                }
            }

            // ── Phase 3: 图色工具 + OCR ──

            "get_color" -> {
                cmdExecutor.execute {
                    try {
                        val x = params.get("x")?.asString ?: params.get("x")?.asInt?.toString() ?: "0"
                        val y = params.get("y")?.asString ?: params.get("y")?.asInt?.toString() ?: "0"
                        val result = if (ExportHandle.checkAutoEngine()) {
                            ExportHandle.getHandler().http("/get_color", mapOf("x" to x, "y" to y))
                        } else "error: engine not ready"
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "get_color failed")
                    }
                }
            }

            "find_color" -> {
                cmdExecutor.execute {
                    try {
                        val p = mutableMapOf<String, String>()
                        params.get("color")?.asString?.let { p["color"] = it }
                        params.get("tolerance")?.asString?.let { p["tolerance"] = it }
                        params.get("x")?.asString?.let { p["x"] = it }
                        params.get("y")?.asString?.let { p["y"] = it }
                        params.get("w")?.asString?.let { p["w"] = it }
                        params.get("h")?.asString?.let { p["h"] = it }
                        val result = if (ExportHandle.checkAutoEngine()) {
                            ExportHandle.getHandler().http("/find_color", p)
                        } else "error: engine not ready"
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "find_color failed")
                    }
                }
            }

            "find_multi_color" -> {
                cmdExecutor.execute {
                    try {
                        val p = mutableMapOf<String, String>()
                        params.get("first_color")?.asString?.let { p["first_color"] = it }
                        params.get("offsets")?.asString?.let { p["offsets"] = it }
                        params.get("tolerance")?.asString?.let { p["tolerance"] = it }
                        params.get("x")?.asString?.let { p["x"] = it }
                        params.get("y")?.asString?.let { p["y"] = it }
                        params.get("w")?.asString?.let { p["w"] = it }
                        params.get("h")?.asString?.let { p["h"] = it }
                        val result = if (ExportHandle.checkAutoEngine()) {
                            ExportHandle.getHandler().http("/find_multi_color", p)
                        } else "error: engine not ready"
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "find_multi_color failed")
                    }
                }
            }

            "find_image" -> {
                cmdExecutor.execute {
                    try {
                        val p = mutableMapOf<String, String>()
                        params.get("image")?.asString?.let { p["image"] = it }
                        params.get("prob")?.asString?.let { p["prob"] = it }
                        params.get("x")?.asString?.let { p["x"] = it }
                        params.get("y")?.asString?.let { p["y"] = it }
                        params.get("w")?.asString?.let { p["w"] = it }
                        params.get("h")?.asString?.let { p["h"] = it }
                        val result = if (ExportHandle.checkAutoEngine()) {
                            ExportHandle.getHandler().http("/find_image", p)
                        } else "error: engine not ready"
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "find_image failed")
                    }
                }
            }

            "match_image" -> {
                cmdExecutor.execute {
                    try {
                        val p = mutableMapOf<String, String>()
                        params.get("image")?.asString?.let { p["image"] = it }
                        params.get("prob")?.asString?.let { p["prob"] = it }
                        params.get("x")?.asString?.let { p["x"] = it }
                        params.get("y")?.asString?.let { p["y"] = it }
                        params.get("w")?.asString?.let { p["w"] = it }
                        params.get("h")?.asString?.let { p["h"] = it }
                        val result = if (ExportHandle.checkAutoEngine()) {
                            ExportHandle.getHandler().http("/match_image", p)
                        } else "error: engine not ready"
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "match_image failed")
                    }
                }
            }

            "screen_ocr" -> {
                cmdExecutor.execute {
                    try {
                        val p = mutableMapOf<String, String>()
                        params.get("use_gpu")?.asString?.let { p["use_gpu"] = it }
                        val result = if (ExportHandle.checkAutoEngine()) {
                            ExportHandle.getHandler().http("/screen_ocr", p)
                        } else "error: engine not ready"
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "screen_ocr failed")
                    }
                }
            }

            "image_ocr" -> {
                cmdExecutor.execute {
                    try {
                        val p = mutableMapOf<String, String>()
                        params.get("image")?.asString?.let { p["image"] = it }
                        params.get("use_gpu")?.asString?.let { p["use_gpu"] = it }
                        val result = if (ExportHandle.checkAutoEngine()) {
                            ExportHandle.getHandler().http("/image_ocr", p)
                        } else "error: engine not ready"
                        sendResponse(id, true, result)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "image_ocr failed")
                    }
                }
            }

            // --- Agent control + history (forward to CPython HTTP server) ---
            "agent_status", "agent_history", "agent_history_detail", "agent_history_clear",
            "agent_run", "agent_stop", "agent_config_get", "agent_config_set",
            "agent_providers", "agent_models", "agent_takeover", "agent_resume",
            "agent_test_connection" -> {
                cmdExecutor.execute {
                    try {
                        // Determine HTTP path, method, and optional request body
                        val path: String
                        val method: String
                        val requestBody: String?
                        when (action) {
                            "agent_status" -> { path = "/agent/status"; method = "GET"; requestBody = null }
                            "agent_history" -> { path = "/agent/history"; method = "GET"; requestBody = null }
                            "agent_history_detail" -> {
                                val runId = params.get("run_id")?.asString ?: ""
                                path = "/agent/history/$runId"; method = "GET"; requestBody = null
                            }
                            "agent_history_clear" -> { path = "/agent/history"; method = "DELETE"; requestBody = null }
                            "agent_run" -> {
                                path = "/agent/run"; method = "POST"
                                requestBody = com.google.gson.JsonObject().apply {
                                    addProperty("instruction", params.get("instruction")?.asString ?: "")
                                }.let { com.google.gson.Gson().toJson(it) }
                            }
                            "agent_stop" -> { path = "/agent/stop"; method = "GET"; requestBody = null }
                            "agent_config_get" -> { path = "/agent/config"; method = "GET"; requestBody = null }
                            "agent_config_set" -> {
                                path = "/agent/config"; method = "POST"
                                requestBody = com.google.gson.Gson().toJson(params)
                            }
                            "agent_providers" -> { path = "/agent/providers"; method = "GET"; requestBody = null }
                            "agent_models" -> {
                                val provider = params.get("provider")?.asString ?: ""
                                path = "/agent/models?provider=${java.net.URLEncoder.encode(provider, "UTF-8")}"
                                method = "GET"; requestBody = null
                            }
                            "agent_takeover" -> { path = "/agent/takeover"; method = "POST"; requestBody = "{}" }
                            "agent_resume" -> { path = "/agent/resume"; method = "POST"; requestBody = "{}" }
                            "agent_test_connection" -> {
                                path = "/agent/test-connection"; method = "POST"
                                requestBody = com.google.gson.Gson().toJson(params)
                            }
                            else -> { path = "/agent/status"; method = "GET"; requestBody = null }
                        }
                        val url = "http://127.0.0.1:61140$path"
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = method
                        conn.connectTimeout = 5000
                        conn.readTimeout = 30000
                        if (requestBody != null) {
                            conn.doOutput = true
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.outputStream.use { it.write(requestBody.toByteArray()) }
                        }
                        val code = conn.responseCode
                        val body = if (code in 200..299) {
                            conn.inputStream.bufferedReader().readText()
                        } else {
                            conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                        }
                        conn.disconnect()
                        sendResponse(id, code in 200..299, body)
                    } catch (e: Exception) {
                        sendResponseSync(id, false, e.message ?: "$action failed")
                    }
                }
            }

            else -> {
                ExtSystem.printInfo("[$TAG] 未知action: $action")
                sendResponse(id, false, ">> Unknown action: $action")
            }
        }
    }

    // --- WebRTC signaling ---

    private val rtcChannels = java.util.concurrent.ConcurrentHashMap<String, WebRtcDataChannel>()

    private suspend fun handleRtcOffer(obj: JsonObject) {
        val browserId = obj.get("browser_id")?.asString ?: return
        val sdp = obj.get("sdp")?.asString ?: return
        ExtSystem.printInfo("[$TAG] 收到RTC Offer from browser: $browserId")

        val s = session ?: return
        // Close existing channel for this browser if any
        rtcChannels.remove(browserId)?.close()

        try {
            val channel = WebRtcDataChannel(browserId, s)
            rtcChannels[browserId] = channel
            channel.handleOffer(sdp)
        } catch (e: Exception) {
            ExtSystem.printDebugError("[$TAG] WebRTC offer处理失败，浏览器将回退到WS中继", e)
            rtcChannels.remove(browserId)?.close()
        }
    }

    private suspend fun handleRtcIce(obj: JsonObject) {
        val browserId = obj.get("browser_id")?.asString ?: return
        val candidate = obj.get("candidate")?.asString ?: return
        ExtSystem.printInfo("[$TAG] 收到ICE candidate from browser: $browserId")

        rtcChannels[browserId]?.addIceCandidate(candidate)
    }

    // --- Streaming ---

    private fun startStreaming() {
        if (isStreaming.getAndSet(true)) return
        ExtSystem.printInfo("[$TAG] 开始截图推流 quality=${streamQuality.get()} interval=${streamInterval}ms")

        streamJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isStreaming.get() && isConnected.get()) {
                try {
                    val frameStart = System.currentTimeMillis()
                    val data = ScreenCapture.getStreamData(streamQuality.get())
                    if (data.isNotEmpty()) {
                        queueFrame(Frame.Binary(true, data))
                    }
                    // 自适应延迟：扣除截图编码耗时，保证帧间隔稳定
                    val elapsed = System.currentTimeMillis() - frameStart
                    val sleepMs = streamInterval - elapsed
                    if (sleepMs > 0) delay(sleepMs)
                } catch (e: Exception) {
                    ExtSystem.printDebugError("[$TAG] 截图推流异常", e)
                    delay(100)
                }
            }
            ExtSystem.printInfo("[$TAG] 截图推流已停止")
        }
    }

    private fun stopStreaming() {
        isStreaming.set(false)
        streamJob?.cancel()
        streamJob = null
        ExtSystem.printInfo("[$TAG] 停止截图推流")
    }

    // --- Response sending (all via sendChannel, never runBlocking) ---

    private fun queueFrame(frame: Frame) {
        sendChannel?.trySend(frame)
    }

    private fun queueJson(obj: JsonObject) {
        queueFrame(Frame.Text(gson.toJson(obj)))
    }

    private fun sendResponse(id: String, success: Boolean, data: String) {
        val response = JsonObject().apply {
            addProperty("type", "response")
            addProperty("id", id)
            addProperty("success", success)
            try {
                val parsed = gson.fromJson(data, com.google.gson.JsonElement::class.java)
                add("data", parsed)
            } catch (_: Exception) {
                addProperty("data", data)
            }
        }
        queueJson(response)
    }

    private fun sendResponseSync(id: String, success: Boolean, data: String) {
        sendResponse(id, success, data)
    }

    private suspend fun sendStatusReport() {
        val (running, project) = try {
            val status = PyEngine.getProjectRunningStatus()
            Pair(status?.first ?: false, status?.second ?: "")
        } catch (_: Exception) {
            Pair(false, "")
        }

        val fg = try {
            if (ExportHandle.checkAutoEngine()) {
                Foreground.getCurrentForegroundString() ?: ""
            } else ""
        } catch (_: Exception) { "" }

        val statusMsg = JsonObject().apply {
            addProperty("type", "status")
            addProperty("running", running)
            addProperty("project", project)
            addProperty("fg", fg)
        }
        queueJson(statusMsg)
    }

    // --- Log forwarding ---

    /**
     * Send a log line to the server. Called from PyOut callback.
     * Non-blocking: uses sendChannel.trySend.
     */
    fun forwardLog(text: String) {
        if (!isConnected.get()) return
        try {
            val logMsg = JsonObject().apply {
                addProperty("type", "log")
                addProperty("text", text)
            }
            queueJson(logMsg)
        } catch (_: Exception) {}
    }

    fun isConnectedToServer(): Boolean = isConnected.get()

    // --- Local HTTP helpers (forward to yyds.py on 127.0.0.1:61140) ---

    private val LOCAL_BASE = "http://127.0.0.1:61140"

    private fun httpGetLocal(path: String): String {
        val conn = java.net.URL("$LOCAL_BASE$path").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        conn.requestMethod = "GET"
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return java.io.BufferedReader(java.io.InputStreamReader(stream, "UTF-8")).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPostLocal(path: String, body: String): String {
        val conn = java.net.URL("$LOCAL_BASE$path").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 60000
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        java.io.OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return java.io.BufferedReader(java.io.InputStreamReader(stream, "UTF-8")).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // --- Security ---

    private val SAFE_PATH_PREFIXES = arrayOf("/sdcard/", "/storage/emulated/", "/data/local/tmp/")

    private fun isPathSafe(path: String): Boolean {
        if (path.isBlank()) return false
        val canonical = try { File(path).canonicalPath } catch (_: Exception) { path }
        return SAFE_PATH_PREFIXES.any { canonical.startsWith(it) }
    }
}
