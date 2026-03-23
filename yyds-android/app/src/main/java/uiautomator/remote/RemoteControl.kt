package uiautomator

import android.os.SystemClock
import com.topjohnwu.superuser.ShellUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.*
import okhttp3.internal.wait
import pyengine.PyEngine
import uiautomator.remote.PythonWsRPC
import uiautomator.remote.RemoteUtil
import uiautomator.remote.RemoteUtil.Companion.imei
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


object FRPConnector {
    private const val frpsIp = "125.124.7.182"
    private const val frpRelease = "frp_0.44.0_linux_arm64"
    private const val originConfig = "/data/local/tmp/${frpRelease}/frpc.ini"
    private val tempConfig = originConfig.replace(".ini", ".ini.patch")
    private const val defaultP2PName = "p2p_ssh"
    /**
     * 释放frp二进制运行文件
     * */
    private fun prepareFrp() {
        val rm = "rm -rf $frpRelease.tar.gz"
        ExtSystem.shell(
                "cd /data/local/tmp",
                "curl https://shenzhouyun.fss-my.vhostgo.com/jb/${frpRelease}.tar.gz > ${frpRelease}.tar.gz || $rm && wget http://shenzhouyun.fss-my.vhostgo.com/jb/${frpRelease}.tar.gz",
                "tar -xzf ${frpRelease}.tar.gz",
                "chmod +x ${frpRelease}/frpc")
    }

    private fun writeFrpcConfig(p2pName:String) {
        File(tempConfig).writeText(File(originConfig).readText()
                .replace(defaultP2PName, p2pName)
                .replace("{server_addr}", frpsIp)
                .replace("{local_port}", "1139")
                .replace("{local_port_ftp}", PyEngine.enginePort.toString())
        )
    }

    fun startFRPC(tunnelName:String) {
        // 准备文件 不存在或者需要更新
        if (!File(tempConfig).exists()
                || !ShellUtils.fastCmdResult("grep -q WjzyConfig@0928 $originConfig")) prepareFrp()

        // 新建通道|
        writeFrpcConfig(tunnelName)
        // 后台运行
        ExtSystem.shell("cd /data/local/tmp", "nohup $frpRelease/frpc -c $tempConfig&")
    }
}

/**
 * 使用Socket后的优点
 * 1. 手机断网或者断开
 * 2. 主动推信息过来重启链接
 */
object RemoteControl:RemoteUtil {
    enum class ConnectType(val typeName:String) {
        FRPC("frpc"),
        WIFI("wifi"),
        USB("usb"),
        DIRECT("direct"),
    }

    private const val SIP = "125.124.7.182"
    private const val SPORT = 9997
    const val START_SCRIPT = "/data/local/tmp/con-start.sh"

    private lateinit var conTypeName:String
    private lateinit var pcCode:String
    private lateinit var deviceTag: String

    private val singleThreadPool = ThreadPoolExecutor(1, 1, 1, TimeUnit.DAYS,
            SynchronousQueue(), ThreadPoolExecutor.DiscardOldestPolicy())

    private val reqQueue: LinkedBlockingQueue<String>
            = LinkedBlockingQueue()

    private val resQueue: LinkedBlockingQueue<String>
            = LinkedBlockingQueue()

    // 如果不再连接，则不再尝试
    private val isTryConnect: AtomicBoolean = AtomicBoolean(false)
    private val isConnecting: AtomicBoolean = AtomicBoolean(false)

    private var mClient:HttpClient? = null
    private val jobs: HashSet<Job> = HashSet()

    private val heartbeatJson = PythonWsRPC("/heartbeat", hashMapOf()).toJson()

