package com.tencent.yyds.frag

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tencent.yyds.R

class ShortcutsAdapter : RecyclerView.Adapter<ShortcutsAdapter.ViewHolder>() {

    private val items = mutableListOf<ShortcutItem>()
    var onClickListener: ((ShortcutItem) -> Unit)? = null
    var onLongClickListener: ((ShortcutItem) -> Unit)? = null

    fun setItems(newItems: List<ShortcutItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shortcut_chip, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.itemView.setOnClickListener { onClickListener?.invoke(item) }
        holder.itemView.setOnLongClickListener {
            onLongClickListener?.invoke(item)
            true
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvShortcutTitle)
    }
}
