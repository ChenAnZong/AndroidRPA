package com.tencent.yyds

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import pyengine.ApkPackageHelper
import pyengine.EngineClient
import uiautomator.ExtSystem
import kotlin.concurrent.thread

/**
 * Runner模式Activity — 打包后的APK配置与控制中心
 *
 * 产品设计：
 * - 默认进入配置界面，用户可查看/调整运行选项
 * - 提供getString(R.string.runner_auto_run_title)等开关，所有设置持久化
 * - 手动启动/停止按钮始终可用
 * - 可折叠的日志面板
 */
class RunnerActivity : AppCompatActivity() {

    // UI 组件
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var runTimeText: TextView
    private lateinit var btnAction: MaterialButton
    private lateinit var logContainer: LinearLayout
    private lateinit var logText: TextView
    private lateinit var logToggleBtn: TextView

    // 开关
    private lateinit var swAutoRun: MaterialSwitch
    private lateinit var swKeepScreen: MaterialSwitch
    private lateinit var swShowLog: MaterialSwitch
    private lateinit var swExitOnStop: MaterialSwitch

    // 状态
    private var packConfig: ApkPackageHelper.PackConfig? = null
    private var isRunning = false
    private var engineReady = false
    private var runStartTime = 0L
    private lateinit var prefs: SharedPreferences

    // 主题色
    companion object {
        private const val BG = "#FBF7F1"
        private const val CARD_BG = "#FFFFFF"
        private const val ACCENT = "#264B6F"
        private const val TEXT1 = "#1A1A1A"
        private const val TEXT2 = "#5A6A7A"
        private const val TEXT_HINT = "#8A95A0"
        private const val DIVIDER = "#E8EAED"
        private const val SUCCESS = "#2E7D32"
        private const val RUNNING_GREEN = "#4CAF50"
        private const val STOPPED_GRAY = "#BDBDBD"
        private const val ERROR_RED = "#D32F2F"
        private const val LOG_BG = "#F5F5F5"

        // SharedPreferences keys
        private const val PREF_NAME = "runner_settings"
        private const val KEY_AUTO_RUN = "auto_run_on_open"
        private const val KEY_KEEP_SCREEN = "keep_screen_on"
        private const val KEY_SHOW_LOG = "show_log"
        private const val KEY_EXIT_ON_STOP = "exit_on_script_stop"
        private const val KEY_FIRST_LAUNCH = "first_launch"
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        packConfig = ApkPackageHelper.readPackConfig(this)
        if (packConfig == null) { finish(); return }

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        if (isFirstLaunch) {
            // 首次启动：用 pack_config.json 中的预设值初始化 SharedPreferences
            prefs.edit()
                .putBoolean(KEY_AUTO_RUN, packConfig!!.shouldAutoRun())
                .putBoolean(KEY_KEEP_SCREEN, packConfig!!.keepScreenOn)
                .putBoolean(KEY_SHOW_LOG, packConfig!!.showLog)
                .putBoolean(KEY_EXIT_ON_STOP, packConfig!!.exitOnScriptStop)
                .putBoolean(KEY_FIRST_LAUNCH, false)
                .apply()
        }

        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }
        buildUI(dp)

        // 应用屏幕常亮设置
        applyKeepScreenOn()

