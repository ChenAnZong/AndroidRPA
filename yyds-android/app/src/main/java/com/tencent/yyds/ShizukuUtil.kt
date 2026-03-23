package com.tencent.yyds

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import rikka.shizuku.Shizuku
import uiautomator.AppProcess
import uiautomator.ExtSystem
import com.tencent.yyds.R

/**
 * Shizuku 深度集成工具类
 *
 * 生命周期：App.onCreate → init() → onBinder/onDead → 权限请求 → 引擎启动
 * 状态机：NOT_INSTALLED → INSTALLED_NOT_RUNNING → RUNNING_NO_PERMISSION → READY
 */
object ShizukuUtil {

    private const val TAG = "ShizukuUtil"
    private const val REQUEST_CODE = 7749

    // ======================== 状态枚举 ========================

    enum class Status(val labelResId: Int, val actionLabelResId: Int?) {
        /** Shizuku 未安装 */
        NOT_INSTALLED(R.string.shizuku_not_installed, R.string.shizuku_go_install),
        /** 已安装但未启动 */
        INSTALLED_NOT_RUNNING(R.string.shizuku_not_running, R.string.shizuku_go_start),
        /** 已运行但未授权 */
        RUNNING_NO_PERMISSION(R.string.shizuku_no_permission, R.string.shizuku_go_authorize),
        /** 已授权，可用 */
        READY(R.string.shizuku_ready, null),
        /** 正在启动引擎 */
        STARTING_ENGINE(R.string.shizuku_starting_engine, null),
        /** 引擎已启动 */
        ENGINE_STARTED(R.string.shizuku_engine_started, null),
        /** 启动失败 */
        ENGINE_FAILED(R.string.shizuku_engine_failed, R.string.shizuku_retry),
    }

    // ======================== 状态 ========================

    @Volatile var status: Status = Status.NOT_INSTALLED
        private set

    /** Shizuku binder 是否存活 */
    @Volatile var isBinderAlive = false
        private set

    /** 是否已获得 Shizuku 授权 */
    @Volatile var isPermissionGranted = false
        private set

    /** Shizuku 运行的 UID（0=ROOT, 2000=ADB/Shell） */
    @Volatile var shizukuUid = -1
        private set

    /** 是否以 ROOT 身份运行 */
    val isRunningAsRoot: Boolean get() = shizukuUid == 0

    /** 是否以 ADB/Shell 身份运行 */
    val isRunningAsAdb: Boolean get() = shizukuUid == 2000

    /** Shizuku 是否完全可用（binder存活 + 已授权） */
    val isAvailable: Boolean get() = isBinderAlive && isPermissionGranted

    /** UserService 连接 */
    @Volatile private var shellService: IShellService? = null
    @Volatile private var isServiceBound = false

    /** 状态变化监听器 */
    private val statusListeners = java.util.concurrent.CopyOnWriteArrayList<(Status) -> Unit>()

    // ======================== UserService ========================

    class ShellService : IShellService.Stub {
        constructor()
        constructor(ctx: Context) : this() {
            Log.d(TAG, "ShellService created with context: $ctx")
        }

        /** Shizuku 约定的销毁方法，transactionCode = 16777115 */
        fun destroy() {
            Log.i(TAG, "ShellService destroy")
            System.exit(3)
        }

        fun exit() = destroy()

