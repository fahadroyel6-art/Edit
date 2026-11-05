package com.ultravidmerger.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ultravidmerger.app.VideoInfo
import com.ultravidmerger.app.databinding.ItemVideoBinding

class VideoListAdapter(
    private val items: MutableList<VideoInfo>,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<VideoListAdapter.VH>() {

    class VH(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val v = items[position]
        h.binding.tvIndex.text = "${position + 1}"
        h.binding.tvFileName.text = v.fileName
        h.binding.tvMeta.text =
            "${v.durationLabel()} · ${v.resolution()} · ${v.fpsLabel()}fps · ${v.sizeLabel()}"
        h.binding.tvCodec.text =
            "V: ${v.videoCodec.substringAfterLast('/').ifBlank { "?" }.uppercase()}   " +
                "A: ${if (!v.hasAudio()) "NONE" else v.audioCodec.substringAfterLast('/').uppercase()}"
        h.binding.btnRemove.setOnClickListener { onRemove(h.bindingAdapterPosition) }
    }

    override fun getItemCount() = items.size

    fun update(list: List<VideoInfo>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    fun moveItem(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }
}
