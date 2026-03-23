package common

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.tencent.yyds.BuildConfig
import me.caz.xp.ui.ContextAction
import uiautomator.ExportHandle
import uiautomator.ExportHttp
import uiautomator.ExtSystem
import uiautomator.util.NetUtils
import kotlin.concurrent.thread

class BootProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method == "con-boot") {
            val sp = context?.getSharedPreferences(BuildConfig.APPLICATION_ID, Activity.MODE_PRIVATE)
            if (sp != null && sp.getBoolean("bootOnStart", false)) {
                thread {
                    try {
                        while (!NetUtils.isNetOnline()) {
                            Thread.sleep(3000)
                        }
                        while (!ExportHandle.checkEngineStatus().first) {
                            Thread.sleep(2000)
                        }
                        // == 开始
                        val ret = ExportHandle.getHandler().http("/remote_login",
                            mapOf(
                                "pcName" to sp.getString("pcName", "")!!,
                                "deviceTag" to sp.getString("deviceTag", "")!!,
                                "conType" to  sp.getString("conType", "")!!))
                        ExtSystem.printDebugLog("开机启动:", ret)
                    } catch(e: Exception) {
                        Log.e("BootProvider", Log.getStackTraceString(e));
                    }
                }
            } else {
                Log.d("BootProvider", "未设置开机启动, PC CODE:${sp?.getString("pcName", "")}")
            }
        }
        if (method == "toast") {
            if (arg != null) {
                ExtSystem.printDebugLog(method, arg)
                // 此处可能会在非主线程线程执行
                ContextAction.toast(arg)
            }
        }
        // yyds.Py 获取状态方便
        if (method == "status") {
             val stauts = ExportHandle.checkEngineStatus()
             return Bundle().apply {
                 putBoolean("isRunning", stauts.first)
                 putInt("uid", stauts.second)
             }
        }
        // 供shell 统一使用
        if (method == "api") {
            val apiRetBundle = Bundle()
            apiRetBundle.putString("uir", arg)
            try {
                apiRetBundle.putString("params", extras!!.getString("params"))
                val apiRet = ExportHandle.getHandler().http(arg, ExportHttp.convertJsonToParamMap(extras!!.getString("params")!!))!!
                apiRetBundle.putBoolean("remoteCallSuccess", true)
                apiRetBundle.putString("ret", apiRet)
            } catch(e:Exception) {
                apiRetBundle.putBoolean("remoteCallSuccess", false)
                apiRetBundle.putString("ret", Log.getStackTraceString(e))
            }
            return apiRetBundle
        }
        return Bundle().apply { putString("method", method) }
    }

    override fun onCreate(): Boolean {
        return false
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        return null
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        return 0
    }
}