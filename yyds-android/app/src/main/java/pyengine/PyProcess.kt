package pyengine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.os.Process
import android.system.Os
import androidx.annotation.Keep
import com.tencent.yyds.BuildConfig
import pyengine.PyEngine.initPythonParser
import uiautomator.AppProcess
import uiautomator.ExtSystem
import uiautomator.util.ZipUtils
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

@Keep
object PyProcess {
    // CLASSPATH=$(cut -d ':' -f2 <<< `pm path com.yyds.auto`) setsid app_process /system/bin pyengine.PyProcess test&
    // CLASSPATH=$(cut -d ':' -f2 <<< `pm path com.yyds.auto`) exec app_process /system/bin pyengine.PyProcess 案例工程
    // CLASSPATH=$(cut -d ':' -f2 <<< `pm path com.yyds.auto`) app_process  pyengine.PyProcess
    private fun setArgV0(text: String) {
        try {
            val setter =
                Process::class.java.getMethod("setArgV0", String::class.java)
            setter.invoke(Process::class.java, text)
        } catch (e: NoSuchMethodException) {
            e.printStackTrace()
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        } catch (e: InvocationTargetException) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    fun main(args:Array<String>) {
        Looper.prepareMainLooper()
        ExtSystem.printDebugLog("Py Process PID=${ExtSystem.pid()} Arg:", args.contentToString())
        if (args.isEmpty()) {
            println("启动参数缺失!")
            exitProcess(1)
        }
        try {
            Os.umask(0)
            Os.setsid()
        } catch (ignore: Exception) {
        }
        val sysContext = ContextUtil.getSystemContext()
        var isApkInstalled = true
        try {
            sysContext.packageManager.getPackageGids(BuildConfig.APPLICATION_ID)
        } catch (e: PackageManager.NameNotFoundException) {
            isApkInstalled = false
            ExtSystem.printInfo("!APK似乎未安装!")
        }
        if (isApkInstalled) {
            // 赋值到全局变量
            val context = sysContext.createPackageContext(
                BuildConfig.APPLICATION_ID,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            ) //android
            AppProcess.appContext = context
            ZipUtils.unZipSoFile(
                context.packageCodePath,
                AppProcess.pyUnzipTo
            )
            // ExtSystem.printDebugLog("2-----------" + context);
            initPythonParser(context)
        }
        Thread.setDefaultUncaughtExceptionHandler { t, e -> ExtSystem.printDebugError("${t.name}:${t.id} 未处理的线程异常" , e)  }
        // 子进程: toConsole=false 避免双重输出（ConsoleOutputStream 已写 fd 1，父进程管道转发）
        PyOut.setConfig(toCache = false, toConsole = false)
        val projectName = args[0]
        setArgV0("py-${projectName}")

        // entry 模块已在 initPythonParser() 中导入并安装了 ConsoleOutputStream，
        // 子进程通过 YYDS_SUBPROCESS=1 环境变量自动禁用 PyOut 转发（父进程管道负责）
        var exitCode = 0
        try {
            val entryModule = CPythonBridge.importModule("entry")
            entryModule.callAttr("run_project", projectName)
            entryModule.close()
        } catch (e: Exception) {
            if (e.message?.contains("SystemExit") == true) {
                println("项目($projectName)已正常退出")
            } else {
                System.err.println("项目($projectName)运行出错: ${e.message}")
                e.printStackTrace(System.err)
                exitCode = 1
            }
        }
        ExtSystem.printDebugLog("PyProcess 运行结束: $projectName, exitCode=$exitCode")
        exitProcess(exitCode)
    }
}