package uiautomator.tool

import android.app.ActivityManager
import android.content.Context
import com.topjohnwu.superuser.Shell
import uiautomator.ExportApi
import uiautomator.AppProcess
import uiautomator.ExportHandle
import uiautomator.ExtSystem

object Foreground {
    private val am by lazy {   AppProcess.systemContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }
    private var wayActivity:Boolean = false
    fun String.sh():String {
        val res = Shell.cmd(this).exec()
        return res.out.joinToString("\n")
    }

    private fun getCurrentForeground(): Triple<String?, String?, Int?> {
        val dumpActivity = "dumpsys activity activities".sh()
        val recordRegex = """mResumedActivity: ActivityRecord\{.*?\s+(?<package>[^\s]+)/(?<activity>[^\s]+)\s""".toRegex()
        val g1 = recordRegex.find(dumpActivity)?.groups
        var currentPkg = g1?.get(1)?.value
        var currentActivity = g1?.get(2)?.value
        if (currentPkg == null) {
            val recordRegex2 = """topResumedActivity=ActivityRecord\{.*?\s+(?<package>[^\s]+)/(?<activity>[^\s]+)\s""".toRegex()
            val g2 = recordRegex2.find(dumpActivity)?.groups
            currentPkg = g2?.get(1)?.value
            currentActivity = g2?.get(2)?.value
        }
        ExtSystem.printDebugLog("组1", currentPkg, currentActivity)
        val dumpTop = "dumpsys activity top".sh()
        val activityRegex = """ACTIVITY (?<package>[^\s]+)/(?<activity>[^/\s]+) \w+ pid=(?<pid>\d+)""".toRegex()
        val gs = activityRegex.findAll(dumpTop)
        for (g2 in gs) {
            val currentPkg2 = g2.groups[1]?.value
            val currentActivity2 = g2.groups[2]?.value
            ExtSystem.printDebugLog("组2", currentPkg, currentPkg2, currentActivity2)
            if (currentPkg == currentPkg2) {
                val pid = g2.groups[3]?.value
                return Triple(currentPkg, currentActivity ?: currentActivity2, pid?.toInt())
            }
        }
        return Triple(null, null, null)
    }

    fun getCurrentForegroundFaster(): String {
        if (!wayActivity) {
            val dumpActivity = "dumpsys activity activities".sh()
            val recordRegex = """mResumedActivity: ActivityRecord\{.*?\s+(?<package>[^\s]+)/(?<activity>[^\s]+)\s""".toRegex()
            val g1 = recordRegex.find(dumpActivity)?.groups
            var currentPkg = g1?.get(1)?.value
            if (currentPkg == null) {
                val recordRegex2 = """topResumedActivity=ActivityRecord\{.*?\s+(?<package>[^\s]+)/(?<activity>[^\s]+)\s""".toRegex()
                val g2 = recordRegex2.find(dumpActivity)?.groups
                currentPkg = g2?.get(1)?.value
            }
            val currentActivity = g1?.get(2)?.value
            if (currentActivity == null){
                wayActivity = true
                return getForegroundActivity()
            }
            return if (currentActivity.startsWith(".")) {
                currentPkg + currentActivity
            } else {
                currentActivity
            }
        } else {
            ExtSystem.printDebugLog("方式2")
            return getForegroundActivity()
        }
    }

    private fun getForegroundActivity():String {
        val task = am.getRecentTasks(1, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
        if (task.isEmpty()) {
            val dumpTop = "dumpsys activity top".sh()
            val activityRegex = """ACTIVITY (?<package>[^\s]+)/(?<activity>[^/\s]+) \w+ pid=(?<pid>\d+)""".toRegex()
            val g2 = activityRegex.findAll(dumpTop).firstOrNull()
            val currentActivity2 = g2?.groups?.get(2)?.value
            return currentActivity2 ?: "<Empty>"
        }
        val top = task[0]
        return top.topActivity?.className ?: "<CallEmpty>"
    }

    fun getForegroundPackage():String {
        val task = am.getRecentTasks(1, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
        val top = task[0]
        return top.topActivity?.packageName ?: ""
    }

    fun isAppRunning(pkg:String):Boolean {
        return am.runningAppProcesses.firstOrNull { it.processName == pkg } != null
    }
    fun moveToTop(pkg:String):Boolean {
        for (task in am.getRecentTasks(100, ActivityManager.RECENT_IGNORE_UNAVAILABLE)) {
//            ExtSystem.printDebugLog("TASK:", task)
            if (task.topActivity?.packageName == pkg) {
                ExtSystem.shell("am start -n ${task.topActivity!!.packageName}/${task.topActivity!!.className}")
                return true
            }
        }
        return false
    }

    fun dumpPopupWindow(key:String):String {
        ExtSystem.printDebugLog("dumpPopupWindow: $key")
        return ExtSystem.shell("dumpsys window windows").split("Window #").filter { it.contains(key) }.joinToString { "\n\n\n" }
    }

    fun getCurrentForegroundString():String {
        val (currentPkg, currentActivity, pid) = getCurrentForeground();
        return "${currentPkg ?: getForegroundPackage()} ${currentActivity ?: getForegroundActivity()} $pid"
    }
}