    /**
     * 保存三个关键连接参数，并尝试保持连接，跟之前差不多
     * */
    fun registerRemote(newConType:String, newPcCode:String, newDeviceTag:String):String {
        conTypeName = newConType
        pcCode = newPcCode
        deviceTag = newDeviceTag

        val retString = java.lang.StringBuilder()
        retString.append("\n- 检查是否已连接：")

        if (isConnecting.get()) {
            retString.append("当前已连接，刷新连接")
            registerToServerSocket()
            return retString.toString()
        } else {
            retString.append("当前未连接")
        }

        retString.append("\n- 检查服务线程是否后台运行:")
        if (singleThreadPool.activeCount > 0) {
            retString.append("服务线程仍在运行，请先注销当前远程")
            return retString.toString()
        } else {
            retString.append("服务线程已结束")
        }
        isTryConnect.set(true)
        singleThreadPool.execute {
            while (isTryConnect.get()) {
                startConnect()
                SystemClock.sleep(3000)
            }
        }
        retString.append("\n- 已提交远程设备注册申请，远程完成登录✅")
        return retString.toString()
    }

    /**
     * 主动断开 Socket链接，不进行远程
     * */
    fun unRegisterRemote():String {
        val ret = StringBuilder()
        ret.append("\n- 清理进程: " +  killExistsSCRCPY())
        killExistsFRPC()
        ret.append("\n- 通知服务器进行注销")
        enQueueServerRPC(PythonWsRPC("/device_stop", mapOf<String,String>()))
        ret.append("\n- 当前是否连接中: ${isTryConnect.get()}")
        if (isConnecting.get()) {
            jobs.forEach { it.cancel("unRegisterRemote") }
            mClient?.close()
            isTryConnect.set(false)
            ret.append("\n- 已关闭远程服务")
        } else {
            ret.append("\n- 远程服务未连接")
        }
        return ret.toString()
    }

    private fun registerToServerSocket() {
        // FRP    方式 | tunnelName 未frp通道名
        // DIRECT 方式 | tunnelName 多个局域网IP
        val tunnelName = genTunnel(deviceTag.replace(" ", ""))
        enQueueServerRPC(PythonWsRPC("/device_register", mapOf(
            "tunnel" to tunnelName,
            "pc" to pcCode,
            "type" to conTypeName,
            "lan" to getAllLanIp(),
        )))
    }
    
    private fun preparePcConnect(script:String):String {
        // FRP    方式 tunnelName 未frp通道名
        // DIRECT 方式 tunnelName 多个局域网IP
        val tunnelName = if(conTypeName != ConnectType.DIRECT.typeName) genTunnel(deviceTag.replace(" ", "")) else getAllLanIp()
        killExistsFRPC()
        killExistsSCRCPY()

        if (conTypeName == ConnectType.FRPC.typeName) {
            FRPConnector.startFRPC(tunnelName)
        }
        if (!ExtSystem.isRootMode) {
            ExtSystem.shell("rm -rf /data/local/tmp/wjss.jar")
            ExtSystem.shell("rm -rf $START_SCRIPT") // open failed: EACCES (Permission denied) shell下先删除再写入
        }
        File(START_SCRIPT).writeText(script.replace("\r", ""))
        // 执行adb启用脚本，写入Key
        val shResult = ExtSystem.shell("sh $START_SCRIPT > /data/local/tmp/yyds.con.log 2&>1")
        ExtSystem.printDebugLog("# -----------------运行启动脚本------------------")
        if (conTypeName == ConnectType.DIRECT.typeName) {
            return getAllLanIp() + "\n" + shResult
        }
        ExtSystem.printDebugLog(shResult)
        return shResult
    }

    private fun handleServerRPC(rpc:PythonWsRPC) {
        when(rpc.uri) {
            "/heartbeat" -> {
                ExtSystem.printDebugLog("# 服务器存在心跳:" + rpc.params.toString())
            }
            "/connect" -> {
                val script = rpc.params["script"]
                preparePcConnect(script!!)
            }
            "/disconnect" -> {
                unRegisterRemote()
            }
            "/reconnect" -> {
                killExistsFRPC()
                killExistsSCRCPY()
                // 刷新下网络地址
                registerToServerSocket()
                val script = rpc.params["script"]
                preparePcConnect(script!!)
            }
        }
    }

