package com.tencent.yyds

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
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.caz.xp.ui.ContextAction
import pyengine.EngineClient
import uiautomator.ExtSystem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
class FloatingLogService : Service() {

    private lateinit var windowManager: WindowManager
    private var consoleView: View? = null
    private var consoleParams: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    private val allLogItems = ArrayDeque<LogItem>()
    private val filteredLogItems = mutableListOf<LogItem>()
    private var logAdapter: LogAdapter? = null

    private var isMinimized = false
    private var currentAlpha = 0.9f
    private var fontSize = 11f
    private var autoScroll = true
    private var filterLevel = LogLevel.VERBOSE
    private var isSettingsShowing = false
    private var consoleWidth = 0
    private var consoleHeight = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var lastScrollTime = 0L
    private val SCROLL_THROTTLE_MS = 100L

    // Console command protocol
    private val logListener: (String) -> Unit = { msg -> handleLogMessage(msg) }

    companion object {
        private const val TAG = "FloatingLogService"
        val CONSOLE_CMD_PREFIX = EngineClient.CONSOLE_CMD_PREFIX
        private const val MAX_LOG_LINES = 5000
        private const val PREF_NAME = "floating_console_prefs"
        private const val PREF_KEY_ENABLED = "console_enabled"
        private const val PREF_KEY_ALPHA = "console_alpha"
        private const val PREF_KEY_FONTSIZE = "console_fontsize"

        @Volatile
        private var instance: FloatingLogService? = null

        // 兼容查询：委托到 UnifiedFloatingService
        fun isRunning(): Boolean = instance != null || UnifiedFloatingService.isRunning()

        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getBoolean(PREF_KEY_ENABLED, false)
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit().putBoolean(PREF_KEY_ENABLED, enabled).apply()
        }

        fun start(context: Context) {
            setEnabled(context, true)
            // 委托到 UnifiedFloatingService（合并后的统一悬浮窗）
            if (!UnifiedFloatingService.isRunning()) {
                UnifiedFloatingService.start(context, UnifiedFloatingService.DisplayMode.PANEL)
            } else {
                // 已运行，切换到面板模式展示日志
                UnifiedFloatingService.start(context)
            }
        }

        fun stop(context: Context) {
            setEnabled(context, false)
            // 切回气泡模式（不完全停止，保留气泡）
            UnifiedFloatingService.start(context, UnifiedFloatingService.DisplayMode.BUBBLE)
            instance?.let { context.stopService(Intent(context, FloatingLogService::class.java)) }
        }

        // 从Python脚本调用: 追加日志到悬浮控制台
        fun appendLog(text: String, level: LogLevel = LogLevel.INFO) {
            UnifiedFloatingService.appendLog(text, UnifiedFloatingService.LogLevel.valueOf(level.name))
            instance?.addLogItem(LogItem(text, level))
        }

        // 从Python脚本调用: 清空日志
        fun clearLogs() {
            UnifiedFloatingService.clearLogs()
            instance?.clearAllLogs()
        }

        // 从Python脚本调用: 设置透明度
        fun setAlpha(alpha: Float) {
            UnifiedFloatingService.setAlpha(alpha)
            instance?.handler?.post { instance?.updateAlpha(alpha) }
        }

