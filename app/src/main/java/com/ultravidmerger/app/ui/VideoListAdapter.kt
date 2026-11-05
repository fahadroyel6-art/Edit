package com.ultravidmerger.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ultravidmerger.app.VideoInfo
import com.ultravidmerger.app.databinding.ItemVideoBinding

class VideoListAdapter(
    private val videos: MutableList<VideoInfo>,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<VideoListAdapter.VideoViewHolder>() {

    class VideoViewHolder(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val video = videos[position]

        holder.binding.tvIndex.text = "${position + 1}"
        holder.binding.tvFileName.text = video.fileName
        holder.binding.tvMeta.text =
            "${video.getDurationFormatted()}  •  ${video.getResolution()}  •  ${video.fps}fps  •  ${video.getFileSizeFormatted()}"
        holder.binding.tvCodec.text =
            "Video: ${video.videoCodec.substringAfterLast('/').uppercase()}   Audio: ${if (video.audioCodec == "none") "NONE" else video.audioCodec.substringAfterLast('/').uppercase()}"

        holder.binding.btnRemove.setOnClickListener { onRemove(holder.bindingAdapterPosition) }
    }

    override fun getItemCount() = videos.size

    fun updateVideos(newVideos: List<VideoInfo>) {
        videos.clear()
        videos.addAll(newVideos)
        notifyDataSetChanged()
    }

    fun moveItem(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= videos.size || to >= videos.size) return
        val item = videos.removeAt(from)
        videos.add(to, item)
        notifyItemMoved(from, to)
        notifyItemRangeChanged(minOf(from, to), kotlin.math.abs(to - from) + 1)
    }
}