    private fun enQueueServerRPC(rpc:PythonWsRPC) {
        reqQueue.offer(rpc.toJson())
    }

    private suspend fun DefaultClientWebSocketSession.pullRes() {
        try {
            while (true) {
                val response = incoming.receive()
                val json = String(response.readBytes(), Charsets.UTF_8)
                ExtSystem.printDebugLog("# ~~~远程服务 Local client pull: $json")
                resQueue.add(json)
            }
        } catch (e:Exception) {
            ExtSystem.printDebugLog("RC:Error while fetch response |${e.message}", e)
        }
    }

    private suspend fun DefaultClientWebSocketSession.postReq() {
        try {
            var heartbeatCount = 0
            while (true) {
                if (!isTryConnect.get()) return
                if (reqQueue.isEmpty()) {
                    delay(1000)
                    if (++heartbeatCount > 30) {
                        reqQueue.put(heartbeatJson)
                        heartbeatCount = 0
                    }
                    continue
                }
                val req = reqQueue.poll()
                // ExtSystem.printDebugLog("#~~~远程服务 Local client post: $req")
                send(Frame.Text(req!!))
            }
        } catch (e:Exception) {
            ExtSystem.printDebugError("Error while fetch postReq|", e)
        }
    }

    private suspend fun handlePull() {
        try {
            while (true) {
                if (!isTryConnect.get() || !isConnecting.get()) return
                if (resQueue.isNotEmpty()) {
                    handleServerRPC(PythonWsRPC.fromJson(resQueue.poll()!!))
                } else {
                    delay(2000)
                }
            }
        } catch (e:Exception) {
            ExtSystem.printDebugError("handlePull", e)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun startConnect() {
        val handler = CoroutineExceptionHandler { _, e ->
            ExtSystem.printDebugLog("==========CoroutineExceptionHandler==========", e)
        }

        mClient = HttpClient(OkHttp) {
            install(WebSockets) {}
        }
        jobs.forEach { it.cancel("re StartConnect。。。") }
        jobs.clear()
        runBlocking {
            GlobalScope.launch  {
                isConnecting.set(true)
                ExtSystem.printDebugLog("开始远程服务器长连接1")
                reqQueue.clear()
                resQueue.clear()
                mClient!!.webSocket(method = HttpMethod.Get,
                    host = SIP,
                    port = SPORT,
                    path = "/ws/${imei}") {
                    registerToServerSocket()
                    val handleRes =  launch(handler) { pullRes() }
                    val handleReq =  launch(handler) { postReq() }
                    jobs.add(handleReq)
                    jobs.add(handleRes)
                    jobs.add(launch(handler) { handlePull() })
                    jobs.toList().joinAll()
                    mClient!!.close()
                }
                isConnecting.set(false)
                ExtSystem.printDebugLog("结束远程服务器长连接")
            }.join()
        }
    }

    fun testConnect() {
        val handler = CoroutineExceptionHandler { _, e ->
            ExtSystem.printDebugLog("==========CoroutineExceptionHandler==========", e)
        }

        mClient = HttpClient(OkHttp) {
            install(WebSockets) {}
        }
        reqQueue.clear()
        resQueue.clear()

        runBlocking  {
            isConnecting.set(true)
            ExtSystem.printDebugLog("开始远程服务器长连接2")
            mClient!!.webSocket(method = HttpMethod.Get,
                host = SIP,
                port = SPORT,
                path = "/ws/${imei}") {
                    reqQueue.add(PythonWsRPC("/helloass", mapOf("arg1" to "你好", "arg2" to "灰色")).toJson())
                    val handleRes =  launch(handler) { pullRes() }
                    val handleReq =  launch(handler) { postReq() }
                    launch(Dispatchers.IO) { handlePull() }
                    handleReq.join()
                    handleRes.join()
                    mClient!!.close()
            }
            isConnecting.set(false)
            ExtSystem.printDebugLog("结束远程服务器长连接")
        }
    }

}