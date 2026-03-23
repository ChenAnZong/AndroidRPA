package yyapp

import android.annotation.SuppressLint
import com.topjohnwu.superuser.ShellUtils
import uiautomator.AppProcess
import uiautomator.ExtSystem
import java.io.File

object AppDataHelper {
    @SuppressLint("SdCardPath")
    fun appRecoverySdcard(backupGz:String, pn: String):Boolean {
        if (!ShellUtils.fastCmdResult("[ -f $backupGz ]")) {
            return false
        }
        val uid = AppProcess.systemContext.packageManager.getPackageUid(pn, 0)
        ExtSystem.printDebugLog(pn," UID= ", uid)
        ExtSystem.shell("""
chdir /
am force-stop $pn
pm clear $pn
tar -zxf $backupGz
chown -R $uid:$uid /data/user/0/${pn}
chown -R $uid:$uid /data/user_de/0/${pn}
chown -R $uid:$uid /sdcard/Android/data/${pn}
        """)
        return true
    }

    @SuppressLint("SdCardPath")
    fun appBackupSdcard(backupGz:String, pn: String):Boolean {
        val backupInclude = "/data/local/tmp/$pn.txt"
        val userDecFolder = ExtSystem.shell("[ -d data/user_de/0/$pn ] && echo data/user_de/0/$pn")
        val sdcard = ExtSystem.shell("[ -d sdcard/Android/data/$pn ] && echo sdcard/Android/data/$pn")
        File(backupInclude).writeText("""
data/user/0/$pn
$userDecFolder
$sdcard
""".trimIndent())
        val shellCmd = ExtSystem.shell("chdir /;tar -zhpcf '$backupGz' -T $backupInclude && echo success")
        ExtSystem.printDebugLog("备份输出:${shellCmd}")
        return shellCmd.contains("success") || shellCmd.contains("unknown file type")
    }
}