        // 从Python脚本调用: 设置窗口标题
        fun setTitle(title: String) {
            UnifiedFloatingService.setTitle(title)
            instance?.handler?.post {
                instance?.consoleView?.findViewById<TextView>(R.id.console_title)?.text = title
            }
        }
    }

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 恢复偏好设置
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        currentAlpha = prefs.getFloat(PREF_KEY_ALPHA, 0.9f)
        fontSize = prefs.getFloat(PREF_KEY_FONTSIZE, 11f)

        showConsole()

        // 注册日志监听并确保日志连接
        EngineClient.addLogListener(logListener)
        EngineClient.ensureLogConnect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 处理来自Python脚本的控制命令
        intent?.let { handleIntent(it) }
        return START_STICKY
    }

    override fun onDestroy() {
        EngineClient.removeLogListener(logListener)
        removeConsole()
        // 保存偏好
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
            .putFloat(PREF_KEY_ALPHA, currentAlpha)
            .putFloat(PREF_KEY_FONTSIZE, fontSize)
            .apply()
        instance = null
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent) {
        when (intent.getStringExtra("action")) {
            "show" -> if (isMinimized) restoreConsole()
            "hide" -> if (!isMinimized) minimizeConsole()
            "clear" -> clearAllLogs()
            "log" -> {
                val text = intent.getStringExtra("text") ?: return
                val level = try {
                    LogLevel.valueOf(intent.getStringExtra("level") ?: "INFO")
                } catch (_: Exception) { LogLevel.INFO }
                addLogItem(LogItem(text, level))
            }
        }
    }

    // ================================================================
    // Console Window
    // ================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun showConsole() {
        if (consoleView != null) return

        consoleView = LayoutInflater.from(this).inflate(R.layout.floating_log_console, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        consoleWidth = (screenWidth * 0.88).toInt()
        consoleHeight = (screenHeight * 0.55).toInt()

        consoleParams = WindowManager.LayoutParams(
            consoleWidth,
            consoleHeight,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = (dm.widthPixels - consoleWidth) / 2
            y = (dm.heightPixels - consoleHeight) / 3
        }

        setupHeader()
        setupToolbar()
        setupSettings()
        setupLogRecycler()
        setupResize()

        consoleView!!.alpha = currentAlpha

        try {
            windowManager.addView(consoleView, consoleParams)

            // 入场动画
            consoleView!!.alpha = 0f
            consoleView!!.scaleX = 0.9f
            consoleView!!.scaleY = 0.9f
            consoleView!!.animate()
                .alpha(currentAlpha)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } catch (e: Exception) {
            ExtSystem.printDebugError("显示悬浮日志控制台失败", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHeader() {
        val header = consoleView?.findViewById<RelativeLayout>(R.id.console_header) ?: return

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var lastClickTime = 0L

        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = consoleParams!!.x
                    initialY = consoleParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    consoleParams!!.x = initialX + (event.rawX - initialTouchX).toInt()
                    consoleParams!!.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(consoleView, consoleParams)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val now = System.currentTimeMillis()
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    if (dx < 8 && dy < 8) {
                        // 双击切换最小化
                        if (now - lastClickTime < 350) {
                            if (isMinimized) restoreConsole() else minimizeConsole()
                            lastClickTime = 0
                        } else {
                            lastClickTime = now
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // 按钮事件
        consoleView?.findViewById<ImageView>(R.id.console_btn_minimize)?.setOnClickListener {
            if (isMinimized) restoreConsole() else minimizeConsole()
        }

        consoleView?.findViewById<ImageView>(R.id.console_btn_settings)?.setOnClickListener {
            toggleSettings()
        }

        consoleView?.findViewById<ImageView>(R.id.console_btn_close)?.setOnClickListener {
            stop(this)
        }
    }

    private fun setupToolbar() {
        // 清空日志
        consoleView?.findViewById<ImageView>(R.id.console_btn_clear)?.setOnClickListener {
            clearAllLogs()
            ContextAction.toast(getString(R.string.float_log_cleared))
        }

        // 自动滚动切换
        val autoScrollBtn = consoleView?.findViewById<ImageView>(R.id.console_btn_autoscroll)
        updateAutoScrollIcon()
        autoScrollBtn?.setOnClickListener {
            autoScroll = !autoScroll
            updateAutoScrollIcon()
            if (autoScroll) scrollToBottom()
            ContextAction.toast(if (autoScroll) getString(R.string.float_log_auto_scroll_on) else getString(R.string.float_log_auto_scroll_off))
        }

        // 复制全部日志
        consoleView?.findViewById<ImageView>(R.id.console_btn_copy)?.setOnClickListener {
            copyAllLogs()
        }

        // 日志级别过滤
        setupFilterChips()
    }

    private fun updateAutoScrollIcon() {
        val btn = consoleView?.findViewById<ImageView>(R.id.console_btn_autoscroll)
        btn?.alpha = if (autoScroll) 1.0f else 0.4f
    }

    private fun setupFilterChips() {
        val chipV = consoleView?.findViewById<TextView>(R.id.console_chip_v)
        val chipD = consoleView?.findViewById<TextView>(R.id.console_chip_d)
        val chipI = consoleView?.findViewById<TextView>(R.id.console_chip_i)
        val chipW = consoleView?.findViewById<TextView>(R.id.console_chip_w)
        val chipE = consoleView?.findViewById<TextView>(R.id.console_chip_e)

        val chips = mapOf(
            chipV to LogLevel.VERBOSE,
            chipD to LogLevel.DEBUG,
            chipI to LogLevel.INFO,
            chipW to LogLevel.WARN,
            chipE to LogLevel.ERROR
        )

        fun updateChipState() {
            for ((chip, level) in chips) {
                chip?.isSelected = filterLevel.ordinal <= level.ordinal
            }
        }

        for ((chip, level) in chips) {
            chip?.setOnClickListener {
                filterLevel = level
                updateChipState()
                applyFilter()
            }
        }

        updateChipState()
    }

    private fun setupSettings() {
        val alphaSeekBar = consoleView?.findViewById<SeekBar>(R.id.console_seekbar_alpha)
        val alphaValue = consoleView?.findViewById<TextView>(R.id.console_alpha_value)
        val fontSeekBar = consoleView?.findViewById<SeekBar>(R.id.console_seekbar_fontsize)
        val fontValue = consoleView?.findViewById<TextView>(R.id.console_fontsize_value)

        // 初始值 — alpha seekbar: max=80, progress映射为(progress+20)%
        alphaSeekBar?.max = 80
        alphaSeekBar?.progress = ((currentAlpha * 100).toInt() - 20).coerceIn(0, 80)
        alphaValue?.text = "${(currentAlpha * 100).toInt()}%"
        fontSeekBar?.progress = (fontSize - 8).toInt() // 8sp ~ 22sp
        fontValue?.text = "${fontSize.toInt()}sp"

        alphaSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = (progress + 20) / 100f // 范围 20%~100%
                alphaValue?.text = "${(alpha * 100).toInt()}%"
                if (fromUser) updateAlpha(alpha)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        fontSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = progress + 8f
                fontValue?.text = "${size.toInt()}sp"
                if (fromUser) updateFontSize(size)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupLogRecycler() {
        val recycler = consoleView?.findViewById<RecyclerView>(R.id.console_recycler) ?: return
        logAdapter = LogAdapter()
        recycler.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = false
        }
        recycler.adapter = logAdapter
        recycler.itemAnimator = null // 禁用动画提高性能

        // 用户手动滚动时关闭自动滚动
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recycler: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    autoScroll = false
                    updateAutoScrollIcon()
                }
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResize() {
        val resizeHandle = consoleView?.findViewById<View>(R.id.console_resize_handle) ?: return
        val minW = dpToPx(200)
        val minH = dpToPx(150)
        val maxW = screenWidth
        val maxH = screenHeight

        var initialW = 0
        var initialH = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialW = consoleParams!!.width
                    initialH = consoleParams!!.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dw = (event.rawX - initialTouchX).toInt()
                    val dh = (event.rawY - initialTouchY).toInt()
                    val newW = (initialW + dw).coerceIn(minW, maxW)
                    val newH = (initialH + dh).coerceIn(minH, maxH)
                    consoleParams!!.width = newW
                    consoleParams!!.height = newH
                    // P0-2: 同步记录尺寸，防止minimize→restore回弹
                    consoleWidth = newW
                    consoleHeight = newH
                    try {
                        windowManager.updateViewLayout(consoleView, consoleParams)
                    } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    // ================================================================
    // Log Management
    // ================================================================

    private fun handleLogMessage(raw: String) {
        // 去掉 "out:" / "err:" 前缀
        val isError = raw.startsWith("err:")
        val msg = when {
            raw.startsWith("out:") -> raw.substring(4)
            raw.startsWith("err:") -> raw.substring(4)
            else -> raw
        }

        // 检查控制台命令（必须以前缀开头，防止用户print注入）
        val trimmedMsg = msg.trimStart()
        if (trimmedMsg.startsWith(CONSOLE_CMD_PREFIX)) {
            val cmdStr = trimmedMsg.substringAfter(CONSOLE_CMD_PREFIX).trim()
            handleConsoleCommand(cmdStr)
            return
        }

        // 确定日志级别
        val level = when {
            isError -> LogLevel.ERROR
            msg.contains("[ERROR]", true) || msg.contains("Traceback", true) ||
                msg.contains("Exception", true) -> LogLevel.ERROR
            msg.contains("[WARN]", true) || msg.contains("[WARNING]", true) -> LogLevel.WARN
            msg.contains("[DEBUG]", true) -> LogLevel.DEBUG
            msg.contains("[VERBOSE]", true) -> LogLevel.VERBOSE
            else -> LogLevel.INFO
        }

        val text = msg.replace("\n\n", "\n").trimEnd('\n')
        if (text.isNotBlank()) {
            addLogItem(LogItem(text, level))
        }
    }

    private fun handleConsoleCommand(cmd: String) {
        handler.post {
            try {
                when {
                    cmd == "show" -> {
                        if (!isRunning()) start(this)
                        if (isMinimized) restoreConsole()
                    }
                    cmd == "hide" -> minimizeConsole()
                    cmd == "close" -> stop(this)
                    cmd == "clear" -> clearAllLogs()
                    cmd.startsWith("alpha:") -> {
                        val alpha = cmd.substringAfter("alpha:").toFloatOrNull() ?: return@post
                        updateAlpha(alpha.coerceIn(0.2f, 1.0f))
                    }
                    cmd.startsWith("size:") -> {
                        val parts = cmd.substringAfter("size:").split(",")
                        if (parts.size == 2) {
                            val w = dpToPx(parts[0].trim().toInt())
                            val h = dpToPx(parts[1].trim().toInt())
                            resizeConsole(w, h)
                        }
                    }
                    cmd.startsWith("pos:") -> {
                        val parts = cmd.substringAfter("pos:").split(",")
                        if (parts.size == 2) {
                            val x = dpToPx(parts[0].trim().toInt())
                            val y = dpToPx(parts[1].trim().toInt())
                            moveConsole(x, y)
                        }
                    }
                    cmd.startsWith("title:") -> {
                        val title = cmd.substringAfter("title:")
                        consoleView?.findViewById<TextView>(R.id.console_title)?.text = title
                    }
                    cmd.startsWith("log:") -> {
                        val rest = cmd.substringAfter("log:")
                        val level = when {
                            rest.startsWith("V:") -> LogLevel.VERBOSE
                            rest.startsWith("D:") -> LogLevel.DEBUG
                            rest.startsWith("I:") -> LogLevel.INFO
                            rest.startsWith("W:") -> LogLevel.WARN
                            rest.startsWith("E:") -> LogLevel.ERROR
                            else -> LogLevel.INFO
                        }
                        val text = if (rest.length > 2 && rest[1] == ':') rest.substring(2) else rest
                        addLogItem(LogItem(text, level))
                    }
                }
            } catch (e: Exception) {
                ExtSystem.printDebugError("处理控制台命令失败: $cmd", e)
            }
        }
    }

    private fun addLogItem(item: LogItem) {
        // 所有对 allLogItems/filteredLogItems 的访问统一在UI线程，避免线程安全问题
        handler.post {
            // 限制最大行数 — ArrayDeque.removeFirst() 是 O(1)
            while (allLogItems.size >= MAX_LOG_LINES) {
                allLogItems.removeFirst()
            }
            allLogItems.addLast(item)

            // 应用过滤
            if (item.level.ordinal >= filterLevel.ordinal) {
                filteredLogItems.add(item)
                logAdapter?.notifyItemInserted(filteredLogItems.size - 1)
                updateLineCount()
                if (autoScroll) scrollToBottom()
            }
        }
    }

    private fun clearAllLogs() {
        handler.post {
            allLogItems.clear()
            filteredLogItems.clear()
            logAdapter?.notifyDataSetChanged()
            updateLineCount()
        }
    }

    private fun applyFilter() {
        handler.post {
            filteredLogItems.clear()
            for (item in allLogItems) {
                if (item.level.ordinal >= filterLevel.ordinal) {
                    filteredLogItems.add(item)
                }
            }
            logAdapter?.notifyDataSetChanged()
            updateLineCount()
            val filterInfo = consoleView?.findViewById<TextView>(R.id.console_filter_info)
            filterInfo?.text = if (filterLevel != LogLevel.VERBOSE) getString(R.string.float_log_filter_level, filterLevel.tag) else ""
        }
    }

    private fun updateLineCount() {
        val tv = consoleView?.findViewById<TextView>(R.id.console_line_count)
        tv?.text = "${filteredLogItems.size} lines (${allLogItems.size} total)"
    }

    private fun scrollToBottom() {
        val now = System.currentTimeMillis()
        if (now - lastScrollTime < SCROLL_THROTTLE_MS) return
        lastScrollTime = now
        val recycler = consoleView?.findViewById<RecyclerView>(R.id.console_recycler)
        if (filteredLogItems.isNotEmpty()) {
            recycler?.scrollToPosition(filteredLogItems.size - 1)
        }
    }

    private fun copyAllLogs() {
        val text = buildString {
            for (item in filteredLogItems) {
                val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(item.timestamp))
                append("[$ts][${item.level.tag}] ${item.text}\n")
            }
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("console_log", text))
        ContextAction.toast(getString(R.string.float_log_copied_lines, filteredLogItems.size))
    }

    // ================================================================
    // Window Control
    // ================================================================

    private fun minimizeConsole() {
        if (isMinimized) return
        isMinimized = true

        val recycler = consoleView?.findViewById<RecyclerView>(R.id.console_recycler)
        val toolbar = consoleView?.findViewById<LinearLayout>(R.id.console_toolbar)
        val settings = consoleView?.findViewById<LinearLayout>(R.id.console_settings_panel)
        val footer = consoleView?.findViewById<RelativeLayout>(R.id.console_footer)

        recycler?.visibility = View.GONE
        toolbar?.visibility = View.GONE
        settings?.visibility = View.GONE
        footer?.visibility = View.GONE

        // 缩小为标题栏大小（WRAP_CONTENT自适应标题长度，但不超过原宽度）
        consoleParams?.let { params ->
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            try { windowManager.updateViewLayout(consoleView, params) } catch (_: Exception) {}
        }
    }

    private fun restoreConsole() {
        if (!isMinimized) return
        isMinimized = false

        val recycler = consoleView?.findViewById<RecyclerView>(R.id.console_recycler)
        val toolbar = consoleView?.findViewById<LinearLayout>(R.id.console_toolbar)
        val footer = consoleView?.findViewById<RelativeLayout>(R.id.console_footer)

        recycler?.visibility = View.VISIBLE
        toolbar?.visibility = View.VISIBLE
        footer?.visibility = View.VISIBLE

        consoleParams?.let { params ->
            params.width = consoleWidth
            params.height = consoleHeight
            try { windowManager.updateViewLayout(consoleView, params) } catch (_: Exception) {}
        }

        if (autoScroll) scrollToBottom()
    }

    private fun toggleSettings() {
        val panel = consoleView?.findViewById<LinearLayout>(R.id.console_settings_panel) ?: return
        isSettingsShowing = !isSettingsShowing
        panel.visibility = if (isSettingsShowing) View.VISIBLE else View.GONE
    }

    private fun updateAlpha(alpha: Float) {
        currentAlpha = alpha
        consoleView?.alpha = alpha
        consoleView?.findViewById<SeekBar>(R.id.console_seekbar_alpha)?.progress = (alpha * 100).toInt()
        consoleView?.findViewById<TextView>(R.id.console_alpha_value)?.text = "${(alpha * 100).toInt()}%"
    }

    private fun updateFontSize(size: Float) {
        fontSize = size
        logAdapter?.notifyDataSetChanged()
    }

    private fun resizeConsole(w: Int, h: Int) {
        consoleWidth = w
        consoleHeight = h
        consoleParams?.let { params ->
            params.width = w
            params.height = h
            try { windowManager.updateViewLayout(consoleView, params) } catch (_: Exception) {}
        }
    }

    private fun moveConsole(x: Int, y: Int) {
        consoleParams?.let { params ->
            params.x = x
            params.y = y
            try { windowManager.updateViewLayout(consoleView, params) } catch (_: Exception) {}
        }
    }

    private fun removeConsole() {
        consoleView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        consoleView = null
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // ================================================================
    // RecyclerView Adapter
    // ================================================================

    inner class LogAdapter : RecyclerView.Adapter<LogViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
                typeface = Typeface.MONOSPACE
                setPadding(dpToPx(4), dpToPx(1), dpToPx(4), dpToPx(1))
                setTextIsSelectable(false)
            }
            val holder = LogViewHolder(tv)
            // 长按复制 — 在onCreateViewHolder注册一次，避免重复分配
            tv.setOnLongClickListener {
                val pos = holder.adapterPosition
                if (pos in filteredLogItems.indices) {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("log_line", filteredLogItems[pos].text))
                    ContextAction.toast(getString(R.string.float_log_copied))
                }
                true
            }
            return holder
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            if (position >= filteredLogItems.size) return
            val item = filteredLogItems[position]
            val tv = holder.textView
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)

            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.timestamp))
            tv.text = "$ts ${item.text}"
            tv.setTextColor(item.level.color)
        }

        override fun getItemCount(): Int = filteredLogItems.size
    }

    inner class LogViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
}
