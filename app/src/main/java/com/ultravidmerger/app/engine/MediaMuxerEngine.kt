package com.ultravidmerger.app.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ultravidmerger.app.diag.Component
import com.ultravidmerger.app.diag.ComponentStatus
import com.ultravidmerger.app.diag.DiagnosticLogger
import com.ultravidmerger.app.diag.EngineMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Pure-Android fallback for MODE A. Same guarantee as the FFmpeg path — packets
 * are copied verbatim, never decoded — but implemented with MediaExtractor /
 * MediaMuxer. Used when the FFmpeg concat demuxer rejects a SAF stream.
 *
 * 64-bit safe: durations are handled in microseconds as Long throughout.
 * Memory bounded: one 8 MB rolling buffer, never the whole file.
 */
object MediaMuxerEngine {

    private const val TAG = "MediaMuxerEngine"
    private const val BUFFER_SIZE = 8 * 1024 * 1024

    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    data class Result(
        val success: Boolean,
        val cancelled: Boolean,
        val durationMs: Long,
        val outputBytes: Long,
        val error: String? = null
    )

    private data class Spec(
        val uri: Uri,
        val video: MediaFormat,
        val audio: MediaFormat?,
        val durationUs: Long
    )

    suspend fun merge(
        context: Context,
        uris: List<Uri>,
        outputPath: String,
        onProgress: (Float) -> Unit
    ): Result = withContext(Dispatchers.IO) {

        cancelled = false
        EngineMonitor.set(Component.MEDIA_EXTRACTOR, ComponentStatus.BUSY, "Fallback merge")

        var muxer: MediaMuxer? = null

        try {
            val specs = ArrayList<Spec>(uris.size)
            for ((i, uri) in uris.withIndex()) {
                coroutineContext.ensureActive()
                if (cancelled) return@withContext Result(false, true, 0, 0)

                onProgress(i.toFloat() / (uris.size + 2) * 0.1f)
                EngineMonitor.set(Component.MEDIA_EXTRACTOR, ComponentStatus.BUSY, "Inspect ${i + 1}")

                val spec = inspect(context, uri)
                    ?: return@withContext Result(false, false, 0, 0, "Input ${i + 1} unreadable")
                specs += spec
            }

            val first = specs[0]
            val mux = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            val vTrack = mux.addTrack(first.video)
            val aTrack = first.audio?.let { mux.addTrack(it) } ?: -1
            mux.start()

            EngineMonitor.set(Component.MEDIA_MUXER, ComponentStatus.BUSY, "Writing tracks")

            val totalUs = specs.sumOf { it.durationUs }.coerceAtLeast(1L)
            var offsetUs = 0L
            val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
            val info = MediaCodec.BufferInfo()

            for ((index, spec) in specs.withIndex()) {
                coroutineContext.ensureActive()
                if (cancelled) return@withContext Result(false, true, 0, 0)

                val pfd = context.contentResolver.openFileDescriptor(spec.uri, "r")
                    ?: return@withContext Result(false, false, 0, 0, "Input ${index + 1} unreadable")

                var extractor: MediaExtractor? = null
                try {
                    val ex = MediaExtractor()
                    extractor = ex
                    ex.setDataSource(pfd.fileDescriptor, 0, pfd.statSize)

                    val vIdx = findTrack(ex, "video/")
                    if (vIdx < 0) return@withContext Result(false, false, 0, 0, "No video in input ${index + 1}")

                    ex.selectTrack(vIdx)
                    copyTrack(ex, mux, vTrack, buffer, info, offsetUs) { localUs ->
                        val p = 0.1f + ((offsetUs + localUs).toFloat() / totalUs) * 0.85f
                        onProgress(p.coerceIn(0f, 0.95f))
                    }
                    ex.unselectTrack(vIdx)

                    if (aTrack >= 0) {
                        val aIdx = findTrack(ex, "audio/")
                        if (aIdx >= 0) {
                            ex.selectTrack(aIdx)
                            copyTrack(ex, mux, aTrack, buffer, info, offsetUs, null)
                            ex.unselectTrack(aIdx)
                        }
                    }
                } finally {
                    runCatching { extractor?.release() }
                    runCatching { pfd.close() }
                }

                offsetUs += spec.durationUs
            }

            mux.stop()
            mux.release()
            muxer = null

            val f = File(outputPath)
            if (!f.exists() || f.length() == 0L) {
                return@withContext Result(false, false, 0, 0, "Output not created")
            }

            EngineMonitor.set(Component.MEDIA_MUXER, ComponentStatus.OK, "${f.length() / (1024 * 1024)} MB")
            DiagnosticLogger.info(TAG, "Fallback merge OK: ${f.length()} bytes, ${offsetUs / 1000}ms")

            onProgress(0.97f)
            Result(true, false, offsetUs / 1000, f.length())

        } catch (e: Exception) {
            DiagnosticLogger.error(
                com.ultravidmerger.app.diag.ErrorCode.VIDEO_EXPORT_FAILED, TAG,
                "Fallback merge failed: ${e.message}", e
            )
            runCatching { muxer?.release() }
            runCatching { File(outputPath).delete() }
            Result(false, false, 0, 0, e.message ?: "unknown")
        }
    }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun inspect(context: Context, uri: Uri): Spec? {
        var extractor: MediaExtractor? = null
        var pfd: ParcelFileDescriptor? = null
        return try {
            val d = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd = d
            val ex = MediaExtractor()
            extractor = ex
            ex.setDataSource(d.fileDescriptor, 0, d.statSize)

            var v: MediaFormat? = null
            var a: MediaFormat? = null
            var durUs = 0L

            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && v == null) {
                    v = f
                    if (f.containsKey(MediaFormat.KEY_DURATION)) durUs = f.getLong(MediaFormat.KEY_DURATION)
                } else if (mime.startsWith("audio/") && a == null) {
                    a = f
                }
            }
            val video = v ?: return null
            if (durUs <= 0L) durUs = 1_000_000L
            Spec(uri, video, a, durUs)
        } catch (e: Exception) {
            DiagnosticLogger.warn(TAG, "inspect failed: ${e.message}")
            null
        } finally {
            runCatching { extractor?.release() }
            runCatching { pfd?.close() }
        }
    }

    private fun copyTrack(
        ex: MediaExtractor,
        muxer: MediaMuxer,
        track: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        offsetUs: Long,
        onProgress: ((Long) -> Unit)?
    ) {
        var lastReport = -1L
        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        while (true) {
            if (cancelled) throw IllegalStateException("cancelled")

            buffer.clear()
            val size = ex.readSampleData(buffer, 0)
            if (size < 0) break

            val pts = ex.sampleTime
            if (pts < 0) {
                if (!ex.advance()) break
                continue
            }

            info.offset = 0
            info.size = size
            // 64-bit shift keeps multi-hour timelines monotonic
            info.presentationTimeUs = pts + offsetUs
            info.flags = ex.sampleFlags

            muxer.writeSampleData(track, buffer, info)

            if (onProgress != null && pts - lastReport >= 500_000L) {
                lastReport = pts
                onProgress(pts)
            }

            if (!ex.advance()) break
        }
    }
}
