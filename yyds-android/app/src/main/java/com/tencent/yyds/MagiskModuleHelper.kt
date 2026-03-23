package com.tencent.yyds

import android.content.Context
import uiautomator.ExtSystem
import java.io.File
import java.io.FileOutputStream

object MagiskModuleHelper {

    private const val MODULE_ID = "yyds_autostart"
    private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    private const val ASSET_NAME = "yyds_autostart.zip"

    enum class Status {
        INSTALLED,
        DISABLED,
        NOT_INSTALLED,
        UNKNOWN
    }

    fun checkStatus(): Status {
        return try {
            // 直接用 App.shell()（libsu root），避免走 ExtSystem.shell() → ExportHandle
            // 后者会被 jsonOk() 包装成 JSON 导致字符串比较失败
            val exists = shellSuccess("[ -d $MODULE_DIR ]")
            if (!exists) return Status.NOT_INSTALLED

            val disabled = shellSuccess("[ -f $MODULE_DIR/disable ]")
            if (disabled) Status.DISABLED else Status.INSTALLED
        } catch (_: Exception) {
            Status.UNKNOWN
        }
    }

    fun extractModuleZip(context: Context): String? {
        return try {
            val outFile = File(context.cacheDir, ASSET_NAME)
            context.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile.setReadable(true, false)
            outFile.absolutePath
        } catch (e: Exception) {
            ExtSystem.printDebugError("extractModuleZip", e)
            null
        }
    }

    /**
     * 安装模块，自动适配 APatch / KernelSU / Magisk
     * @return Pair<成功?, 日志内容>
     */
    fun installModule(context: Context): Pair<Boolean, String> {
        val zipPath = extractModuleZip(context) ?: return Pair(false, "模块文件释放失败")
        val logPath = context.cacheDir.absolutePath + "/.magisk_install.log"

        // APatch
        if (isApatch()) {
            val ok = shellSuccess("/data/adb/apd module install \"$zipPath\" > \"$logPath\" 2>&1")
            if (ok) return Pair(true, readLog(logPath))
        }

        // KernelSU (ksud)
        if (shellSuccess("/data/adb/ksu/bin/ksud -V")) {
            val ok = shellSuccess("/data/adb/ksu/bin/ksud module install \"$zipPath\" > \"$logPath\" 2>&1")
            if (ok) return Pair(true, readLog(logPath))
        }

        // Magisk
        val magiskBin = if (shellSuccess("which magisk")) "magisk" else "/data/adb/magisk/magisk"
        val mgOk = shellSuccess("$magiskBin --install-module \"$zipPath\" > \"$logPath\" 2>&1")
        if (mgOk) return Pair(true, readLog(logPath))

        // 兜底：libzakozako.so（历史兼容）
        val soPath = ExtSystem.shell("find /data/app -name 'libzakozako.so' 2>/dev/null").trim()
        if (soPath.isNotEmpty()) {
            val ok = shellSuccess("$soPath module install \"$zipPath\" > \"$logPath\" 2>&1")
            if (ok) return Pair(true, readLog(logPath))
        }

        val log = readLog(logPath)
        return Pair(false, log.ifEmpty { "所有安装方式均失败，请确认 ROOT 权限管理器正常运行" })
    }

    fun uninstallModule(): Boolean {
        return try {
            shellSuccess("rm -rf $MODULE_DIR")
        } catch (_: Exception) {
            false
        }
    }

    fun getRootManagerName(): String {
        return when {
            isApatch() -> "APatch"
            shellSuccess("/data/adb/ksu/bin/ksud -V") -> "KernelSU"
            shellSuccess("which magisk") || shellSuccess("[ -f /data/adb/magisk/magisk ]") -> "Magisk"
            else -> "未知"
        }
    }

    private fun isApatch(): Boolean {
        return shellSuccess("[ -f /data/adb/apd ]")
    }

    private fun shellSuccess(cmd: String): Boolean {
        return try {
            val result = App.shell(cmd + " && echo __OK__")
            result.contains("__OK__")
        } catch (_: Exception) {
            false
        }
    }

    private fun readLog(path: String): String {
        return try {
            ExtSystem.shell("cat $path 2>/dev/null").trim()
        } catch (_: Exception) {
            ""
        }
    }
}
