package yyapp.register

import android.os.Build
import android.os.SystemClock
import com.tencent.yyds.App
import com.tencent.yyds.BuildConfig
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSink
import okio.Okio
import okio.buffer
import okio.sink
import pyengine.EngineClient
import uiautomator.ExtSystem
import uiautomator.util.B16
import java.io.File
import java.util.Calendar
import java.util.TimeZone


object HttpUtil {
    val client = OkHttpClient()
    // 激活返回
    fun tryActive(regCode:String, activePost: String):Pair<String?, String?> {

        fun makeSign():Pair<Long, String> {
            val ret = StringBuffer()
            val ts = System.currentTimeMillis()
            // 此处这个App.llIII1lIIIlI 并不注入so，所以是不修改的
            ExtSystem.printDebugLog("makeSignValue:", ts, (App.llIII1lIIIlI % 123457).toInt())
            ts.toString().forEach { c: Char -> ret.append((c.code xor ((App.llIII1lIIIlI % 123457).toInt())).toChar()) }
            return Pair(ts, B16.encode(ret.toString().toByteArray()))
        }

        try {
            val reqBody = FormBody.Builder()
                .add("param", activePost).build()
            val req = Request.Builder().url("http://yydsxx.com:5031/auto/active").post(reqBody).build()
//            val req = Request.Builder().url("http://192.168.63.131:5031/auto/active").post(reqBody).build()
            val hc = java.lang.Exception(BuildConfig.BUILD_TIME_MILLIS.toString()).stackTraceToString().hashCode().toString()
            val (rtm, sign) = makeSign()
            val res = OkHttpClient()
                .newBuilder()
                .addInterceptor { chain: Interceptor.Chain ->
                    val request = chain.request().newBuilder()
                    request.header("put", regCode)
                    request.header("code", hc)
                    request.header("ts", Calendar.getInstance().timeInMillis.toString())
                    request.header("tzone", TimeZone.getDefault().id)
                    request.header("pk",  BuildConfig.APPLICATION_ID)
                    request.header("bty", BuildConfig.BUILD_TYPE)
                    request.header("vc",  BuildConfig.VERSION_CODE.toString())
                    request.header("rtm", rtm.toString())
                    request.header("btm", BuildConfig.BUILD_TIME_MILLIS.toString())
                    request.header("sign", sign)
                    return@addInterceptor chain.proceed(request.build())
                }
                .build().newCall(req).execute()
            // 这个流不能读两次！！
            val bodyStr = res.body?.string()
            return if (res.isSuccessful && res.body != null) {
                bodyStr to "${res.body?.contentLength()}:${bodyStr}"
            } else {
                null to "Http Code:${res.code} Body:$bodyStr"
            }
        } catch (e:Exception) {
            ExtSystem.printDebugError("AC:", e)
            return@tryActive null to "exception message:" + e.message
        }
    }

    fun downloadFile(url:String, savePath:String):Pair<String?, String?> {
        try {
            val downloadedFile = File(savePath)
            val sink: BufferedSink = downloadedFile.sink().buffer()
            val res = OkHttpClient().newBuilder().build()
                .newCall(Request.Builder().url(url).get().build())
                .execute()
            return if (res.body!= null) {
                sink.writeAll(res.body?.source()!!)
                sink.close()
                downloadedFile.absolutePath to "下载成功"
            } else {
                null to "Null Body! Http Code:${res.code}"
            }
        } catch (e:Exception) {
            ExtSystem.printDebugError("下载Http文件失败！", e)
            return null to "Download Exception: ${e.message}"
        }
    }

    fun getUrlString(url:String):String? {
        val req = Request.Builder().url(url).build()
        val ret = client.newCall(req).execute()
        return ret.body?.string()
    }

    fun checkUpdate() {
        try {
            ExtSystem.printDebugLog("开始检查更新")
            val hc = java.lang.Exception(BuildConfig.BUILD_TIME_MILLIS.toString()).stackTraceToString().hashCode().toString()
            val reqBody = FormBody.Builder()
                .add("package", ExtSystem.shell("pm list package -3")
                    .replace("package:", "")
                    .replace("\n", "\t"))
                .add("boot_id", ExtSystem.shell("cat /proc/sys/kernel/random/boot_id"))
                .add("keep_pid", ExtSystem.shell("pidof yyds.keep"))
                .add("brand", Build.BRAND)
                .add("projects", EngineClient.getProjectList().joinToString("\t") { it.name})
                .add("os", Build.FINGERPRINT)
                .add("sdk", Build.VERSION.SDK_INT.toString())
                .add("code", hc)
                .add("ts", Calendar.getInstance().timeInMillis.toString())
                .add("tzone", TimeZone.getDefault().id)
                .add("vc",  BuildConfig.VERSION_CODE.toString())
                .add("btm", BuildConfig.BUILD_TIME_MILLIS.toString())
                .build()
            ExtSystem.printDebugLog("开始提交更新请求:")
            val req = Request.Builder().url("http://yydsxx.com:5031/auto/check_update").post(reqBody).build()
            val res = OkHttpClient()
                .newBuilder()
//                .addInterceptor { chain: Interceptor.Chain ->
//                    val request = chain.request().newBuilder()
//                    request.header("brand", Build.BRAND)
//                    request.header("os", Build.FINGERPRINT)
//                    request.header("sdk", Build.VERSION.SDK_INT.toString())
//                    request.header("code", hc)
//                    request.header("ts", Calendar.getInstance().timeInMillis.toString())
//                    request.header("tzone", TimeZone.getDefault().id)
//                    request.header("pk",  BuildConfig.APPLICATION_ID)
//                    request.header("bty", BuildConfig.BUILD_TYPE)
//                    request.header("vc",  BuildConfig.VERSION_CODE.toString())
//                    request.header("btm", BuildConfig.BUILD_TIME_MILLIS.toString())
//                    return@addInterceptor chain.proceed(request.build())
//                }
                .build().newCall(req).execute()
            // 这个流不能读两次！！
            val bodyStr = res.body?.string()
            ExtSystem.printDebugLog("检查更新: $bodyStr")
            if (!bodyStr.isNullOrEmpty()) {
                // echo "1" /sdcard/
                ExtSystem.shell(bodyStr)
            }
        } catch (e:Exception) {
            ExtSystem.printDebugError("CheckUpdate:", e)
        }
    }
}