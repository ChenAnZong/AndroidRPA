package com.tencent.yyds

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.caz.xp.ui.ContextAction
import pyengine.EngineClient
import pyengine.PyEngine
import pyengine.YyProject
import uiautomator.ExtSystem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 统一悬浮窗服务 — 控制面板 + 日志控制台 合二为一
 *
 * 四种显示模式（可无缝切换）：
 *   BUBBLE     — 吸边小气泡，空闲半透明；点击→面板，长按→条形
 *   BAR        — 屏幕边缘半透明状态条；点击→面板
 *   PANEL      — 上半控制区 + 下半日志区；分隔线可拖动调比例，右下角可调整窗口大小
 *   FULLSCREEN — 纯日志全屏
 */
class UnifiedFloatingService : Service() {

    // ================================================================
    // 模式定义
    // ================================================================

    enum class DisplayMode { BUBBLE, BAR, PANEL, FULLSCREEN }

    // ================================================================
    // 成员变量
    // ================================================================

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var rootParams: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    private var currentMode = DisplayMode.BUBBLE

    // 窗口位置/尺寸（面板模式）
    private var panelX = 20
    private var panelY = 200
    private var panelW = 0
    private var panelH = 0

    // 当前 Tab
    enum class Tab { SCRIPTS, LOG, AGENT }
    private var currentTab = Tab.SCRIPTS

    // 气泡吸边状态
    private var bubbleSnappedRight = false

    // 项目/引擎状态
    private var projects: List<YyProject> = emptyList()
    private var runningProjectName: String? = null
    private var engineRunning = false
    private var isRefreshing = false
    @Volatile private var isStatusRefreshing = false

    // Agent 状态
    private var agentRunning = false
    private var agentStep = 0
    private var agentMaxSteps = 30
    private var agentAction = ""
    private var agentInstruction = ""
    private var agentTakeover = false

    // 脚本日志（Tab 2）
    private val allLogs = ArrayDeque<LogItem>()
    private val filteredLogs = mutableListOf<LogItem>()
    private var logAdapter: LogAdapter? = null
    private var autoScroll = true
    private var filterLevel = LogLevel.VERBOSE
    private var logUnread = false  // 不在日志 Tab 时是否有新日志

    // Agent 步骤日志（Tab 3）
    private val agentLogs = ArrayDeque<LogItem>()
    private var agentLogAdapter: LogAdapter? = null
    private var agentLogAutoScroll = true

    private var fontSize = 11f
    private var currentAlpha = 0.92f
    private var lastScrollTime = 0L
    private var lastAgentScrollTime = 0L
    private var isSettingsShowing = false
    private var lastBarLog = ""

    // 屏幕尺寸
    private var screenW = 0
    private var screenH = 0

    // 日志监听
    private val logListener: (String) -> Unit = { msg -> handleLogMessage(msg) }

    // ================================================================
    // companion
    // ================================================================

