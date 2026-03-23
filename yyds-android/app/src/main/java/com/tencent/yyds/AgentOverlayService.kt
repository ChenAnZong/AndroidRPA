package com.tencent.yyds

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import pyengine.EngineClient

/**
 * Agent 悬浮窗口 — 在 AI 思考阶段显示思考过程，执行操作时自动隐藏。
 *
 * 视觉设计:
 *   - 暗色毛玻璃卡片风格，顶部彩色状态条随 Agent 阶段变色
 *   - show  → 蓝色 (#5B9CF6)  加载/等待
 *   - think → 青色 (#4DD0E1)  思考中
 *   - done  → 绿色 (#66BB6A)  完成
 *
 * 协议前缀: ##YYDS_AGENT_OVERLAY##
 * 指令:
 *   show:<text>    显示悬浮窗（fade-in + 微弹动画）
 *   think:<text>   更新思考内容（无动画，仅文字切换）
 *   hide           立即隐藏（无动画，screencap 前调用）
 *   done:<text>    显示完成信息，3.5 秒后自动淡出
 */
class AgentOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null
    private var autoHideRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null
    private var isShowing = false
    private var currentAccentColor = COLOR_SHOW

    // 缓存 View 引用
    private var tvTitle: TextView? = null
    private var tvText: TextView? = null
    private var pulseDot: View? = null
    private var btnClose: TextView? = null
    private var accentBar: View? = null

    private val logListener: (String) -> Unit = { msg -> handleLogMessage(msg) }

    companion object {
        private const val AUTO_HIDE_DELAY_MS = 3500L
        private const val WATCHDOG_TIMEOUT_MS = 30_000L

        // 状态色彩
        private const val COLOR_SHOW  = 0xFF5B9CF6.toInt()  // 蓝 — 加载
        private const val COLOR_THINK = 0xFF4DD0E1.toInt()  // 青 — 思考
        private const val COLOR_DONE  = 0xFF66BB6A.toInt()  // 绿 — 完成

        @Volatile
        private var instance: AgentOverlayService? = null

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            context.startService(Intent(context, AgentOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentOverlayService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlayView()
        EngineClient.addLogListener(logListener)
        EngineClient.ensureLogConnect()
    }

    override fun onDestroy() {
        EngineClient.removeLogListener(logListener)
        cancelWatchdog()
        cancelAutoHide()
        pulseAnimator?.cancel()
        removeOverlay()
        instance = null
        super.onDestroy()
    }

    // ================================================================
    // Overlay View 创建
    // ================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        if (overlayView != null) return

        overlayView = LayoutInflater.from(this).inflate(R.layout.floating_agent_overlay, null)

        // 缓存 View 引用
        tvTitle = overlayView?.findViewById(R.id.agent_overlay_title)
        tvText = overlayView?.findViewById(R.id.agent_overlay_text)
        pulseDot = overlayView?.findViewById(R.id.agent_pulse_dot)
        btnClose = overlayView?.findViewById(R.id.agent_overlay_close)
        accentBar = overlayView?.findViewById(R.id.agent_accent_bar)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val dm = resources.displayMetrics
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (dm.heightPixels * 0.08).toInt()
        }

        setupDrag()

        btnClose?.setOnClickListener { fadeOutAndHide() }

        // 脉冲动画 — 柔和呼吸节奏
        pulseDot?.let { dot ->
            pulseAnimator = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.25f).apply {
                duration = 1000
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }

        overlayView?.visibility = View.GONE
        try {
            windowManager.addView(overlayView, overlayParams)
        } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        val root = overlayView?.findViewById<LinearLayout>(R.id.agent_overlay_root) ?: return
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = overlayParams!!.x
                    initialY = overlayParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    overlayParams!!.x = initialX + (event.rawX - initialTouchX).toInt()
                    overlayParams!!.y = initialY - (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(overlayView, overlayParams)
                    } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    // ================================================================
    // 状态色彩系统
    // ================================================================

    /**
     * 平滑切换状态色彩 — 影响顶部色条 + 脉冲点 + 标题文字。
     */
    private fun applyAccentColor(targetColor: Int, animate: Boolean = true) {
        if (targetColor == currentAccentColor && isShowing) return
        val fromColor = currentAccentColor
        currentAccentColor = targetColor

        if (animate && isShowing) {
            ValueAnimator.ofObject(ArgbEvaluator(), fromColor, targetColor).apply {
                duration = 300
                addUpdateListener { anim ->
                    val color = anim.animatedValue as Int
                    tintAccentBar(color)
                    tintPulseDot(color)
                }
                start()
            }
        } else {
            tintAccentBar(targetColor)
            tintPulseDot(targetColor)
        }
    }

    /** 色条使用圆角 GradientDrawable，顶部圆角 16dp 匹配背景 */
    private fun tintAccentBar(color: Int) {
        val r = resources.displayMetrics.density * 16f  // 16dp → px
        val bg = accentBar?.background
        if (bg is GradientDrawable) {
            bg.setColor(color)
        } else {
            accentBar?.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(color)
            }
        }
    }

    private fun tintPulseDot(color: Int) {
        val bg = pulseDot?.background
        if (bg is GradientDrawable) {
            bg.setColor(color)
        } else {
            pulseDot?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
    }

    // ================================================================
    // Show / Hide / Update
    // ================================================================

    private fun showOverlay(text: String) {
        handler.post {
            cancelAutoHide()
            resetWatchdog()
            applyAccentColor(COLOR_SHOW)
            tvTitle?.text = "AI Agent"
            tvText?.text = text

            if (!isShowing) {
                overlayView?.alpha = 0f
                overlayView?.translationY = 24f
                overlayView?.visibility = View.VISIBLE
                overlayView?.animate()
                    ?.alpha(1f)
                    ?.translationY(0f)
                    ?.setDuration(280)
                    ?.setInterpolator(OvershootInterpolator(1.0f))
                    ?.start()
                isShowing = true
                pulseAnimator?.start()
            }
        }
    }

    private fun updateThinking(text: String) {
        handler.post {
            cancelAutoHide()
            resetWatchdog()
            applyAccentColor(COLOR_THINK)
            tvText?.text = text

            if (!isShowing) {
                overlayView?.alpha = 0f
                overlayView?.translationY = 24f
                overlayView?.visibility = View.VISIBLE
                overlayView?.animate()
                    ?.alpha(1f)
                    ?.translationY(0f)
                    ?.setDuration(280)
                    ?.setInterpolator(OvershootInterpolator(1.0f))
                    ?.start()
                isShowing = true
                pulseAnimator?.start()
            }
        }
    }

    private fun showDone(text: String) {
        handler.post {
            cancelAutoHide()
            cancelWatchdog()
            applyAccentColor(COLOR_DONE)
            tvTitle?.text = "AI Agent"
            tvText?.text = text
            pulseAnimator?.cancel()
            pulseDot?.alpha = 1f

            if (!isShowing) {
                overlayView?.alpha = 0f
                overlayView?.translationY = 24f
                overlayView?.visibility = View.VISIBLE
                overlayView?.animate()
                    ?.alpha(1f)
                    ?.translationY(0f)
                    ?.setDuration(280)
                    ?.setInterpolator(OvershootInterpolator(1.0f))
                    ?.start()
                isShowing = true
            }

            // done 后自动淡出
            autoHideRunnable = Runnable { fadeOutAndHide() }
            handler.postDelayed(autoHideRunnable!!, AUTO_HIDE_DELAY_MS)
        }
    }

    /**
     * **立即**隐藏悬浮窗（无动画）。
     * screencap 紧随其后，必须在同一帧内消失。
     */
    private fun hideOverlayImmediate() {
        handler.post {
            cancelAutoHide()
            cancelWatchdog()
            if (!isShowing) return@post
            overlayView?.animate()?.cancel()
            overlayView?.visibility = View.GONE
            overlayView?.alpha = 0f
            overlayView?.translationY = 0f
            isShowing = false
            pulseAnimator?.cancel()
        }
    }

    /**
     * 淡出 + 下滑隐藏（done 自动隐藏和用户手动关闭）。
     */
    private fun fadeOutAndHide() {
        handler.post {
            cancelAutoHide()
            cancelWatchdog()
            if (!isShowing) return@post
            overlayView?.animate()
                ?.alpha(0f)
                ?.translationY(16f)
                ?.setDuration(250)
                ?.setInterpolator(DecelerateInterpolator())
                ?.withEndAction {
                    overlayView?.visibility = View.GONE
                    overlayView?.translationY = 0f
                    isShowing = false
                    pulseAnimator?.cancel()
                }
                ?.start()
        }
    }

    // ================================================================
    // 定时器管理
    // ================================================================

    private fun cancelAutoHide() {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        autoHideRunnable = null
    }

    /**
     * 看门狗：若 30s 内无任何 overlay 命令且仍在显示，说明 Agent 已异常退出，
     * 自动隐藏避免悬浮窗僵死在屏幕上。
     */
    private fun resetWatchdog() {
        cancelWatchdog()
        watchdogRunnable = Runnable {
            if (isShowing) fadeOutAndHide()
        }
        handler.postDelayed(watchdogRunnable!!, WATCHDOG_TIMEOUT_MS)
    }

    private fun cancelWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun removeOverlay() {
        tvTitle = null
        tvText = null
        pulseDot = null
        btnClose = null
        accentBar = null
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        isShowing = false
    }

    // ================================================================
    // 日志流解析
    // ================================================================

    private fun handleLogMessage(raw: String) {
        val msg = EngineClient.stripLogPrefix(raw)
        if (!msg.startsWith(EngineClient.AGENT_OVERLAY_PREFIX)) return

        val cmd = msg.substring(EngineClient.AGENT_OVERLAY_PREFIX.length)
        when {
            cmd.startsWith("show:") -> showOverlay(cmd.substring(5))
            cmd.startsWith("think:") -> updateThinking(cmd.substring(6))
            cmd.startsWith("done:") -> showDone(cmd.substring(5))
            cmd == "hide" -> hideOverlayImmediate()
        }
    }
}
