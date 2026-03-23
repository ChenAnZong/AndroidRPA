package pyengine

import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.tencent.yyds.BuildConfig
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.cio.*
import io.ktor.server.plugins.origin
import io.ktor.server.websocket.*
import io.ktor.server.websocket.WebSockets
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import uiautomator.AppProcess
import uiautomator.DeviceCode
import uiautomator.ExportApi
import uiautomator.ExportHandle
import uiautomator.ExportHttp
import uiautomator.ExtSystem
import uiautomator.tool.ScreenCapture
import uiautomator.util.DateUtil
import java.io.File
import java.net.BindException
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.HashSet
import kotlin.concurrent.thread
import kotlin.jvm.Throws


abstract class LegacyRemoteClient() {
    private val isConnecting = AtomicBoolean(false)
    private val jobs = HashSet<Job>()
    private val IP = "59.47.233.162"
    private val PORT = 8080
    private fun connectServer(imei:String) {
        val handler = CoroutineExceptionHandler { _, e ->
            ExtSystem.printDebugLog("==========ControlCoroutineExceptionHandler==========", e)
        }
        val client = HttpClient(OkHttp) {
            install(io.ktor.client.plugins.websocket.WebSockets)
        }
        val path = "/api?imei=${imei}"

        runBlocking  {
            isConnecting.set(true)
            ExtSystem.printDebugLog("# 做为客户端连接服务器！")
            client.webSocket(method = HttpMethod.Get,
                host = IP,
                port = PORT,
                path = path) {
                try {
                    val thisCon = HandleApiServerConnection(this)
                    ExtSystem.printInfo("连接服务器成功! $thisCon $path")
                    val handleReq =  launch(handler) { thisCon.returnRes() }
                    val handleRes =  launch(handler) { thisCon.handleReq() }
                    val receiveReq = launch(handler) { thisCon.receiveReq() }
                    jobs.add(handleRes)
                    jobs.add(handleReq)
                    jobs.add(receiveReq)
                    receiveReq.join()
                    this.close(CloseReason(CloseReason.Codes.GOING_AWAY, "receiveReq.join() END!"))
                } catch (e:Throwable) {
                    ExtSystem.printDebugLog("连接服务器成功失败！")
                    client.close()
                } finally {
                    jobs.forEach { it.cancel() }
                }
            }
            isConnecting.set(false)
            ExtSystem.printDebugLog("# 断开服务器连接")
        }
    }

    fun keepServerConnect() {
        var imei = ""
        while (true) {
            ExtSystem.printInfo("ConnectServer...")
            try {
                if (imei.isEmpty()) {
                    imei = ExportHandle.http("/imei", hashMapOf<String,String>());
                }
                if (!isConnecting.get()) {
                    connectServer(imei)
                    SystemClock.sleep(4000)
                }
            } catch (e:Throwable) {
                ExtSystem.printDebugError("[Server]保持连接！", e)
            } finally {
                isConnecting.set(false)
            }
            SystemClock.sleep(5000)
        }
    }
}

abstract class WebSocketAsServer:LegacyRemoteClient() {

    private fun logCrawlAsync() {
        PyOut.setConfig(toCache = true, toConsole = false)
        ExtSystem.printDebugLog("logCrawlAsync END")
    }

    private var engine:ApplicationEngine? = null;
    private val logSession: ConcurrentLinkedQueue<DefaultWebSocketServerSession> = ConcurrentLinkedQueue()
    fun stopWsSocket() {
        thread {
            engine?.stop(2000, 2000)
        }
    }