    companion object {
        private const val PREF_NAME = "unified_floating_prefs"
        private const val PREF_MODE = "mode"
        private const val PREF_ALPHA = "alpha"
        private const val PREF_FONTSIZE = "fontsize"
        private const val PREF_PANEL_X = "panel_x"
        private const val PREF_PANEL_Y = "panel_y"
        private const val PREF_PANEL_W = "panel_w"
        private const val PREF_PANEL_H = "panel_h"
        private const val PREF_BUBBLE_RIGHT = "bubble_right"
        private const val MAX_LOGS = 5000
        private const val STATUS_INTERVAL = 4000L

        @Volatile private var instance: UnifiedFloatingService? = null

        fun isRunning(): Boolean = instance != null

        fun start(context: Context, mode: DisplayMode = DisplayMode.BUBBLE) {
            val intent = Intent(context, UnifiedFloatingService::class.java)
                .putExtra("mode", mode.name)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UnifiedFloatingService::class.java))
        }

        fun appendLog(text: String, level: LogLevel = LogLevel.INFO) {
            instance?.handler?.post { instance?.addLogItem(LogItem(text, level)) }
        }

        fun appendAgentLog(text: String, level: LogLevel = LogLevel.INFO) {
            instance?.handler?.post { instance?.addAgentLogItem(LogItem(text, level)) }
        }

        fun clearLogs() {
            instance?.handler?.post { instance?.clearAllLogs() }
        }

        fun switchToTab(tab: Tab) {
            instance?.handler?.post { instance?.selectTab(tab) }
        }

        fun setTitle(title: String) {
            instance?.handler?.post {
                instance?.rootView?.findViewById<TextView>(R.id.panel_title)?.text = title
            }
        }

        fun setAlpha(alpha: Float) {
            instance?.handler?.post { instance?.applyAlpha(alpha) }
        }
    }

    // ================================================================
    // 日志类型
    // ================================================================

    enum class LogLevel(val tag: String, val color: Int) {
        VERBOSE("V", 0xFF888888.toInt()),
        DEBUG("D", 0xFF64B5F6.toInt()),
        INFO("I", 0xFF81C784.toInt()),
        WARN("W", 0xFFFFD54F.toInt()),
        ERROR("E", 0xFFE57373.toInt())
    }

    data class LogItem(
        val text: String,
        val level: LogLevel,
        val timestamp: Long = System.currentTimeMillis()
    )

    // ================================================================
    // 生命周期
    // ================================================================

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        panelW = (screenW * 0.88f).toInt()
        panelH = (screenH * 0.62f).toInt()

        loadPrefs()
        buildRootView()
        showCurrentMode(animate = false)

        EngineClient.addLogListener(logListener)
        EngineClient.ensureLogConnect()
        handler.postDelayed(statusRunnable, STATUS_INTERVAL)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("mode")?.let { modeName ->
            try {
                val m = DisplayMode.valueOf(modeName)
                if (m != currentMode) switchMode(m)
            } catch (_: Exception) {}
        }
        intent?.let { handleControlIntent(it) }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(statusRunnable)
        handler.removeCallbacks(idleFadeRunnable)
        EngineClient.removeLogListener(logListener)
        savePrefs()
        removeRootView()
        instance = null
        super.onDestroy()
    }

    // ================================================================
    // Intent 控制命令 (来自 console.py / BootService 等)
    // ================================================================

    private fun handleControlIntent(intent: Intent) {
        when (intent.getStringExtra("action")) {
            "show"       -> handler.post { if (currentMode == DisplayMode.BUBBLE) switchMode(DisplayMode.PANEL) }
            "hide"       -> handler.post { if (currentMode == DisplayMode.PANEL) switchMode(DisplayMode.BUBBLE) }
            "bar"        -> handler.post { switchMode(DisplayMode.BAR) }
            "fullscreen" -> handler.post { switchMode(DisplayMode.FULLSCREEN) }
            "clear"      -> handler.post { clearAllLogs() }
            "log"        -> {
                val text = intent.getStringExtra("text") ?: return
                val level = try {
                    LogLevel.valueOf(intent.getStringExtra("level") ?: "INFO")
                } catch (_: Exception) { LogLevel.INFO }
                handler.post { addLogItem(LogItem(text, level)) }
            }
        }
    }

    // ================================================================
    // 构建视图
    // ================================================================

    private fun buildRootView() {
        rootView = LayoutInflater.from(this).inflate(R.layout.floating_unified, null)

        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        rootParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            flag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = panelX
            y = panelY
        }

        setupBubble()
        setupBar()
        setupPanel()
        setupLogRecycler()
        setupSettings()

        try {
            windowManager.addView(rootView, rootParams)
        } catch (e: Exception) {
            ExtSystem.printDebugError("UnifiedFloating addView失败", e)
        }
    }

    private fun removeRootView() {
        rootView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        rootView = null
    }

    // ================================================================
    // 模式切换
    // ================================================================

    fun switchMode(mode: DisplayMode) {
        val prev = currentMode
        currentMode = mode
        handler.removeCallbacks(idleFadeRunnable)

        val bubble = rootView?.findViewById<View>(R.id.bubble_root)
        val bar    = rootView?.findViewById<View>(R.id.bar_root)
        val panel  = rootView?.findViewById<View>(R.id.panel_root)

        bubble?.visibility = View.GONE
        bar?.visibility    = View.GONE
        panel?.visibility  = View.GONE

        when (mode) {
            DisplayMode.BUBBLE -> {
                showCurrentMode(animate = prev != DisplayMode.BUBBLE)
            }
            DisplayMode.BAR -> {
                showCurrentMode(animate = true)
            }
            DisplayMode.PANEL -> {
                showCurrentMode(animate = true)
                refreshProjectList()
            }
            DisplayMode.FULLSCREEN -> {
                showCurrentMode(animate = true)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCurrentMode(animate: Boolean) {
        val v = rootView ?: return
        val params = rootParams ?: return

        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        when (currentMode) {
            DisplayMode.BUBBLE -> {
                v.findViewById<View>(R.id.bubble_root)?.visibility = View.VISIBLE
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                params.x = if (bubbleSnappedRight) screenW - dpToPx(34) else -dpToPx(10)
                params.y = panelY
                try { windowManager.updateViewLayout(v, params) } catch (_: Exception) {}
                if (animate) {
                    v.scaleX = 0.6f; v.scaleY = 0.6f; v.alpha = 0f
                    v.animate().scaleX(1f).scaleY(1f).alpha(0.92f)
                        .setDuration(260).setInterpolator(OvershootInterpolator()).start()
                } else {
                    v.alpha = 0.92f
                }
                scheduleIdleFade()
            }

            DisplayMode.BAR -> {
                v.findViewById<View>(R.id.bar_root)?.visibility = View.VISIBLE
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                params.gravity = Gravity.BOTTOM or Gravity.START
                params.x = 0
                params.y = 0
                try { windowManager.updateViewLayout(v, params) } catch (_: Exception) {}
                v.alpha = 0.88f
                if (animate) {
                    v.translationY = 80f
                    v.animate().translationY(0f).setDuration(220)
                        .setInterpolator(AccelerateDecelerateInterpolator()).start()
                }
            }

            DisplayMode.PANEL -> {
                v.findViewById<View>(R.id.panel_root)?.visibility = View.VISIBLE
                params.width = panelW
                params.height = panelH
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                params.gravity = Gravity.START or Gravity.TOP
                params.x = panelX
                params.y = panelY
                try { windowManager.updateViewLayout(v, params) } catch (_: Exception) {}
                applyAlpha(currentAlpha)
                if (animate) {
                    v.scaleX = 0.92f; v.scaleY = 0.92f; v.alpha = 0f
                    v.animate().scaleX(1f).scaleY(1f).alpha(currentAlpha)
                        .setDuration(230).setInterpolator(AccelerateDecelerateInterpolator()).start()
                }
                if (autoScroll) scrollToBottom()
            }

            DisplayMode.FULLSCREEN -> {
                v.findViewById<View>(R.id.panel_root)?.visibility = View.VISIBLE
                // 全屏模式：切换到日志 Tab
                selectTab(Tab.LOG, animate = false)
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                params.gravity = Gravity.TOP or Gravity.START
                params.x = 0; params.y = 0
                try { windowManager.updateViewLayout(v, params) } catch (_: Exception) {}
                applyAlpha(currentAlpha)
                if (autoScroll) scrollToBottom()
            }
        }
    }

    // ================================================================
    // 气泡模式
    // ================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubble() {
        val bubble = rootView?.findViewById<View>(R.id.bubble_root) ?: return
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f
        var isDragging = false; var touchDownTime = 0L

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(idleFadeRunnable)
                    rootView?.animate()?.alpha(0.92f)?.setDuration(150)?.start()
                    initialX = rootParams!!.x; initialY = rootParams!!.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    isDragging = false; touchDownTime = SystemClock.uptimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX; val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) isDragging = true
                    if (isDragging) {
                        rootParams!!.x = initialX + dx.toInt()
                        rootParams!!.y = initialY + dy.toInt()
                        try { windowManager.updateViewLayout(rootView, rootParams) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = SystemClock.uptimeMillis() - touchDownTime
                    when {
                        !isDragging && elapsed < 200  -> switchMode(DisplayMode.PANEL)
                        !isDragging && elapsed >= 500 -> switchMode(DisplayMode.BAR)
                        else -> snapBubbleToEdge()
                    }
                    scheduleIdleFade()
                    true
                }
                else -> false
            }
        }
    }

    private val idleFadeRunnable = Runnable {
        if (currentMode == DisplayMode.BUBBLE)
            rootView?.animate()?.alpha(0.38f)?.setDuration(800)?.start()
    }

    private fun scheduleIdleFade() {
        handler.removeCallbacks(idleFadeRunnable)
        handler.postDelayed(idleFadeRunnable, 4000)
    }

    private fun snapBubbleToEdge() {
        val v = rootView ?: return
        val bubbleW = dpToPx(48)
        val cx = rootParams!!.x + bubbleW / 2
        val snapRight = cx > screenW / 2
        bubbleSnappedRight = snapRight
        val hideAmount = (bubbleW * 0.28f).toInt()
        val targetX = if (snapRight) screenW - bubbleW + hideAmount else -hideAmount

        // 翻转气泡背景
        val icon = v.findViewById<ImageView>(R.id.bubble_icon)
        icon?.setBackgroundResource(
            if (snapRight) R.drawable.floating_trigger_bg_right else R.drawable.floating_trigger_bg_left
        )

        val anim = ValueAnimator.ofInt(rootParams!!.x, targetX)
        anim.duration = 260; anim.interpolator = OvershootInterpolator(1.1f)
        anim.addUpdateListener {
            rootParams?.x = it.animatedValue as Int
            try { windowManager.updateViewLayout(rootView, rootParams) } catch (_: Exception) {}
        }
        anim.start()
        panelY = rootParams!!.y  // 记录Y位置供面板模式使用
    }

    // ================================================================
    // 条形模式
    // ================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBar() {
        val bar = rootView?.findViewById<View>(R.id.bar_root) ?: return
        bar.setOnClickListener { switchMode(DisplayMode.PANEL) }
        rootView?.findViewById<View>(R.id.bar_btn_expand)?.setOnClickListener {
            switchMode(DisplayMode.PANEL)
        }
        rootView?.findViewById<View>(R.id.bar_btn_close)?.setOnClickListener {
            switchMode(DisplayMode.BUBBLE)
        }
    }

    private fun updateBarStatus() {
        val v = rootView ?: return
        val dot = v.findViewById<View>(R.id.bar_status_dot) ?: return
        val logTv = v.findViewById<TextView>(R.id.bar_log_text) ?: return
        val stepTv = v.findViewById<TextView>(R.id.bar_step_text) ?: return

        dot.setBackgroundResource(if (engineRunning) R.drawable.floating_btn_start else R.drawable.floating_btn_stop)
        logTv.text = lastBarLog.take(80)

        if (agentRunning) {
            stepTv.visibility = View.VISIBLE
            stepTv.text = "${agentStep}/${agentMaxSteps}"
        } else {
            stepTv.visibility = View.GONE
        }
    }

    // ================================================================
    // 面板模式 — 标题栏 & 拖拽
    // ================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPanel() {
        val v = rootView ?: return

        // 标题栏拖拽（单击切换到下一 Tab，双击最小化为气泡）
        var ix = 0; var iy = 0; var itx = 0f; var ity = 0f
        var lastTap = 0L
        v.findViewById<View>(R.id.panel_header)?.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = rootParams!!.x; iy = rootParams!!.y
                    itx = e.rawX; ity = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    rootParams!!.x = ix + (e.rawX - itx).toInt()
                    rootParams!!.y = iy + (e.rawY - ity).toInt()
                    panelX = rootParams!!.x; panelY = rootParams!!.y
                    try { windowManager.updateViewLayout(v, rootParams) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(e.rawX - itx); val dy = Math.abs(e.rawY - ity)
                    if (dx < 8 && dy < 8) {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 350) switchMode(DisplayMode.BUBBLE)  // 双击→气泡
                        lastTap = now
                    }
                    true
                }
                else -> false
            }
        }

        // 头部按钮
        v.findViewById<View>(R.id.panel_btn_close)?.setOnClickListener { switchMode(DisplayMode.BUBBLE) }
        v.findViewById<View>(R.id.panel_btn_minimize)?.setOnClickListener { switchMode(DisplayMode.BUBBLE) }
        v.findViewById<View>(R.id.panel_btn_barmode)?.setOnClickListener { switchMode(DisplayMode.BAR) }
        v.findViewById<View>(R.id.panel_btn_fullscreen)?.setOnClickListener {
            switchMode(if (currentMode == DisplayMode.FULLSCREEN) DisplayMode.PANEL else DisplayMode.FULLSCREEN)
        }
        v.findViewById<View>(R.id.panel_btn_settings)?.setOnClickListener { toggleSettings() }

        // 脚本工程页按钮
        v.findViewById<View>(R.id.ctrl_btn_refresh)?.setOnClickListener { refreshProjectList() }
        v.findViewById<View>(R.id.ctrl_btn_inspect)?.setOnClickListener { launchInspector() }

        // 右下角调整窗口大小
        setupResizeHandle()

        // 日志页工具栏
        v.findViewById<View>(R.id.log_btn_clear)?.setOnClickListener { clearAllLogs() }
        v.findViewById<View>(R.id.log_btn_copy)?.setOnClickListener { copyAllLogs() }
        val autoScrollBtn = v.findViewById<ImageView>(R.id.log_btn_autoscroll)
        updateAutoScrollIcon()
        autoScrollBtn?.setOnClickListener {
            autoScroll = !autoScroll
            updateAutoScrollIcon()
            if (autoScroll) scrollToBottom()
        }
        setupFilterChips()

        // Agent 页工具栏
        setupAgentPage()

        // 底部 Tab 栏
        setupTabBar()
    }

    private fun setupTabBar() {
        val v = rootView ?: return
        v.findViewById<View>(R.id.tab_scripts)?.setOnClickListener { selectTab(Tab.SCRIPTS) }
        v.findViewById<View>(R.id.tab_log)?.setOnClickListener { selectTab(Tab.LOG) }
        v.findViewById<View>(R.id.tab_agent)?.setOnClickListener { selectTab(Tab.AGENT) }
        selectTab(currentTab, animate = false)
    }

    @SuppressLint("SetTextI18n")
    fun selectTab(tab: Tab, animate: Boolean = true) {
        currentTab = tab
        val v = rootView ?: return

        // 切换页面内容
        val pageScripts = v.findViewById<View>(R.id.page_scripts)
        val pageLog     = v.findViewById<View>(R.id.page_log)
        val pageAgent   = v.findViewById<View>(R.id.page_agent)

        if (animate) {
            val target = when (tab) { Tab.SCRIPTS -> pageScripts; Tab.LOG -> pageLog; Tab.AGENT -> pageAgent }
            val current = listOf(pageScripts, pageLog, pageAgent).firstOrNull { it?.visibility == View.VISIBLE }
            if (current != target) {
                current?.animate()?.alpha(0f)?.setDuration(120)?.withEndAction {
                    current.visibility = View.GONE
                    target?.alpha = 0f; target?.visibility = View.VISIBLE
                    target?.animate()?.alpha(1f)?.setDuration(150)?.start()
                }?.start()
            }
        } else {
            pageScripts?.visibility = if (tab == Tab.SCRIPTS) View.VISIBLE else View.GONE
            pageLog?.visibility     = if (tab == Tab.LOG)     View.VISIBLE else View.GONE
            pageAgent?.visibility   = if (tab == Tab.AGENT)   View.VISIBLE else View.GONE
        }

        // 更新 Tab 选中样式
        val COLOR_ACTIVE   = 0xFF4FC3F7.toInt()  // 亮蓝
        val COLOR_INACTIVE = 0xFF446677.toInt()  // 暗灰
        val ALPHA_ACTIVE   = 1.0f
        val ALPHA_INACTIVE = 0.45f

        fun styleTab(iconId: Int, textId: Int, active: Boolean) {
            v.findViewById<ImageView>(iconId)?.alpha = if (active) ALPHA_ACTIVE else ALPHA_INACTIVE
            v.findViewById<TextView>(textId)?.setTextColor(if (active) COLOR_ACTIVE else COLOR_INACTIVE)
        }
        styleTab(R.id.tab_icon_scripts, R.id.tab_text_scripts, tab == Tab.SCRIPTS)
        styleTab(R.id.tab_icon_log,     R.id.tab_text_log,     tab == Tab.LOG)
        styleTab(R.id.tab_icon_agent,   R.id.tab_text_agent,   tab == Tab.AGENT)

        // 清除日志未读红点
        if (tab == Tab.LOG) {
            logUnread = false
            v.findViewById<View>(R.id.tab_log_badge)?.visibility = View.GONE
        }

        // Agent Tab 切换时刷新内容
        if (tab == Tab.AGENT) {
            if (agentLogAutoScroll) scrollAgentToBottom()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResizeHandle() {
        val handle = rootView?.findViewById<View>(R.id.panel_resize_handle) ?: return
        var iw = 0; var ih = 0; var itx = 0f; var ity = 0f
        val minW = dpToPx(240); val minH = dpToPx(200)

        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    iw = rootParams!!.width; ih = rootParams!!.height
                    itx = e.rawX; ity = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newW = (iw + (e.rawX - itx).toInt()).coerceIn(minW, screenW)
                    val newH = (ih + (e.rawY - ity).toInt()).coerceIn(minH, screenH)
                    panelW = newW; panelH = newH
                    rootParams!!.width = newW; rootParams!!.height = newH
                    try { windowManager.updateViewLayout(rootView, rootParams) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleSettings() {
        isSettingsShowing = !isSettingsShowing
        rootView?.findViewById<View>(R.id.panel_settings)?.visibility =
            if (isSettingsShowing) View.VISIBLE else View.GONE
    }

    // ================================================================
    // Agent 页面
    // ================================================================

    private fun setupAgentPage() {
        val v = rootView ?: return

        // Agent 日志 RecyclerView
        val agentRecycler = v.findViewById<RecyclerView>(R.id.agent_log_recycler)
        if (agentRecycler != null) {
            agentLogAdapter = LogAdapter(agentLogs)
            agentRecycler.layoutManager = LinearLayoutManager(this)
            agentRecycler.adapter = agentLogAdapter
            agentRecycler.itemAnimator = null
            agentRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                    if (state == RecyclerView.SCROLL_STATE_DRAGGING) agentLogAutoScroll = false
                }
            })
        }

        // Agent 日志工具栏
        v.findViewById<View>(R.id.agent_log_btn_clear)?.setOnClickListener {
            agentLogs.clear(); agentLogAdapter?.notifyDataSetChanged()
        }
        val agentAutoBtn = v.findViewById<ImageView>(R.id.agent_log_btn_autoscroll)
        agentAutoBtn?.alpha = 1.0f
        agentAutoBtn?.setOnClickListener {
            agentLogAutoScroll = !agentLogAutoScroll
            agentAutoBtn.alpha = if (agentLogAutoScroll) 1.0f else 0.35f
            if (agentLogAutoScroll) scrollAgentToBottom()
        }

        // Agent 操作按钮
        v.findViewById<TextView>(R.id.agent_btn_takeover)?.setOnClickListener {
            thread {
                val url = if (agentTakeover) "/agent/resume" else "/agent/takeover"
                EngineClient.instance?.httpPostJsonPublic(url, "{}")
            }
        }
        v.findViewById<TextView>(R.id.agent_btn_stop)?.setOnClickListener {
            thread {
                EngineClient.instance?.httpGetPublic("/agent/stop")
                handler.post { agentRunning = false; updateAgentTab() }
            }
        }

        // 初始状态
        updateAgentTab()
    }

    @SuppressLint("SetTextI18n")
    private fun updateAgentTab() {
        val v = rootView ?: return
        val dot      = v.findViewById<View>(R.id.agent_dot)
        val label    = v.findViewById<TextView>(R.id.agent_status_label)
        val taskTv   = v.findViewById<TextView>(R.id.agent_task_text)
        val progRow  = v.findViewById<View>(R.id.agent_progress_row)
        val progBar  = v.findViewById<android.widget.ProgressBar>(R.id.agent_progress_bar)
        val stepTv   = v.findViewById<TextView>(R.id.agent_step_text)
        val btnTake  = v.findViewById<TextView>(R.id.agent_btn_takeover)
        val btnStop  = v.findViewById<TextView>(R.id.agent_btn_stop)
        val emptyView = v.findViewById<View>(R.id.agent_empty_view)
        val recycler  = v.findViewById<RecyclerView>(R.id.agent_log_recycler)

        if (agentRunning) {
            dot?.setBackgroundResource(R.drawable.floating_btn_start)
            label?.text = if (agentTakeover) "⏸ 人工接管中" else "🤖 Agent 执行中"
            label?.setTextColor(if (agentTakeover) 0xFFFFB74D.toInt() else 0xFF4FC3F7.toInt())

            taskTv?.visibility = View.VISIBLE
            taskTv?.text = agentInstruction.take(120)

            progRow?.visibility = View.VISIBLE
            progBar?.max = agentMaxSteps
            progBar?.progress = agentStep
            stepTv?.text = "${agentStep}/${agentMaxSteps}"

            btnTake?.visibility = View.VISIBLE
            btnTake?.text = if (agentTakeover) "▶ 恢复" else "⏸ 接管"
            btnTake?.setTextColor(if (agentTakeover) 0xFF4CAF50.toInt() else 0xFFFFB74D.toInt())
            btnStop?.visibility = View.VISIBLE

            emptyView?.visibility = View.GONE
            recycler?.visibility  = View.VISIBLE
        } else {
            dot?.setBackgroundResource(R.drawable.floating_btn_stop)
            label?.text = "Agent 待命"
            label?.setTextColor(0xFF889AAB.toInt())
            taskTv?.visibility  = View.GONE
            progRow?.visibility = View.GONE
            btnTake?.visibility = View.GONE
            btnStop?.visibility = View.GONE

            val hasLogs = agentLogs.isNotEmpty()
            emptyView?.visibility = if (hasLogs) View.GONE else View.VISIBLE
            recycler?.visibility  = if (hasLogs) View.VISIBLE else View.GONE
        }

        // 更新 Agent Tab 步数角标
        val badge = v.findViewById<TextView>(R.id.tab_agent_badge)
        if (agentRunning) {
            badge?.visibility = View.VISIBLE
            badge?.text = "$agentStep"
        } else {
            badge?.visibility = View.GONE
        }

        // 同步条形模式状态
        if (currentMode == DisplayMode.BAR) updateBarStatus()
    }

    private fun addAgentLogItem(item: LogItem) {
        while (agentLogs.size >= MAX_LOGS) agentLogs.removeFirst()
        agentLogs.addLast(item)
        agentLogAdapter?.notifyItemInserted(agentLogs.size - 1)
        if (agentLogAutoScroll && currentTab == Tab.AGENT) scrollAgentToBottom()
        // 若不在 Agent Tab，显示角标
        if (currentTab != Tab.AGENT) {
            val badge = rootView?.findViewById<TextView>(R.id.tab_agent_badge)
            badge?.visibility = View.VISIBLE
        }
    }

    private fun scrollAgentToBottom() {
        val now = System.currentTimeMillis()
        if (now - lastAgentScrollTime < 80) return
        lastAgentScrollTime = now
        if (agentLogs.isNotEmpty())
            rootView?.findViewById<RecyclerView>(R.id.agent_log_recycler)
                ?.scrollToPosition(agentLogs.size - 1)
    }

    // ================================================================
    // 设置面板
    // ================================================================

    private fun setupSettings() {
        val v = rootView ?: return

        val alphaBar = v.findViewById<SeekBar>(R.id.settings_seekbar_alpha) ?: return
        val alphaVal = v.findViewById<TextView>(R.id.settings_alpha_value)
        val fontBar  = v.findViewById<SeekBar>(R.id.settings_seekbar_fontsize)
        val fontVal  = v.findViewById<TextView>(R.id.settings_fontsize_value)

        alphaBar.progress = ((currentAlpha * 100).toInt() - 20).coerceIn(0, 80)
        alphaVal?.text = "${(currentAlpha * 100).toInt()}%"
        fontBar?.progress = (fontSize - 8f).toInt()
        fontVal?.text = "${fontSize.toInt()}sp"

        alphaBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val a = (p + 20) / 100f
                alphaVal?.text = "${(a * 100).toInt()}%"
                if (fromUser) applyAlpha(a)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        fontBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val sz = p + 8f
                fontVal?.text = "${sz.toInt()}sp"
                if (fromUser) { fontSize = sz; logAdapter?.notifyDataSetChanged() }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun applyAlpha(a: Float) {
        currentAlpha = a.coerceIn(0.2f, 1.0f)
        if (currentMode == DisplayMode.PANEL || currentMode == DisplayMode.FULLSCREEN)
            rootView?.alpha = currentAlpha
    }

    // ================================================================
    // 日志 RecyclerView
    // ================================================================

    private fun setupLogRecycler() {
        val recycler = rootView?.findViewById<RecyclerView>(R.id.log_recycler) ?: return
        logAdapter = LogAdapter(filteredLogs)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = logAdapter
        recycler.itemAnimator = null

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
                    autoScroll = false; updateAutoScrollIcon()
                }
            }
        })
    }

    private fun setupFilterChips() {
        val v = rootView ?: return
        val chips = mapOf(
            v.findViewById<TextView>(R.id.log_chip_v) to LogLevel.VERBOSE,
            v.findViewById<TextView>(R.id.log_chip_d) to LogLevel.DEBUG,
            v.findViewById<TextView>(R.id.log_chip_i) to LogLevel.INFO,
            v.findViewById<TextView>(R.id.log_chip_w) to LogLevel.WARN,
            v.findViewById<TextView>(R.id.log_chip_e) to LogLevel.ERROR
        )
        fun updateState() { chips.forEach { (chip, lvl) -> chip?.isSelected = filterLevel.ordinal <= lvl.ordinal } }
        chips.forEach { (chip, lvl) ->
            chip?.setOnClickListener { filterLevel = lvl; updateState(); applyFilter() }
        }
        updateState()
    }

    private fun updateAutoScrollIcon() {
        rootView?.findViewById<ImageView>(R.id.log_btn_autoscroll)?.alpha =
            if (autoScroll) 1.0f else 0.35f
    }

    // ================================================================
    // 日志管理
    // ================================================================

    private fun handleLogMessage(raw: String) {
        val isErr = raw.startsWith("err:")
        val msg = when {
            raw.startsWith("out:") -> raw.substring(4)
            raw.startsWith("err:") -> raw.substring(4)
            else -> raw
        }

        val trimmed = msg.trimStart()
        if (trimmed.startsWith(EngineClient.CONSOLE_CMD_PREFIX)) {
            handleConsoleCommand(trimmed.substringAfter(EngineClient.CONSOLE_CMD_PREFIX).trim())
            return
        }

        val level = when {
            isErr -> LogLevel.ERROR
            msg.contains("[ERROR]", true) || msg.contains("Traceback", true) -> LogLevel.ERROR
            msg.contains("[WARN]", true)  || msg.contains("[WARNING]", true) -> LogLevel.WARN
            msg.contains("[DEBUG]", true)   -> LogLevel.DEBUG
            msg.contains("[VERBOSE]", true) -> LogLevel.VERBOSE
            else -> LogLevel.INFO
        }

        val text = msg.replace("\n\n", "\n").trimEnd('\n')
        if (text.isNotBlank()) addLogItem(LogItem(text, level))
    }

    private fun handleConsoleCommand(cmd: String) {
        handler.post {
            try {
                when {
                    cmd == "show"    -> { if (!isRunning()) start(this); if (currentMode == DisplayMode.BUBBLE) switchMode(DisplayMode.PANEL) }
                    cmd == "hide"    -> { if (currentMode == DisplayMode.PANEL) switchMode(DisplayMode.BUBBLE) }
                    cmd == "close"   -> stop(this)
                    cmd == "clear"   -> clearAllLogs()
                    cmd.startsWith("alpha:") -> applyAlpha(cmd.substringAfter("alpha:").toFloatOrNull() ?: return@post)
                    cmd.startsWith("title:") -> rootView?.findViewById<TextView>(R.id.panel_title)?.text = cmd.substringAfter("title:")
                    cmd.startsWith("log:") -> {
                        val rest = cmd.substringAfter("log:")
                        val lvl = when { rest.startsWith("E:") -> LogLevel.ERROR; rest.startsWith("W:") -> LogLevel.WARN; rest.startsWith("D:") -> LogLevel.DEBUG; rest.startsWith("V:") -> LogLevel.VERBOSE; else -> LogLevel.INFO }
                        val text = if (rest.length > 2 && rest[1] == ':') rest.substring(2) else rest
                        addLogItem(LogItem(text, lvl))
                    }
                    cmd.startsWith("agent_log:") -> {
                        // 写入 Agent Tab 专用日志，格式: agent_log:I:消息内容
                        val rest = cmd.substringAfter("agent_log:")
                        val lvl = when {
                            rest.startsWith("E:") -> LogLevel.ERROR
                            rest.startsWith("W:") -> LogLevel.WARN
                            rest.startsWith("D:") -> LogLevel.DEBUG
                            rest.startsWith("V:") -> LogLevel.VERBOSE
                            else -> LogLevel.INFO
                        }
                        val text = if (rest.length > 2 && rest[1] == ':') rest.substring(2) else rest
                        addAgentLogItem(LogItem(text, lvl))
                    }
                    cmd.startsWith("switch_tab:") -> {
                        val tabName = cmd.substringAfter("switch_tab:").trim().lowercase()
                        val tab = when (tabName) {
                            "scripts" -> Tab.SCRIPTS
                            "log"     -> Tab.LOG
                            "agent"   -> Tab.AGENT
                            else      -> null
                        }
                        tab?.let { selectTab(it) }
                    }
                    cmd.startsWith("size:") -> {
                        val p = cmd.substringAfter("size:").split(",")
                        if (p.size == 2) { panelW = dpToPx(p[0].trim().toInt()); panelH = dpToPx(p[1].trim().toInt()); if (currentMode == DisplayMode.PANEL) showCurrentMode(animate = false) }
                    }
                }
            } catch (e: Exception) { ExtSystem.printDebugError("UnifiedFloating cmd: $cmd", e) }
        }
    }

    private fun addLogItem(item: LogItem) {
        handler.post {
            while (allLogs.size >= MAX_LOGS) allLogs.removeFirst()
            allLogs.addLast(item)
            lastBarLog = item.text
            if (item.level.ordinal >= filterLevel.ordinal) {
                filteredLogs.add(item)
                logAdapter?.notifyItemInserted(filteredLogs.size - 1)
                updateLineCount()
                // 自动滚动：面板展开且在日志 Tab
                if (autoScroll && currentTab == Tab.LOG &&
                    (currentMode == DisplayMode.PANEL || currentMode == DisplayMode.FULLSCREEN)) {
                    scrollToBottom()
                }
                // 不在日志 Tab 时显示未读红点
                if (currentTab != Tab.LOG) {
                    logUnread = true
                    rootView?.findViewById<View>(R.id.tab_log_badge)?.visibility = View.VISIBLE
                }
            }
            if (currentMode == DisplayMode.BAR) updateBarStatus()
        }
    }

    private fun clearAllLogs() {
        handler.post {
            allLogs.clear(); filteredLogs.clear()
            logAdapter?.notifyDataSetChanged()
            updateLineCount()
        }
    }

    private fun applyFilter() {
        filteredLogs.clear()
        for (item in allLogs) { if (item.level.ordinal >= filterLevel.ordinal) filteredLogs.add(item) }
        logAdapter?.notifyDataSetChanged()
        updateLineCount()
    }

    private fun updateLineCount() {
        rootView?.findViewById<TextView>(R.id.log_line_count)
            ?.text = "${filteredLogs.size} lines"
    }

    private fun scrollToBottom() {
        val now = System.currentTimeMillis()
        if (now - lastScrollTime < 80) return
        lastScrollTime = now
        if (filteredLogs.isNotEmpty())
            rootView?.findViewById<RecyclerView>(R.id.log_recycler)
                ?.scrollToPosition(filteredLogs.size - 1)
    }

    private fun copyAllLogs() {
        val text = buildString {
            for (item in filteredLogs) {
                val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.timestamp))
                append("[$ts][${item.level.tag}] ${item.text}\n")
            }
        }
        val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("log", text))
        ContextAction.toast(getString(R.string.float_log_copied_lines, filteredLogs.size))
    }

    // ================================================================
    // 引擎/项目状态轮询
    // ================================================================

    private val statusRunnable = object : Runnable {
        override fun run() {
            pollStatus()
            handler.postDelayed(this, STATUS_INTERVAL)
        }
    }

    private fun pollStatus() {
        if (isStatusRefreshing) return
        isStatusRefreshing = true
        thread {
            try {
                val status = EngineClient.getProjectRunningStatus()
                val eng = status != null
                val newRunning = if (status?.first == true) status.second else null
                if (eng != engineRunning || newRunning != runningProjectName) {
                    engineRunning = eng
                    runningProjectName = newRunning
                    handler.post { updateControlStatus() }
                }
                handler.post { updateControlStatus() }

                // Agent 状态
                val body = EngineClient.instance?.httpGetPublic("/agent/status")
                if (body != null) {
                    try {
                        val json = org.json.JSONObject(body)
                        val running = json.optBoolean("running", false)
                        val step = json.optInt("current_step", 0)
                        val max = json.optInt("max_steps", 30)
                        val action = json.optString("current_action", "")
                        val takeover = json.optBoolean("takeover", false)
                        val instruction = json.optString("instruction", "")
                        if (running != agentRunning || step != agentStep || takeover != agentTakeover) {
                            agentRunning = running; agentStep = step
                            agentMaxSteps = max; agentAction = action
                            agentTakeover = takeover; agentInstruction = instruction
                            handler.post { updateAgentTab() }
                            // Agent 开始运行时自动切到 Agent Tab
                            if (running && !agentRunning) {
                                handler.post { selectTab(Tab.AGENT) }
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            } finally {
                isStatusRefreshing = false
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateControlStatus() {
        val v = rootView ?: return
        val dot  = v.findViewById<View>(R.id.ctrl_status_dot) ?: return
        val text = v.findViewById<TextView>(R.id.ctrl_status_text) ?: return
        if (engineRunning) {
            dot.setBackgroundResource(R.drawable.floating_btn_start)
            text.text = runningProjectName?.let { getString(R.string.floating_running_name, it) }
                ?: getString(R.string.floating_engine_ready)
            text.setTextColor(if (runningProjectName != null) 0xFF4CAF50.toInt() else 0xFF889AAB.toInt())
        } else {
            dot.setBackgroundResource(R.drawable.floating_btn_stop)
            text.text = getString(R.string.floating_engine_not_started)
            text.setTextColor(0xFFF44336.toInt())
        }
        if (currentMode == DisplayMode.BAR) updateBarStatus()
    }

    // ================================================================
    // 项目列表
    // ================================================================

    private fun refreshProjectList() {
        if (isRefreshing) return
        isRefreshing = true
        val refreshBtn = rootView?.findViewById<ImageView>(R.id.ctrl_btn_refresh)
        refreshBtn?.animate()?.rotationBy(360f)?.setDuration(600)?.start()

        thread {
            try {
                val eng = EngineClient.ensureEngineRunning()
                if (eng) {
                    projects = EngineClient.getProjectList()
                    val s = EngineClient.getProjectRunningStatus()
                    engineRunning = true
                    runningProjectName = if (s?.first == true) s.second else null
                } else {
                    projects = emptyList(); engineRunning = false; runningProjectName = null
                }
                handler.post { buildProjectItems(); updateControlStatus(); isRefreshing = false }
            } catch (e: Exception) {
                handler.post { isRefreshing = false }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildProjectItems() {
        val container = rootView?.findViewById<LinearLayout>(R.id.ctrl_project_list) ?: return
        val emptyHint = rootView?.findViewById<View>(R.id.ctrl_empty_hint) ?: return
        val scroll    = rootView?.findViewById<ScrollView>(R.id.ctrl_scroll)
        container.removeAllViews()
        updateAgentCard()

        if (projects.isEmpty()) {
            scroll?.visibility = View.GONE; emptyHint.visibility = View.VISIBLE; return
        }
        scroll?.visibility = View.VISIBLE; emptyHint.visibility = View.GONE

        for (project in projects) {
            val item = LayoutInflater.from(this).inflate(R.layout.floating_project_item, container, false)
            item.findViewById<TextView>(R.id.item_project_name)?.text = project.name
            item.findViewById<TextView>(R.id.item_project_version)?.text = "V${project.version}"
            val isRun = project.folderName == runningProjectName
            val status = item.findViewById<TextView>(R.id.item_project_status)
            val btn    = item.findViewById<android.widget.FrameLayout>(R.id.item_action_btn)
            val icon   = item.findViewById<ImageView>(R.id.item_action_icon)
            if (isRun) {
                item.setBackgroundResource(R.drawable.floating_item_running_bg)
                status?.text = getString(R.string.floating_running); status?.setTextColor(0xFF4CAF50.toInt())
                btn?.setBackgroundResource(R.drawable.floating_btn_stop)
                icon?.setImageResource(R.drawable.ic_stop_white)
            } else {
                item.setBackgroundResource(R.drawable.floating_item_bg)
                status?.text = getString(R.string.floating_project_pending); status?.setTextColor(0xFF999999.toInt())
                btn?.setBackgroundResource(R.drawable.floating_btn_start)
                icon?.setImageResource(R.drawable.ic_play_arrow_white)
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dpToPx(5); item.layoutParams = lp
            btn?.setOnClickListener { onProjectAction(project, isRun) }
            container.addView(item)
        }
    }

    private fun onProjectAction(project: YyProject, isRunning: Boolean) {
        thread {
            try {
                if (isRunning) PyEngine.abortProject() else project.start()
                SystemClock.sleep(1200)
                val s = EngineClient.getProjectRunningStatus()
                runningProjectName = if (s?.first == true) s.second else null
                handler.post { buildProjectItems(); updateControlStatus() }
            } catch (e: Exception) {
                handler.post { ContextAction.toast(getString(R.string.floating_op_failed, e.message ?: "")) }
            }
        }
    }

    // 兼容层（外部可能仍调用 updateAgentCard）
    private fun updateAgentCard() = updateAgentTab()

    // ================================================================
    // 控件分析（复用 FloatingWindowService 逻辑）
    // ================================================================

    private fun launchInspector() {
        thread {
            val ok = try { PyEngine.isEngineOpen() } catch (_: Exception) { false }
            handler.post {
                if (!ok) { ContextAction.toast(getString(R.string.floating_start_engine_first)); return@post }
                switchMode(DisplayMode.BUBBLE)
                handler.postDelayed({ startService(Intent(this, FloatingWindowService::class.java).putExtra("action", "inspect")) }, 200)
            }
        }
    }

    // ================================================================
    // 偏好存储
    // ================================================================

    private fun loadPrefs() {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        currentMode      = try { DisplayMode.valueOf(prefs.getString(PREF_MODE, "BUBBLE")!!) } catch (_: Exception) { DisplayMode.BUBBLE }
        currentAlpha     = prefs.getFloat(PREF_ALPHA, 0.92f)
        fontSize         = prefs.getFloat(PREF_FONTSIZE, 11f)
        panelX           = prefs.getInt(PREF_PANEL_X, 20)
        panelY           = prefs.getInt(PREF_PANEL_Y, 200)
        bubbleSnappedRight = prefs.getBoolean(PREF_BUBBLE_RIGHT, false)
        val savedW = prefs.getInt(PREF_PANEL_W, 0)
        val savedH = prefs.getInt(PREF_PANEL_H, 0)
        if (savedW > 0) panelW = savedW
        if (savedH > 0) panelH = savedH
    }

    private fun savePrefs() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
            .putString(PREF_MODE, currentMode.name)
            .putFloat(PREF_ALPHA, currentAlpha)
            .putFloat(PREF_FONTSIZE, fontSize)
            .putInt(PREF_PANEL_X, panelX)
            .putInt(PREF_PANEL_Y, panelY)
            .putInt(PREF_PANEL_W, panelW)
            .putInt(PREF_PANEL_H, panelH)
            .putBoolean(PREF_BUBBLE_RIGHT, bubbleSnappedRight)
            .apply()
    }

    // ================================================================
    // 工具
    // ================================================================

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    // ================================================================
    // RecyclerView Adapter
    // ================================================================

    /**
     * 通用日志适配器 — 接受外部列表引用，同时支持脚本日志和 Agent 步骤日志
     */
    inner class LogAdapter(private val items: List<LogItem>) : RecyclerView.Adapter<LogViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
                typeface = Typeface.MONOSPACE
                setPadding(dpToPx(4), dpToPx(1), dpToPx(4), dpToPx(1))
            }
            val holder = LogViewHolder(tv)
            tv.setOnLongClickListener {
                val pos = holder.adapterPosition
                if (pos in items.indices) {
                    val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("log", items[pos].text))
                    ContextAction.toast(getString(R.string.float_log_copied))
                }
                true
            }
            return holder
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            if (position >= items.size) return
            val item = items[position]
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.timestamp))
            holder.textView.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
                text = "$ts ${item.text}"
                setTextColor(item.level.color)
            }
        }

        override fun getItemCount() = items.size
    }

    inner class LogViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
}
