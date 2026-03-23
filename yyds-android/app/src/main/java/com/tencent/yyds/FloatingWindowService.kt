package com.tencent.yyds

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tencent.yyds.inspector.NodeTreeAdapter
import com.tencent.yyds.inspector.UiInspectorView
import com.tencent.yyds.inspector.UiNode
import me.caz.xp.ui.ContextAction
import pyengine.EngineClient
import pyengine.PyEngine
import pyengine.YyProject
import uiautomator.ExtSystem
import kotlin.concurrent.thread

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private var triggerView: View? = null
    private var panelView: View? = null
    private var triggerParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isPanelShowing = false
    private var projects: List<YyProject> = emptyList()
    private var runningProjectName: String? = null
    private var isRefreshing = false
    @Volatile private var isStatusRefreshing = false
    private var isSnappedToRight = false // track which edge trigger is on

    // Agent 状态
    private var isAgentRunning = false
    private var agentStep = 0
    private var agentMaxSteps = 25
    private var agentAction = ""

    // Inspector
    private var inspectorView: View? = null
    private var inspectorParams: WindowManager.LayoutParams? = null
    private var isInspectorShowing = false
    private var inspectorNodes: List<UiNode> = emptyList()
    private var nodeTreeAdapter: NodeTreeAdapter? = null
    private var isTreePanelShowing = false

    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            refreshStatusOnly()
            handler.postDelayed(this, STATUS_REFRESH_INTERVAL)
        }
    }

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val STATUS_REFRESH_INTERVAL = 5000L
        private const val PREF_KEY_FLOATING = "floating_window_enabled"

        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences("floating_prefs", MODE_PRIVATE)
                .getBoolean(PREF_KEY_FLOATING, false)
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("floating_prefs", MODE_PRIVATE)
                .edit().putBoolean(PREF_KEY_FLOATING, enabled).apply()
        }

        fun start(context: Context) {
            setEnabled(context, true)
            // 统一悬浮窗：如果 UnifiedFloatingService 未运行则启动它，否则直接切到面板模式
            if (!UnifiedFloatingService.isRunning()) {
                UnifiedFloatingService.start(context, UnifiedFloatingService.DisplayMode.BUBBLE)
            }
            // 保留原始 Service 仍可启动（处理 Inspector 等功能）
            context.startService(Intent(context, FloatingWindowService::class.java))
        }

        fun stop(context: Context) {
            setEnabled(context, false)
            UnifiedFloatingService.stop(context)
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // 气泡触发器已由 UnifiedFloatingService 统一管理，此处不再显示独立气泡
        // showTrigger() — 仅保留 Inspector 功能
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoRefreshRunnable)
        handler.removeCallbacks(idleFadeRunnable)
        removeTrigger()
        removePanel()
        removeInspector()
        super.onDestroy()
    }

    // ================================================================
    // Trigger Button (small draggable button on left edge)
    // ================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun showTrigger() {
        if (triggerView != null) return

        triggerView = LayoutInflater.from(this).inflate(R.layout.floating_trigger, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 初始位置：左侧吸附（部分隐藏）
        val initHide = dpToPx(13) // 约30% of 44dp
        triggerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = -initHide
            y = 0
        }
        isSnappedToRight = false

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var touchStartTime = 0L

        triggerView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Cancel any idle fade
                    handler.removeCallbacks(idleFadeRunnable)
                    triggerView?.alpha = 0.92f
                    initialX = triggerParams!!.x
                    initialY = triggerParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    touchStartTime = SystemClock.uptimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        isDragging = true
                    }
                    if (isDragging) {
                        triggerParams!!.x = initialX + dx.toInt()
                        triggerParams!!.y = initialY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(triggerView, triggerParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = SystemClock.uptimeMillis() - touchStartTime
                    if (!isDragging && elapsed < 300) {
                        onTriggerClick()
                    } else {
                        snapTriggerToEdge()
                    }
                    // Schedule idle fade after snap
                    scheduleIdleFade()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(triggerView, triggerParams)
            // Entrance animation
            triggerView!!.alpha = 0f
            triggerView!!.scaleX = 0.5f
            triggerView!!.scaleY = 0.5f
            triggerView!!.animate()
                .alpha(0.92f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator())
                .start()
            // 空闲一段时间后半透明化，减少遮挡
            scheduleIdleFade()
        } catch (e: Exception) {
            ExtSystem.printDebugError("添加悬浮触发器失败", e)
        }
    }

    private val idleFadeRunnable = Runnable {
        triggerView?.animate()?.alpha(0.45f)?.setDuration(800)?.start()
    }

    private fun scheduleIdleFade() {
        handler.removeCallbacks(idleFadeRunnable)
        handler.postDelayed(idleFadeRunnable, 4000)
    }

    private fun getScreenWidth(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.width
        }
    }

    private fun snapTriggerToEdge() {
        val screenWidth = getScreenWidth()
        val triggerW = triggerView?.width ?: dpToPx(44)
        val currentX = triggerParams?.x ?: 0
        val centerX = currentX + triggerW / 2

        // Determine snap side
        val snapRight = centerX > screenWidth / 2
        isSnappedToRight = snapRight

        // 吸附效果：让按钮部分隐藏在屏幕边缘外（约30%藏入）
        val hideAmount = (triggerW * 0.30).toInt()
        val targetX = if (snapRight) {
            screenWidth - triggerW + hideAmount
        } else {
            -hideAmount
        }

        // Flip background drawable to match edge
        val icon = triggerView?.findViewById<ImageView>(R.id.floating_trigger_icon)
        icon?.setBackgroundResource(
            if (snapRight) R.drawable.floating_trigger_bg_right
            else R.drawable.floating_trigger_bg_left
        )

        // Smooth spring animation
        val animator = ValueAnimator.ofInt(currentX, targetX)
        animator.duration = 280
        animator.interpolator = OvershootInterpolator(1.2f)
        animator.addUpdateListener { anim ->
            triggerParams?.x = anim.animatedValue as Int
            try {
                windowManager.updateViewLayout(triggerView, triggerParams)
            } catch (_: Exception) {}
        }
        animator.start()
    }

    private fun onTriggerClick() {
        if (isPanelShowing) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    private fun removeTrigger() {
        triggerView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        triggerView = null
    }

    // ================================================================
    // Panel (project list + controls)
    // ================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel() {
        if (panelView != null) return

        panelView = LayoutInflater.from(this).inflate(R.layout.floating_panel, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 10
            y = 200
        }

        // Setup drag on header
        setupPanelDrag()

        // Setup close button
        panelView!!.findViewById<ImageView>(R.id.floating_btn_close).setOnClickListener {
            hidePanel()
        }

        // Setup refresh button
        panelView!!.findViewById<ImageView>(R.id.floating_btn_refresh).setOnClickListener {
            refreshProjectList()
        }

        // Setup console button
        panelView!!.findViewById<ImageView>(R.id.floating_btn_console).setOnClickListener {
            if (FloatingLogService.isRunning()) {
                FloatingLogService.stop(this)
                ContextAction.toast(getString(R.string.floating_log_closed))
            } else {
                FloatingLogService.start(this)
                ContextAction.toast(getString(R.string.floating_log_opened))
            }
        }

        // Setup inspector button
        panelView!!.findViewById<ImageView>(R.id.floating_btn_inspect).setOnClickListener {
            thread {
                val engineOk = try { PyEngine.isEngineOpen() } catch (_: Exception) { false }
                handler.post {
                    if (!engineOk) {
                        ContextAction.toast(getString(R.string.floating_start_engine_first))
                        return@post
                    }
                    hidePanel()
                    handler.postDelayed({ showInspector() }, 250)
                }
            }
        }

        try {
            windowManager.addView(panelView, panelParams)
            isPanelShowing = true

            // Fix: 在代码中限制ScrollView最大高度（xml的maxHeight无效）
            val scrollView = panelView!!.findViewById<ScrollView>(R.id.floating_scroll_view)
            scrollView.post {
                val maxH = dpToPx(320)
                if (scrollView.height > maxH) {
                    scrollView.layoutParams = scrollView.layoutParams.apply { height = maxH }
                }
            }

            // Entrance animation
            panelView!!.alpha = 0f
            panelView!!.translationX = -100f
            panelView!!.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

            // Load data
            refreshProjectList()
            // Start auto-refresh for status
            handler.postDelayed(autoRefreshRunnable, STATUS_REFRESH_INTERVAL)
        } catch (e: Exception) {
            ExtSystem.printDebugError("显示悬浮面板失败", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPanelDrag() {
        val header = panelView?.findViewById<View>(R.id.floating_panel_header) ?: return

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = panelParams!!.x
                    initialY = panelParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams!!.x = initialX + (event.rawX - initialTouchX).toInt()
                    panelParams!!.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(panelView, panelParams)
                    } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    private fun hidePanel() {
        handler.removeCallbacks(autoRefreshRunnable)
        val panel = panelView ?: return
        panelView = null
        isPanelShowing = false
        panel.animate()
            .alpha(0f)
            .translationX(-80f)
            .setDuration(180)
            .withEndAction {
                try { windowManager.removeView(panel) } catch (_: Exception) {}
            }
            .start()
    }

    private fun removePanel() {
        panelView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        panelView = null
        isPanelShowing = false
    }

    // ================================================================
    // Data Loading
    // ================================================================

    private fun refreshProjectList() {
        if (isRefreshing) return
        isRefreshing = true

        // Show loading state
        handler.post {
            panelView?.let { panel ->
                panel.findViewById<TextView>(R.id.floating_status_text)?.text = getString(R.string.floating_refreshing)
                val refreshBtn = panel.findViewById<ImageView>(R.id.floating_btn_refresh)
                refreshBtn?.animate()?.rotationBy(360f)?.setDuration(600)?.start()
            }
        }

        thread {
            try {
                val engineRunning = EngineClient.ensureEngineRunning()
                if (engineRunning) {
                    projects = EngineClient.getProjectList()
                    val status = EngineClient.getProjectRunningStatus()
                    runningProjectName = if (status?.first == true) status.second else null
                } else {
                    projects = emptyList()
                    runningProjectName = null
                }

                handler.post {
                    updatePanelUI(engineRunning)
                    isRefreshing = false
                }
            } catch (e: Exception) {
                ExtSystem.printDebugError("悬浮窗刷新项目失败", e)
                handler.post {
                    panelView?.let { panel ->
                        panel.findViewById<TextView>(R.id.floating_status_text)?.text = getString(R.string.floating_refresh_failed)
                    }
                    isRefreshing = false
                }
            }
        }
    }

    private fun refreshStatusOnly() {
        if (isStatusRefreshing) return
        isStatusRefreshing = true
        thread {
            try {
                val status = EngineClient.getProjectRunningStatus()
                val newRunning = if (status?.first == true) status.second else null
                if (newRunning != runningProjectName) {
                    runningProjectName = newRunning
                    handler.post { updateProjectItems() }
                }
                handler.post { updateStatusBar(status != null) }

                // Agent 状态轮询
                val agentBody = EngineClient.instance?.httpGetPublic("/agent/status")
                if (agentBody != null) {
                    try {
                        val json = org.json.JSONObject(agentBody)
                        val running = json.optBoolean("running", false)
                        val step = json.optInt("current_step", 0)
                        val max = json.optInt("max_steps", 25)
                        val action = json.optString("current_action", "")
                        if (running != isAgentRunning || step != agentStep) {
                            isAgentRunning = running
                            agentStep = step
                            agentMaxSteps = max
                            agentAction = action
                            handler.post { updateAgentStatusCard() }
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
    private fun updatePanelUI(engineRunning: Boolean) {
        val panel = panelView ?: return

        // Agent 状态卡片（动态插入到面板顶部）
        updateAgentStatusCard()

        // Status bar
        val statusDot = panel.findViewById<View>(R.id.floating_status_dot)
        val statusText = panel.findViewById<TextView>(R.id.floating_status_text)
        val projectCount = panel.findViewById<TextView>(R.id.floating_project_count)

        if (engineRunning) {
            statusDot.setBackgroundResource(R.drawable.floating_btn_start)
            if (runningProjectName != null) {
                statusText.text = getString(R.string.floating_running_name, runningProjectName)
                statusText.setTextColor(0xFF4CAF50.toInt())
            } else {
                statusText.text = getString(R.string.floating_engine_ready)
                statusText.setTextColor(0xFF666666.toInt())
            }
        } else {
            statusDot.setBackgroundResource(R.drawable.floating_btn_stop)
            statusText.text = getString(R.string.floating_engine_not_started)
            statusText.setTextColor(0xFFF44336.toInt())
        }

        projectCount.text = getString(R.string.floating_project_count_fmt, projects.size)

        // Project list
        val listContainer = panel.findViewById<LinearLayout>(R.id.floating_project_list)
        val emptyView = panel.findViewById<LinearLayout>(R.id.floating_empty_view)
        val scrollView = panel.findViewById<ScrollView>(R.id.floating_scroll_view)

        if (projects.isEmpty()) {
            scrollView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            scrollView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            buildProjectItems(listContainer)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatusBar(engineRunning: Boolean) {
        val panel = panelView ?: return
        val statusDot = panel.findViewById<View>(R.id.floating_status_dot)
        val statusText = panel.findViewById<TextView>(R.id.floating_status_text)

        if (!engineRunning) {
            statusDot.setBackgroundResource(R.drawable.floating_btn_stop)
            statusText.text = getString(R.string.floating_engine_not_started)
            statusText.setTextColor(0xFFF44336.toInt())
        } else if (runningProjectName != null) {
            statusDot.setBackgroundResource(R.drawable.floating_btn_start)
            statusText.text = getString(R.string.floating_running_name, runningProjectName)
            statusText.setTextColor(0xFF4CAF50.toInt())
        } else {
            statusDot.setBackgroundResource(R.drawable.floating_btn_start)
            statusText.text = getString(R.string.floating_engine_ready)
            statusText.setTextColor(0xFF666666.toInt())
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildProjectItems(container: LinearLayout) {
        container.removeAllViews()

        for (project in projects) {
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.floating_project_item, container, false)

            val nameText = itemView.findViewById<TextView>(R.id.item_project_name)
            val versionText = itemView.findViewById<TextView>(R.id.item_project_version)
            val statusText = itemView.findViewById<TextView>(R.id.item_project_status)
            val actionBtn = itemView.findViewById<FrameLayout>(R.id.item_action_btn)
            val actionIcon = itemView.findViewById<ImageView>(R.id.item_action_icon)

            nameText.text = project.name
            versionText.text = "V${project.version}"

            val isRunning = project.folderName == runningProjectName

            if (isRunning) {
                itemView.setBackgroundResource(R.drawable.floating_item_running_bg)
                statusText.text = getString(R.string.floating_running)
                statusText.setTextColor(0xFF4CAF50.toInt())
                actionBtn.setBackgroundResource(R.drawable.floating_btn_stop)
                actionIcon.setImageResource(R.drawable.ic_stop_white)
            } else {
                itemView.setBackgroundResource(R.drawable.floating_item_bg)
                statusText.text = getString(R.string.floating_project_pending)
                statusText.setTextColor(0xFF999999.toInt())
                actionBtn.setBackgroundResource(R.drawable.floating_btn_start)
                actionIcon.setImageResource(R.drawable.ic_play_arrow_white)
            }

            // Add margin bottom
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dpToPx(6)
            itemView.layoutParams = lp

            actionBtn.setOnClickListener {
                onProjectAction(project, isRunning)
            }

            container.addView(itemView)
        }
    }

    private fun updateProjectItems() {
        val panel = panelView ?: return
        val container = panel.findViewById<LinearLayout>(R.id.floating_project_list) ?: return
        if (container.childCount != projects.size) {
            buildProjectItems(container)
            return
        }

        for (i in projects.indices) {
            val itemView = container.getChildAt(i) ?: continue
            val statusText = itemView.findViewById<TextView>(R.id.item_project_status)
            val actionBtn = itemView.findViewById<FrameLayout>(R.id.item_action_btn)
            val actionIcon = itemView.findViewById<ImageView>(R.id.item_action_icon)
            val isRunning = projects[i].folderName == runningProjectName

            if (isRunning) {
                itemView.setBackgroundResource(R.drawable.floating_item_running_bg)
                statusText?.text = getString(R.string.floating_running)
                statusText?.setTextColor(0xFF4CAF50.toInt())
                actionBtn?.setBackgroundResource(R.drawable.floating_btn_stop)
                actionIcon?.setImageResource(R.drawable.ic_stop_white)
            } else {
                itemView.setBackgroundResource(R.drawable.floating_item_bg)
                statusText?.text = getString(R.string.floating_project_pending)
                statusText?.setTextColor(0xFF999999.toInt())
                actionBtn?.setBackgroundResource(R.drawable.floating_btn_start)
                actionIcon?.setImageResource(R.drawable.ic_play_arrow_white)
            }
        }
    }

    private fun onProjectAction(project: YyProject, isRunning: Boolean) {
        thread {
            try {
                if (isRunning) {
                    PyEngine.abortProject()
                    handler.post {
                        ContextAction.toast(getString(R.string.floating_stopping, project.name))
                    }
                } else {
                    project.start()
                    handler.post {
                        ContextAction.toast(getString(R.string.floating_starting, project.name))
                    }
                }
                // Wait a bit then refresh
                SystemClock.sleep(1500)
                val status = EngineClient.getProjectRunningStatus()
                runningProjectName = if (status?.first == true) status.second else null
                handler.post { updateProjectItems() }
            } catch (e: Exception) {
                ExtSystem.printDebugError("悬浮窗操作项目失败", e)
                handler.post {
                    ContextAction.toast(getString(R.string.floating_op_failed, e.message ?: ""))
                }
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    @SuppressLint("SetTextI18n")
    private fun updateAgentStatusCard() {
        val panel = panelView ?: return
        val container = panel.findViewById<LinearLayout>(R.id.floating_project_list) ?: return

        // 查找或创建 Agent 状态卡片（用 tag 标识）
        var agentCard = container.findViewWithTag<View>("agent_status_card")

        if (!isAgentRunning) {
            // Agent 未运行，移除卡片
            agentCard?.let { container.removeView(it) }
            return
        }

        if (agentCard == null) {
            // 动态创建 Agent 状态卡片
            agentCard = LinearLayout(this).apply {
                tag = "agent_status_card"
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
                setBackgroundColor(0xFF1A1A2E.toInt())
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dpToPx(8)
                layoutParams = lp
            }

            // 标题行
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val dot = View(this).apply {
                val size = dpToPx(8)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dpToPx(6) }
                setBackgroundColor(0xFF264B6F.toInt())
            }
            val title = TextView(this).apply {
                tag = "agent_title"
                text = getString(R.string.floating_agent_running)
                setTextColor(0xFF264B6F.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val stopBtn = TextView(this).apply {
                text = getString(R.string.floating_stop)
                setTextColor(0xFFF44336.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
                setOnClickListener {
                    thread {
                        EngineClient.instance?.httpGetPublic("/agent/stop")
                        handler.post {
                            isAgentRunning = false
                            updateAgentStatusCard()
                            ContextAction.toast(getString(R.string.floating_agent_stopped))
                        }
                    }
                }
            }
            titleRow.addView(dot)
            titleRow.addView(title)
            titleRow.addView(stopBtn)

            // 进度行
            val progressText = TextView(this).apply {
                tag = "agent_progress"
                setTextColor(0xFFBBBBBB.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dpToPx(14), dpToPx(4), 0, 0)
            }

            (agentCard as LinearLayout).addView(titleRow)
            (agentCard as LinearLayout).addView(progressText)

            // 插入到列表最前面
            container.addView(agentCard, 0)
        }

        // 更新文字
        agentCard.findViewWithTag<TextView>("agent_progress")?.text =
            getString(R.string.floating_agent_step_fmt, agentStep, agentMaxSteps, agentAction.take(30))
    }

    // ================================================================
    // UI Inspector (控件分析)
    // ================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun showInspector() {
        if (isInspectorShowing) return
        isInspectorShowing = true

        // Hide trigger while inspector is active
        triggerView?.visibility = View.GONE

        // Fetch UI dump BEFORE showing overlay (captures real screen, not our overlay)
        thread {
            val xml = try { EngineClient.getUiDumpXml() } catch (_: Exception) { null }

            if (!isInspectorShowing) return@thread

            if (xml == null || xml.startsWith("{")) {
                handler.post {
                    isInspectorShowing = false
                    triggerView?.let {
                        it.visibility = View.VISIBLE
                        it.alpha = 0.92f
                        scheduleIdleFade()
                    }
                    ContextAction.toast(getString(R.string.floating_inspector_failed))
                }
                return@thread
            }

            inspectorNodes = UiNode.parseXml(xml)

            handler.post {
                if (!isInspectorShowing) return@post
                showInspectorOverlay()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showInspectorOverlay() {
        inspectorView = LayoutInflater.from(this).inflate(R.layout.floating_inspector, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        inspectorParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val inspector = inspectorView!!

        // Back key handling (tree panel → detail panel → close inspector)
        inspector.isFocusableInTouchMode = true
        inspector.requestFocus()
        inspector.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                when {
                    isTreePanelShowing -> hideTreePanel()
                    inspector.findViewById<LinearLayout>(R.id.inspector_detail_panel).visibility == View.VISIBLE -> {
                        inspector.findViewById<LinearLayout>(R.id.inspector_detail_panel).visibility = View.GONE
                    }
                    else -> hideInspector()
                }
                true
            } else false
        }

        // Toolbar buttons
        inspector.findViewById<ImageView>(R.id.inspector_btn_close).setOnClickListener { hideInspector() }
        inspector.findViewById<ImageView>(R.id.inspector_btn_refresh).setOnClickListener { refreshInspector() }
        inspector.findViewById<ImageView>(R.id.inspector_btn_tree).setOnClickListener { toggleTreePanel() }
        inspector.findViewById<ImageView>(R.id.detail_btn_copy).setOnClickListener { copySelectedNodeInfo() }
        inspector.findViewById<ImageView>(R.id.detail_btn_close).setOnClickListener {
            inspector.findViewById<LinearLayout>(R.id.inspector_detail_panel).visibility = View.GONE
        }

        // Node selection from overlay
        val uiView = inspector.findViewById<UiInspectorView>(R.id.inspector_view)
        uiView.setOnNodeSelectedListener { node ->
            handler.post {
                showNodeDetail(node)
                // Sync tree adapter selection + auto-scroll
                nodeTreeAdapter?.let { adapter ->
                    adapter.setSelectedNode(node)
                    if (node != null) {
                        adapter.ensureNodeVisible(node)
                        val pos = adapter.scrollToNode(node)
                        if (pos >= 0) {
                            inspector.findViewById<RecyclerView>(R.id.tree_recycler_view)
                                ?.smoothScrollToPosition(pos)
                        }
                    }
                }
            }
        }

        // Load data into view
        uiView.setData(inspectorNodes)
        inspector.findViewById<TextView>(R.id.inspector_node_count).text = getString(R.string.floating_node_count, inspectorNodes.size)
        inspector.findViewById<LinearLayout>(R.id.inspector_loading).visibility = View.GONE

        // Setup tree panel
        setupTreePanel(inspector)

        try {
            windowManager.addView(inspectorView, inspectorParams)
        } catch (e: Exception) {
            ExtSystem.printDebugError("显示控件分析失败", e)
            isInspectorShowing = false
            triggerView?.let {
                it.visibility = View.VISIBLE
                it.alpha = 0.92f
                scheduleIdleFade()
            }
        }
    }

    private fun refreshInspector() {
        // Remove current overlay, re-fetch data with real screen visible
        val oldView = inspectorView
        inspectorView = null
        oldView?.let {
            it.findViewById<UiInspectorView>(R.id.inspector_view)?.clear()
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }

        // Show a brief loading toast
        ContextAction.toast(getString(R.string.floating_refreshing_nodes))

        thread {
            val xml = try { EngineClient.getUiDumpXml() } catch (_: Exception) { null }

            if (!isInspectorShowing) return@thread

            if (xml == null || xml.startsWith("{")) {
                handler.post {
                    ContextAction.toast(getString(R.string.floating_refresh_engine_fail))
                    // Re-show with old data
                    showInspectorOverlay()
                }
                return@thread
            }

            inspectorNodes = UiNode.parseXml(xml)

            handler.post {
                if (!isInspectorShowing) return@post
                showInspectorOverlay()
            }
        }
    }

    // ================================================================
    // Node Tree Panel
    // ================================================================

    @SuppressLint("SetTextI18n")
    private fun setupTreePanel(inspector: View) {
        val treeRecycler = inspector.findViewById<RecyclerView>(R.id.tree_recycler_view)
        treeRecycler.layoutManager = LinearLayoutManager(this)

        nodeTreeAdapter = NodeTreeAdapter(
            onNodeClick = { node ->
                // Highlight on overlay
                val uiView = inspector.findViewById<UiInspectorView>(R.id.inspector_view)
                uiView.selectNode(node)
                nodeTreeAdapter?.setSelectedNode(node)
                // Show detail panel
                showNodeDetail(node)
            },
            onNodeLongClick = { node ->
                // Copy node info on long press
                val sb = StringBuilder()
                sb.appendLine("class=${node.className}")
                if (node.text.isNotEmpty()) sb.appendLine("text=${node.text}")
                if (node.resourceId.isNotEmpty()) sb.appendLine("resource-id=${node.resourceId}")
                if (node.contentDesc.isNotEmpty()) sb.appendLine("content-desc=${node.contentDesc}")
                sb.appendLine("bounds=[${node.bounds.left},${node.bounds.top}][${node.bounds.right},${node.bounds.bottom}]")
                sb.appendLine("clickable=${node.clickable}")
                sb.appendLine("package=${node.packageName}")
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("UiNode", sb.toString()))
                ContextAction.toast(getString(R.string.floating_node_copied))
            }
        )
        treeRecycler.adapter = nodeTreeAdapter
        nodeTreeAdapter?.setData(inspectorNodes, resources.displayMetrics.density)

        // Search input with real-time filtering
        val searchInput = inspector.findViewById<EditText>(R.id.tree_search_input)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                nodeTreeAdapter?.setSearchQuery(query)
                updateTreeFilterCount(inspector)
            }
        })

        // Expand / collapse all
        inspector.findViewById<TextView>(R.id.tree_btn_expand_all).setOnClickListener {
            nodeTreeAdapter?.expandAll()
            updateTreeFilterCount(inspector)
        }
        inspector.findViewById<TextView>(R.id.tree_btn_collapse_all).setOnClickListener {
            nodeTreeAdapter?.collapseAll()
            updateTreeFilterCount(inspector)
        }

        // Close tree panel
        inspector.findViewById<ImageView>(R.id.tree_btn_close).setOnClickListener {
            hideTreePanel()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateTreeFilterCount(inspector: View) {
        val count = nodeTreeAdapter?.getDisplayCount() ?: 0
        inspector.findViewById<TextView>(R.id.tree_filter_count).text = "$count/${inspectorNodes.size}"
    }

    private fun toggleTreePanel() {
        if (isTreePanelShowing) hideTreePanel() else showTreePanel()
    }

    @SuppressLint("SetTextI18n")
    private fun showTreePanel() {
        val inspector = inspectorView ?: return
        isTreePanelShowing = true

        // Hide detail panel when opening tree
        inspector.findViewById<LinearLayout>(R.id.inspector_detail_panel).visibility = View.GONE

        val treePanel = inspector.findViewById<LinearLayout>(R.id.tree_panel)
        treePanel.visibility = View.VISIBLE
        treePanel.alpha = 0f
        treePanel.animate().alpha(1f).setDuration(200).start()

        updateTreeFilterCount(inspector)
    }

    private fun hideTreePanel() {
        val inspector = inspectorView ?: return
        isTreePanelShowing = false

        val treePanel = inspector.findViewById<LinearLayout>(R.id.tree_panel)
        treePanel.animate().alpha(0f).setDuration(150).withEndAction {
            treePanel.visibility = View.GONE
        }.start()

        // Clear search
        inspector.findViewById<EditText>(R.id.tree_search_input).setText("")
    }

    // ================================================================
    // Node Detail Panel
    // ================================================================

    @SuppressLint("SetTextI18n")
    private fun showNodeDetail(node: UiNode?) {
        val inspector = inspectorView ?: return
        val detailPanel = inspector.findViewById<LinearLayout>(R.id.inspector_detail_panel)

        if (node == null) {
            detailPanel.visibility = View.GONE
            return
        }

        // Don't show detail panel when tree is active (tree row shows enough info)
        if (isTreePanelShowing) return

        detailPanel.visibility = View.VISIBLE
        detailPanel.alpha = 0f
        detailPanel.animate().alpha(1f).setDuration(200).start()

        // Class name header
        inspector.findViewById<TextView>(R.id.detail_class_name).text = node.className

        // Build properties
        val container = inspector.findViewById<LinearLayout>(R.id.detail_props_container)
        container.removeAllViews()

        fun addPropRow(key: String, value: String, highlight: Boolean = false) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpToPx(3), 0, dpToPx(3))
            }

            val keyView = TextView(this).apply {
                text = key
                setTextColor(Color.parseColor("#AAAAAA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(dpToPx(100), ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val valueView = TextView(this).apply {
                text = value
                setTextColor(if (highlight) Color.parseColor("#4CAF50") else Color.parseColor("#EEEEEE"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (highlight) typeface = Typeface.DEFAULT_BOLD
            }

            row.addView(keyView)
            row.addView(valueView)
            container.addView(row)
        }

        if (node.text.isNotEmpty()) addPropRow("text", node.text, true)
        if (node.resourceId.isNotEmpty()) addPropRow("resource-id", node.resourceId, true)
        if (node.contentDesc.isNotEmpty()) addPropRow("content-desc", node.contentDesc, true)
        addPropRow("class", node.className)
        addPropRow("package", node.packageName)
        addPropRow("bounds", "[${node.bounds.left},${node.bounds.top}][${node.bounds.right},${node.bounds.bottom}]")
        addPropRow("size", "${node.bounds.width()}x${node.bounds.height()}")

        // Boolean flags row
        val flags = mutableListOf<String>()
        if (node.clickable) flags.add("clickable")
        if (node.longClickable) flags.add("long-click")
        if (node.checkable) flags.add("checkable")
        if (node.checked) flags.add("checked")
        if (node.scrollable) flags.add("scrollable")
        if (node.focusable) flags.add("focusable")
        if (node.enabled) flags.add("enabled")
        if (node.selected) flags.add("selected")

        if (flags.isNotEmpty()) {
            // Add a tags row
            val tagsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpToPx(5), 0, dpToPx(2))
            }

            val tagsLabel = TextView(this).apply {
                text = "flags"
                setTextColor(Color.parseColor("#AAAAAA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(dpToPx(100), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            tagsRow.addView(tagsLabel)

            val tagsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            for (flag in flags.take(4)) {
                val tagView = TextView(this).apply {
                    text = flag
                    setTextColor(Color.parseColor("#4CAF50"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setBackgroundResource(R.drawable.inspector_prop_tag_bg)
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    lp.marginEnd = dpToPx(4)
                    layoutParams = lp
                }
                tagsContainer.addView(tagView)
            }
            tagsRow.addView(tagsContainer)
            container.addView(tagsRow)
        }

        addPropRow("depth", "${node.depth}")
        addPropRow("index", "${node.index}")
    }

    private fun copySelectedNodeInfo() {
        val inspector = inspectorView ?: return
        val uiView = inspector.findViewById<UiInspectorView>(R.id.inspector_view)
        val node = uiView.getSelectedNode() ?: run {
            ContextAction.toast(getString(R.string.floating_select_node_first))
            return
        }

        val sb = StringBuilder()
        sb.appendLine("class=${node.className}")
        if (node.text.isNotEmpty()) sb.appendLine("text=${node.text}")
        if (node.resourceId.isNotEmpty()) sb.appendLine("resource-id=${node.resourceId}")
        if (node.contentDesc.isNotEmpty()) sb.appendLine("content-desc=${node.contentDesc}")
        sb.appendLine("bounds=[${node.bounds.left},${node.bounds.top}][${node.bounds.right},${node.bounds.bottom}]")
        sb.appendLine("clickable=${node.clickable}")
        sb.appendLine("package=${node.packageName}")

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("UiNode", sb.toString()))
        ContextAction.toast(getString(R.string.floating_node_copied_clipboard))
    }

    private fun hideInspector() {
        val view = inspectorView ?: return
        inspectorView = null
        isInspectorShowing = false
        isTreePanelShowing = false
        nodeTreeAdapter = null
        // Show trigger again with proper opacity + restart idle fade
        triggerView?.let {
            it.visibility = View.VISIBLE
            it.alpha = 0.92f
            scheduleIdleFade()
        }
        view.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                view.findViewById<UiInspectorView>(R.id.inspector_view)?.clear()
                try { windowManager.removeView(view) } catch (_: Exception) {}
            }
            .start()
    }

    private fun removeInspector() {
        inspectorView?.let {
            it.findViewById<UiInspectorView>(R.id.inspector_view)?.clear()
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        inspectorView = null
        isInspectorShowing = false
        isTreePanelShowing = false
        nodeTreeAdapter = null
    }
}
