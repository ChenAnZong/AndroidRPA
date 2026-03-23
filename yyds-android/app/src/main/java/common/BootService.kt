package common

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.*
import androidx.core.app.NotificationManagerCompat
import com.tencent.yyds.BuildConfig
import com.tencent.yyds.LogcatActivity
import com.tencent.yyds.MainActivity
import com.tencent.yyds.R
import pyengine.EngineClient
import pyengine.PyEngine
import pyengine.YyProject
import uiautomator.AppProcess
import uiautomator.Const
import uiautomator.ExtSystem
import java.util.*
import kotlin.concurrent.thread


class BootService : Service() {
    var mNotifyId: Int = 0
    lateinit var mNotification: Notification.Builder
    var curProjectIndex = 0
    var allProject = listOf<YyProject>()
    lateinit var curProject: YyProject
    @Volatile
    var lastRunningStatus:Pair<Boolean, String?>? = null

    private val intentStartOrStop
        get() = wrapPendingIntent { intent ->
        intent.putExtra("method", "start/stop")
    }

    private fun sysProperty(key: String, defValue: String): String? {
        var res: String? = null
        try {
            @SuppressLint("PrivateApi") val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod(
                "get", *arrayOf<Class<*>>(
                    String::class.java,
                    String::class.java
                )
            )
            res = method.invoke(clazz, *arrayOf<Any>(key, defValue)) as String?
            if (res.isNullOrEmpty()) {
                return defValue
            }
        } catch (e: java.lang.Exception) {
            ExtSystem.printDebugLog("System property invoke error: $e")
        }
        return res
    }

    private val isMyt by lazy {
        !sysProperty("ro.vendor.rk_sdk", "").isNullOrEmpty() ||
                !sysProperty("sys.rkadb.root", "").isNullOrEmpty()
    }

