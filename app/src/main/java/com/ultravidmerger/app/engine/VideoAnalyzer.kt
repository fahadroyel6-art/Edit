package com.ultravidmerger.app.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.ultravidmerger.app.VideoInfo
import com.ultravidmerger.app.diag.Component
import com.ultravidmerger.app.diag.ComponentStatus
import com.ultravidmerger.app.diag.DiagnosticLogger
import com.ultravidmerger.app.diag.EngineMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Two-pass inspection.
 *
 * Pass 1 — FFprobe (authoritative): exact codec names, avg_frame_rate,
 *          time_base, stream count, container format.
 * Pass 2 — MediaExtractor / MediaMetadataRetriever (fallback): used if FFprobe
 *          fails, and for fields FFprobe omits.
 */
object VideoAnalyzer {

    private const val TAG = "VideoAnalyzer"

    suspend fun analyze(context: Context, uri: Uri): VideoInfo? = withContext(Dispatchers.IO) {
        EngineMonitor.set(Component.VIDEO_ANALYZER, ComponentStatus.BUSY, "Inspecting…")

        val name = queryString(context, uri, android.provider.OpenableColumns.DISPLAY_NAME)
            ?: uri.lastPathSegment ?: "video"
        val size = queryLong(context, uri, android.provider.OpenableColumns.SIZE)

        val probed = probeWithFfmpeg(context, uri)

        val info = probed ?: fallbackExtractor(context, uri, name, size)

        if (info == null) {
            EngineMonitor.set(Component.VIDEO_ANALYZER, ComponentStatus.ERROR, "Unreadable: $name")
            DiagnosticLogger.warn(TAG, "Analyzer could not read $name")
            return@withContext null
        }

        EngineMonitor.set(
            Component.VIDEO_ANALYZER, ComponentStatus.OK,
            "${info.videoCodec.substringAfterLast('/')} ${info.resolution()} ${info.fpsLabel()}fps"
        )
        EngineMonitor.markSuccess("Analyzed ${info.fileName}")
        DiagnosticLogger.info(
            TAG,
            "Analyzed: ${info.fileName} | ${info.videoCodec} ${info.resolution()} " +
                "${info.fpsLabel()}fps ${info.durationLabel()} | audio=${info.audioCodec} " +
                "${info.sampleRate}Hz/${info.channels}ch | size=${info.sizeLabel()}"
        )
        info
    }

    // ------------------------------------------------------------- FFprobe

    private fun probeWithFfmpeg(context: Context, uri: Uri): VideoInfo? = try {
        EngineMonitor.set(Component.FFPROBE, ComponentStatus.BUSY, "Probing…")

        val saf = FFmpegKitConfig.getSafParameterForRead(context, uri)
        val session = FFprobeKit.getMediaInformation(saf)
        val mediaInfo = session.mediaInformation

        if (mediaInfo == null) {
            EngineMonitor.set(Component.FFPROBE, ComponentStatus.WARN, "No output")
            return null
        }

        var width = 0; var height = 0; var fps = 0.0; var vCodec = "unknown"
        var aCodec = "none"; var sampleRate = 0; var channels = 0; var aBitrate = 0
        var vBitrate = 0; var timeBase = ""; var audioCount = 0

        for (s in mediaInfo.streams) {
            val type = s.type ?: continue
            if (type == "video" && width == 0) {
                vCodec = s.codec ?: "unknown"
                width = s.getStringProperty("width")?.toIntOrNull() ?: 0
                height = s.getStringProperty("height")?.toIntOrNull() ?: 0
                fps = parseRate(s.averageFrameRate) ?: parseRate(s.realFrameRate) ?: 0.0
                vBitrate = s.bitrate?.toIntOrNull() ?: 0
                timeBase = s.timeBase ?: ""
            } else if (type == "audio") {
                audioCount++
                if (aCodec == "none") {
                    aCodec = s.codec ?: "unknown"
                    sampleRate = s.sampleRate?.toIntOrNull() ?: 0
                    channels = s.channelLayout?.let { layout -> channelCount(layout) } ?: 0
                    aBitrate = s.bitrate?.toIntOrNull() ?: 0
                }
            }
        }

        if (width == 0 || height == 0) {
            EngineMonitor.set(Component.FFPROBE, ComponentStatus.WARN, "No video stream")
            return null
        }

        val durationMs = mediaInfo.duration?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
        val size = queryLong(context, uri, android.provider.OpenableColumns.SIZE)
        val name = queryString(context, uri, android.provider.OpenableColumns.DISPLAY_NAME)
            ?: uri.lastPathSegment ?: "video"

        EngineMonitor.set(Component.FFPROBE, ComponentStatus.OK, "v${com.arthenica.ffmpegkit.FFmpegKitConfig.getVersion()}")

        VideoInfo(
            uri = uri,
            fileName = name,
            durationMs = durationMs,
            width = width,
            height = height,
            fps = fps,
            videoCodec = vCodec,
            audioCodec = aCodec,
            fileSize = size,
            videoBitrate = if (vBitrate > 0) vBitrate else estimateBitrate(size, durationMs),
            audioBitrate = aBitrate,
            sampleRate = sampleRate,
            channels = channels,
            timeBase = timeBase,
            audioStreamCount = audioCount,
            containerFormat = mediaInfo.format ?: ""
        )
    } catch (e: Exception) {
        DiagnosticLogger.warn(TAG, "FFprobe failed: ${e.message}")
        EngineMonitor.set(Component.FFPROBE, ComponentStatus.WARN, e.message ?: "failed")
        null
    }