    // Py 引擎的网络服务
    @Throws(BindException::class)
    fun startWsSocket()   {
        logCrawlAsync()
        val gson = GsonBuilder().create()
        var lastSize = 0;
        engine = embeddedServer(CIO, host = "0.0.0.0", port = PyEngine.enginePort, configure = {
            connectionIdleTimeoutSeconds = 60*60
        }) {
            install(WebSockets) {
                pingPeriodMillis = 500
                timeoutMillis = 3600_000
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }
            routing {
                get("/") {
                    call.respondText("Yyds.Auto(${BuildConfig.VERSION_CODE}|${BuildConfig.BUILD_DATE}) py engine, pid=${ExtSystem.pid()}, uid=${ExtSystem.uid()}\n")
                }
                get("/ping") {
                    try {
                        val res = ExportHandle.getHandler().http("/ping", emptyMap())
                        call.respondText(res)
                    } catch(e:Throwable) {
                        ExtSystem.printDebugError("/ping", e)
                        call.respondText("执行错误:" + Log.getStackTraceString(e))
                    }
                }

                // ============================================================
                // WebSocket: 日志流 + 截图流（保留）
                // ============================================================

                webSocket("/log") {
                    ExtSystem.printDebugLog("#### LOG_CONNECT size:${logSession.size}")
                    send(Frame.Text("${DateUtil.getCommonDate()} 连接引擎${if (ExtSystem.uid() == 0) "(ROOT激活)" else ""}成功，开始同步日志\n"))
                    if (logSession.size > 0) {
                        send(Frame.Text("注意:当前存在其它日志流获取请求, 将会被强行关闭\n"))
                        logSession.forEach { it.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "不支持同时连接日志流!")); }
                        logSession.clear()
                    }
                    logSession.add(this)
                    try {
                        while (logSession.contains(this)) {
                            if (PyOut.logQueue.isNotEmpty()) {
                                PyOut.logQueue.poll()?.let { send(it); }
                            } else {
                                delay(1000)
                            }
                        }
                    } catch (e:Throwable) {
                        ExtSystem.printDebugError("/log", e)
                        close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, Log.getStackTraceString(e)))
                    } finally {
                        close()
                        logSession.remove(this)
                    }
                    ExtSystem.printDebugLog("#### LOG_DISCONNECT")
                }

                webSocket("/shot/{quality}/{count}/{interval}") {
                    ExtSystem.printDebugLog("#### SHOT ")
                    try {
                        val quality = call.parameters["quality"]!!.toInt()
                        val count = call.parameters["count"]!!.toInt()
                        val interval = call.parameters["interval"]!!.toLong()
                        for (i in 1..count) {
                            val s = System.currentTimeMillis()
                            val d = ScreenCapture.getBitmapData(quality)
                            if (d.isNotEmpty())
                            {
                                if (lastSize == d.size) {
                                    continue
                                }
                                lastSize = d.size
                                send(d)
                                ExtSystem.printDebugLog("截图发送${quality}/${i}/${interval}: ${d.size}/${System.currentTimeMillis() - s}ms")
                                SystemClock.sleep(interval)
                            }
                        }
                    } catch (e:Throwable) {
                        ExtSystem.printDebugError("/shot", e)
                        close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, Log.getStackTraceString(e)))
                    } finally {
                        close()
                        ExtSystem.printDebugLog("#### SHOT END!!")
                    }
                }

                // ============================================================
                // HTTP REST: 项目管理
                // ============================================================

                get("/project/list") {
                    try {
                        call.respondText(gson.toJson(YyProject.scanProject()), ContentType.Application.Json)
                    } catch (e:Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                get("/project/status") {
                    try {
                        val s = PyEngine.getProjectRunningStatus()
                        call.respondText(gson.toJson(mapOf(
                            "running" to (s?.first ?: false),
                            "project" to s?.second
                        )), ContentType.Application.Json)
                    } catch (e:Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                get("/project/start") {
                    try {
                        val projectName = call.request.queryParameters["name"]
                            ?: return@get call.respondText(gson.toJson(mapOf("error" to "缺少name参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        thread { PyEngine.startProject(projectName) }
                        call.respondText(gson.toJson(mapOf("success" to true, "project" to projectName)), ContentType.Application.Json)
                    } catch (e:Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                get("/project/stop") {
                    try {
                        PyEngine.abortProject()
                        call.respondText(gson.toJson(mapOf("success" to true)), ContentType.Application.Json)
                    } catch (e:Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                // ============================================================
                // HTTP REST: 引擎控制
                // ============================================================

                post("/engine/run-code") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java)
                        val code = body["code"]?.toString()
                            ?: return@post call.respondText(gson.toJson(mapOf("error" to "缺少code参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        PyEngine.runCodeSnippet(code)
                        call.respondText(gson.toJson(mapOf("success" to true)), ContentType.Application.Json)
                    } catch (e:Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                post("/engine/reboot") {
                    try {
                        call.respondText(gson.toJson(mapOf("success" to true)), ContentType.Application.Json)
                        thread {
                            SystemClock.sleep(3000)
                            AppProcess.rebootPyEngine("@HttpApi")
                        }
                    } catch (e:Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                post("/engine/shell") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java)
                        val command = body["command"]?.toString()
                            ?: return@post call.respondText(gson.toJson(mapOf("error" to "缺少command参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val ret = ExtSystem.shell(command)
                        call.respondText(gson.toJson(mapOf("success" to true, "result" to ret)), ContentType.Application.Json)
                    } catch (e:Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                post("/engine/click") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java)
                        val x = body["x"]?.toString() ?: return@post call.respondText(gson.toJson(mapOf("error" to "缺少x参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val y = body["y"]?.toString() ?: return@post call.respondText(gson.toJson(mapOf("error" to "缺少y参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        if (ExportHandle.checkAutoEngine()) {
                            val ret = ExportHandle.getHandler().http("/touch", mapOf("x" to x, "y" to y))
                            call.respondText(gson.toJson(mapOf("success" to ret.equals("true"))), ContentType.Application.Json)
                        } else {
                            call.respondText(gson.toJson(mapOf("error" to "连接设备自动引擎失败")), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                        }
                    } catch (e:Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                post("/engine/auto") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java) as Map<String, String>
                        val uri = body["uri"] ?: return@post call.respondText(gson.toJson(mapOf("error" to "缺少uri参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        if (ExportHandle.checkAutoEngine()) {
                            val ret = ExportHandle.getHandler().http(uri, body)
                            call.respondText(gson.toJson(mapOf("success" to true, "result" to ret)), ContentType.Application.Json)
                        } else {
                            call.respondText(gson.toJson(mapOf("error" to "连接设备自动引擎失败")), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                        }
                    } catch (e:Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                get("/engine/foreground") {
                    try {
                        if (ExportHandle.checkAutoEngine()) {
                            val ret = ExportHandle.getHandler().http("/foreground", emptyMap())
                            call.respondText(gson.toJson(mapOf("success" to true, "result" to ret)), ContentType.Application.Json)
                        } else {
                            call.respondText(gson.toJson(mapOf("error" to "连接自动引擎失败")), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                        }
                    } catch (e:Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                // ============================================================
                // HTTP REST + WebSocket: Logcat 日志收集
                // ============================================================

                get("/logcat/dump") {
                    try {
                        val level = call.request.queryParameters["level"] ?: "V"
                        val pid = call.request.queryParameters["pid"]
                        val tag = call.request.queryParameters["tag"]
                        val format = call.request.queryParameters["format"] ?: "threadtime"
                        val lines = call.request.queryParameters["lines"]?.toIntOrNull() ?: 0
                        val since = call.request.queryParameters["since"] // timestamp like "01-01 00:00:00.000"

                        val cmd = buildLogcatCommand(
                            dump = true, level = level, pid = pid, tag = tag,
                            format = format, lines = lines, since = since
                        )
                        val result = ExtSystem.shell(cmd)
                        call.respondText(gson.toJson(mapOf(
                            "success" to true,
                            "data" to result,
                            "lineCount" to result.lines().count { it.isNotBlank() },
                            "command" to cmd
                        )), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        ExtSystem.printDebugError("/logcat/dump", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                post("/logcat/clear") {
                    try {
                        val body = try { gson.fromJson(call.receiveText(), Map::class.java) } catch (_: Exception) { null }
                        val buffer = (body?.get("buffer") as? String) ?: "all"
                        val cmd = when (buffer) {
                            "main" -> "logcat -b main -c"
                            "system" -> "logcat -b system -c"
                            "crash" -> "logcat -b crash -c"
                            "events" -> "logcat -b events -c"
                            else -> "logcat -c"
                        }
                        ExtSystem.shell(cmd)
                        call.respondText(gson.toJson(mapOf("success" to true)), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        ExtSystem.printDebugError("/logcat/clear", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                get("/logcat/buffers") {
                    try {
                        val raw = ExtSystem.shell("logcat -g 2>&1")
                        call.respondText(gson.toJson(mapOf("success" to true, "data" to raw)), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                webSocket("/logcat/stream") {
                    val params = call.request.queryParameters
                    val level = params["level"] ?: "V"
                    val pid = params["pid"]
                    val tag = params["tag"]
                    val format = params["format"] ?: "threadtime"

                    val cmd = buildLogcatCommand(
                        dump = false, level = level, pid = pid, tag = tag, format = format
                    )
                    ExtSystem.printDebugLog("#### LOGCAT_STREAM start: $cmd")

                    var process: Process? = null
                    try {
                        // 启动 logcat 进程（持续输出）
                        process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                        val reader = process.inputStream.bufferedReader()

                        // 启动一个协程读取 WebSocket 消息（用于接收 close/control 指令）
                        val controlJob = launch {
                            try {
                                for (frame in incoming) {
                                    if (frame is Frame.Text) {
                                        val text = frame.readText()
                                        if (text == "stop" || text == "close") break
                                    }
                                }
                            } catch (_: ClosedReceiveChannelException) {}
                        }

                        // 主循环：逐行读取 logcat 输出并发送
                        val sendJob = launch(Dispatchers.IO) {
                            try {
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    if (!isActive) break
                                    val l = line ?: continue
                                    if (l.isBlank()) continue
                                    send(Frame.Text(l))
                                }
                            } catch (_: Exception) {}
                        }

                        // 等待任一结束
                        controlJob.join()
                        sendJob.cancel()
                    } catch (e: Throwable) {
                        ExtSystem.printDebugError("/logcat/stream", e)
                    } finally {
                        try { process?.destroy() } catch (_: Exception) {}
                        try { process?.destroyForcibly() } catch (_: Exception) {}
                        try { close() } catch (_: Exception) {}
                        ExtSystem.printDebugLog("#### LOGCAT_STREAM end")
                    }
                }

                // ============================================================
                // HTTP REST: 截图与UI Dump
                // ============================================================

                get("/screen/{quality?}") {
                    try {
                        var isForce = false
                        val isNoCache = call.request.queryParameters["no-cache"]
                        if (isNoCache != null) {
                            isForce = true
                        }
                        val quality = call.parameters["quality"]?.toInt() ?: (call.request.queryParameters["quality"]?.toInt() ?: 100)
                        val d = ScreenCapture.getBitmapData(quality)
                        if (d.size == lastSize && !isForce) {
                            call.respondBytes("".toByteArray())
                        } else if (d.isNotEmpty()) {
                            lastSize = d.size
                            call.respondBytes(d, ContentType.Image.JPEG)
                            ExtSystem.printDebugLog("HTTP截图发送${quality}")
                        } else {
                            call.respondText(gson.toJson(mapOf("error" to "截图数据为空")), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                        }
                    } catch (e:Throwable) {
                        ExtSystem.printDebugError("/http/shot", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                get("/screenshot") {
                    try {
                        val saveTo = "${PyEngine.getProjectDir()}/screenshot.png"
                        val screenFile = File(saveTo)
                        if (screenFile.exists()) { screenFile.delete() }
                        if (ScreenCapture.writeTo(saveTo) && screenFile.exists()) {
                            call.respondFile(screenFile.parentFile!!, screenFile.name)
                        } else {
                            call.respondText(gson.toJson(mapOf("error" to "截图失败")), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                        }
                    } catch (e:Throwable) {
                        ExtSystem.printDebugError("/screenshot", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                get("/uia_dump") {
                    try {
                        if (ExportHandle.checkAutoEngine()) {
                            val saveTo = "/data/local/tmp/dump.xml"
                            ExportHandle.getHandler().http("/uia_dump",
                                mapOf(RPC_MAP_KEY.FILE_PATH to saveTo, "all_window" to "true"))
                            val dumpFile = File(saveTo)
                            if (dumpFile.exists()) {
                                call.respondText(dumpFile.readText(), ContentType.Text.Xml)
                            } else {
                                call.respondText(gson.toJson(mapOf("error" to "dump文件不存在")), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                            }
                        } else {
                            call.respondText(gson.toJson(mapOf("error" to "auto engine未启动")), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                        }
                    } catch (e:Throwable) {
                        ExtSystem.printDebugError("/uia_dump", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                // ============================================================
                // HTTP REST: 文件操作（守护进程有ROOT/SHELL权限）
                // ============================================================

                get("/file/exists") {
                    try {
                        val path = call.request.queryParameters["path"]
                            ?: return@get call.respondText(gson.toJson(mapOf("error" to "缺少path参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        call.respondText(gson.toJson(mapOf("exists" to File(path).exists())), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                get("/file/read-text") {
                    try {
                        val path = call.request.queryParameters["path"]
                            ?: return@get call.respondText(gson.toJson(mapOf("error" to "缺少path参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val file = File(path)
                        if (file.exists() && file.isFile) {
                            call.respondText(file.readText())
                        } else {
                            call.respondText(gson.toJson(mapOf("error" to "文件不存在: $path")), ContentType.Application.Json, HttpStatusCode.NotFound)
                        }
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                post("/file/write-text") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java)
                        val path = body["path"]?.toString()
                            ?: return@post call.respondText(gson.toJson(mapOf("error" to "缺少path参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val content = body["content"]?.toString() ?: ""
                        val file = File(path)
                        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
                        file.writeText(content)
                        call.respondText(gson.toJson(mapOf("success" to true)), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                get("/file/list") {
                    try {
                        val path = call.request.queryParameters["path"]
                            ?: return@get call.respondText(gson.toJson(mapOf("error" to "缺少path参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val dir = File(path)
                        if (!dir.exists() || !dir.isDirectory) {
                            return@get call.respondText(gson.toJson(mapOf("error" to "目录不存在: $path")), ContentType.Application.Json, HttpStatusCode.NotFound)
                        }
                        val files = dir.listFiles()?.map { f ->
                            mapOf(
                                "name" to f.name,
                                "path" to f.absolutePath,
                                "isDir" to f.isDirectory,
                                "size" to f.length(),
                                "lastModified" to f.lastModified(),
                                "readable" to f.canRead(),
                                "writable" to f.canWrite()
                            )
                        }?.sortedWith(compareBy<Map<String, Any?>> { !(it["isDir"] as Boolean) }.thenBy { (it["name"] as String).lowercase() })
                            ?: emptyList()
                        call.respondText(gson.toJson(mapOf("files" to files, "parent" to dir.parent)), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                get("/file/delete") {
                    try {
                        val path = call.request.queryParameters["path"]
                            ?: return@get call.respondText(gson.toJson(mapOf("error" to "缺少path参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val file = File(path)
                        val deleted = if (file.exists()) file.deleteRecursively() else false
                        call.respondText(gson.toJson(mapOf("success" to deleted)), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                post("/file/rename") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java)
                        val oldPath = body["oldPath"]?.toString()
                            ?: return@post call.respondText(gson.toJson(mapOf("error" to "缺少oldPath参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val newName = body["newName"]?.toString()
                            ?: return@post call.respondText(gson.toJson(mapOf("error" to "缺少newName参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val file = File(oldPath)
                        val newFile = File(file.parentFile, newName)
                        val renamed = if (file.exists()) file.renameTo(newFile) else false
                        call.respondText(gson.toJson(mapOf("success" to renamed, "newPath" to newFile.absolutePath)), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                get("/file/mkdir") {
                    try {
                        val path = call.request.queryParameters["path"]
                            ?: return@get call.respondText(gson.toJson(mapOf("error" to "缺少path参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val dir = File(path)
                        val created = dir.mkdirs()
                        val success = created || dir.exists()
                        call.respondText(gson.toJson(mapOf("success" to success)), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                get("/file/last-modified") {
                    try {
                        val path = call.request.queryParameters["path"]
                            ?: return@get call.respondText(gson.toJson(mapOf("error" to "缺少path参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val file = File(path)
                        if (file.exists()) {
                            call.respondText(gson.toJson(mapOf("lastModified" to file.lastModified())), ContentType.Application.Json)
                        } else {
                            call.respondText(gson.toJson(mapOf("lastModified" to 0)), ContentType.Application.Json)
                        }
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                // ============================================================
                // HTTP REST: 文件传输
                // ============================================================

                get("/pull_file") {
                    try {
                        val path = call.request.queryParameters["path"]
                        val file = File(path!!)
                        if (file.isFile) {
                            call.respondFile(file.parentFile!!, file.name)
                        } else {
                            call.respondText("path:${path} not exists", status = HttpStatusCode.Forbidden)
                        }
                    } catch (e:Throwable) {
                        ExtSystem.printDebugError("/pull_file", e)
                        call.respondText(Log.getStackTraceString(e))
                    }
                }
                post("/post_file") {
                    try {
                        var path:String? = null
                        val mp = call.receiveMultipart()
                        var fileName:String? = null
                        var data:ByteArray? = null
                        mp.forEachPart { part ->
                            when(part) {
                                is PartData.FormItem -> {
                                    if (part.name == "path") path = part.value
                                }
                                is PartData.FileItem -> {
                                    fileName = part.originalFileName
                                    data = part.streamProvider().readBytes()
                                }
                                else -> {
                                    ExtSystem.printInfo("/post_file:", part.name, part.contentType)
                                }
                            }
                        }
                        if (path != null && fileName != null && data != null) {
                            val pf = File(path!!)
                            if (!pf.exists()) pf.mkdirs()
                            val of = File(File(path!!), fileName!!)
                            of.writeBytes(data!!)
                            call.respondText(gson.toJson(mapOf("success" to true, "path" to of.absolutePath, "size" to data!!.size)),
                                ContentType.Application.Json)
                        } else {
                            call.respondText(gson.toJson(mapOf("error" to "缺少参数 path=${path}, fileName=${fileName}")),
                                ContentType.Application.Json, HttpStatusCode.BadRequest)
                        }
                    } catch (e:Throwable) {
                        ExtSystem.printDebugError("/post_file", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                post("/push_project") {
                    try {
                        var projectName:String? = null
                        var data:ByteArray? = null
                        val mp = call.receiveMultipart()
                        mp.forEachPart { part ->
                            when(part) {
                                is PartData.FormItem -> {
                                    if (part.name == "name") projectName = part.value
                                }
                                is PartData.FileItem -> {
                                    data = part.streamProvider().readBytes()
                                }
                                else -> {}
                            }
                        }
                        if (projectName != null && data != null) {
                            val folderFile = if (!projectName!!.contains("/")) {
                                File(PyEngine.getProjectDir(), projectName!!)
                            } else {
                                File("/sdcard/Yyds.Auto")
                            }
                            if (!folderFile.exists()) folderFile.mkdirs()
                            val zipFilePath = if (projectName!!.contains("/")) {
                                File(projectName!!, ".local.zip")
                            } else {
                                File(File(PyEngine.getProjectDir(), projectName!!), ".local.zip")
                            }
                            zipFilePath.writeBytes(data!!)
                            ZipUtility.unzip(zipFilePath.absolutePath, zipFilePath.parentFile!!.absolutePath)
                            zipFilePath.delete()
                            call.respondText(gson.toJson(mapOf("success" to true, "project" to projectName)), ContentType.Application.Json)
                        } else {
                            call.respondText(gson.toJson(mapOf("error" to "缺少参数 name=${projectName}")),
                                ContentType.Application.Json, HttpStatusCode.BadRequest)
                        }
                    } catch (e:Throwable) {
                        ExtSystem.printDebugError("/push_project", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                // ============================================================
                // HTTP REST: 悬浮日志控制台控制
                // ============================================================

                post("/console/show") {
                    PyOut.out("##YYDS_CONSOLE##show\n")
                    call.respondText(gson.toJson(mapOf("success" to true)), ContentType.Application.Json)
                }
                post("/console/hide") {
                    PyOut.out("##YYDS_CONSOLE##hide\n")
                    call.respondText(gson.toJson(mapOf("success" to true)), ContentType.Application.Json)
                }
                post("/console/close") {
                    PyOut.out("##YYDS_CONSOLE##close\n")
                    call.respondText(gson.toJson(mapOf("success" to true)), ContentType.Application.Json)
                }
                post("/console/clear") {
                    PyOut.out("##YYDS_CONSOLE##clear\n")
                    call.respondText(gson.toJson(mapOf("success" to true)), ContentType.Application.Json)
                }
                post("/console/log") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java)
                        val text = body["text"]?.toString() ?: ""
                        val level = body["level"]?.toString() ?: "I"
                        PyOut.out("##YYDS_CONSOLE##log:$level:$text\n")
                        call.respondText(gson.toJson(mapOf("success" to true)), ContentType.Application.Json)
                    } catch (e: Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                post("/console/set-alpha") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java)
                        val alpha = body["alpha"]?.toString() ?: "0.9"
                        PyOut.out("##YYDS_CONSOLE##alpha:$alpha\n")
                        call.respondText(gson.toJson(mapOf("success" to true)), ContentType.Application.Json)
                    } catch (e: Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                post("/console/set-size") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java)
                        val w = body["width"]?.toString() ?: "300"
                        val h = body["height"]?.toString() ?: "400"
                        PyOut.out("##YYDS_CONSOLE##size:$w,$h\n")
                        call.respondText(gson.toJson(mapOf("success" to true)), ContentType.Application.Json)
                    } catch (e: Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                post("/console/set-position") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java)
                        val x = body["x"]?.toString() ?: "0"
                        val y = body["y"]?.toString() ?: "0"
                        PyOut.out("##YYDS_CONSOLE##pos:$x,$y\n")
                        call.respondText(gson.toJson(mapOf("success" to true)), ContentType.Application.Json)
                    } catch (e: Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                post("/console/set-title") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java)
                        val title = body["title"]?.toString() ?: ""
                        PyOut.out("##YYDS_CONSOLE##title:$title\n")
                        call.respondText(gson.toJson(mapOf("success" to true)), ContentType.Application.Json)
                    } catch (e: Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                // ============================================================
                // HTTP REST: APK打包
                // ============================================================

                post("/package/build") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java)
                        val appName = body["appName"]?.toString()
                            ?: return@post call.respondText(gson.toJson(mapOf("error" to "缺少appName参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val projectName = body["projectName"]?.toString()
                            ?: return@post call.respondText(gson.toJson(mapOf("error" to "缺少projectName参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val version = body["version"]?.toString() ?: "1.0"
                        val packageName = body["packageName"]?.toString()
                        var iconPath = body["iconPath"]?.toString()
                        val autoStart = body["autoStart"]?.toString()?.toBoolean() ?: true

                        // 处理克隆图标：iconPath 以 "clone:" 开头时，从已安装应用提取图标
                        if (iconPath != null && iconPath.startsWith("clone:")) {
                            val clonePkg = iconPath.removePrefix("clone:")
                            try {
                                val ctx = uiautomator.AppProcess.appContext
                                if (ctx != null) {
                                    val pm = ctx.packageManager
                                    val drawable = pm.getApplicationIcon(clonePkg)
                                    val bitmap = if (drawable is android.graphics.drawable.BitmapDrawable) {
                                        drawable.bitmap
                                    } else {
                                        val bmp = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888)
                                        val canvas = android.graphics.Canvas(bmp)
                                        drawable.setBounds(0, 0, 192, 192)
                                        drawable.draw(canvas)
                                        bmp
                                    }
                                    val tmpIcon = java.io.File("/data/local/tmp/pack_clone_icon.png")
                                    java.io.FileOutputStream(tmpIcon).use { fos ->
                                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
                                    }
                                    iconPath = tmpIcon.absolutePath
                                    ExtSystem.printDebugLog("克隆图标成功: $clonePkg -> $iconPath")
                                }
                            } catch (e: Throwable) {
                                ExtSystem.printDebugError("克隆图标失败: $clonePkg", e)
                                iconPath = null
                            }
                        }

                        val autoRunOnOpen = body["autoRunOnOpen"]?.toString()?.toBoolean() ?: false
                        val keepScreenOn = body["keepScreenOn"]?.toString()?.toBoolean() ?: true
                        val showLog = body["showLog"]?.toString()?.toBoolean() ?: true
                        val exitOnScriptStop = body["exitOnScriptStop"]?.toString()?.toBoolean() ?: false
                        val encryptScripts = body["encryptScripts"]?.toString()?.toBoolean() ?: false

                        val config = ApkPackageHelper.PackConfig(
                            appName = appName,
                            projectName = projectName,
                            version = version,
                            packageName = packageName,
                            iconPath = iconPath,
                            autoStart = autoStart,
                            autoRunOnOpen = autoRunOnOpen,
                            keepScreenOn = keepScreenOn,
                            showLog = showLog,
                            exitOnScriptStop = exitOnScriptStop,
                            encryptScripts = encryptScripts
                        )
                        // 后台线程执行打包，阻塞等待结果（HTTP timeout由客户端控制）
                        val future = java.util.concurrent.CompletableFuture<ApkPackageHelper.BuildResult>()
                        Thread {
                            try {
                                val result = ApkPackageHelper.buildApk(config)
                                future.complete(result)
                            } catch (e: Throwable) {
                                future.complete(ApkPackageHelper.BuildResult(false, error = e.message))
                            }
                        }.start()
                        val result = future.get(180, java.util.concurrent.TimeUnit.SECONDS)
                        call.respondText(gson.toJson(result), ContentType.Application.Json, HttpStatusCode.OK)
                    } catch (e: java.util.concurrent.TimeoutException) {
                        call.respondText(gson.toJson(mapOf("error" to "打包超时（>180秒）")), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    } catch (e: Throwable) {
                        ExtSystem.printDebugError("/package/build", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                get("/package/list") {
                    try {
                        call.respondText(gson.toJson(ApkPackageHelper.getBuiltApkList()), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                get("/package/download") {
                    try {
                        val path = call.request.queryParameters["path"]
                            ?: return@get call.respondText(gson.toJson(mapOf("error" to "缺少path参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val file = java.io.File(path)
                        if (file.exists() && file.extension == "apk") {
                            val parent = file.parentFile
                            if (parent != null) {
                                call.respondFile(parent, file.name)
                            } else {
                                call.respondFile(file)
                            }
                        } else {
                            call.respondText(gson.toJson(mapOf("error" to "文件不存在")), ContentType.Application.Json, HttpStatusCode.NotFound)
                        }
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                get("/package/installed-apps") {
                    try {
                        val context = uiautomator.AppProcess.appContext
                        if (context == null) {
                            call.respondText(gson.toJson(mapOf("error" to "Context未初始化")), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                            return@get
                        }
                        val pm = context.packageManager
                        val apps = pm.getInstalledApplications(0)
                            .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
                            .map { ai ->
                                mapOf(
                                    "packageName" to ai.packageName,
                                    "appName" to (pm.getApplicationLabel(ai)?.toString() ?: ai.packageName)
                                )
                            }
                            .sortedBy { it["appName"] }
                        call.respondText(gson.toJson(apps), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        ExtSystem.printDebugError("/package/installed-apps", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                get("/package/app-icon") {
                    try {
                        val pkg = call.request.queryParameters["pkg"]
                            ?: return@get call.respondText(gson.toJson(mapOf("error" to "缺少pkg参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val context = uiautomator.AppProcess.appContext
                        if (context == null) {
                            call.respondText(gson.toJson(mapOf("error" to "Context未初始化")), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                            return@get
                        }
                        val pm = context.packageManager
                        val drawable = pm.getApplicationIcon(pkg)
                        val bitmap = if (drawable is android.graphics.drawable.BitmapDrawable) {
                            drawable.bitmap
                        } else {
                            val bmp = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(bmp)
                            drawable.setBounds(0, 0, 192, 192)
                            drawable.draw(canvas)
                            bmp
                        }
                        val baos = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                        call.respondBytes(baos.toByteArray(), ContentType.Image.PNG)
                    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                        call.respondText(gson.toJson(mapOf("error" to "应用未安装: ${call.request.queryParameters["pkg"]}")), ContentType.Application.Json, HttpStatusCode.NotFound)
                    } catch (e: Throwable) {
                        ExtSystem.printDebugError("/package/app-icon", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                post("/package/extract") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java)
                        val projectName = body["projectName"]?.toString()
                            ?: return@post call.respondText(gson.toJson(mapOf("error" to "缺少projectName参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        // 工作进程拥有ROOT权限，可以从APK assets提取到外部存储
                        val context = uiautomator.AppProcess.appContext
                        if (context != null) {
                            val ok = ApkPackageHelper.extractBundledProject(context, projectName)
                            call.respondText(gson.toJson(mapOf("success" to ok)), ContentType.Application.Json)
                        } else {
                            call.respondText(gson.toJson(mapOf("error" to "工作进程Context未初始化")), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                        }
                    } catch (e: Throwable) {
                        ExtSystem.printDebugError("/package/extract", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                // ============================================================
                // HTTP REST: Pip包管理（在工作进程中执行，拥有ROOT/SHELL权限）
                // ============================================================

                get("/pip/list") {
                    try {
                        val pipBin = PyEngine.getPipBin()
                        val raw = ExtSystem.shell("$pipBin list --format=json 2>&1")
                        val json = extractJsonArray(raw)
                        if (json != null) {
                            call.respondText(json, ContentType.Application.Json)
                        } else {
                            call.respondText(gson.toJson(mapOf("error" to "pip输出非法", "raw" to raw.take(500))), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                        }
                    } catch (e: Throwable) {
                        ExtSystem.printDebugError("/pip/list", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                get("/pip/outdated") {
                    try {
                        val pipBin = PyEngine.getPipBin()
                        val raw = ExtSystem.shell("$pipBin list --outdated --format=json 2>&1")
                        val json = extractJsonArray(raw)
                        if (json != null) {
                            call.respondText(json, ContentType.Application.Json)
                        } else {
                            call.respondText("[]", ContentType.Application.Json)
                        }
                    } catch (e: Throwable) {
                        ExtSystem.printDebugError("/pip/outdated", e)
                        call.respondText("[]", ContentType.Application.Json)
                    }
                }
                get("/pip/show") {
                    try {
                        val pkg = call.request.queryParameters["name"]
                            ?: return@get call.respondText(gson.toJson(mapOf("error" to "缺少name参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val safeName = pkg.replace(Regex("[^a-zA-Z0-9_.\\-]"), "")
                        if (safeName.isBlank()) {
                            return@get call.respondText(gson.toJson(mapOf("error" to "无效的包名")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        }
                        val pipBin = PyEngine.getPipBin()
                        val raw = ExtSystem.shell("$pipBin show $safeName 2>&1")
                        val info = mutableMapOf<String, String>()
                        raw.trim().lines().forEach { line ->
                            val idx = line.indexOf(": ")
                            if (idx > 0) {
                                info[line.substring(0, idx).trim()] = line.substring(idx + 2).trim()
                            }
                        }
                        call.respondText(gson.toJson(info), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        ExtSystem.printDebugError("/pip/show", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                post("/pip/install") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java)
                        val pkg = body["name"]?.toString()
                            ?: return@post call.respondText(gson.toJson(mapOf("error" to "缺少name参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val safePkg = sanitizePipSpec(pkg)
                        if (safePkg.isBlank()) {
                            return@post call.respondText(gson.toJson(mapOf("error" to "无效的包名")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        }
                        val pipBin = PyEngine.getPipBin()
                        val mirrorArg = sanitizeMirrorArg(body["mirror"]?.toString())
                        val result = ExtSystem.shell("$pipBin install $safePkg$mirrorArg 2>&1")
                        val success = result.contains("Successfully installed") || result.contains("Requirement already satisfied")
                        call.respondText(gson.toJson(mapOf("success" to success, "output" to result)), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        ExtSystem.printDebugError("/pip/install", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                post("/pip/uninstall") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java)
                        val pkg = body["name"]?.toString()
                            ?: return@post call.respondText(gson.toJson(mapOf("error" to "缺少name参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val safeName = pkg.replace(Regex("[^a-zA-Z0-9_.\\-]"), "")
                        if (safeName.isBlank()) {
                            return@post call.respondText(gson.toJson(mapOf("error" to "无效的包名")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        }
                        val pipBin = PyEngine.getPipBin()
                        val result = ExtSystem.shell("$pipBin uninstall -y $safeName 2>&1")
                        val success = result.contains("Successfully uninstalled")
                        call.respondText(gson.toJson(mapOf("success" to success, "output" to result)), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        ExtSystem.printDebugError("/pip/uninstall", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                post("/pip/upgrade") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java)
                        val pkg = body["name"]?.toString()
                            ?: return@post call.respondText(gson.toJson(mapOf("error" to "缺少name参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val safePkg = sanitizePipSpec(pkg)
                        if (safePkg.isBlank()) {
                            return@post call.respondText(gson.toJson(mapOf("error" to "无效的包名")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        }
                        val pipBin = PyEngine.getPipBin()
                        val mirrorArg = sanitizeMirrorArg(body["mirror"]?.toString())
                        val result = ExtSystem.shell("$pipBin install --upgrade $safePkg$mirrorArg 2>&1")
                        val success = result.contains("Successfully installed") || result.contains("Requirement already satisfied")
                        call.respondText(gson.toJson(mapOf("success" to success, "output" to result)), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        ExtSystem.printDebugError("/pip/upgrade", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                get("/pip/search") {
                    try {
                        val pkg = call.request.queryParameters["name"]
                            ?: return@get call.respondText(gson.toJson(mapOf("error" to "缺少name参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val safeName = pkg.replace(Regex("[^a-zA-Z0-9_.\\-]"), "")
                        if (safeName.isBlank()) {
                            return@get call.respondText(gson.toJson(mapOf("error" to "无效的包名")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        }
                        val mirrorParam = call.request.queryParameters["mirror"]
                        // 多源搜索：优先用户指定源，然后依次尝试国内镜像和官方源
                        val pypiSources = mutableListOf<String>()
                        if (!mirrorParam.isNullOrBlank()) {
                            // 用户指定了镜像，将simple URL转换为JSON API URL
                            val baseHost = try {
                                java.net.URL(mirrorParam).host
                            } catch (_: Exception) { null }
                            if (baseHost != null) {
                                pypiSources.add("https://$baseHost/pypi/$safeName/json")
                            }
                        }
                        pypiSources.addAll(listOf(
                            "https://pypi.tuna.tsinghua.edu.cn/pypi/$safeName/json",
                            "https://pypi.doubanio.com/pypi/$safeName/json",
                            "https://pypi.org/pypi/$safeName/json"
                        ))
                        // 去重
                        val sources = pypiSources.distinct()

                        val result = mutableMapOf<String, Any>()
                        result["name"] = safeName
                        var lastError = ""

                        for (apiUrl in sources) {
                            try {
                                val url = java.net.URL(apiUrl)
                                val conn = url.openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout = 8000
                                conn.readTimeout = 8000
                                conn.requestMethod = "GET"
                                conn.setRequestProperty("Accept", "application/json")
                                val code = conn.responseCode
                                if (code == 200) {
                                    val body = conn.inputStream.bufferedReader().readText()
                                    conn.disconnect()
                                    @Suppress("UNCHECKED_CAST")
                                    val json = gson.fromJson(body, Map::class.java) as? Map<String, Any>
                                    val info = json?.get("info") as? Map<*, *>
                                    val releases = json?.get("releases") as? Map<*, *>
                                    result["found"] = true
                                    result["name"] = info?.get("name")?.toString() ?: safeName
                                    result["latest_version"] = info?.get("version")?.toString() ?: "未知"
                                    result["source"] = url.host
                                    if (releases != null) {
                                        val versions = releases.keys.filterIsInstance<String>()
                                            .filter { v -> releases[v].let { it is List<*> && it.isNotEmpty() } }
                                            .sortedWith(compareByDescending<String> {
                                                it.split(".").getOrNull(0)?.toIntOrNull() ?: 0
                                            }.thenByDescending {
                                                it.split(".").getOrNull(1)?.toIntOrNull() ?: 0
                                            }.thenByDescending {
                                                it.split(".").getOrNull(2)?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
                                            })
                                        result["versions"] = versions.take(15)
                                    }
                                    try {
                                        val pipBin = PyEngine.getPipBin()
                                        val showRaw = ExtSystem.shell("$pipBin show $safeName 2>&1")
                                        val verLine = showRaw.lines().find { it.startsWith("Version:") }
                                        if (verLine != null) {
                                            result["installed_version"] = verLine.substringAfter(":").trim()
                                        }
                                    } catch (_: Exception) {}
                                    break
                                } else if (code == 404) {
                                    conn.disconnect()
                                    result["found"] = false
                                    result["raw"] = "包 $safeName 不存在"
                                    break
                                } else {
                                    conn.disconnect()
                                    lastError = "$apiUrl → HTTP $code"
                                }
                            } catch (e: Exception) {
                                lastError = "$apiUrl → ${e.message}"
                            }
                        }
                        if (!result.containsKey("found")) {
                            result["found"] = false
                            result["raw"] = "所有源均请求失败: $lastError"
                        }
                        call.respondText(gson.toJson(result), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        ExtSystem.printDebugError("/pip/search", e)
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                // ============================================================
                // HTTP REST: AI Agent 配置与控制
                // ============================================================

                get("/agent/config") {
                    try {
                        val configFile = File("/sdcard/Yyds.Auto/agent.json")
                        if (configFile.exists()) {
                            val obj = org.json.JSONObject(configFile.readText())
                            val apiKey = obj.optString("api_key", "")
                            obj.put("is_configured", apiKey.trim().isNotEmpty())
                            call.respondText(obj.toString(), ContentType.Application.Json)
                        } else {
                            call.respondText(gson.toJson(mapOf(
                                "provider" to "autoglm",
                                "api_key" to "",
                                "base_url" to "",
                                "model" to "",
                                "max_steps" to 25,
                                "use_ui_dump" to true,
                                "use_reflector" to true,
                                "is_configured" to false
                            )), ContentType.Application.Json)
                        }
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                post("/agent/config") {
                    try {
                        val body = call.receiveText()
                        val configDir = File("/sdcard/Yyds.Auto")
                        if (!configDir.exists()) configDir.mkdirs()
                        File(configDir, "agent.json").writeText(body)
                        call.respondText(gson.toJson(mapOf("success" to true)), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                post("/agent/test-connection") {
                    try {
                        val body = call.receiveText()
                        val escapedBody = body.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                        val pyCode = """
import json, sys, os
sys.path.insert(0, '/data/local/tmp/cache/python-shims')
try:
    from vlm_client import PRESETS
    import urllib.request, urllib.error, ssl
    params = json.loads("$escapedBody")
    provider = params.get('provider', 'autoglm')
    api_key = params.get('api_key', '')
    model = params.get('model', '')
    base_url = params.get('base_url', '')
    if provider != 'custom' and provider in PRESETS:
        preset = PRESETS[provider]
        if not base_url: base_url = preset['base_url']
        if not model: model = preset['model']
    url = base_url.rstrip('/') + '/chat/completions'
    headers = {'Content-Type': 'application/json', 'Authorization': f'Bearer {api_key}'}
    extra = PRESETS.get(provider, {}).get('extra_headers', {})
    if extra: headers.update(extra)
    payload = json.dumps({"model": model, "messages": [{"role": "user", "content": "hi"}], "max_tokens": 5}).encode()
    req = urllib.request.Request(url, data=payload, headers=headers, method='POST')
    ctx = ssl.create_default_context()
    with urllib.request.urlopen(req, timeout=30, context=ctx) as resp:
        data = json.loads(resp.read().decode())
    m = data.get('model', model)
    c = data.get('choices', [{}])[0].get('message', {}).get('content', '')
    result = json.dumps({"success": True, "model": m, "content": c})
except urllib.error.HTTPError as e:
    body_text = e.read().decode() if hasattr(e, 'read') else ''
    result = json.dumps({"success": False, "error": f"HTTP {e.code}: {body_text[:200]}"})
except Exception as e:
    result = json.dumps({"success": False, "error": str(e)})
with open('/data/local/tmp/agent_test_result.json', 'w') as f:
    f.write(result)
""".trimIndent()
                        // 在后台线程执行 Python 代码并等待结果
                        val future = java.util.concurrent.CompletableFuture<String>()
                        Thread {
                            try {
                                CPythonBridge.execCode(pyCode)
                                val resultFile = File("/data/local/tmp/agent_test_result.json")
                                future.complete(if (resultFile.exists()) resultFile.readText() else """{"success":false,"error":"no result"}""")
                            } catch (e: Throwable) {
                                future.complete("""{"success":false,"error":"${e.message?.replace("\"", "'")}"}""")
                            }
                        }.start()
                        val result = future.get(60, java.util.concurrent.TimeUnit.SECONDS)
                        call.respondText(result, ContentType.Application.Json)
                    } catch (e: java.util.concurrent.TimeoutException) {
                        call.respondText(gson.toJson(mapOf("success" to false, "error" to "连接超时（>60秒）")), ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("success" to false, "error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                post("/agent/run") {
                    try {
                        val body = gson.fromJson(call.receiveText(), Map::class.java)
                        val instruction = body["instruction"]?.toString()
                            ?: return@post call.respondText(gson.toJson(mapOf("error" to "缺少instruction参数")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val escapedInstruction = instruction.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("'", "\\'")
                        val pyCode = """
import json, sys, os, threading
sys.path.insert(0, '/data/local/tmp/cache/python-shims')
try:
    from agent_loop import get_agent, reset_agent
    from agent_config import get_config
    config = get_config()
    if not config.is_configured:
        with open('/data/local/tmp/agent_run_result.json', 'w') as f:
            f.write(json.dumps({"success": False, "error": "Agent未配置API Key"}))
    else:
        import asyncio, time as _time
        agent = get_agent()
        with open('/data/local/tmp/agent_debug.log', 'w') as df:
            df.write(f"[{_time.time()}] before thread start\n")
        def run_task():
            with open('/data/local/tmp/agent_debug.log', 'a') as df:
                df.write(f"[{_time.time()}] thread started\n")
            try:
                loop = asyncio.new_event_loop()
                asyncio.set_event_loop(loop)
                with open('/data/local/tmp/agent_debug.log', 'a') as df:
                    df.write(f"[{_time.time()}] before run_until_complete\n")
                loop.run_until_complete(agent.run("$escapedInstruction"))
                loop.close()
                with open('/data/local/tmp/agent_debug.log', 'a') as df:
                    df.write(f"[{_time.time()}] agent.run completed\n")
            except Exception as e:
                import traceback
                with open('/data/local/tmp/agent_error.log', 'w') as ef:
                    ef.write(traceback.format_exc())
                with open('/data/local/tmp/agent_debug.log', 'a') as df:
                    df.write(f"[{_time.time()}] error: {e}\n")
        t = threading.Thread(target=run_task, daemon=False)
        t.start()
        with open('/data/local/tmp/agent_debug.log', 'a') as df:
            df.write(f"[{_time.time()}] thread started, writing result\n")
        with open('/data/local/tmp/agent_run_result.json', 'w') as f:
            f.write(json.dumps({"success": True, "message": "Agent已启动"}))
except Exception as e:
    with open('/data/local/tmp/agent_run_result.json', 'w') as f:
        f.write(json.dumps({"success": False, "error": str(e)}))
""".trimIndent()
                        val future = java.util.concurrent.CompletableFuture<String>()
                        Thread {
                            try {
                                CPythonBridge.execCode(pyCode)
                                val resultFile = File("/data/local/tmp/agent_run_result.json")
                                future.complete(if (resultFile.exists()) resultFile.readText() else """{"success":false,"error":"no result"}""")
                            } catch (e: Throwable) {
                                future.complete("""{"success":false,"error":"${e.message?.replace("\"", "'")}"}""")
                            }
                        }.start()
                        val result = future.get(30, java.util.concurrent.TimeUnit.SECONDS)
                        call.respondText(result, ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("success" to false, "error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                get("/agent/stop") {
                    try {
                        val pyCode = """
import json, sys
sys.path.insert(0, '/data/local/tmp/cache/python-shims')
try:
    from agent_loop import get_agent
    agent = get_agent()
    agent.stop()
    result = json.dumps({"success": True})
except Exception as e:
    result = json.dumps({"success": False, "error": str(e)})
with open('/data/local/tmp/agent_stop_result.json', 'w') as f:
    f.write(result)
""".trimIndent()
                        val future = java.util.concurrent.CompletableFuture<String>()
                        Thread {
                            try {
                                CPythonBridge.execCode(pyCode)
                                val resultFile = File("/data/local/tmp/agent_stop_result.json")
                                future.complete(if (resultFile.exists()) resultFile.readText() else """{"success":true}""")
                            } catch (e: Throwable) {
                                future.complete("""{"success":false,"error":"${e.message?.replace("\"", "'")}"}""")
                            }
                        }.start()
                        val result = future.get(10, java.util.concurrent.TimeUnit.SECONDS)
                        call.respondText(result, ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("success" to false, "error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                get("/agent/status") {
                    try {
                        val pyCode = """
import json, sys
sys.path.insert(0, '/data/local/tmp/cache/python-shims')
try:
    from agent_loop import get_agent
    agent = get_agent()
    s = agent.status
    status = {
        "running": s.running,
        "current_step": s.current_step,
        "max_steps": s.max_steps,
        "instruction": s.instruction,
        "phase": s.phase,
        "current_action": s.current_action,
        "logs": s.logs[-20:] if s.logs else [],
    }
    result = json.dumps(status, ensure_ascii=False)
except Exception as e:
    result = json.dumps({"running": False, "error": str(e)})
with open('/data/local/tmp/agent_status_result.json', 'w') as f:
    f.write(result)
""".trimIndent()
                        val future = java.util.concurrent.CompletableFuture<String>()
                        Thread {
                            try {
                                CPythonBridge.execCode(pyCode)
                                val resultFile = File("/data/local/tmp/agent_status_result.json")
                                future.complete(if (resultFile.exists()) resultFile.readText() else """{"running":false}""")
                            } catch (e: Throwable) {
                                future.complete("""{"running":false,"error":"${e.message?.replace("\"", "'")}"}""")
                            }
                        }.start()
                        val result = future.get(10, java.util.concurrent.TimeUnit.SECONDS)
                        call.respondText(result, ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("running" to false, "error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                get("/agent/providers") {
                    try {
                        val pyCode = """
import json, sys
sys.path.insert(0, '/data/local/tmp/cache/python-shims')
try:
    from vlm_client import PRESETS
    providers = {}
    for pid, preset in PRESETS.items():
        providers[pid] = {
            "name": preset.get("name", pid),
            "model": preset.get("model", ""),
            "models": preset.get("models", []),
            "vision": preset.get("vision", False),
            "console_url": preset.get("console_url", ""),
        }
    result = json.dumps(providers, ensure_ascii=False)
except Exception as e:
    result = json.dumps({"error": str(e)})
with open('/data/local/tmp/agent_providers_result.json', 'w') as f:
    f.write(result)
""".trimIndent()
                        val future = java.util.concurrent.CompletableFuture<String>()
                        Thread {
                            try {
                                CPythonBridge.execCode(pyCode)
                                val resultFile = File("/data/local/tmp/agent_providers_result.json")
                                future.complete(if (resultFile.exists()) resultFile.readText() else "{}")
                            } catch (e: Throwable) {
                                future.complete("""{"error":"${e.message?.replace("\"", "'")}"}""")
                            }
                        }.start()
                        val result = future.get(10, java.util.concurrent.TimeUnit.SECONDS)
                        call.respondText(result, ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                get("/agent/models") {
                    try {
                        val provider = call.request.queryParameters["provider"] ?: "autoglm"
                        val escapedProvider = provider.replace("\\", "\\\\").replace("\"", "\\\"")
                        val pyCode = """
import json, sys
sys.path.insert(0, '/data/local/tmp/cache/python-shims')
try:
    from vlm_client import PRESETS
    preset = PRESETS.get("$escapedProvider", {})
    result = json.dumps({
        "provider": "$escapedProvider",
        "models": preset.get("models", []),
        "default_model": preset.get("model", ""),
    })
except Exception as e:
    result = json.dumps({"error": str(e)})
with open('/data/local/tmp/agent_models_result.json', 'w') as f:
    f.write(result)
""".trimIndent()
                        val future = java.util.concurrent.CompletableFuture<String>()
                        Thread {
                            try {
                                CPythonBridge.execCode(pyCode)
                                val resultFile = File("/data/local/tmp/agent_models_result.json")
                                future.complete(if (resultFile.exists()) resultFile.readText() else """{"models":[]}""")
                            } catch (e: Throwable) {
                                future.complete("""{"error":"${e.message?.replace("\"", "'")}"}""")
                            }
                        }.start()
                        val result = future.get(10, java.util.concurrent.TimeUnit.SECONDS)
                        call.respondText(result, ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                // ============================================================
                // HTTP: Agent 历史记录
                // ============================================================

                get("/agent/history") {
                    try {
                        val limit = call.request.queryParameters["limit"] ?: "20"
                        val offset = call.request.queryParameters["offset"] ?: "0"
                        val pyCode = """
import json, sys, os
sys.path.insert(0, '/data/local/tmp/cache/python-shims')
try:
    from agent_loop import get_agent_run_list
    data = get_agent_run_list(limit=$limit, offset=$offset)
    result = json.dumps(data, ensure_ascii=False)
except Exception as e:
    result = json.dumps({"error": str(e)})
with open('/data/local/tmp/agent_history_result.json', 'w') as f:
    f.write(result)
""".trimIndent()
                        val future = java.util.concurrent.CompletableFuture<String>()
                        Thread {
                            try {
                                CPythonBridge.execCode(pyCode)
                                val resultFile = File("/data/local/tmp/agent_history_result.json")
                                future.complete(if (resultFile.exists()) resultFile.readText() else """{"total":0,"runs":[]}""")
                            } catch (e: Throwable) {
                                future.complete("""{"error":"${e.message?.replace("\"", "'")}"}""")
                            }
                        }.start()
                        val result = future.get(10, java.util.concurrent.TimeUnit.SECONDS)
                        call.respondText(result, ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                get("/agent/history/{run_id}") {
                    try {
                        val runId = call.parameters["run_id"] ?: ""
                        val escapedRunId = runId.replace("\\", "\\\\").replace("\"", "\\\"")
                        val pyCode = """
import json, sys
sys.path.insert(0, '/data/local/tmp/cache/python-shims')
try:
    from agent_loop import get_agent_run_detail
    data = get_agent_run_detail("$escapedRunId")
    if data is None:
        result = json.dumps({"error": "not found"})
    else:
        result = json.dumps(data, ensure_ascii=False)
except Exception as e:
    result = json.dumps({"error": str(e)})
with open('/data/local/tmp/agent_history_detail_result.json', 'w') as f:
    f.write(result)
""".trimIndent()
                        val future = java.util.concurrent.CompletableFuture<String>()
                        Thread {
                            try {
                                CPythonBridge.execCode(pyCode)
                                val resultFile = File("/data/local/tmp/agent_history_detail_result.json")
                                future.complete(if (resultFile.exists()) resultFile.readText() else """{"error":"not found"}""")
                            } catch (e: Throwable) {
                                future.complete("""{"error":"${e.message?.replace("\"", "'")}"}""")
                            }
                        }.start()
                        val result = future.get(10, java.util.concurrent.TimeUnit.SECONDS)
                        val parsed = gson.fromJson(result, Map::class.java)
                        if (parsed.containsKey("error") && parsed["error"] == "not found") {
                            call.respondText(result, ContentType.Application.Json, HttpStatusCode.NotFound)
                        } else {
                            call.respondText(result, ContentType.Application.Json)
                        }
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                delete("/agent/history") {
                    try {
                        val pyCode = """
import json, sys
sys.path.insert(0, '/data/local/tmp/cache/python-shims')
try:
    from agent_loop import clear_agent_logs
    count = clear_agent_logs()
    result = json.dumps({"deleted": count})
except Exception as e:
    result = json.dumps({"error": str(e)})
with open('/data/local/tmp/agent_history_clear_result.json', 'w') as f:
    f.write(result)
""".trimIndent()
                        val future = java.util.concurrent.CompletableFuture<String>()
                        Thread {
                            try {
                                CPythonBridge.execCode(pyCode)
                                val resultFile = File("/data/local/tmp/agent_history_clear_result.json")
                                future.complete(if (resultFile.exists()) resultFile.readText() else """{"deleted":0}""")
                            } catch (e: Throwable) {
                                future.complete("""{"error":"${e.message?.replace("\"", "'")}"}""")
                            }
                        }.start()
                        val result = future.get(10, java.util.concurrent.TimeUnit.SECONDS)
                        call.respondText(result, ContentType.Application.Json)
                    } catch (e: Throwable) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                // ============================================================
                // HTTP: 自动化引擎代理（保留旧接口兼容）
                // ============================================================

                post("/api/{api}") {
                    try {
                        val api = call.parameters["api"]
                        val postText = call.receiveText()
                        val params = ExportHttp.convertJsonToParamMap(postText)
                        val res = ExportHandle.getHandler().http("/$api", params)
                        call.respondText(res)
                    } catch(e:Throwable) {
                        ExtSystem.printDebugError("/api", e)
                        call.respondText("执行错误:" + Log.getStackTraceString(e))
                    }
                }
            }
        }
        ExtSystem.printDebugLog("=====当前进程id=${ExtSystem.pid()} App模式:${ExtSystem.isInAppMode} 建立Py引擎控制通讯=====")
        engine?.start(true) ?: ExtSystem.printDebugError("engine创建失败，无法启动", null)
    }

    // 远程控制RPC请求处理（仅用于公网服务器WebSocket连接）
    companion object {

        /**
         * 构建 logcat 命令字符串
         * @param dump  true=一次性dump(-d), false=持续流式输出
         * @param level 最低日志级别 V/D/I/W/E/F
         * @param pid   过滤进程ID（可选）
         * @param tag   过滤Tag（可选）
         * @param format 输出格式 brief/threadtime/time/long 等
         * @param lines  只获取最近N行(-t N)，0=全部
         * @param since  从指定时间开始(-T 'timestamp')
         */
        fun buildLogcatCommand(
            dump: Boolean = true,
            level: String = "V",
            pid: String? = null,
            tag: String? = null,
            format: String = "threadtime",
            lines: Int = 0,
            since: String? = null
        ): String {
            val parts = mutableListOf("logcat")
            if (dump) parts.add("-d")
            parts.add("-v")
            parts.add(format)
            if (!pid.isNullOrBlank()) {
                parts.add("--pid=${pid.trim()}")
            }
            if (lines > 0) {
                parts.add("-t")
                parts.add(lines.toString())
            }
            if (!since.isNullOrBlank()) {
                parts.add("-T")
                parts.add("'${since.trim()}'")
            }
            // 日志级别与Tag过滤
            val safeLevel = if (level.length == 1 && level[0] in "VDIWEF") level else "V"
            if (!tag.isNullOrBlank()) {
                val safeTag = tag.trim().replace(Regex("[^a-zA-Z0-9_.*-]"), "")
                if (safeTag.isNotEmpty()) {
                    parts.add("-s")
                    parts.add("$safeTag:$safeLevel")
                }
            } else if (safeLevel != "V") {
                parts.add("*:$safeLevel")
            }
            return parts.joinToString(" ")
        }

        private val ALLOWED_MIRRORS = setOf(
            "https://pypi.tuna.tsinghua.edu.cn/simple",
            "https://mirrors.aliyun.com/pypi/simple",
            "https://pypi.mirrors.ustc.edu.cn/simple",
            "https://pypi.douban.com/simple"
        )

        fun extractJsonArray(raw: String): String? {
            val trimmed = raw.trim()
            val start = trimmed.indexOf('[')
            if (start < 0) return null
            val end = trimmed.lastIndexOf(']')
            if (end <= start) return null
            val candidate = trimmed.substring(start, end + 1)
            return try {
                Gson().fromJson(candidate, List::class.java)
                candidate
            } catch (e: Exception) { null }
        }

        fun sanitizePipSpec(spec: String): String {
            return spec.replace(Regex("[^a-zA-Z0-9_.\\-\\[\\],<>=!~ ]"), "").trim()
        }

        fun sanitizeMirrorArg(mirror: String?): String {
            if (mirror.isNullOrBlank()) return ""
            return if (ALLOWED_MIRRORS.contains(mirror)) " -i $mirror" else ""
        }

        fun handleRpcRequest(req:RpcDataModel, resQueue: LinkedBlockingQueue<RpcDataModel>):RpcDataModel {
            val response = RpcDataModel.initResponseFromRequest(req)
            ExtSystem.printInfo("#开始处理 $req")
            response.setSuccess(false)
            try {
                when(req.method) {
                    RPC_METHOD.ENGINE_PROJECT_START -> {
                        val projectName = req.mapData[RPC_MAP_KEY.ENGINE_START_PROJECT_NAME]
                        if (projectName.isNullOrEmpty()) {
                            response.setDesc("缺少工程名参数")
                        } else {
                            ExtSystem.printDebugLog("开始工程！$projectName")
                            val response2 = RpcDataModel.initResponseFromRequest(req)
                            response2.setSuccess(true)
                            response2.setResult("STARTING###$projectName")
                            response2.setDesc("开始执行工程")
                            resQueue.put(response2)
                            PyEngine.startProject(projectName)
                            response.setSuccess(true)
                            ExtSystem.printDebugLog("结束工程！$projectName")
                        }
                    }
                    RPC_METHOD.ENGINE_ABORT -> {
                        PyEngine.abortProject()
                        response.setSuccess(true)
                    }
                    RPC_METHOD.ENGINE_REBOOT -> {
                        response.setSuccess(true)
                        thread {
                            SystemClock.sleep(3000)
                            AppProcess.rebootPyEngine("@WebApi")
                        }
                    }
                    RPC_METHOD.ENGINE_CODE_RUN -> {
                        val code = req.mapData[RPC_MAP_KEY.ENGINE_RUN_CODE]
                        if (code.isNullOrEmpty()) {
                            response.setDesc("缺少代码参数")
                        } else {
                            PyEngine.runCodeSnippet(code)
                            response.setSuccess(true)
                        }
                    }
                    RPC_METHOD.ENGINE_GET_RUNNING_STATUS -> {
                        val r = PyEngine.getProjectRunningStatus()
                        if (r != null && r.first) {
                            response.addBoolean(RPC_MAP_KEY.ENGINE_IS_PROJECT_RUNNING, true)
                            response.addString(RPC_MAP_KEY.ENGINE_CURRENT_PROJECT_NAME, r.second ?: "")
                        } else {
                            response.addBoolean(RPC_MAP_KEY.ENGINE_IS_PROJECT_RUNNING, false)
                        }
                        response.setSuccess(true)
                    }
                    RPC_METHOD.AUTO_API_SHELL -> {
                        val ret = ExtSystem.shell(req.mapData[RPC_MAP_KEY.ENGINE_RUN_SHELL])
                        response.setResult(ret)
                        response.setSuccess(true)
                    }
                    RPC_METHOD.AUTO_API_FOREGROUND -> {
                        if (ExportHandle.checkAutoEngine()) {
                            val ret = ExportHandle.getHandler().http("/foreground", emptyMap())
                            response.setResult(ret)
                            response.setSuccess(true)
                        } else {
                            response.setDesc("连接自动引擎，获取前台应用信息失败")
                        }
                    }
                    RPC_METHOD.ENGINE_CLICK -> {
                        if (ExportHandle.checkAutoEngine()) {
                            val x = req.mapData["x"] ?: "0"
                            val y = req.mapData["y"] ?: "0"
                            val ret = ExportHandle.getHandler().http("/touch", mapOf(
                                "x" to x,
                                "y" to y
                            ))
                            if (ret.equals("true")) {
                                response.setSuccess(true)
                            }
                        } else {
                            response.setDesc("连接设备自动引擎失败")
                        }
                    }
                    RPC_METHOD.ENGINE_AUTO -> {
                        if (ExportHandle.checkAutoEngine()) {
                            val ret = ExportHandle.getHandler().http(req.mapData["uri"], req.mapData)
                            ExtSystem.printDebugLog("获取auto执行返回:", ret)
                            response.setSuccess(true)
                            response.setResult(ret)
                        } else {
                            response.setDesc("连接设备自动引擎失败")
                            response.setResult("")
                        }
                    }
                    else -> {
                        response.setDesc("Unknown rpc method ${req.method}")
                    }
                }
            } catch(e:Throwable) {
                response.setDesc("handle Exception: ${Log.getStackTraceString(e)}")
            }
            ExtSystem.printInfo("#完成处理 $response")
            return response
        }
    }
}