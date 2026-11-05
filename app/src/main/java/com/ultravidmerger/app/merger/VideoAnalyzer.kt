package com.ultravidmerger.app.merger

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.ultravidmerger.app.VideoInfo

/**
 * Reads real stream metadata straight from MediaExtractor.
 * Falls back to MediaMetadataRetriever for fields the container omits.
 */
object VideoAnalyzer {

    private const val TAG = "VideoAnalyzer"

    fun analyzeVideo(context: Context, uri: Uri): VideoInfo? {
        val fileName = queryString(context, uri, android.provider.OpenableColumns.DISPLAY_NAME) ?: uri.lastPathSegment ?: "video"
        val fileSize = queryLong(context, uri, android.provider.OpenableColumns.SIZE)

        var durationMs = 0L
        var width = 0
        var height = 0
        var fps = 0
        var videoMime = "unknown"
        var audioMime = "none"
        var bitrate = 0
        var audioBitrate = 0
        var sampleRate = 0
        var channels = 0

        // ---- Pass 1: MediaExtractor (authoritative for codec/resolution/fps) ----
        var extractor: MediaExtractor? = null
        var fd: android.os.ParcelFileDescriptor? = null
        try {
            fd = context.contentResolver.openFileDescriptor(uri, "r")
            if (fd == null) return null

            extractor = MediaExtractor()
            extractor.setDataSource(fd.fileDescriptor, 0, fd.statSize)

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue

                if (mime.startsWith("video/") && width == 0) {
                    videoMime = mime
                    width = f.getInteger(MediaFormat.KEY_WIDTH)
                    height = f.getInteger(MediaFormat.KEY_HEIGHT)
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        fps = try { f.getInteger(MediaFormat.KEY_FRAME_RATE) } catch (_: Exception) { 0 }
                    }
                    if (f.containsKey(MediaFormat.KEY_DURATION)) {
                        durationMs = f.getLong(MediaFormat.KEY_DURATION) / 1000
                    }
                    if (f.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        bitrate = f.getInteger(MediaFormat.KEY_BIT_RATE)
                    }
                } else if (mime.startsWith("audio/") && audioMime == "none") {
                    audioMime = mime
                    if (f.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        audioBitrate = f.getInteger(MediaFormat.KEY_BIT_RATE)
                    }
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaExtractor pass failed", e)
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
            try { fd?.close() } catch (_: Exception) {}
        }

        // ---- Pass 2: MediaMetadataRetriever fallback ----
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)

            if (durationMs <= 0L) {
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            }
            if (width == 0) {
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
            }
            if (fps <= 0) {
                fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()?.toInt() ?: 0
            }
            if (bitrate == 0) {
                bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toIntOrNull() ?: 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Retriever pass failed", e)
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }

        if (width == 0 || height == 0) {
            Log.e(TAG, "No video track in $uri")
            return null
        }

        if (fps <= 0) fps = 30 // sensible default for compat checks

        return VideoInfo(
            uri = uri,
            fileName = fileName,
            durationMs = durationMs,
            width = width,
            height = height,
            fps = fps,
            videoCodec = videoMime,
            audioCodec = audioMime,
            fileSize = fileSize,
            bitrate = if (bitrate > 0) bitrate else estimateBitrate(fileSize, durationMs),
            audioBitrate = audioBitrate,
            sampleRate = sampleRate,
            channels = channels
        )
    }

    private fun estimateBitrate(fileSize: Long, durationMs: Long): Int {
        if (durationMs <= 0L || fileSize <= 0L) return 0
        return ((fileSize * 8) / (durationMs / 1000.0)).toInt()
    }

    private fun queryString(context: Context, uri: Uri, column: String): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(column)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun queryLong(context: Context, uri: Uri, column: String): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(column)
                    if (idx >= 0) c.getLong(idx) else 0L
                } else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
