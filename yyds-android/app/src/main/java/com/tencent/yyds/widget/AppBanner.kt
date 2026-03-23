package com.tencent.yyds.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.tencent.yyds.R

/**
 * 应用内自定义浮动卡片通知，替代 Snackbar
 * 居中弹出 + 半透明遮罩 + 缩放动画，支持四种消息类型
 */
object AppBanner {

    enum class Type {
        SUCCESS, ERROR, WARNING, INFO
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentOverlay: View? = null

    /**
     * 显示浮动卡片通知
     */
    fun show(
        activity: Activity,
        message: String,
        type: Type = Type.INFO,
        actionText: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        handler.post {
            // 先移除已有弹窗
            dismissImmediate()

            val decorView = activity.window.decorView as ViewGroup
            val contentView = decorView.findViewById<FrameLayout>(android.R.id.content)

            val overlay = LayoutInflater.from(activity)
                .inflate(R.layout.view_app_banner, contentView, false)

            val dim = overlay.findViewById<View>(R.id.bannerDim)
            val card = overlay.findViewById<com.google.android.material.card.MaterialCardView>(R.id.bannerCard)
            val strip = overlay.findViewById<View>(R.id.bannerStrip)
            val icon = overlay.findViewById<ImageView>(R.id.bannerIcon)
            val title = overlay.findViewById<TextView>(R.id.bannerTitle)
            val text = overlay.findViewById<TextView>(R.id.bannerText)
            val actionBtn = overlay.findViewById<TextView>(R.id.bannerAction)
            val closeBtn = overlay.findViewById<ImageView>(R.id.bannerClose)

            // 设置消息内容
            text.text = message

            // 根据类型配置样式和标题
            applyTypeStyle(type, strip, icon, title, card, actionBtn)

            // 操作按钮
            if (actionText != null) {
                actionBtn.visibility = View.VISIBLE
                actionBtn.text = actionText
                actionBtn.setOnClickListener {
                    onAction?.invoke()
                    dismiss()
                }
            }

            // 关闭按钮（唯一关闭方式之一）
            closeBtn.setOnClickListener { dismiss() }

            // 遮罩和卡片消费点击事件，防止穿透到下层界面
            dim.setOnClickListener { /* 不关闭，需用户主动点关闭按钮 */ }
            card.setOnClickListener { /* 消费事件 */ }

            // 全屏添加到内容视图
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            contentView.addView(overlay, params)

            currentOverlay = overlay

            // === 入场动画：遮罩渐显 + 卡片缩放弹入 ===
            dim.alpha = 0f
            card.alpha = 0f
            card.scaleX = 0.8f
            card.scaleY = 0.8f

            val dimFadeIn = ObjectAnimator.ofFloat(dim, "alpha", 0f, 1f).apply {
                this.duration = 250
            }
            val cardFadeIn = ObjectAnimator.ofFloat(card, "alpha", 0f, 1f).apply {
                this.duration = 200
            }
            val cardScaleX = ObjectAnimator.ofFloat(card, "scaleX", 0.8f, 1f).apply {
                this.duration = 380
                interpolator = OvershootInterpolator(0.9f)
            }
            val cardScaleY = ObjectAnimator.ofFloat(card, "scaleY", 0.8f, 1f).apply {
                this.duration = 380
                interpolator = OvershootInterpolator(0.9f)
            }
            AnimatorSet().apply {
                playTogether(dimFadeIn, cardFadeIn, cardScaleX, cardScaleY)
                start()
            }
        }
    }

