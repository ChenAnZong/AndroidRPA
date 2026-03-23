package com.tencent.yyds.inspector

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class UiInspectorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var allNodes: List<UiNode> = emptyList()
    private var selectedNode: UiNode? = null
    private var onNodeSelectedListener: ((UiNode?) -> Unit)? = null
    private var screenOffsetY = 0

    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#4400BCD4")
    }

    private val paintSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#FF4CAF50")
    }

    private val paintSelectedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A4CAF50")
    }

    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val paintLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC333333")
        style = Paint.Style.FILL
    }

    fun setData(nodes: List<UiNode>) {
        allNodes = nodes
        selectedNode = null
        invalidate()
    }

    fun setOnNodeSelectedListener(listener: (UiNode?) -> Unit) {
        onNodeSelectedListener = listener
    }

    fun getSelectedNode(): UiNode? = selectedNode

    fun selectNode(node: UiNode?) {
        selectedNode = node
        invalidate()
        // Don't invoke listener here - this is called FROM listener context
    }

    fun clear() {
        allNodes = emptyList()
        selectedNode = null
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        screenOffsetY = loc[1]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val oy = screenOffsetY.toFloat()

        // Draw all node borders (compensate for status bar offset)
        for (node in allNodes) {
            val r = node.bounds
            if (r.width() <= 0 || r.height() <= 0) continue
            canvas.drawRect(r.left.toFloat(), r.top.toFloat() - oy,
                r.right.toFloat(), r.bottom.toFloat() - oy, paintBorder)
        }

        // Draw selected node highlight + label
        selectedNode?.let { sel ->
            val r = sel.bounds
            val rf = RectF(r.left.toFloat(), r.top.toFloat() - oy, r.right.toFloat(), r.bottom.toFloat() - oy)
            canvas.drawRect(rf, paintSelectedFill)
            canvas.drawRect(rf, paintSelected)

            // Draw compact label above the selected rect
            val label = sel.displayLabel
            val labelWidth = paintLabel.measureText(label) + 12f
            val labelHeight = 28f
            val labelX = rf.left.coerceAtLeast(0f)
            val labelY = (rf.top - labelHeight - 2f).coerceAtLeast(0f)

            canvas.drawRoundRect(
                RectF(labelX, labelY, labelX + labelWidth, labelY + labelHeight),
                6f, 6f, paintLabelBg
            )
            canvas.drawText(label, labelX + 6f, labelY + labelHeight - 8f, paintLabel)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val px = event.x.toInt()
            val py = event.y.toInt() + screenOffsetY

            // Find the smallest (deepest) node containing this point
            var bestNode: UiNode? = null
            var bestArea = Long.MAX_VALUE

            for (node in allNodes) {
                val r = node.bounds
                if (r.width() <= 0 || r.height() <= 0) continue
                if (r.contains(px, py)) {
                    val area = r.width().toLong() * r.height().toLong()
                    if (area < bestArea) {
                        bestArea = area
                        bestNode = node
                    }
                }
            }

            selectedNode = bestNode
            onNodeSelectedListener?.invoke(bestNode)
            invalidate()
            return true
        }
        return true
    }
}
