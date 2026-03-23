package com.tencent.yyds

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.Process
import android.provider.Settings
import androidx.annotation.Keep
import com.topjohnwu.superuser.Shell
import pyengine.EngineClient
import uiautomator.AppProcess
import uiautomator.ExportHandle
import uiautomator.ExtSystem
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.system.exitProcess

@Keep
class App : Application() {
    private fun getProcessName1(): String? {
        return try {
            FileInputStream("/proc/${Process.myPid()}/cmdline")
                .buffered()
                .readBytes()
                .filter { it > 0 }
                .toByteArray()
                .inputStream()
                .reader(Charsets.ISO_8859_1)
                .use { it.readText() }
        } catch (e: Throwable) {
            null
        }
    }

    override fun onCreate() {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            ExtSystem.printDebugError("${t.name}:${t.id} 未处理的线程异常" , e)
            if (Looper.getMainLooper() != null) {
                if (getProcessName1() != BuildConfig.APPLICATION_ID) {
                    ExtSystem.printDebugLog("--------主动退出!防止ANR：" + getProcessName1())
                    exitProcess(0)
                }
            }
        }

        app = this
        AppProcess.appContext = this
        super.onCreate()

        // 注册控制台命令拦截: Python端 console.show() 能自动启动悬浮日志Service
        EngineClient.onConsoleShowRequest = {
            try {
                val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    Settings.canDrawOverlays(this) else true
                if (canOverlay && !FloatingLogService.isRunning()) {
                    FloatingLogService.start(this)
                }
            } catch (e: Exception) {
                ExtSystem.printDebugError("自动启动悬浮日志控制台失败(可能App在后台)", e)
            }
        }
        // 注册 Agent 悬浮窗命令拦截: agent 思考时自动启动悬浮窗 Service
        // Python 端 _overlay() 已根据 show_floating_window 配置决定是否发送，
        // 命令到达即表示用户希望显示，无需二次判断 isEnabled
        EngineClient.onAgentOverlayRequest = {
            try {
                val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    Settings.canDrawOverlays(this) else true
                if (canOverlay && !AgentOverlayService.isRunning()) {
                    AgentOverlayService.start(this)
                }
            } catch (e: Exception) {
                ExtSystem.printDebugError("自动启动Agent悬浮窗失败", e)
            }
        }
        // 初始化 Shizuku 生命周期监听（尽早注册，确保 binder 回调不丢失）
        ShizukuUtil.init()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 破解暗桩 2
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.apkContentsSigners.forEach {
                if (!it.toCharsString().contains("9643110300e060355040b1307416e64726f69643110300e06035504031307416e64726f696430820122300d06092a864886f70d01010105000382010f0")) {
                    Looper.prepareMainLooper()
                    val ActivityThread = Class.forName("android.app.ActivityThread")
                    val systemMain = ActivityThread.getDeclaredMethod("systemMain")
                    val `object` = systemMain.invoke(null)

                    val ContextImpl = Class.forName("android.app.ContextImpl")
                    val createSystemContext =
                        ContextImpl.getDeclaredMethod("createSystemContext", ActivityThread)
                    createSystemContext.isAccessible = true
                    val contextInstace = createSystemContext.invoke(null, `object`) as Context?
                        ?: throw java.lang.RuntimeException("get mContext null")
                }
            }
        }
    }

    fun jumpBrowserUrl(url:String) {
        //方式一：代码实现跳转
        val intent = Intent()
        intent.action = "android.intent.action.VIEW"
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val content_url: android.net.Uri = android.net.Uri.parse(url) //此处填链接
        intent.data = content_url
        app.startActivity(intent)
    }

    fun rebootApp() {
        val ctx = applicationContext
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(ctx.packageName) ?: return
        val mainIntent = Intent.makeRestartActivityTask(intent.component)
        ctx.startActivity(mainIntent)
        Runtime.getRuntime().exit(0)
    }
    fun getContextFile(name: String): File {
        return File(
            this.createDeviceProtectedStorageContext().getFilesDir(),
            name
        )
    }
    fun reqRootMount() {
       thread {
                val notifyFile = getContextFile("/root/" + getProcessName1())
                try {
                    if (notifyFile.parentFile?.exists() != true) {
                        notifyFile.parentFile?.mkdirs()
                    }
                    if (notifyFile.exists()) {
                        notifyFile.delete()
                    }
                    notifyFile.createNewFile()
                    ExtSystem.printDebugLog( "创建目标文件:" + notifyFile.absolutePath)
                } catch (e: IOException) {
                    ExtSystem.printDebugError("r!@", e)
                }
            }
    }

    companion object {
        @JvmField
        val llIII1lIIIlI: Long = 1675936919

        lateinit var app: App
        private const val Key_Main_SharePreferenceName = "Vx542999277"
        public const val KEY_CACHE_REGISTER_CODE = "ck"
        public const val KEY_CACHE_REGISTER_RET = "ck_ret"
        public const val KEY_AGREE = "k-agree";
        public const val KEY_PROJECT = "k-pjn"

        public val Main_Sp by lazy { app.getSharedPreferences(Key_Main_SharePreferenceName, MODE_PRIVATE) }
        // 编译时间tm
        var bt = BuildConfig.BUILD_TIME_MILLIS

        @JvmStatic fun hasRootShellPermission():Boolean {
            if (ExportHandle.checkRootService()) return true

            for (i in 1..2) {
                try {
                    val idStr = ExtSystem.shell("id")
                    if (idStr.contains("root") || idStr.contains("shell")) return true
                } catch (e:Exception) {
                    ExtSystem.printDebugError(e)
                }
                Shell.getCachedShell()?.close()
            }

            return if (!shell("id").contains("root")) {
                Shell.getShell().close()
                false
            } else {
                true
            }
        }

        @JvmStatic fun shell(vararg cmd:String):String {
            val ret = ArrayList<String>()
            Shell.getShell().newJob().to(ret).add(*cmd).exec()
            return ret.joinToString("\n")
        }
    }
}