        override fun exec(command: String?): String {
            Log.d(TAG, "ShellService exec: $command")
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command ?: ""))
                val output = process.inputStream.bufferedReader().readText()
                val error = process.errorStream.bufferedReader().readText()
                process.waitFor()
                if (error.isNotEmpty()) "$output\n$error" else output
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name)
        ).apply {
            daemon(true)
            debuggable(BuildConfig.DEBUG)
            processNameSuffix("yyds_shell")
            version(BuildConfig.BUILD_DATE.hashCode())
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            Log.i(TAG, "UserService connected")
            shellService = IShellService.Stub.asInterface(service)
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "UserService disconnected")
            shellService = null
            isServiceBound = false
        }
    }

    // ======================== Binder 生命周期监听 ========================

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        isBinderAlive = true
        refreshPermissionState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        isBinderAlive = false
        isPermissionGranted = false
        shizukuUid = -1
        shellService = null
        isServiceBound = false
        updateStatus(Status.INSTALLED_NOT_RUNNING)
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                Log.i(TAG, "Permission result: granted=$granted")
                isPermissionGranted = granted
                if (granted) {
                    shizukuUid = try { Shizuku.getUid() } catch (_: Exception) { -1 }
                    updateStatus(Status.READY)
                    bindUserService()
                } else {
                    updateStatus(Status.RUNNING_NO_PERMISSION)
                }
            }
        }

    // ======================== 初始化 & 销毁 ========================

    /**
     * 在 Application.onCreate 中调用，注册所有 Shizuku 监听器
     */
    fun init() {
        Log.i(TAG, "init()")
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        // 初始检测
        refreshStatus()
    }

    /**
     * 在 Application.onTerminate 或不再需要时调用
     */
    fun destroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        unbindUserService()
        statusListeners.clear()
    }

    // ======================== 状态刷新 ========================

    /** 全量刷新状态（可在 onResume 中调用） */
    fun refreshStatus() {
        isBinderAlive = try { Shizuku.pingBinder() } catch (_: Exception) { false }
        if (!isBinderAlive) {
            val installed = isShizukuInstalled()
            updateStatus(if (installed) Status.INSTALLED_NOT_RUNNING else Status.NOT_INSTALLED)
            return
        }
        refreshPermissionState()
    }

    private fun refreshPermissionState() {
        isPermissionGranted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }

        if (isPermissionGranted) {
            shizukuUid = try { Shizuku.getUid() } catch (_: Exception) { -1 }
            updateStatus(Status.READY)
            bindUserService()
        } else {
            updateStatus(Status.RUNNING_NO_PERMISSION)
        }
    }

    // ======================== 权限请求 ========================

    /**
     * 请求 Shizuku 权限
     * @return true=已有权限无需请求, false=已发起请求等待回调
     */
    fun requestPermission(): Boolean {
        if (!isBinderAlive) return false
        if (isPermissionGranted) return true

        if (Shizuku.shouldShowRequestPermissionRationale()) {
            // 用户选了"拒绝且不再询问"
            Log.w(TAG, "Permission permanently denied")
            return false
        }
        Shizuku.requestPermission(REQUEST_CODE)
        return false
    }

    // ======================== UserService 管理 ========================

    private fun bindUserService() {
        if (isServiceBound || !isAvailable) return
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            Log.i(TAG, "bindUserService called")
        } catch (e: Exception) {
            Log.e(TAG, "bindUserService failed", e)
        }
    }

    private fun unbindUserService() {
        if (!isServiceBound) return
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (_: Exception) {}
        shellService = null
        isServiceBound = false
    }

    /**
     * 通过 UserService 执行 shell 命令
     */
    fun exec(command: String): String? {
        return try {
            shellService?.exec(command)
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $command", e)
            null
        }
    }

    // ======================== 引擎启动 ========================

    /**
     * 通过 Shizuku 启动所有引擎（yyds.keep → yyds.auto + yyds.py）
     * 在子线程中调用
     */
    fun startEngines(onResult: ((success: Boolean, msg: String) -> Unit)? = null) {
        if (!isAvailable) {
            val msg = "Shizuku不可用: binder=${isBinderAlive}, perm=${isPermissionGranted}"
            Log.w(TAG, msg)
            onResult?.invoke(false, msg)
            return
        }

        updateStatus(Status.STARTING_ENGINE)

        Thread {
            try {
                val service = shellService
                if (service == null) {
                    updateStatus(Status.ENGINE_FAILED)
                    onResult?.invoke(false, "UserService not connected")
                    return@Thread
                }

                // 1. 释放SO文件（需要先确保缓存目录存在）
                val mkdirResult = service.exec("mkdir -p ${AppProcess.unzipTo}/lib/${AppProcess.defaultABI}")
                Log.d(TAG, "mkdir result: $mkdirResult")

                // 2. 从APK释放SO到缓存目录（通过cp命令）
                // 用shell内置参数展开去除"package:"前缀，不依赖sed
                val apkPath = service.exec("p=$(pm path com.yyds.auto) && echo \${p#*:}")?.trim() ?: ""
                if (apkPath.isEmpty() || !apkPath.endsWith(".apk")) {
                    updateStatus(Status.ENGINE_FAILED)
                    onResult?.invoke(false, "Cannot get APK path")
                    return@Thread
                }
                Log.d(TAG, "APK path: $apkPath")

                // 3. 解压SO文件
                val unzipCmd = "cd ${AppProcess.unzipTo} && unzip -o '$apkPath' 'lib/${AppProcess.defaultABI}/*' -d . 2>&1"
                val unzipResult = service.exec(unzipCmd)
                Log.d(TAG, "unzip result: $unzipResult")

                // 4. 设置权限
                service.exec("chmod -R 755 ${AppProcess.unzipTo}/lib/")

                // 5. 启动守护进程
                val keeperCmd = AppProcess.getActiveEngineKeeperCmd()
                Log.i(TAG, "Starting keeper: $keeperCmd")
                val keeperResult = service.exec(keeperCmd)
                Log.i(TAG, "Keeper result: $keeperResult")

                // 6. 等待引擎端口就绪（用shell内置方式探测TCP端口，不依赖curl）
                var ready = false
                for (i in 1..30) {
                    SystemClock.sleep(1000)
                    // 用 /dev/tcp 或 nc 探测端口；若都不可用则用 exec 3<>/dev/tcp 回退
                    val probe = service.exec(
                        "(echo >/dev/tcp/127.0.0.1/61140) 2>/dev/null && echo ok || " +
                        "(nc -z -w1 127.0.0.1 61140 2>/dev/null && echo ok)"
                    )
                    if (probe != null && probe.contains("ok")) {
                        ready = true
                        break
                    }
                    Log.d(TAG, "Waiting for engine... attempt $i")
                }

                if (ready) {
                    updateStatus(Status.ENGINE_STARTED)
                    onResult?.invoke(true, "Engine started via Shizuku (UID=$shizukuUid)")
                } else {
                    updateStatus(Status.ENGINE_FAILED)
                    onResult?.invoke(false, "Engine start timeout")
                }
            } catch (e: Exception) {
                Log.e(TAG, "startEngines failed", e)
                updateStatus(Status.ENGINE_FAILED)
                onResult?.invoke(false, "Start exception: ${e.message}")
            }
        }.start()
    }

    // ======================== 辅助方法 ========================

    /** 检测 Shizuku 是否已安装 */
    fun isShizukuInstalled(): Boolean {
        return try {
            val ctx = AppProcess.appContext ?: return false
            ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** 获取友好的状态描述 */
    fun getStatusDescription(context: Context? = AppProcess.appContext): String {
        val ctx = context ?: return status.name
        return when (status) {
            Status.NOT_INSTALLED -> ctx.getString(R.string.shizuku_desc_not_installed)
            Status.INSTALLED_NOT_RUNNING -> ctx.getString(R.string.shizuku_desc_not_running)
            Status.RUNNING_NO_PERMISSION -> ctx.getString(R.string.shizuku_desc_no_permission)
            Status.READY -> {
                val identity = if (isRunningAsRoot) "ROOT" else "ADB(Shell)"
                ctx.getString(R.string.shizuku_desc_ready, identity, getVersion())
            }
            Status.STARTING_ENGINE -> ctx.getString(R.string.shizuku_desc_starting)
            Status.ENGINE_STARTED -> ctx.getString(R.string.shizuku_desc_started)
            Status.ENGINE_FAILED -> ctx.getString(R.string.shizuku_desc_failed)
        }
    }

    /** 获取 Shizuku 版本号 */
    fun getVersion(): Int {
        return try { Shizuku.getVersion() } catch (_: Exception) { -1 }
    }

    /** 获取非ROOT用户的引导提示 */
    fun getNonRootGuideSteps(context: Context? = AppProcess.appContext): List<String> {
        val ctx = context ?: return emptyList()
        return listOf(
            ctx.getString(R.string.shizuku_guide_step1),
            ctx.getString(R.string.shizuku_guide_step2),
            ctx.getString(R.string.shizuku_guide_step3),
            ctx.getString(R.string.shizuku_guide_step4),
            ctx.getString(R.string.shizuku_guide_step5)
        )
    }

    // ======================== 监听器 ========================

    fun addStatusListener(listener: (Status) -> Unit) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: (Status) -> Unit) {
        statusListeners.remove(listener)
    }

    private fun updateStatus(newStatus: Status) {
        if (status == newStatus) return
        Log.i(TAG, "Status: $status → $newStatus")
        status = newStatus
        try {
            statusListeners.forEach { it(newStatus) }
        } catch (e: Exception) {
            Log.e(TAG, "Status listener error", e)
        }
    }
}
