package com.tencent.yyds.inspector

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tencent.yyds.R

class NodeTreeAdapter(
    private val onNodeClick: (UiNode) -> Unit,
    private val onNodeLongClick: (UiNode) -> Unit
) : RecyclerView.Adapter<NodeTreeAdapter.VH>() {

    private var allNodes: List<UiNode> = emptyList()
    private var displayNodes: List<UiNode> = emptyList()
    private var selectedNode: UiNode? = null
    private var searchQuery: String = ""
    private val expandedNodes = mutableSetOf<UiNode>()
    private var density = 1f

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val viewIndent: View = view.findViewById(R.id.viewIndent)
        val ivExpand: ImageView = view.findViewById(R.id.ivExpand)
        val viewClickable: View = view.findViewById(R.id.viewClickable)
        val tvClassName: TextView = view.findViewById(R.id.tvClassName)
        val tvNodeInfo: TextView = view.findViewById(R.id.tvNodeInfo)
        val tvBounds: TextView = view.findViewById(R.id.tvBounds)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setData(nodes: List<UiNode>, screenDensity: Float) {
        allNodes = nodes
        density = screenDensity
        expandedNodes.clear()
        // Auto-expand first 2 levels
        for (node in allNodes) {
            if (node.depth < 2 && node.children.isNotEmpty()) {
                expandedNodes.add(node)
            }
        }
        rebuildDisplayList()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setSearchQuery(query: String) {
        searchQuery = query.trim()
        rebuildDisplayList()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setSelectedNode(node: UiNode?) {
        if (selectedNode == node) return
        selectedNode = node
        notifyDataSetChanged()
    }

    fun getDisplayCount(): Int = displayNodes.size

    private fun matchesSearch(node: UiNode): Boolean {
        if (searchQuery.isBlank()) return true
        return node.text.contains(searchQuery, ignoreCase = true) ||
                node.resourceId.contains(searchQuery, ignoreCase = true) ||
                node.className.contains(searchQuery, ignoreCase = true) ||
                node.contentDesc.contains(searchQuery, ignoreCase = true)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun rebuildDisplayList() {
        displayNodes = if (searchQuery.isNotBlank()) {
            // Search mode: show all matching nodes, flattened
            allNodes.filter { matchesSearch(it) }
        } else {
            // Tree mode: respect expand/collapse
            computeVisibleNodes()
        }
        notifyDataSetChanged()
    }

    private fun computeVisibleNodes(): List<UiNode> {
        val result = mutableListOf<UiNode>()
        var hideBelowDepth = Int.MAX_VALUE

        for (node in allNodes) {
            if (node.depth > hideBelowDepth) continue
            hideBelowDepth = Int.MAX_VALUE
            result.add(node)

            if (node.children.isNotEmpty() && node !in expandedNodes) {
                hideBelowDepth = node.depth
            }
        }
        return result
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.row_node_tree_item, parent, false)
        return VH(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val node = displayNodes[position]
        val isSearchMode = searchQuery.isNotBlank()

        // Indent (depth-based in tree mode, flat in search mode)
        val indentDp = if (isSearchMode) 4 else node.depth * 14
        holder.viewIndent.layoutParams.width = (indentDp * density).toInt()

        // Expand/collapse arrow
        if (node.children.isNotEmpty() && !isSearchMode) {
            holder.ivExpand.visibility = View.VISIBLE
            val isExpanded = node in expandedNodes
            holder.ivExpand.rotation = if (isExpanded) 90f else 0f
            holder.ivExpand.alpha = 0.6f
            holder.ivExpand.setOnClickListener {
                toggleExpand(node)
            }
        } else {
            holder.ivExpand.visibility = View.INVISIBLE
            if (node.children.isEmpty()) {
                // Leaf node - make the expand area invisible but keep spacing
                holder.ivExpand.visibility = View.INVISIBLE
            }
            holder.ivExpand.setOnClickListener(null)
        }

        // Clickable indicator dot
        if (node.clickable || node.longClickable) {
            holder.viewClickable.visibility = View.VISIBLE
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (node.clickable) Color.parseColor("#4CAF50") else Color.parseColor("#FF9800"))
            }
            holder.viewClickable.background = dot
        } else {
            holder.viewClickable.visibility = View.GONE
        }

        // Class name
        holder.tvClassName.text = node.shortClassName
        holder.tvClassName.setTextColor(
            if (node == selectedNode) Color.parseColor("#4CAF50") else Color.parseColor("#EEEEEE")
        )

        // Info line (text, resource-id, content-desc)
        val info = buildString {
            if (node.text.isNotEmpty()) {
                val t = if (node.text.length > 30) node.text.take(30) + "…" else node.text
                append("\"$t\" ")
            }
            if (node.contentDesc.isNotEmpty()) {
                val d = if (node.contentDesc.length > 20) node.contentDesc.take(20) + "…" else node.contentDesc
                append("[$d] ")
            }
            if (node.shortResourceId.isNotEmpty()) {
                append(node.shortResourceId)
            }
        }.trim()

        if (info.isNotEmpty()) {
            holder.tvNodeInfo.visibility = View.VISIBLE
            holder.tvNodeInfo.text = info
        } else {
            holder.tvNodeInfo.visibility = View.GONE
        }

        // Bounds size badge
        val w = node.bounds.width()
        val h = node.bounds.height()
        holder.tvBounds.text = if (w > 0 && h > 0) "${w}×${h}" else ""

        // Selected highlight
        holder.itemView.setBackgroundColor(
            if (node == selectedNode) Color.parseColor("#1A4CAF50") else Color.TRANSPARENT
        )

        // Click handlers
        holder.itemView.setOnClickListener { onNodeClick(node) }
        holder.itemView.setOnLongClickListener {
            onNodeLongClick(node)
            true
        }
    }

    override fun getItemCount(): Int = displayNodes.size

    @SuppressLint("NotifyDataSetChanged")
    private fun toggleExpand(node: UiNode) {
        if (node in expandedNodes) {
            expandedNodes.remove(node)
            // Also collapse all descendants
            collapseDescendants(node)
        } else {
            expandedNodes.add(node)
        }
        rebuildDisplayList()
    }

    private fun collapseDescendants(parent: UiNode) {
        for (child in parent.children) {
            expandedNodes.remove(child)
            if (child.children.isNotEmpty()) {
                collapseDescendants(child)
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun expandAll() {
        for (node in allNodes) {
            if (node.children.isNotEmpty()) {
                expandedNodes.add(node)
            }
        }
        rebuildDisplayList()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun collapseAll() {
        expandedNodes.clear()
        rebuildDisplayList()
    }

    fun scrollToNode(node: UiNode): Int {
        return displayNodes.indexOf(node)
    }

    fun ensureNodeVisible(node: UiNode) {
        // Expand all ancestors to make this node visible
        if (searchQuery.isNotBlank()) return
        var found = false
        val ancestors = mutableListOf<UiNode>()
        for (n in allNodes) {
            if (n == node) {
                found = true
                break
            }
            // Track potential ancestors by depth
            while (ancestors.isNotEmpty() && ancestors.last().depth >= n.depth) {
                ancestors.removeAt(ancestors.lastIndex)
            }
            if (n.children.isNotEmpty()) {
                ancestors.add(n)
            }
        }
        if (found) {
            var changed = false
            for (ancestor in ancestors) {
                if (ancestor !in expandedNodes) {
                    expandedNodes.add(ancestor)
                    changed = true
                }
            }
            if (changed) rebuildDisplayList()
        }
    }
}