        // 初始化引擎（后台）
        thread { initRunner() }
    }

    // ==========================================================================
    // UI 构建
    // ==========================================================================

    @SuppressLint("SetTextI18n")
    private fun buildUI(dp: (Int) -> Int) {
        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor(BG))
            fitsSystemWindows = true
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        scrollView.addView(content)
        rootFrame.addView(scrollView)

        // ---- 头部：应用名 + 版本 ----
        content.addView(TextView(this).apply {
            text = packConfig!!.appName
            setTextColor(Color.parseColor(TEXT1))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        content.addView(TextView(this).apply {
            text = "v${packConfig!!.version}  ·  ${packConfig!!.projectName}"
            setTextColor(Color.parseColor(TEXT_HINT))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(20))
        })

        // ---- 状态卡片 ----
        val statusCard = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            radius = dp(12).toFloat()
            setCardBackgroundColor(Color.parseColor(CARD_BG))
            cardElevation = dp(2).toFloat()
        }
        val statusLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }

        // 状态指示灯
        statusIndicator = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(STOPPED_GRAY))
            }
        }
        statusLayout.addView(statusIndicator)

        // 状态文字列
        val statusCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusText = TextView(this).apply {
            text = getString(R.string.runner_initializing)
            setTextColor(Color.parseColor(TEXT1))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        statusCol.addView(statusText)

        runTimeText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor(TEXT2))
            textSize = 12f
            visibility = View.GONE
        }
        statusCol.addView(runTimeText)
        statusLayout.addView(statusCol)
        statusCard.addView(statusLayout)
        content.addView(statusCard)

        // ---- 控制按钮 ----
        btnAction = MaterialButton(this).apply {
            text = getString(R.string.runner_start)
            isEnabled = false
            setBackgroundColor(Color.parseColor(ACCENT))
            setTextColor(Color.WHITE)
            cornerRadius = dp(10)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply { bottomMargin = dp(20) }
        }
        btnAction.setOnClickListener { toggleProject() }
        content.addView(btnAction)

        // ---- 设置区块 ----
        content.addView(sectionLabel(getString(R.string.runner_settings_section), dp))

        val settingsCard = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
            radius = dp(12).toFloat()
            setCardBackgroundColor(Color.parseColor(CARD_BG))
            cardElevation = dp(1).toFloat()
        }
        val settingsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        swAutoRun = addSwitchRow(settingsLayout, getString(R.string.runner_auto_run_title),
            getString(R.string.runner_auto_run_desc), KEY_AUTO_RUN, dp)
        addDivider(settingsLayout, dp)

        swKeepScreen = addSwitchRow(settingsLayout, getString(R.string.runner_keep_screen_title),
            getString(R.string.runner_keep_screen_desc), KEY_KEEP_SCREEN, dp)
        addDivider(settingsLayout, dp)

        swShowLog = addSwitchRow(settingsLayout, getString(R.string.runner_show_log_title),
            getString(R.string.runner_show_log_desc), KEY_SHOW_LOG, dp)
        addDivider(settingsLayout, dp)

        swExitOnStop = addSwitchRow(settingsLayout, getString(R.string.runner_exit_on_stop_short),
            getString(R.string.runner_exit_on_stop_desc), KEY_EXIT_ON_STOP, dp)

        settingsCard.addView(settingsLayout)
        content.addView(settingsCard)

        // ---- 日志区块（可折叠） ----
        logToggleBtn = TextView(this).apply {
            text = getString(R.string.runner_log_arrow_expand)
            setTextColor(Color.parseColor(TEXT2))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(4), dp(4), dp(8))
            setOnClickListener { toggleLogPanel() }
        }
        content.addView(logToggleBtn)

        logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (prefs.getBoolean(KEY_SHOW_LOG, true)) View.VISIBLE else View.GONE
        }

        logText = TextView(this).apply {
            setTextColor(Color.parseColor("#333333"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(LOG_BG))
                cornerRadius = dp(8).toFloat()
            }
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            text = ""
            maxLines = 300
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        logContainer.addView(logText)

        // 清除日志按钮
        logContainer.addView(TextView(this).apply {
            text = getString(R.string.runner_clear_log)
            setTextColor(Color.parseColor(ACCENT))
            textSize = 12f
            gravity = Gravity.END
            setPadding(0, 0, dp(4), dp(8))
            setOnClickListener { logText.text = "" }
        })
        content.addView(logContainer)
        updateLogToggleLabel()

        // ---- 退出按钮 ----
        content.addView(MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.runner_exit_app)
            setTextColor(Color.parseColor(TEXT2))
            strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor(DIVIDER))
            cornerRadius = dp(10)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(8) }
            setOnClickListener { finish() }
        })

        setContentView(rootFrame)
    }

    // ==========================================================================
    // UI 辅助
    // ==========================================================================

    private fun sectionLabel(title: String, dp: (Int) -> Int): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.parseColor(TEXT2))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.03f
            setPadding(dp(4), 0, 0, dp(8))
        }
    }

    private fun addSwitchRow(
        parent: LinearLayout, title: String, subtitle: String,
        prefKey: String, dp: (Int) -> Int
    ): MaterialSwitch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(14))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(Color.parseColor(TEXT1))
            textSize = 14f
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor(TEXT_HINT))
            textSize = 11.5f
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textCol)

        val sw = MaterialSwitch(this).apply {
            isChecked = prefs.getBoolean(prefKey, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(prefKey, checked).apply()
                onSettingChanged(prefKey, checked)
            }
        }
        row.addView(sw)
        parent.addView(row)
        return sw
    }

    private fun addDivider(parent: LinearLayout, dp: (Int) -> Int) {
        parent.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            ).apply { marginStart = dp(16); marginEnd = dp(16) }
            setBackgroundColor(Color.parseColor(DIVIDER))
        })
    }

    private fun onSettingChanged(key: String, value: Boolean) {
        when (key) {
            KEY_KEEP_SCREEN -> applyKeepScreenOn()
            KEY_SHOW_LOG -> {
                logContainer.visibility = if (value) View.VISIBLE else View.GONE
                updateLogToggleLabel()
            }
        }
    }

    private fun applyKeepScreenOn() {
        if (prefs.getBoolean(KEY_KEEP_SCREEN, true)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun toggleLogPanel() {
        val visible = logContainer.visibility == View.VISIBLE
        logContainer.visibility = if (visible) View.GONE else View.VISIBLE
        prefs.edit().putBoolean(KEY_SHOW_LOG, !visible).apply()
        swShowLog.isChecked = !visible
        updateLogToggleLabel()
    }

    private fun updateLogToggleLabel() {
        val open = logContainer.visibility == View.VISIBLE
        logToggleBtn.text = if (open) getString(R.string.runner_log_arrow_collapse) else getString(R.string.runner_log_arrow_expand)
    }

    // ==========================================================================
    // 引擎 & 脚本控制
    // ==========================================================================

    private fun initRunner() {
        updateStatus(getString(R.string.runner_starting_engine), STOPPED_GRAY)

        val engineOk = EngineClient.ensureEngineRunning()
        if (!engineOk) {
            updateStatus(getString(R.string.runner_engine_fail_root), ERROR_RED)
            return
        }

        updateStatus(getString(R.string.runner_preparing_script), STOPPED_GRAY)
        val projectName = packConfig!!.projectName
        val extracted = EngineClient.extractBundledProjectViaEngine(projectName)
        if (!extracted) {
            updateStatus(getString(R.string.runner_extract_failed), ERROR_RED)
            return
        }

        engineReady = true
        updateStatus(getString(R.string.runner_ready), STOPPED_GRAY)
        runOnUiThread { btnAction.isEnabled = true }

        // 根据用户设置决定是否自动运行
        if (prefs.getBoolean(KEY_AUTO_RUN, false)) {
            SystemClock.sleep(500)
            startProject()
        }

        startLogStream()
    }

    private fun startProject() {
        if (!engineReady) return
        val projectName = packConfig?.projectName ?: return
        isRunning = true
        runStartTime = System.currentTimeMillis()
        updateStatus(getString(R.string.runner_running_name, projectName), RUNNING_GREEN)
        runOnUiThread {
            btnAction.text = getString(R.string.runner_stop)
            btnAction.setBackgroundColor(Color.parseColor(ERROR_RED))
            runTimeText.visibility = View.VISIBLE
        }
        startRunTimer()
        try {
            EngineClient.sendStartProject(projectName)
        } catch (e: Exception) {
            ExtSystem.printDebugError("Runner启动项目失败", e)
            updateStatus(getString(R.string.runner_start_failed, e.message ?: ""), ERROR_RED)
            isRunning = false
        }
    }

    private fun stopProject() {
        isRunning = false
        updateStatus(getString(R.string.runner_stopping), STOPPED_GRAY)
        runOnUiThread {
            btnAction.text = getString(R.string.runner_start)
            btnAction.setBackgroundColor(Color.parseColor(ACCENT))
            runTimeText.visibility = View.GONE
        }
        try {
            EngineClient.sendStopProject()
            updateStatus(getString(R.string.runner_stopped), STOPPED_GRAY)
        } catch (e: Exception) {
            ExtSystem.printDebugError("Runner停止项目失败", e)
        }
        // 脚本停止后自动退出
        if (prefs.getBoolean(KEY_EXIT_ON_STOP, false)) {
            runOnUiThread {
                Toast.makeText(this, getString(R.string.runner_stopped_exiting), Toast.LENGTH_SHORT).show()
            }
            SystemClock.sleep(1500)
            runOnUiThread { finish() }
        }
    }

    private fun toggleProject() {
        if (isRunning) stopProject() else {
            thread { startProject() }
        }
    }

    private fun updateStatus(text: String, indicatorColor: String) {
        runOnUiThread {
            statusText.text = text
            (statusIndicator.background as? GradientDrawable)?.setColor(Color.parseColor(indicatorColor))
        }
    }

    // ==========================================================================
    // 运行计时
    // ==========================================================================

    private fun startRunTimer() {
        thread {
            while (isRunning && !isFinishing) {
                val elapsed = (System.currentTimeMillis() - runStartTime) / 1000
                val h = elapsed / 3600
                val m = (elapsed % 3600) / 60
                val s = elapsed % 60
                val timeStr = if (h > 0) String.format(getString(R.string.runner_elapsed_hms), h, m, s)
                else String.format(getString(R.string.runner_elapsed_ms), m, s)
                runOnUiThread { runTimeText.text = timeStr }
                SystemClock.sleep(1000)
            }
        }
    }

    // ==========================================================================
    // 日志
    // ==========================================================================

    private fun appendLog(text: String) {
        runOnUiThread {
            logText.append(text)
            if (logText.text.length > 20000) {
                logText.text = logText.text.subSequence(logText.text.length - 15000, logText.text.length)
            }
        }
    }

    private fun startLogStream() {
        thread {
            try {
                EngineClient.ensureLogConnect()
                while (!isFinishing) {
                    if (EngineClient.logHasnext()) {
                        val log = EngineClient.nextLog()
                        if (log != null) appendLog(log)
                    } else {
                        SystemClock.sleep(500)
                    }
                }
            } catch (e: Exception) {
                ExtSystem.printDebugError("Runner日志流错误", e)
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        EngineClient.closeLogConnect()
        super.onDestroy()
    }
}
