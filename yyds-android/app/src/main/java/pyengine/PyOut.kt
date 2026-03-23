package pyengine

import androidx.annotation.Keep
import java.util.concurrent.LinkedBlockingQueue

/**
 * Python 输出中枢 — 所有 print() / stderr 输出最终汇聚于此。
 *
 * 输出链路:
 *   print() → ConsoleOutputStream.write() → PyOut.out() → WebSocket → IDE 控制台
 *
 * 注意: toConsole 不可与 ConsoleOutputStream 同时启用，否则会产生无限递归:
 *   PyOut.out() → print() → ConsoleOutputStream.write() → PyOut.out() → ...
 *   子进程模式下 toConsole=false（由 ConsoleOutputStream 负责写 fd 1）。
 */
@Keep
object PyOut {

    val logQueue: LinkedBlockingQueue<String> = LinkedBlockingQueue(3000)

    @Volatile var toCache = false
        private set
    @Volatile var toConsole = false
        private set

    fun setConfig(toCache: Boolean, toConsole: Boolean) {
        this.toCache = toCache
        this.toConsole = toConsole
    }

    @JvmStatic
    fun out(text: String) {
        if (toConsole) print(text)
        enqueueIfNeeded("out:$text")
        WebSocketAsClient.forwardLog("out:$text")
    }

    @JvmStatic
    fun err(text: String) {
        if (toConsole) System.err.print(text)
        enqueueIfNeeded("err:$text")
        WebSocketAsClient.forwardLog("err:$text")
    }

    private fun enqueueIfNeeded(entry: String) {
        if (!toCache) return
        if (logQueue.remainingCapacity() < 10) logQueue.poll()
        logQueue.put(entry)
    }
}