    private fun registerService() {
        val filter = IntentFilter()
        // filter.addAction("android.media.VOLUME_CHANGED_ACTION")
        filter.addAction(SCREEN_OFF_ACTION)
        this.registerReceiver(object:BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
//                val pre_vlaue = intent?.extras?.get("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE")
//                val extra_value = intent?.extras?.get("android.media.EXTRA_VOLUME_STREAM_VALUE")
//                val type = intent?.extras?.get("android.media.EXTRA_VOLUME_STREAM_TYPE")
//                val alias = intent?.extras?.get("android.media.EXTRA_VOLUME_STREAM_TYPE_ALIAS")
//                ExtSystem.printDebugLog("音量: ${intent?.action} -> ${pre_vlaue} ${extra_value} ${type} ${alias} -> ${intent?.extras?.keySet()?.joinToString(",")}")
                if (intent?.action.equals(SCREEN_OFF_ACTION)) {
                    thread {
                        PyEngine.abortProject()
                        ExtSystem.printDebugLog("熄屏，停止工程!")
                    }
                }
            }
        } , filter)
        ExtSystem.printDebugLog("注册熄屏监控广播!")
    }

    private fun wrapPendingIntent(extraFunc: (i:Intent) -> Unit):PendingIntent {
        val intent = Intent(this, BootService::class.java).apply {
            extraFunc(this)
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                UUID.randomUUID().toString().hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                UUID.randomUUID().toString().hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )
        }
        return pendingIntent
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun showControlNotify() {
        if (isMyt) return
        val ctx = this
        val channelId = BuildConfig.APPLICATION_ID

        val notificationManager = NotificationManagerCompat.from(ctx)

        val channelName: CharSequence = "for Yyds.Auto Control"
        val importance = NotificationManager.IMPORTANCE_HIGH

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(channelId, channelName, importance)
            notificationChannel.enableLights(true)
            notificationChannel.lightColor = Color.CYAN
            notificationChannel.enableVibration(true)
            notificationChannel.vibrationPattern = longArrayOf(1000, 2000)
            notificationManager.createNotificationChannel(notificationChannel)
        }

        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(
                ctx, channelId
            )
        } else {
            Notification.Builder(ctx)
        }

        mNotification = notificationBuilder
            .setContentTitle("选择工程")
            .setContentText("上个工程/开始或结束/下个工程/查看日志")
            .setSmallIcon(R.drawable.ic_engine)
            .addAction(R.drawable.ic_prev, "上一个", wrapPendingIntent { intent ->
                intent.putExtra("method", "last")
            }) // #0
            .addAction(R.drawable.ic_start, "启动", intentStartOrStop) // #1
            .addAction(R.drawable.ic_next, "下一个", wrapPendingIntent { intent ->
                intent.putExtra("method", "next")
            }) // #2
            .addAction(R.drawable.ic_console, "日志查看", PendingIntent.getActivity(
                ctx,
                UUID.randomUUID().toString().hashCode(),
                Intent(this, LogcatActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            ))
            .setWhen(System.currentTimeMillis())
            .setAutoCancel(false)
            .setStyle(Notification.MediaStyle()
                .setShowActionsInCompactView(1)
             )
            .setContentIntent(PendingIntent.getActivity(
                ctx,
                UUID.randomUUID().toString().hashCode(),
                Intent(this, MainActivity::class.java).apply {
                    putExtra(Const.NAV_TO_ACTIVITY_FRAGMENT, 1) },
                PendingIntent.FLAG_IMMUTABLE
            ))
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mNotification.setBadgeIconType(Notification.BADGE_ICON_SMALL)
        }
        mNotifyId = channelId.hashCode()
        ctx.startForeground(mNotifyId, mNotification.build())
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun showOnceNotify(title: String, text: String) {
        if (isMyt) return
        ExtSystem.printDebugLog("showOnceNotify")
        val ctx = this
        val channelId = BuildConfig.APPLICATION_ID
        val id = 456
        val notificationManager = NotificationManagerCompat.from(ctx)

        val channelName: CharSequence = "for Yyds.Auto Control"
        val importance = NotificationManager.IMPORTANCE_DEFAULT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(channelId, channelName, importance)
            notificationChannel.enableLights(true)
            notificationChannel.lightColor = Color.CYAN
            notificationChannel.enableVibration(true)
            notificationChannel.vibrationPattern = longArrayOf(1000, 2000)
            notificationManager.createNotificationChannel(notificationChannel)
        }

        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(
                ctx, channelId
            )
        } else {
            Notification.Builder(ctx)
        }

        val n = notificationBuilder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_engine)
            .setWhen(System.currentTimeMillis())
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(
                ctx,
                UUID.randomUUID().toString().hashCode(),
                Intent(this, MainActivity::class.java).apply {  putExtra(Const.NAV_TO_ACTIVITY_FRAGMENT, 1) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .setOngoing(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        ctx.startForeground(id, n.build())
        Handler(Looper.getMainLooper()).postDelayed({
            notificationManager.cancel(id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        },
            5000)
    }

    private fun updateControlNotify(useCacheStatus: Boolean) {
        if (isMyt) return
        try {
            val runningStatus = if (useCacheStatus && lastRunningStatus != null) lastRunningStatus else EngineClient.getProjectRunningStatus()
            lastRunningStatus = runningStatus
            var title = ""
            var content = ""
            var subContent = ""
            var action:Notification.Action? = null
            // 检查引擎运行情况
            if (runningStatus == null) {
                title = "Python执行引擎未激活"
                content = "请检查引擎运行情况"
            } else {
                if (!::curProject.isInitialized) {
                    refreshProject(true)
                }

                // 如果正在运行中
                if (runningStatus.first) {
                    val projectName = runningStatus.second ?: ""
                    val index = if (projectName.isNotEmpty()) getProjectIndexElseRefresh(projectName) else 0
                    curProject = allProject[index]
                    subContent = "${index + 1}/${allProject.size}"
                    title = "当前工程 $subContent ${curProject.folderName} ${curProject.version}"
                    content = "正在运行"
                    action = Notification.Action(R.drawable.ic_stop, "暂停", intentStartOrStop)
                } else {
                    if (!::curProject.isInitialized) {
                        // 可能是服务重启了！
                        title = "选择工程"
                        content = "上个工程/开始或结束/下个工程/查看日志"
                    } else {
                        val index = allProject.indexOf(curProject)
                        subContent = "${index + 1}/${allProject.size}"
                        title = "当前工程 $subContent ${curProject.folderName} ${curProject.version}"
                    }
                    action = Notification.Action(R.drawable.ic_start, "开始", intentStartOrStop)
                }
            }

            val newNotification = mNotification.apply {
                setContentTitle(title)
                setSubText(subContent)
                setContentText(content)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setColorized(true)
                }
                setWhen(System.currentTimeMillis())
            }.build()

            // 震动通知
            if(!useCacheStatus) {
                val p = longArrayOf(300, 300)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                    vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(p, -1))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    v.vibrate(VibrationEffect.createWaveform(p, -1))
                } else {
                    @Suppress("DEPRECATION")
                    val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    @Suppress("DEPRECATION")
                    v.vibrate(1000)
                }
            }

            newNotification.actions[1] = action
            // NotificationManagerCompat.from(this).notify(mNotifyId, newNotification)
        } catch (e:java.lang.Exception) {
            ExtSystem.printDebugError("更新控制通知", e)
        }

    }

    private fun updateControlNotifyAsync(useCacheStatus: Boolean) {
        if (isMyt) return
        thread {
            updateControlNotify(useCacheStatus)
        }
    }

    private fun refreshProject(wait:Boolean = false) {
        // 通过工作进程获取项目列表（工作进程拥有ROOT/SHELL权限可访问文件系统）
        val t = thread {
            synchronized(this) {
                val engineRunning = EngineClient.ensureEngineRunning()
                allProject = if (engineRunning) {
                    EngineClient.getProjectList()
                } else {
                    emptyList()
                }
            }
            if (lastRunningStatus == null) {
                lastRunningStatus = PyEngine.getProjectRunningStatus()
            }
        }
        if (wait) {
            t.join()
        }
    }

    // 如果有新的工程，刷新一下, 注意此函数有可能阻塞
    private fun getProjectIndexElseRefresh(name: String): Int {
        val found = allProject.indexOfFirst { it.folderName == name }
        if (found >= 0) return found
        // 刷新一次后再找，避免无限递归
        refreshProject(true)
        val retry = allProject.indexOfFirst { it.folderName == name }
        return if (retry >= 0) retry else 0
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        try {
            ExtSystem.printDebugLog("BootService onStartCommand method:" + intent + ">> Method:" + intent.getStringExtra("method"))

            val method = intent.getStringExtra("method")

            // -------- 通知系列
            when(method) {
                "REFRESH", null -> {
                    refreshProject()
                }
                "next" -> {
                    val index = ++curProjectIndex % allProject.size
                    curProject = allProject[index]
                    updateControlNotifyAsync(true)
                }
                "last" -> {
                    if (curProjectIndex <= 0) {
                        curProjectIndex = allProject.size
                    }
                    val index = --curProjectIndex % allProject.size
                    curProject = allProject[index]
                    updateControlNotifyAsync(true)
                }
                "start/stop" -> {
                    val index = curProjectIndex % allProject.size
                    curProject = allProject[index]
                    thread {
                        val runningStatus = EngineClient.getProjectRunningStatus()
                        if (runningStatus == null) {
                            updateControlNotify(false)
                            return@thread
                        } else {
                            if (!runningStatus.first) {
                                // 未运行
                                PyEngine.startProject(curProject.folderName)
                                SystemClock.sleep(1500)
                                updateControlNotify(false)
                            } else {
                                val postProjectName = runningStatus.second
                                if (!postProjectName.isNullOrEmpty() && postProjectName != curProject.folderName) {
                                    curProjectIndex = getProjectIndexElseRefresh(postProjectName)
                                }
                                curProject = allProject[curProjectIndex]
                                // 正在运行
                                PyEngine.abortProject()
                                SystemClock.sleep(1500)
                                updateControlNotify(false)
                            }
                        }
                    }
                }
                // 运行的时候会进行指示，主要是调试模式用与界面模式，但是目前跟上面冲突了！
                M_CURRENT -> {
                    if (allProject.isNotEmpty()) {
                        if (intent.hasExtra("project")) {
                            val projectName = intent.getStringExtra("project")
                            val index = allProject.indexOfFirst { it.folderName == projectName }
                            if (index > -1 && allProject.isNotEmpty()) {
                                curProject = allProject[index]
                                curProjectIndex = index
                            }
                        }
                        if (intent.hasExtra("title") && intent.hasExtra("text")) {
                            val title = intent.getStringExtra("title")!!
                            val text = intent.getStringExtra("text")!!
                            showOnceNotify(title, text)
                        }
                        updateControlNotifyAsync(false)
                    }
                }
                M_NOTIFY -> {
                    if (intent.hasExtra("title") && intent.hasExtra("text")) {
                        val title = intent.getStringExtra("title")!!
                        val text = intent.getStringExtra("text")!!
                        showOnceNotify(title, text)
                    }
                }
            }
        } catch (e:Exception) {
            ExtSystem.printDebugError("onStartCommand", e)
        }
        return START_NOT_STICKY;
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        showControlNotify()
        thread {
            updateControlNotify(false)
        }
        registerService()
        ExtSystem.printDebugLog("====BootService")
        super.onCreate()
    }

    companion object {
        private const val M_REFRESH = "REFRESH"
        private const val M_CURRENT = "CURRENT"
        private const val M_NOTIFY = "NOTIFY"
        private const val SCREEN_OFF_ACTION = "android.intent.action.SCREEN_OFF"

        private val startServiceCommand = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            "am start-foreground-service"
        } else {
            "am start-service"
        }
        // -------------- 工程系列 ------------
        // 刷新工程
        fun sendRefreshProject() {
            thread {
                ExtSystem.shell("$startServiceCommand -n com.yyds.auto/common.BootService -e \"method\" $M_REFRESH")
            }
        }

        // 刷新当前工程与运行状态
        fun sendCurrentRunningStatus(project:String, title:String, text:String) {
            // 不弹通知, 直接弹出来的, 干扰脚本运行!
            if (AppProcess.isMytDevice()) {
                return
            }
            thread {
                ExtSystem.shell("service call notification 1")
                ExtSystem.shell("$startServiceCommand -n com.yyds.auto/common.BootService -e method $M_CURRENT -e title \"$title\" -e text \"$text\" -e project \"$project\"")
            }
        }

        fun sendNotify(title: String, text: String) {
            ExtSystem.shell("$startServiceCommand -n com.yyds.auto/common.BootService -e method $M_NOTIFY -e title \"$title\" -e text \"$text\"")
        }
    }
}