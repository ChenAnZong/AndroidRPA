package com.tencent.yyds.frag

import android.annotation.SuppressLint
import org.json.JSONObject
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import pyengine.CPythonBridge
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tencent.yyds.App
import com.tencent.yyds.LogcatActivity
import com.tencent.yyds.FloatingWindowService
import com.tencent.yyds.MagiskModuleHelper
import com.tencent.yyds.UnifiedFloatingService
import com.tencent.yyds.R
import com.tencent.yyds.ShizukuUtil
import com.tencent.yyds.databinding.FragmentHomeBinding
import com.tencent.yyds.widget.AppBanner
import me.caz.xp.ui.ContextAction
import pyengine.PyEngine
import uiautomator.AppProcess
import uiautomator.ExportHandle
import uiautomator.ExtSystem
import uiautomator.RemoteControl
import uiautomator.util.NetUtil
import kotlin.concurrent.thread


class HomeFragment : Fragment() {

    private var fragmentHomeBinding: FragmentHomeBinding? = null
    private val binding get() = fragmentHomeBinding!!
    private val handler = Handler(Looper.getMainLooper())
    private var isFirstActive = false
    /** 自动刷新是否激活 */
    private var autoRefreshActive = false
    /** 是否已弹出权限不足提示（每次 onResume 重置，避免反复弹窗） */
    private var hasShownPermissionAlert = false

    companion object {
        /** 状态自动刷新间隔（毫秒） */
        private const val AUTO_REFRESH_INTERVAL = 8_000L
        /** 首次刷新延迟（毫秒） */
        private const val FIRST_REFRESH_DELAY = 500L
    }

