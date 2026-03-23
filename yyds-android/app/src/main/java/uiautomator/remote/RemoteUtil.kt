package uiautomator.remote

import android.os.Build
import uiautomator.AppProcess
import uiautomator.ExportHandle
import uiautomator.ExtSystem
import uiautomator.util.NetUtil

interface RemoteUtil {

    companion object {
        val imei by lazy { ExportHandle.http("/imei", hashMapOf()) }
    }

    public fun killExistsSCRCPY():String {
        val ret = ExtSystem.shell("ps -ef | grep scrcpy | grep com | awk '{print \$1}'")
        ExtSystem.printDebugLog("scan scrcpy: $ret")
        if (ret.isNotEmpty()) {
            ExtSystem.shell("kill -9 $ret && echo kill")
        }
        return "not found scrcpy"
    }

    public fun killExistsFRPC() {
        val pid = ExtSystem.shell("ps -ef | grep frpc | grep ini | awk '{print \$1}'")
        ExtSystem.printDebugLog("# found frpc pid=$pid")
        ExtSystem.shell("kill -9 $pid")
    }

    fun checkSCRCPYRunning():Boolean {
        return ExtSystem.shell("ps -ef | grep scrcpy").contains("app_process")
//        var ss:ServerSocket? = null
//        return try {
//            ss = ServerSocket(1139, 100, InetSocketAddress(0).address)
//            ss.reuseAddress = true
//            true
//        } catch(e:java.lang.Exception) {
//            false
//        } finally {
//            kotlin.runCatching {  ss?.close() }
//        }
    }

    fun checkSCRCPYConnecting():Boolean {
        return ExtSystem.shell("netstat -ap | grep 1139").contains("ESTABLISHED")
    }

    /**
     * 返回所有局域网Ip地址,  兼容双WIFI的情况 就是不知道会不会存在兼容性！
     * */
    fun getAllLanIp():String {
        val content = ExtSystem.shell("ifconfig | grep inet | grep Bcast")
        val ipStr =  "inet addr:(.*?)\\s".toRegex().findAll(content).map { it.groupValues[1] }.joinToString(";")
        return if (ipStr.length < 5 || !ipStr.contains(".")) {
            NetUtil.wifiIpAddress(AppProcess.systemContext)
        } else {
            ipStr
        }
    }

    fun genTunnel(tag:String):String {
        val stag = tag.ifEmpty { Build.PRODUCT }

        var model = ExtSystem.shell("getprop persist.sys.device_name")
        if (model.isEmpty()) model = Build.MODEL

        return (stag + "_" + model + "_" + imei).replace(" ", "")
    }
}