package com.ultravidmerger.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ultravidmerger.app.databinding.ItemComponentBinding
import com.ultravidmerger.app.diag.ComponentState
import com.ultravidmerger.app.diag.ComponentStatus

class ComponentAdapter : RecyclerView.Adapter<ComponentAdapter.VH>() {

    private val items = mutableListOf<ComponentState>()

    class VH(val b: ItemComponentBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(list: List<ComponentState>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemComponentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val s = items[position]
        h.b.tvName.text = s.component.displayName
        h.b.tvDetail.text = s.detail
        h.b.tvStatus.text = s.status.name

        val color = when (s.status) {
            ComponentStatus.OK -> 0xFF10B981.toInt()
            ComponentStatus.BUSY -> 0xFF38BDF8.toInt()
            ComponentStatus.WARN -> 0xFFFBBF24.toInt()
            ComponentStatus.ERROR -> 0xFFEF4444.toInt()
            ComponentStatus.OFFLINE -> 0xFF6B7280.toInt()
            ComponentStatus.IDLE -> 0xFF9CA3AF.toInt()
        }
        h.b.tvStatus.setTextColor(color)
        h.b.dot.setBackgroundColor(color)
        h.b.tvDetail.setTextColor(
            if (s.detail.isBlank() || s.detail == "—") 0xFF6B7280.toInt() else 0xFF9CA3AF.toInt()
        )
        h.b.tvDetail.visibility = if (s.detail.isBlank()) View.GONE else View.VISIBLE
    }

    override fun getItemCount() = items.size
}
