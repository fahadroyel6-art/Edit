package com.ultravidmerger.app

import android.net.Uri

data class VideoInfo(
    val uri: Uri,
    val fileName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoCodec: String,
    val audioCodec: String,
    val fileSize: Long,
    val bitrate: Int,
    val audioBitrate: Int,
    val sampleRate: Int,
    val channels: Int
) {
    fun getResolution(): String = "${width}x${height}"
    
    fun getDurationFormatted(): String {
        val totalSec = durationMs / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    fun getFileSizeFormatted(): String {
        return when {
            fileSize < 1024 -> "$fileSize B"
            fileSize < 1024 * 1024 -> String.format("%.1f KB", fileSize / 1024.0)
            fileSize < 1024L * 1024 * 1024 -> String.format("%.1f MB", fileSize / (1024.0 * 1024))
            else -> String.format("%.2f GB", fileSize / (1024.0 * 1024 * 1024))
        }
    }
}
