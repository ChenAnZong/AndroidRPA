package uiautomator

import android.os.Build
import android.os.FileObserver
import androidx.annotation.RequiresApi
import java.io.File
import kotlin.concurrent.thread

// 安卓10以下不兼容
@RequiresApi(Build.VERSION_CODES.Q)
object RootWatch {
    private val watcher = mutableListOf<FileObserver>()

    class ApkInstallWatch(val path: String): FileObserver(File(path), MODIFY) {
        override fun onEvent(event: Int, path_arg: String?) {
            this.finalize()
            ExtSystem.printDebugLog("(APK)  $event $path_arg")
            kotlin.runCatching {
                thread {
                    writeLog(ExtSystem.shell("sleep 1 && pm install -r -t $path && rm $path; exit"))
                }
            }
            this.startWatching()
        }
    }

    class ShellCmdWatch(val path: String): FileObserver(File(path), MODIFY) {
        override fun onEvent(event: Int, path_arg: String?) {
            ExtSystem.printDebugLog("(SHELL) $event $path_arg")
            kotlin.runCatching {
                thread {
                    writeLog(ExtSystem.shell("sh $path"))
                }
            }
        }
    }

    fun writeLog(text:String) {
        File("/data/local/tmp/fileObserver.log").writeText(text);
    }

    fun start():RootWatch {
        watcher.add(ApkInstallWatch("/data/local/tmp/install.apk").apply { startWatching() })
        watcher.add(ShellCmdWatch("/data/local/tmp/rssr.sh").apply { startWatching() })
        return this
    }

    fun stop() {
        watcher.forEach { it.stopWatching() }
    }
}