    /** 定时刷新任务 */
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            if (!autoRefreshActive || !isAdded) return
            updateStatus()
            handler.postDelayed(this, AUTO_REFRESH_INTERVAL)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatus() {
        if (!isAdded || fragmentHomeBinding == null) return

        thread {
            try {
                // 网络信息
                val netText = getString(R.string.home_lan_address, NetUtil.wifiIpAddress()) + "\n" +
                    getString(R.string.home_wan_address, NetUtil.tryGetNetIp() ?: getString(R.string.home_net_fetch_failed))
                uiSafe { binding.cardNetStatusText.text = netText }

                // === 自动化引擎状态 ===
                val engineStatus = ExportHandle.checkEngineStatus()
                // 首次检测时尝试自动启动所有引擎
                if (!engineStatus.first && App.hasRootShellPermission() && !isFirstActive) {
                    PyEngine.startAllEngines()
                    isFirstActive = true
                }
                val engineStatusText = if (!engineStatus.first) {
                    getString(R.string.home_engine_waiting)
                } else {
                    val modeText = if (engineStatus.second == 0) "(root)" else "(adb)"
                    getString(R.string.home_engine_activated, modeText)
                }
                uiSafe {
                    binding.cardEngineStatusText.text = engineStatusText
                    binding.dotEngine.setBackgroundResource(
                        if (engineStatus.first) R.drawable.bg_dot_green else R.drawable.bg_dot_red
                    )
                }

                // === Py引擎状态 ===
                val isOuterEngineRunning = PyEngine.isOuterEngineRunning
                val pyStatusText = if (CPythonBridge.isInitialized()) {
                    getString(R.string.home_py_builtin_limited)
                } else {
                    if (isOuterEngineRunning) getString(R.string.home_py_running) else getString(R.string.home_py_not_started)
                }
                uiSafe {
                    binding.cardPyEngineStatusText.text = pyStatusText
                    binding.dotPyEngine.setBackgroundResource(
                        if (isOuterEngineRunning) R.drawable.bg_dot_green else R.drawable.bg_dot_red
                    )
                }

                // === Shizuku 状态刷新 ===
                ShizukuUtil.refreshStatus()

                // === 更新 Shizuku 状态卡片（仅非ROOT设备显示） ===
                val hasRoot = App.hasRootShellPermission()
                uiSafe {
                    if (hasRoot && engineStatus.first) {
                        // ROOT设备且引擎已运行，隐藏Shizuku卡片
                        binding.cardShizukuStatus.visibility = android.view.View.GONE
                    } else if (!hasRoot) {
                        // 非ROOT设备，始终显示Shizuku卡片
                        binding.cardShizukuStatus.visibility = android.view.View.VISIBLE
                        binding.cardShizukuStatusText.text = ShizukuUtil.getStatusDescription()
                        val actionResId = ShizukuUtil.status.actionLabelResId
                        if (actionResId != null) {
                            binding.cardShizukuAction.visibility = android.view.View.VISIBLE
                            binding.cardShizukuAction.text = getString(actionResId)
                        } else {
                            binding.cardShizukuAction.visibility = android.view.View.GONE
                        }
                    } else {
                        // ROOT设备但引擎未运行，隐藏（ROOT走正常启动流程）
                        binding.cardShizukuStatus.visibility = android.view.View.GONE
                    }
                }

                // === 开机自启模块状态（仅ROOT设备） ===
                if (hasRoot) {
                    val moduleStatus = MagiskModuleHelper.checkStatus()
                    uiSafe {
                        binding.cardMagiskModule.visibility = View.VISIBLE
                        when (moduleStatus) {
                            MagiskModuleHelper.Status.INSTALLED -> {
                                binding.cardMagiskModuleDesc.text = getString(R.string.home_magisk_installed)
                                binding.cardMagiskModuleDesc.setTextColor(0xFF2E7D32.toInt())
                                binding.cardMagiskModuleAction.text = getString(R.string.home_magisk_manage)
                                binding.cardMagiskModuleAction.setBackgroundResource(R.drawable.bg_status_badge_ok)
                                binding.cardMagiskModuleAction.setTextColor(0xFF2E7D32.toInt())
                            }
                            MagiskModuleHelper.Status.DISABLED -> {
                                binding.cardMagiskModuleDesc.text = getString(R.string.home_magisk_disabled)
                                binding.cardMagiskModuleDesc.setTextColor(0xFFE65100.toInt())
                                binding.cardMagiskModuleAction.text = getString(R.string.home_magisk_install)
                                binding.cardMagiskModuleAction.setBackgroundResource(R.drawable.bg_status_badge_warn)
                                binding.cardMagiskModuleAction.setTextColor(0xFFE65100.toInt())
                            }
                            else -> {
                                binding.cardMagiskModuleDesc.text = getString(R.string.home_magisk_not_installed)
                                binding.cardMagiskModuleDesc.setTextColor(0xFF999999.toInt())
                                binding.cardMagiskModuleAction.text = getString(R.string.home_magisk_install)
                                binding.cardMagiskModuleAction.setBackgroundResource(R.drawable.bg_status_badge_warn)
                                binding.cardMagiskModuleAction.setTextColor(0xFFE65100.toInt())
                            }
                        }
                    }
                } else {
                    uiSafe { binding.cardMagiskModule.visibility = View.GONE }
                }

                // === 首次检测：无ROOT时尝试 Shizuku 自动启动 ===
                if (!engineStatus.first && !isFirstActive && !App.hasRootShellPermission()) {
                    if (ShizukuUtil.isAvailable) {
                        isFirstActive = true
                        ExtSystem.printDebugLog("无ROOT，尝试通过Shizuku自动启动引擎...")
                        ShizukuUtil.startEngines { success, msg ->
                            ExtSystem.printDebugLog("Shizuku自动启动: $success - $msg")
                        }
                    }
                }

                // === 权限不足检测：引擎未运行 + 无 ROOT/SHELL + 无 Shizuku ===
                if (!hasShownPermissionAlert && (!engineStatus.first || !isOuterEngineRunning)) {
                    val hasRootShell = App.hasRootShellPermission()
                    if (!hasRootShell && !ShizukuUtil.isAvailable) {
                        hasShownPermissionAlert = true
                        showPermissionAlert(engineStatus.first, isOuterEngineRunning)
                    }
                }

                // === 群控与投屏组件状态 ===
                var isConRunning = false
                val conStatusText = if (!engineStatus.first) {
                    getString(R.string.home_waiting_engine)
                } else if (!RemoteControl.checkSCRCPYRunning()) {
                    getString(R.string.home_not_activated)
                } else {
                    isConRunning = true
                    if (!RemoteControl.checkSCRCPYConnecting()) getString(R.string.home_running_not_connected) else getString(R.string.home_connected)
                }
                uiSafe {
                    binding.cardRemoteStatusText.text = conStatusText
                    binding.dotRemote.setBackgroundResource(
                        if (isConRunning) R.drawable.bg_dot_green else R.drawable.bg_dot_gray
                    )
                }

                // === 触控注入模式 ===
                var touchModeText: String
                var touchModeActive: Boolean
                if (!engineStatus.first) {
                    touchModeText = getString(R.string.home_waiting_engine)
                    touchModeActive = false
                } else {
                    try {
                        val raw = ExportHandle.getHandler().http("/get_touch_mode", emptyMap()).trim()
                        val mode = try {
                            JSONObject(raw).getJSONObject("data").getString("mode")
                        } catch (_: Exception) { raw }
                        touchModeActive = mode != "auto"
                        touchModeText = when (mode) {
                            "uinput" -> getString(R.string.home_touch_uinput)
                            "kernel" -> getString(R.string.home_touch_kernel)
                            "java"   -> getString(R.string.home_touch_java)
                            "auto"   -> getString(R.string.home_touch_auto)
                            else     -> mode
                        }
                    } catch (e: Exception) {
                        touchModeText = getString(R.string.home_touch_query_failed)
                        touchModeActive = false
                    }
                }
                uiSafe {
                    binding.cardTouchModeText.text = touchModeText
                    binding.dotTouchMode.setBackgroundResource(
                        if (touchModeActive) R.drawable.bg_dot_green else R.drawable.bg_dot_gray
                    )
                }
            } catch (e: Exception) {
                ExtSystem.printDebugError("HomeFragment刷新状态异常", e)
            }
        }
    }

