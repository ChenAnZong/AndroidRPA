package uiautomator

import android.system.Os
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.*
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import org.slf4j.event.Level
import uiautomator.util.NetUtils
import uiautomator.util.WebPost
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean


open class ExportHttp {
    companion object {
        private const val PORT = 49009
        private val gson: Gson = GsonBuilder().setLenient().disableJdkUnsafe().setPrettyPrinting().create()
        private val paramsType = object : TypeToken<Map<String, String>>() {}.type
        private val isHttpStarted = AtomicBoolean(false)

        @JvmStatic
        public fun isHttpServerRunning():Boolean {
            return isHttpStarted.get()
        }

        @JvmStatic
        public fun convertJsonToParamMap(json: String): Map<String,String> {
            return if (json.isEmpty()) emptyMap<String,String>() else gson.fromJson(json, paramsType)
        }

        @JvmStatic
        protected fun httpServerCheck():Boolean {
            return NetUtils.checkUrlOk("http://127.0.0.1:${PORT}")
        }

        @JvmStatic
        protected fun httpServerStart() {
            val httpServer = embeddedServer(CIO, port = PORT) {
                install(WebSockets) {
                    pingPeriodMillis = 500
                    timeoutMillis = 3600_000
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }
                install(CallLogging) {
                    level = Level.TRACE
                }

                routing {
                    get("/") {
                        call.respondText(Os.getpid().toString())
                    }
                    get("/pull_file") {
                        try {
                            val path = call.parameters["path"]
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
                                call.respondText("Success write to ${of.absolutePath}, size=${data!!.size}", status = HttpStatusCode.OK)
                            } else {
                                call.respondText("Failed, path=${path}, fileName=${fileName}, dataSize=${data?.size}", status=HttpStatusCode.BadRequest)
                            }
                        } catch (e:Throwable) {
                            ExtSystem.printDebugError("/post_file", e)
                            call.respondText(Log.getStackTraceString(e))
                        }
                    }
                    post("/{api}") {
                        try {
                            val api = call.parameters["api"]
                            val postText = call.receiveText()
                            val params = convertJsonToParamMap(postText)
                            Thread.currentThread().name = "@${api}"
                            ExtSystem.printInfo("# [http调用]($api)参数解析:${Gson().toJson(params)}")
                            val res = ExportApi.instance.exportLocalHandle("/$api", params)
                            call.respondText(res)
                        } catch(e:Throwable) {
                            ExtSystem.printDebugError("/api", e)
                            call.respondText(Log.getStackTraceString(e))
                        }
                    }
                }
            }
            isHttpStarted.set(true)
            httpServer.start(false)
            isHttpStarted.set(false)
        }

        @JvmStatic
        protected fun httpClientRequest(method: String, params: Map<String,String>):String {
            return WebPost("http://127.0.0.1:${PORT}${method}", gson.toJson(params), "token").returnResult().dropLast(1) // 结尾去一个换行符
        }
    }
}