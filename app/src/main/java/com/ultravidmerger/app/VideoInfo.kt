package com.ultravidmerger.app

import android.net.Uri

data class VideoInfo(
    val uri: Uri,
    val fileName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Double,
    val videoCodec: String,
    val audioCodec: String,
    val fileSize: Long,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val sampleRate: Int,
    val channels: Int,
    val timeBase: String = "",
    val audioStreamCount: Int = 0,
    val containerFormat: String = ""
) {
    fun resolution(): String = "${width}x$height"

    fun fpsLabel(): String =
        if (fps <= 0.0) "?" else String.format(java.util.Locale.US, "%.2f", fps)

    fun durationLabel(): String {
        val totalSec = durationMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    fun sizeLabel(): String = formatBytes(fileSize)

    fun hasAudio(): Boolean = audioStreamCount > 0 && audioCodec != "none"

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }

        fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
            else String.format("%d:%02d", m, s)
        }
    }
}
