package common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.tencent.yyds.App
import uiautomator.AppProcess
import uiautomator.ExtSystem
import kotlin.concurrent.thread


open class BootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        thread {
            ExtSystem.shell(AppProcess.getActiveEngineKeeperCmd())
            ExtSystem.printDebugLog("开机启动native守护进程(yyds.keep)，由其派生启动工作进程")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            {
                context.startForegroundService(Intent(context, BootService::class.java));
            } else {
                context.startService(Intent(context, BootService::class.java))
            }
            ExtSystem.printDebugLog("启动服务!")

            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                var wakeLockType = PowerManager.PARTIAL_WAKE_LOCK
                val wakeLockTag = "CPULockUtil:WakeLock"
                wakeLockType = PowerManager.ACQUIRE_CAUSES_WAKEUP
                val cpuWakeLock = powerManager.newWakeLock(wakeLockType, wakeLockTag)
                cpuWakeLock.acquire(10*60*1000L /*10 minutes*/)
            } catch (e:Exception) {
                ExtSystem.printDebugLog("长期持有CPU锁")
            }
        }
    }
}