    /**
     * 平滑关闭当前弹窗
     */
    fun dismiss() {
        handler.post {
            val overlay = currentOverlay ?: return@post

            val dim = overlay.findViewById<View>(R.id.bannerDim)
            val card = overlay.findViewById<View>(R.id.bannerCard)

            val dimFadeOut = ObjectAnimator.ofFloat(dim, "alpha", dim.alpha, 0f).apply {
                duration = 200
            }
            val cardFadeOut = ObjectAnimator.ofFloat(card, "alpha", card.alpha, 0f).apply {
                duration = 180
            }
            val cardScaleX = ObjectAnimator.ofFloat(card, "scaleX", card.scaleX, 0.85f).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
            }
            val cardScaleY = ObjectAnimator.ofFloat(card, "scaleY", card.scaleY, 0.85f).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
            }
            AnimatorSet().apply {
                playTogether(dimFadeOut, cardFadeOut, cardScaleX, cardScaleY)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        removeOverlay(overlay)
                    }
                })
                start()
            }
            currentOverlay = null
        }
    }

    private fun dismissImmediate() {
        val overlay = currentOverlay ?: return
        removeOverlay(overlay)
        currentOverlay = null
    }

    private fun removeOverlay(overlay: View) {
        (overlay.parent as? ViewGroup)?.removeView(overlay)
    }

    private fun applyTypeStyle(
        type: Type,
        strip: View,
        icon: ImageView,
        title: TextView,
        card: com.google.android.material.card.MaterialCardView,
        actionBtn: TextView
    ) {
        val stripColor: Int
        val iconRes: Int
        val iconTint: Int
        val cardBgColor: Int
        val actionBgColor: Int
        val titleText: String

        val ctx = title.context
        when (type) {
            Type.SUCCESS -> {
                stripColor = Color.parseColor("#43A047")
                iconRes = R.drawable.ic_check
                iconTint = Color.parseColor("#2E7D32")
                cardBgColor = Color.parseColor("#F6FBF4")
                actionBgColor = Color.parseColor("#43A047")
                titleText = ctx.getString(R.string.banner_success)
            }
            Type.ERROR -> {
                stripColor = Color.parseColor("#E53935")
                iconRes = R.drawable.ic_problem
                iconTint = Color.parseColor("#C62828")
                cardBgColor = Color.parseColor("#FFFAF9")
                actionBgColor = Color.parseColor("#E53935")
                titleText = ctx.getString(R.string.banner_error)
            }
            Type.WARNING -> {
                stripColor = Color.parseColor("#FB8C00")
                iconRes = R.drawable.ic_problem
                iconTint = Color.parseColor("#E65100")
                cardBgColor = Color.parseColor("#FFFCF5")
                actionBgColor = Color.parseColor("#FB8C00")
                titleText = ctx.getString(R.string.banner_warning)
            }
            Type.INFO -> {
                stripColor = Color.parseColor("#264B6F")
                iconRes = R.drawable.ic_tip
                iconTint = Color.parseColor("#264B6F")
                cardBgColor = Color.parseColor("#F5F8FB")
                actionBgColor = Color.parseColor("#264B6F")
                titleText = ctx.getString(R.string.banner_info)
            }
        }

        strip.setBackgroundColor(stripColor)
        icon.setImageResource(iconRes)
        icon.setColorFilter(iconTint)
        title.text = titleText
        title.setTextColor(iconTint)
        card.setCardBackgroundColor(cardBgColor)

        // Action button background tint
        actionBtn.background?.setTint(actionBgColor)
    }

    // ===================== 便捷方法 =====================

    fun success(activity: Activity, message: String) {
        show(activity, message, Type.SUCCESS)
    }

    fun error(activity: Activity, message: String) {
        show(activity, message, Type.ERROR)
    }

    fun warning(activity: Activity, message: String) {
        show(activity, message, Type.WARNING)
    }

    fun info(activity: Activity, message: String) {
        show(activity, message, Type.INFO)
    }

    fun showWithAction(
        activity: Activity,
        message: String,
        type: Type = Type.INFO,
        actionText: String,
        onAction: () -> Unit
    ) {
        show(activity, message, type, actionText, onAction)
    }

    // ===================== 从任意上下文调用 =====================

    fun showGlobal(message: String, type: Type = Type.INFO) {
        handler.post {
            val activity = getCurrentActivity() ?: return@post
            show(activity, message, type)
        }
    }

    fun successGlobal(message: String) = showGlobal(message, Type.SUCCESS)
    fun errorGlobal(message: String) = showGlobal(message, Type.ERROR)
    fun warningGlobal(message: String) = showGlobal(message, Type.WARNING)
    fun infoGlobal(message: String) = showGlobal(message, Type.INFO)

    private fun getCurrentActivity(): Activity? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val activities = activitiesField.get(currentActivityThread) as? android.util.ArrayMap<Any, Any>
            activities?.values?.forEach { record ->
                val recordClass = record.javaClass
                val pausedField = recordClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                if (!pausedField.getBoolean(record)) {
                    val activityField = recordClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    return activityField.get(record) as? Activity
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
