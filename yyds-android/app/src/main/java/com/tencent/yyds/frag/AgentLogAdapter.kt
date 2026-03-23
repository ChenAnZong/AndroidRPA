package com.tencent.yyds.frag

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tencent.yyds.R

class AgentLogAdapter : RecyclerView.Adapter<AgentLogAdapter.ViewHolder>() {

    private val logs = mutableListOf<AgentLogItem>()
    private val expandedPositions = mutableSetOf<Int>()

    fun setLogs(newLogs: List<AgentLogItem>) {
        val oldSize = logs.size
        logs.clear()
        logs.addAll(newLogs)
        // 清理越界的展开状态
        expandedPositions.removeAll { it >= newLogs.size }
        notifyDataSetChanged()
    }

    fun clear() {
        logs.clear()
        expandedPositions.clear()
        notifyDataSetChanged()
    }

    /** P0-2: 增量追加新日志，避免全量刷新 RecyclerView */
    fun appendLogs(newItems: List<AgentLogItem>) {
        val start = logs.size
        logs.addAll(newItems)
        notifyItemRangeInserted(start, newItems.size)
    }

    override fun getItemCount() = logs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_agent_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = logs[position]
        val expanded = expandedPositions.contains(position)
        holder.bind(item, position == 0, position == logs.size - 1, expanded) {
            if (expandedPositions.contains(position)) {
                expandedPositions.remove(position)
            } else {
                expandedPositions.add(position)
            }
            notifyItemChanged(position)
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        private val tvDetail: TextView = view.findViewById(R.id.tvDetail)
        private val tvStep: TextView = view.findViewById(R.id.tvStep)
        private val viewDot: View = view.findViewById(R.id.viewDot)
        private val viewLineTop: View = view.findViewById(R.id.viewLineTop)
        private val viewLineBottom: View = view.findViewById(R.id.viewLineBottom)

        fun bind(item: AgentLogItem, isFirst: Boolean, isLast: Boolean, expanded: Boolean, onToggle: () -> Unit) {
            // ③ 人话化播报：有 humanMsg 时优先展示，技术细节折叠到 detail
            val displayTitle = if (item.humanMsg.isNotBlank()) item.humanMsg else item.title
            tvTitle.text = displayTitle
            // 构建详情文本（含 token 信息）
            val detailBuilder = StringBuilder()
            // 有 humanMsg 时把原始标题折叠进 detail
            if (item.humanMsg.isNotBlank() && item.title.isNotBlank()) {
                detailBuilder.append(item.title)
            }
            if (item.detail.isNotEmpty()) {
                if (detailBuilder.isNotEmpty()) detailBuilder.append("\n")
                detailBuilder.append(item.detail)
            }
            item.tokenInfo?.let { t ->
                if (detailBuilder.isNotEmpty()) detailBuilder.append("\n")
                detailBuilder.append("🔤 ${t.promptTokens}+${t.completionTokens}=${t.totalTokens} tokens")
                if (t.latencyMs > 0) detailBuilder.append("  ⏱${t.latencyMs}ms")
            }
            item.tokenTotal?.let { t ->
                if (t.totalTokens > 0) {
                    if (detailBuilder.isNotEmpty()) detailBuilder.append("\n")
                    detailBuilder.append("📊 累计 ${t.totalTokens} tokens (${t.callCount}次调用)")
                }
            }
            val detailText = detailBuilder.toString()
            tvDetail.text = detailText
            tvDetail.visibility = if (detailText.isNotEmpty()) View.VISIBLE else View.GONE
            tvDetail.maxLines = if (expanded) Int.MAX_VALUE else 2
            tvDetail.ellipsize = if (expanded) null else android.text.TextUtils.TruncateAt.END
            tvStep.text = "#${item.step}"

            itemView.setOnClickListener { onToggle() }

            // 时间线连接线
            viewLineTop.visibility = if (isFirst) View.INVISIBLE else View.VISIBLE
            viewLineBottom.visibility = if (isLast) View.INVISIBLE else View.VISIBLE

            // 圆点颜色
            val dotColor = when (item.type) {
                "success" -> Color.parseColor("#4CAF50")
                "error" -> Color.parseColor("#F44336")
                "action" -> Color.parseColor("#2196F3")
                "thinking" -> Color.parseColor("#FF9800")
                "result" -> Color.parseColor("#2C5F8A")
                else -> Color.parseColor("#BBBBBB")
            }

            // 动态设置圆点背景色
            val drawable = viewDot.background
            if (drawable is android.graphics.drawable.GradientDrawable) {
                drawable.setColor(dotColor)
            } else {
                val gd = android.graphics.drawable.GradientDrawable()
                gd.shape = android.graphics.drawable.GradientDrawable.OVAL
                gd.setColor(dotColor)
                viewDot.background = gd
            }

            // 标题颜色
            tvTitle.setTextColor(
                when (item.type) {
                    "error" -> Color.parseColor("#F44336")
                    "success", "result" -> Color.parseColor("#4CAF50")
                    else -> Color.parseColor("#1A1A1A")
                }
            )
        }
    }
}
