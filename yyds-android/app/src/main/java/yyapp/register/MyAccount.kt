package yyapp.register

import android.annotation.SuppressLint
import android.icu.text.SimpleDateFormat
import android.os.Build
import android.system.Os
import pyengine.EngineClient
import uiautomator.ExtSystem
import java.sql.Timestamp

data class ActiveParam (
    val registerType: Int,   // 激活类型
    public val registerVip: String, // 激活码
    val expireTime: Long,     // 激活到期时间
    val tip:String
) {
    @SuppressLint("SimpleDateFormat", "WeekBasedYear")
    fun fmtActiveExpireDate():String =
         SimpleDateFormat("YYYY-MM-dd hh:mm:ss").format(Timestamp(expireTime))

    fun fmtActiveType():String {
        // 激活但是已到期
        if (Build.SUPPORTED_ABIS[0] == "x86_64") return "X86_64 模拟器自动授权"
        if (registerVip.isBlank()) return "未登录"
        return when(registerType) {
            ActiveCard.D_CARD.hour -> ActiveCard.D_CARD.tag
            ActiveCard.H_CARD.hour -> ActiveCard.H_CARD.tag
            ActiveCard.X_CARD.hour -> ActiveCard.X_CARD.tag
            ActiveCard.M_CARD.hour -> ActiveCard.M_CARD.tag
            ActiveCard.Y_CARD.hour -> ActiveCard.Y_CARD.tag
            else -> { ActiveCard.N_ACTIVE.tag }
        }
    }

    fun isActiveValid():Boolean {
        return expireTime > System.currentTimeMillis()
    }
}

object MyAccount {
    // 在app层面，只需要有三个值即可
    // 注册 "注册类型:", "注册会员", "到期时间"
    // 注册的时候，提示是否注册，引擎是否激活

    // 认证文件路径（由守护进程读写，守护进程有ROOT/SHELL权限）
    @SuppressLint("SdCardPath")
    private const val summaryFilePath = "/sdcard/Yyds.Auto/.b2"

    private var cacheActiveParam: ActiveParam? = null

    private var lastModify:Long = 0

    public val emptyActiveParam: ActiveParam = ActiveParam(ActiveCard.N_ACTIVE.hour, "", System.currentTimeMillis(), "")

    // 未注册
    public fun isNoRegister():Boolean {
        return !EngineClient.fileExists(summaryFilePath)
    }

    public fun clearRegister() {
        EngineClient.fileDelete(summaryFilePath)
    }

    public fun readActiveParam():ActiveParam? {
        // 如果文件改变了，则重新读取!
        val currentModify = EngineClient.fileLastModified(summaryFilePath)
        if (lastModify == currentModify && cacheActiveParam != null) return cacheActiveParam
        return try {
            val summaryText = EngineClient.readFileText(summaryFilePath) ?: return null
            val split = summaryText.split("$^$")
            val isSuccess = split[0] == "true"
            val registerType = split[1].toInt()
            val registerCode = split[2]
            val expireTime = split[3].toLong()
            val tip = split[4]
            lastModify = currentModify
            cacheActiveParam = ActiveParam(registerType,
                if (isSuccess) { registerCode } else { "注册错误" }, expireTime, tip)
            cacheActiveParam
        } catch (e:Exception) {
            ExtSystem.printDebugError("注册信息读取错误", e)
            null
        }
    }
}