    /**
     * 弹出引擎激活引导 BottomSheet
     * 当引擎进程未运行且设备既无 ROOT/SHELL 权限也无 Shizuku 时调用
     * 提供三种激活方案：ROOT / Shizuku / ADB
     */
    private fun showPermissionAlert(autoEngineRunning: Boolean, pyEngineRunning: Boolean) {
        val problems = mutableListOf<String>()
        if (!autoEngineRunning) problems.add(getString(R.string.home_auto_engine))
        if (!pyEngineRunning) problems.add(getString(R.string.home_script_engine))
        val problemList = problems.joinToString("、")

        uiSafe {
            activity?.let { act ->
                // Banner 即时提醒
                AppBanner.show(act, getString(R.string.home_engine_not_running_activate, problemList), AppBanner.Type.ERROR)
            }

            // 延迟弹出 BottomSheet，避免与 Banner 动画重叠
            handler.postDelayed({
                if (!isAdded || fragmentHomeBinding == null) return@postDelayed
                val act = activity ?: return@postDelayed

                val dialog = BottomSheetDialog(act, R.style.BottomSheetDialogTheme)
                val sheetView = LayoutInflater.from(act).inflate(
                    R.layout.bottom_sheet_engine_activation, null
                )

                // 动态更新状态描述
                val tvDesc = sheetView.findViewById<TextView>(R.id.tv_activation_desc)
                tvDesc.text = getString(R.string.home_activation_desc, problemList)

                // === ROOT 方案 ===
                val tvRootStatus = sheetView.findViewById<TextView>(R.id.tv_root_status)
                val hasRoot = App.hasRootShellPermission()
                if (hasRoot) {
                    tvRootStatus.text = getString(R.string.home_authorized)
                    tvRootStatus.setTextColor(0xFF2E7D32.toInt())
                    tvRootStatus.setBackgroundResource(R.drawable.bg_status_badge_ok)
                }
                sheetView.findViewById<View>(R.id.option_root).setOnClickListener {
                    dialog.dismiss()
                    if (hasRoot) {
                        ContextAction.toast(getString(R.string.home_starting_with_root))
                        thread { PyEngine.startAllEngines() }
                    } else {
                        MaterialAlertDialogBuilder(act, R.style.MyDialog)
                            .setTitle(getString(R.string.home_get_root_title))
                            .setMessage(getString(R.string.home_get_root_message))
                            .setPositiveButton(getString(R.string.home_understood)) { d, _ -> d.dismiss() }
                            .show()
                    }
                }

                // === Shizuku 方案 ===
                val tvShizukuStatus = sheetView.findViewById<TextView>(R.id.tv_shizuku_status)
                val shizukuStatus = ShizukuUtil.status

                when (shizukuStatus) {
                    ShizukuUtil.Status.READY, ShizukuUtil.Status.ENGINE_STARTED -> {
                        tvShizukuStatus.text = getString(R.string.home_authorized)
                        tvShizukuStatus.setTextColor(0xFF2E7D32.toInt())
                        tvShizukuStatus.setBackgroundResource(R.drawable.bg_status_badge_ok)
                    }
                    ShizukuUtil.Status.RUNNING_NO_PERMISSION -> {
                        tvShizukuStatus.text = getString(R.string.home_not_authorized)
                    }
                    ShizukuUtil.Status.INSTALLED_NOT_RUNNING -> {
                        tvShizukuStatus.text = getString(R.string.home_not_started)
                    }
                    else -> {
                        tvShizukuStatus.text = getString(R.string.home_not_installed)
                    }
                }

                sheetView.findViewById<View>(R.id.option_shizuku).setOnClickListener {
                    dialog.dismiss()
                    when (shizukuStatus) {
                        ShizukuUtil.Status.NOT_INSTALLED -> {
                            // 引导安装 Shizuku
                            MaterialAlertDialogBuilder(act, R.style.MyDialog)
                                .setTitle(getString(R.string.home_install_shizuku_title))
                                .setMessage(buildString {
                                    append(getString(R.string.home_install_shizuku_desc))
                                    append("\n\n")
                                    ShizukuUtil.getNonRootGuideSteps().forEach { append("$it\n") }
                                })
                                .setPositiveButton(getString(R.string.home_go_download)) { d, _ ->
                                    d.dismiss()
                                    App.app.jumpBrowserUrl("https://shizuku.rikka.app/download/")
                                }
                                .setNegativeButton(getString(R.string.btn_cancel)) { d, _ -> d.dismiss() }
                                .show()
                        }
                        ShizukuUtil.Status.INSTALLED_NOT_RUNNING -> {
                            // 引导启动 Shizuku
                            MaterialAlertDialogBuilder(act, R.style.MyDialog)
                                .setTitle(getString(R.string.home_start_shizuku_title))
                                .setMessage(getString(R.string.home_start_shizuku_message))
                                .setPositiveButton(getString(R.string.home_open_shizuku)) { d, _ ->
                                    d.dismiss()
                                    try {
                                        val intent = act.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                        if (intent != null) startActivity(intent)
                                        else AppBanner.error(act, getString(R.string.home_cannot_open_shizuku))
                                    } catch (_: Exception) {
                                        AppBanner.error(act, getString(R.string.home_cannot_open_shizuku))
                                    }
                                }
                                .setNegativeButton(getString(R.string.btn_cancel)) { d, _ -> d.dismiss() }
                                .show()
                        }
                        ShizukuUtil.Status.RUNNING_NO_PERMISSION -> {
                            // 请求授权
                            if (!ShizukuUtil.requestPermission()) {
                                MaterialAlertDialogBuilder(act, R.style.MyDialog)
                                    .setTitle(getString(R.string.home_shizuku_auth_title))
                                    .setMessage(getString(R.string.home_shizuku_auth_message))
                                    .setPositiveButton(getString(R.string.home_open_shizuku)) { d, _ ->
                                        d.dismiss()
                                        try {
                                            val intent = act.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                            if (intent != null) startActivity(intent)
                                        } catch (_: Exception) {}
                                    }
                                    .setNegativeButton(getString(R.string.btn_cancel)) { d, _ -> d.dismiss() }
                                    .show()
                            }
                        }
                        ShizukuUtil.Status.READY -> {
                            // 已授权，直接启动引擎
                            ContextAction.toast(getString(R.string.home_starting_with_shizuku))
                            ShizukuUtil.startEngines { success, msg ->
                                uiSafe {
                                    if (success) {
                                        activity?.let { AppBanner.show(it, msg, AppBanner.Type.SUCCESS) }
                                    } else {
                                        activity?.let { AppBanner.show(it, getString(R.string.home_start_failed_msg, msg), AppBanner.Type.ERROR) }
                                    }
                                }
                            }
                        }
                        else -> {
                            ContextAction.toast(ShizukuUtil.getStatusDescription())
                        }
                    }
                }

                // === ADB 方案 ===
                sheetView.findViewById<View>(R.id.option_adb).setOnClickListener {
                    dialog.dismiss()
                    // 后台写入激活脚本到外部存储
                    thread {
                        val written = AppProcess.writeActivateScript(act)
                        uiSafe {
                            val adbCmd = AppProcess.ACTIVATE_SCRIPT_ADB_CMD
                            val msg = if (written) {
                                getString(R.string.home_adb_activate_message_v2, adbCmd)
                            } else {
                                getString(R.string.home_adb_activate_message)
                            }
                            MaterialAlertDialogBuilder(act, R.style.MyDialog)
                                .setTitle(getString(R.string.home_adb_activate_title))
                                .setMessage(msg)
                                .setPositiveButton(getString(R.string.home_adb_copy_command)) { d, _ ->
                                    d.dismiss()
                                    val clipboard = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("adb command", adbCmd))
                                    AppBanner.show(act, getString(R.string.home_adb_command_copied), AppBanner.Type.SUCCESS)
                                }
                                .setNeutralButton(getString(R.string.home_view_tutorial)) { d, _ ->
                                    d.dismiss()
                                    App.app.jumpBrowserUrl("https://yydsxx.com/docs/yyds-auto/script")
                                }
                                .setNegativeButton(getString(R.string.btn_close)) { d, _ -> d.dismiss() }
                                .show()
                        }
                    }
                }

                dialog.setContentView(sheetView)
                dialog.show()
            }, 600)
        }
    }

    /** 弹出模块安装确认对话框 */
    private fun showModuleInstallDialog() {
        val act = activity ?: return
        val rootMgr = try { MagiskModuleHelper.getRootManagerName() } catch (_: Exception) { "ROOT" }

        MaterialAlertDialogBuilder(act, R.style.MyDialog)
            .setIcon(R.drawable.ic_launch)
            .setTitle(getString(R.string.home_magisk_install_title))
            .setMessage(getString(R.string.home_magisk_install_message, rootMgr))
            .setPositiveButton(getString(R.string.home_magisk_install_confirm)) { dialog, _ ->
                dialog.dismiss()
                performModuleInstall()
            }
            .setNegativeButton(getString(R.string.btn_cancel)) { d, _ -> d.dismiss() }
            .show()
    }

    /** 执行模块安装 */
    private fun performModuleInstall() {
        val act = activity ?: return
        AppBanner.show(act, getString(R.string.home_magisk_installing), AppBanner.Type.INFO)

        thread {
            val (success, log) = MagiskModuleHelper.installModule(act)
            uiSafe {
                if (success) {
                    AppBanner.show(act, getString(R.string.home_magisk_install_success), AppBanner.Type.SUCCESS)
                    MaterialAlertDialogBuilder(act, R.style.MyDialog)
                        .setTitle(getString(R.string.home_magisk_install_done_title))
                        .setMessage(getString(R.string.home_magisk_install_done_message))
                        .setPositiveButton(getString(R.string.home_understood)) { d, _ -> d.dismiss() }
                        .show()
                    // 直接更新卡片为已安装状态（不依赖 updateStatus，某些 ROOT 管理器重启前目录不存在）
                    binding.cardMagiskModuleDesc.text = getString(R.string.home_magisk_installed)
                    binding.cardMagiskModuleDesc.setTextColor(0xFF2E7D32.toInt())
                    binding.cardMagiskModuleAction.text = getString(R.string.home_magisk_manage)
                    binding.cardMagiskModuleAction.setBackgroundResource(R.drawable.bg_status_badge_ok)
                    binding.cardMagiskModuleAction.setTextColor(0xFF2E7D32.toInt())
                } else {
                    AppBanner.show(act, getString(R.string.home_magisk_install_failed), AppBanner.Type.ERROR)
                    MaterialAlertDialogBuilder(act, R.style.MyDialog)
                        .setTitle(getString(R.string.home_magisk_install_failed))
                        .setMessage(log.ifEmpty { getString(R.string.home_magisk_install_failed_desc) })
                        .setPositiveButton(getString(R.string.home_understood)) { d, _ -> d.dismiss() }
                        .show()
                }
            }
        }
    }

    /** 弹出模块管理对话框（已安装状态） */
    private fun showModuleManageDialog() {
        val act = activity ?: return
        val items = arrayOf(
            getString(R.string.home_magisk_reinstall),
            getString(R.string.home_magisk_uninstall)
        )
        MaterialAlertDialogBuilder(act, R.style.MyDialog)
            .setTitle(getString(R.string.home_magisk_module_title))
            .setItems(items) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> performModuleInstall()
                    1 -> {
                        MaterialAlertDialogBuilder(act, R.style.MyDialog)
                            .setTitle(getString(R.string.home_magisk_uninstall_title))
                            .setMessage(getString(R.string.home_magisk_uninstall_message))
                            .setPositiveButton(getString(R.string.home_magisk_uninstall_confirm)) { d, _ ->
                                d.dismiss()
                                thread {
                                    val ok = MagiskModuleHelper.uninstallModule()
                                    uiSafe {
                                        if (ok) {
                                            AppBanner.show(act, getString(R.string.home_magisk_uninstall_success), AppBanner.Type.SUCCESS)
                                        } else {
                                            AppBanner.show(act, getString(R.string.home_magisk_uninstall_failed), AppBanner.Type.ERROR)
                                        }
                                        updateStatus()
                                    }
                                }
                            }
                            .setNegativeButton(getString(R.string.btn_cancel)) { d, _ -> d.dismiss() }
                            .show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel)) { d, _ -> d.dismiss() }
            .show()
    }

    /** 弹出新手引导 BottomSheet */
    private fun showBeginnerGuide() {
        val act = activity ?: return
        val dialog = BottomSheetDialog(act, R.style.BottomSheetDialogTheme)
        val sheetView = LayoutInflater.from(act).inflate(
            R.layout.bottom_sheet_beginner_guide, null
        )
        sheetView.findViewById<View>(R.id.btn_guide_doc).setOnClickListener {
            dialog.dismiss()
            App.app.jumpBrowserUrl("https://yydsxx.com/docs/yyds-auto/script")
        }
        sheetView.findViewById<View>(R.id.btn_guide_dismiss).setOnClickListener {
            dialog.dismiss()
        }
        dialog.setContentView(sheetView)
        dialog.show()
    }

    /** 安全地在UI线程执行，避免Fragment已销毁时崩溃 */
    private inline fun uiSafe(crossinline action: () -> Unit) {
        if (isAdded && fragmentHomeBinding != null) {
            ContextAction.uiThread { if (isAdded && fragmentHomeBinding != null) action() }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.cardGuide.setOnClickListener {
            showBeginnerGuide()
        }
        binding.cardRunLog.setOnClickListener {
            startActivity(Intent(requireContext(), LogcatActivity::class.java))
        }
        binding.cardRemoteStatus.setOnClickListener { }
        binding.cardTouchMode.setOnClickListener {
            if (!ExportHandle.checkEngineStatus().first) {
                activity?.let { AppBanner.show(it, getString(R.string.home_engine_not_started_touch), AppBanner.Type.ERROR) }
                return@setOnClickListener
            }
            val modes = arrayOf("auto", "uinput", "kernel", "java")
            val labels = arrayOf(
                getString(R.string.home_touch_label_auto),
                getString(R.string.home_touch_label_uinput),
                getString(R.string.home_touch_label_kernel),
                getString(R.string.home_touch_label_java)
            )
            MaterialAlertDialogBuilder(requireActivity(), R.style.MyDialog)
                .setTitle(getString(R.string.home_switch_touch_mode_title))
                .setItems(labels) { dialog, which ->
                    dialog.dismiss()
                    val selectedMode = modes[which]
                    thread {
                        try {
                            val raw = ExportHandle.getHandler().http("/set_touch_mode", mapOf("mode" to selectedMode)).trim()
                            val result = try {
                                JSONObject(raw).getJSONObject("data").getString("mode")
                            } catch (_: Exception) { raw }
                            val label = when (result) {
                                "uinput" -> getString(R.string.home_touch_label_uinput)
                                "kernel" -> getString(R.string.home_touch_label_kernel)
                                "java"   -> getString(R.string.home_touch_label_java)
                                "auto"   -> getString(R.string.home_touch_label_auto_short)
                                else     -> labels[which]
                            }
                            uiSafe {
                                activity?.let {
                                    AppBanner.show(it, getString(R.string.home_touch_switched, label), AppBanner.Type.SUCCESS)
                                }
                                binding.cardTouchModeText.text = label
                                binding.dotTouchMode.setBackgroundResource(
                                    if (result != "auto") R.drawable.bg_dot_green else R.drawable.bg_dot_gray
                                )
                            }
                        } catch (e: Exception) {
                            uiSafe {
                                activity?.let {
                                    AppBanner.show(it, getString(R.string.home_touch_switch_failed, e.message), AppBanner.Type.ERROR)
                                }
                            }
                        }
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel)) { d, _ -> d.cancel() }
                .show()
        }
        binding.cardFloatingWindow.setOnClickListener {
            toggleUnifiedFloating()
        }

        // 开机自启模块卡片点击
        binding.cardMagiskModule.setOnClickListener {
            val status = MagiskModuleHelper.checkStatus()
            if (status == MagiskModuleHelper.Status.INSTALLED) {
                showModuleManageDialog()
            } else {
                showModuleInstallDialog()
            }
        }

        // Shizuku 卡片点击 — 根据状态执行不同操作
        binding.cardShizukuStatus.setOnClickListener {
            when (ShizukuUtil.status) {
                ShizukuUtil.Status.NOT_INSTALLED -> {
                    // 跳转到 Shizuku 下载页
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                    } catch (_: Exception) {
                        ContextAction.toast(getString(R.string.home_search_install_shizuku))
                    }
                }
                ShizukuUtil.Status.INSTALLED_NOT_RUNNING -> {
                    // 尝试打开 Shizuku 应用
                    try {
                        val intent = requireContext().packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (intent != null) {
                            startActivity(intent)
                        } else {
                            ContextAction.toast(getString(R.string.home_cannot_open_shizuku_manual))
                        }
                    } catch (_: Exception) {
                        ContextAction.toast(getString(R.string.home_cannot_open_shizuku_manual))
                    }
                }
                ShizukuUtil.Status.RUNNING_NO_PERMISSION -> {
                    // 请求权限
                    val alreadyGranted = ShizukuUtil.requestPermission()
                    if (alreadyGranted) {
                        ContextAction.toast(getString(R.string.home_already_has_shizuku_perm))
                    }
                }
                ShizukuUtil.Status.READY -> {
                    // 已就绪，启动引擎
                    ContextAction.toast(getString(R.string.home_starting_with_shizuku_ellipsis))
                    ShizukuUtil.startEngines { success, msg ->
                        activity?.runOnUiThread {
                            ContextAction.toast(if (success) getString(R.string.home_engine_start_success) else getString(R.string.home_start_failed_msg, msg))
                        }
                    }
                }
                ShizukuUtil.Status.ENGINE_FAILED -> {
                    // 重试
                    ContextAction.toast(getString(R.string.home_retrying))
                    ShizukuUtil.startEngines { success, msg ->
                        activity?.runOnUiThread {
                            ContextAction.toast(if (success) getString(R.string.home_engine_start_success) else getString(R.string.home_start_failed_msg, msg))
                        }
                    }
                }
                else -> { /* STARTING_ENGINE / ENGINE_STARTED — 无操作 */ }
            }
        }
        updateUnifiedFloatingStatus()
        binding.cardEngineStatusText.setOnClickListener {
            thread {
                try {
                    val msg = ExtSystem.shell("ps -ef | grep yyds").trim()
                    val time = try {
                        ExportHandle.getHandler().http("/start_time", emptyMap()).take(100)
                    } catch (e: Exception) { getString(R.string.home_net_fetch_failed) }
                    uiSafe {
                        MaterialAlertDialogBuilder(requireActivity(), R.style.MyDialog)
                            .setIcon(R.drawable.ic_tip)
                            .setTitle(getString(R.string.home_engine_process_info_title))
                            .setMessage(getString(R.string.home_engine_process_info_message, msg, time))
                            .setNegativeButton(getString(R.string.home_understood)) { dialog, _ -> dialog.cancel() }
                            .show()
                    }
                } catch (e: Exception) {
                    ExtSystem.printDebugError("获取引擎进程信息失败", e)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        fragmentHomeBinding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    private fun updateUnifiedFloatingStatus() {
        if (!isAdded || fragmentHomeBinding == null) return
        val running = UnifiedFloatingService.isRunning()
        val hasOverlayPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(requireContext()) else true
        binding.cardFloatingWindow.isChecked = running
        binding.cardFloatingWindowText.text = when {
            running && hasOverlayPerm -> getString(R.string.home_float_on_click_close)
            !hasOverlayPerm -> getString(R.string.home_float_not_on_need_perm)
            else -> getString(R.string.home_float_click_to_open)
        }
        binding.cardFloatingWindowText.setTextColor(
            if (running && hasOverlayPerm) ResourcesCompat.getColor(App.app.resources, R.color.ok, null)
            else ResourcesCompat.getColor(App.app.resources, R.color.primary_lay, null)
        )
    }

    private fun toggleUnifiedFloating() {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            MaterialAlertDialogBuilder(requireActivity(), R.style.MyDialog)
                .setIcon(R.drawable.ic_floating_window)
                .setTitle(getString(R.string.home_open_float_window_title))
                .setMessage(getString(R.string.home_open_float_window_message))
                .setCancelable(true)
                .setPositiveButton(getString(R.string.home_go_settings)) { dialog, _ ->
                    dialog.dismiss()
                    try {
                        startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${ctx.packageName}")
                        ))
                    } catch (e: Exception) {
                        ContextAction.toast(getString(R.string.home_cannot_open_settings))
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel)) { dialog, _ -> dialog.cancel() }
                .show()
            return
        }
        val wasRunning = UnifiedFloatingService.isRunning()
        if (wasRunning) {
            UnifiedFloatingService.stop(ctx)
            ContextAction.toast(getString(R.string.home_float_window_closed))
        } else {
            UnifiedFloatingService.start(ctx, UnifiedFloatingService.DisplayMode.BUBBLE)
            ContextAction.toast(getString(R.string.home_float_window_opened))
        }
        // 直接根据操作结果更新 UI（服务 start/stop 异步，isRunning 可能尚未变化）
        val nowRunning = !wasRunning
        val hasOverlayPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(ctx) else true
        binding.cardFloatingWindow.isChecked = nowRunning
        binding.cardFloatingWindowText.text = when {
            nowRunning && hasOverlayPerm -> getString(R.string.home_float_on_click_close)
            !hasOverlayPerm -> getString(R.string.home_float_not_on_need_perm)
            else -> getString(R.string.home_float_click_to_open)
        }
        binding.cardFloatingWindowText.setTextColor(
            if (nowRunning && hasOverlayPerm) ResourcesCompat.getColor(App.app.resources, R.color.ok, null)
            else ResourcesCompat.getColor(App.app.resources, R.color.primary_lay, null)
        )
    }

    override fun onResume() {
        super.onResume()
        // 页面可见时启动自动刷新
        autoRefreshActive = true
        // 重置权限提示标记，回到首页后若问题仍存在可再次提醒
        hasShownPermissionAlert = false
        handler.postDelayed(autoRefreshRunnable, FIRST_REFRESH_DELAY)
        updateUnifiedFloatingStatus()
    }

    override fun onPause() {
        super.onPause()
        // 页面不可见时停止自动刷新，节省资源
        autoRefreshActive = false
        handler.removeCallbacks(autoRefreshRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        autoRefreshActive = false
        handler.removeCallbacks(autoRefreshRunnable)
        fragmentHomeBinding = null
    }
}
