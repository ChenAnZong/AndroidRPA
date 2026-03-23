package pyengine

import com.google.gson.Gson
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import uiautomator.ExtSystem
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

// 作为客户端处理服务器端链接（远程控制，JSON over WebSocket）
class HandleApiServerConnection(private val session: DefaultClientWebSocketSession) {
    private val reqQueue: LinkedBlockingQueue<RpcDataModel> = LinkedBlockingQueue()

    private val resQueue: LinkedBlockingQueue<RpcDataModel> = LinkedBlockingQueue()

    private val threadPool = ThreadPoolExecutor(3, 5, 60, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>())

    private val gson = Gson()

    // 收到（JSON文本帧）
    suspend fun receiveReq() {
        try {
            delay(1500)
            while (true) {
                try {
                    val frame = session.incoming.receive()
                    if (frame is Frame.Text) {
                        val json = frame.readText()
                        val req = gson.fromJson(json, RpcDataModel::class.java)
                        if (req.method == RPC_METHOD.DISCONNECT) {
                            session.close(CloseReason(CloseReason.Codes.NOT_CONSISTENT, "bye"))
                            break
                        }
                        ExtSystem.printInfo("#收到:$req")
                        reqQueue.offer(req)
                    }
                    delay(500)
                } catch (e: Exception) {
                    ExtSystem.printInfo("!![Server]收到错误数据包：${e.message}")
                }
            }
        } catch (e: Exception) {
            ExtSystem.printDebugError("[Server]收 | Message:${e.message}\n}", e)
        }
    }

    // 处理
    suspend fun handleReq() {
        try {
            while (true) {
                if (reqQueue.isEmpty()) {
                    delay(500)
                    continue
                }
                val req = reqQueue.poll()
                threadPool.submit {
                    resQueue.offer(WebSocketAsServer.handleRpcRequest(req!!, resQueue))
                }
            }
        } catch (e: Exception) {
            ExtSystem.printDebugLog("[Server]处 |${e.message}\n${e.stackTraceToString()}")
        }
    }

    // 返回<发送>（JSON文本帧）
    suspend fun returnRes() {
        try {
            var count = 0
            while (true) {
                if (resQueue.isEmpty()) {
                    count++
                    if (count % 10 == 0) {
                        count = 0
                        session.send(Frame.Text("设备心跳"))
                    }
                    delay(1500)
                } else {
                    val res = resQueue.poll()
                    if (res != null) {
                        ExtSystem.printDebugLog("#发送:$res")
                        session.send(Frame.Text(gson.toJson(res)))
                    }
                }
            }
        } catch (e: Exception) {
            ExtSystem.printDebugLog("[Server]发 |${e.message}\n${e.stackTraceToString()}")
        }
    }

    fun onClose(e: ClosedReceiveChannelException, reason: CloseReason?) {
        ExtSystem.printDebugLog("[Server] $session onClose, $e， $reason")
    }

    fun onError(e: Throwable, reason: CloseReason?) {
        ExtSystem.printDebugLog("[Server] $session onError, $e， $reason")
    }
}