    private fun parseRate(s: String?): Double? {
        if (s.isNullOrBlank() || s == "0/0") return null
        return if (s.contains('/')) {
            val (n, d) = s.split('/')
            val num = n.toDoubleOrNull() ?: return null
            val den = d.toDoubleOrNull() ?: return null
            if (den == 0.0) null else num / den
        } else s.toDoubleOrNull()
    }

    private fun channelCount(layout: String): Int = when {
        layout.startsWith("mono") -> 1
        layout.startsWith("stereo") -> 2
        layout.contains("5.1") -> 6
        layout.contains("7.1") -> 8
        else -> 2
    }

    // ------------------------------------------------------ MediaExtractor

    private fun fallbackExtractor(
        context: Context,
        uri: Uri,
        name: String,
        size: Long
    ): VideoInfo? {
        var extractor: MediaExtractor? = null
        var pfd: android.os.ParcelFileDescriptor? = null

        var durationMs = 0L; var width = 0; var height = 0; var fps = 0.0
        var vMime = "unknown"; var aMime = "none"
        var vBitrate = 0; var aBitrate = 0; var sampleRate = 0; var channels = 0
        var audioCount = 0

        try {
            EngineMonitor.set(Component.MEDIA_EXTRACTOR, ComponentStatus.BUSY, "Fallback inspect")
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            extractor = MediaExtractor()
            extractor.setDataSource(pfd.fileDescriptor, 0, pfd.statSize)

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && width == 0) {
                    vMime = mime
                    width = f.getInteger(MediaFormat.KEY_WIDTH)
                    height = f.getInteger(MediaFormat.KEY_HEIGHT)
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        runCatching { fps = f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                    }
                    if (f.containsKey(MediaFormat.KEY_DURATION)) {
                        durationMs = f.getLong(MediaFormat.KEY_DURATION) / 1000
                    }
                    if (f.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        vBitrate = f.getInteger(MediaFormat.KEY_BIT_RATE)
                    }
                } else if (mime.startsWith("audio/")) {
                    audioCount++
                    if (aMime == "none") {
                        aMime = mime
                        if (f.containsKey(MediaFormat.KEY_BIT_RATE)) aBitrate = f.getInteger(MediaFormat.KEY_BIT_RATE)
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            EngineMonitor.set(Component.MEDIA_EXTRACTOR, ComponentStatus.OK, "fallback ok")
        } catch (e: Exception) {
            DiagnosticLogger.warn(TAG, "MediaExtractor failed: ${e.message}")
            return null
        } finally {
            runCatching { extractor?.release() }
            runCatching { pfd?.close() }
        }

        if (width == 0) return null

        // Retriever pass for anything still missing
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                if (durationMs <= 0L) durationMs =
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (fps <= 0.0) fps =
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull() ?: 30.0
            } finally {
                r.release()
            }
        }
        if (fps <= 0.0) fps = 30.0

        return VideoInfo(
            uri = uri, fileName = name, durationMs = durationMs, width = width, height = height,
            fps = fps, videoCodec = vMime, audioCodec = aMime, fileSize = size,
            videoBitrate = if (vBitrate > 0) vBitrate else estimateBitrate(size, durationMs),
            audioBitrate = aBitrate, sampleRate = sampleRate, channels = channels,
            audioStreamCount = audioCount
        )
    }

    private fun estimateBitrate(size: Long, durationMs: Long): Int {
        if (durationMs <= 0L || size <= 0L) return 0
        return ((size * 8) / (durationMs / 1000.0)).toInt()
    }

    private fun queryString(context: Context, uri: Uri, column: String): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getColumnIndex(column).takeIf { it >= 0 }?.let { c.getString(it) } else null
        }
    }.getOrNull()

    private fun queryLong(context: Context, uri: Uri, column: String): Long = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getColumnIndex(column).takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L else 0L
        } ?: 0L
    }.getOrDefault(0L)
}
