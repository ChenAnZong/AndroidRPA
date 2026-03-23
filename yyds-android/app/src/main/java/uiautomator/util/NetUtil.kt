package uiautomator.util

import android.content.Context
import android.net.wifi.WifiManager
import androidx.appcompat.app.AppCompatActivity
import com.tencent.yyds.App
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import uiautomator.ExtSystem
import java.math.BigInteger
import java.net.InetAddress

object NetUtil {
    fun wifiIpAddress(): String {
        return try {
            val wifiMgr = App.app.getSystemService(AppCompatActivity.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiMgr.connectionInfo
            return InetAddress.getByAddress(BigInteger.valueOf(wifiInfo.ipAddress.toLong()).toByteArray().reversedArray()).hostAddress
        } catch(e:Exception) {
            try {
                val content = ExtSystem.shell("ifconfig | grep inet | grep Bcast")
                val ipStr =  "inet addr:(.*?)\\s".toRegex().findAll(content).map { it.groupValues[1] }.joinToString(";")
                return ipStr
            } catch (e:Exception) {
                return "获取失败"
            }
        }
    }

    fun wifiIpAddress(ctx:Context): String {
        return try {
            val wifiMgr = ctx.getSystemService(AppCompatActivity.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiMgr.connectionInfo
            return InetAddress.getByAddress(BigInteger.valueOf(wifiInfo.ipAddress.toLong()).toByteArray().reversedArray()).hostAddress
        } catch(e:Exception) {
            ""
        }
    }

    fun tryGetNetIp():String? {
        kotlin.runCatching {
            val json = OkHttpClient().newCall(Request.Builder().url("https://whois.pconline.com.cn/ipJson.jsp?json=true").get().build()).execute().body?.string() ?: return null
            val jo = JSONObject(json)
            return jo.getString("ip") + " " + jo.getString("addr")
        }
        return null
    }

}