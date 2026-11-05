package com.ultravidmerger.app.merger

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Lossless video merger using MediaExtractor -> MediaMuxer stream copy.
 *
 * Nothing is re-encoded: samples are copied bit-for-bit from each input into the
 * output. Each input is shifted forward on the timeline by the sum of all
 * previous durations so presentation timestamps stay monotonic.
 *
 * Designed for very large inputs: only a single rolling 8 MB buffer is used,
 * file contents are never loaded into memory.
 */
object LosslessMergeEngine {

    private const val TAG = "LosslessMerge"
    private const val COPY_BUFFER_SIZE = 8 * 1024 * 1024

    data class MergeState(
        val isProcessing: Boolean = false,
        val progress: Float = 0f,
        val status: String = "Initializing...",
        val isComplete: Boolean = false,
        val error: String? = null,
        val outputFilePath: String? = null,
        val outputDurationMs: Long = 0L,
        val outputFileSize: Long = 0L
    )

    private val _state = MutableStateFlow(MergeState())
    val state: StateFlow<MergeState> = _state

    @Volatile
    private var isCancelled = false

    @Volatile
    private var isPaused = false

    sealed class Result {
        data class Success(val outputPath: String, val durationMs: Long, val fileSize: Long) : Result()
        data class Error(val message: String) : Result()
        object Cancelled : Result()
    }

    // ------------------------------------------------------------------ control

    fun cancel() {
        isCancelled = true
    }

    fun setPaused(paused: Boolean) {
        isPaused = paused
        Log.i(TAG, if (paused) "Paused" else "Resumed")
        updateState(status = if (paused) "Paused" else "Resuming...")
    }

    fun isPausedState(): Boolean = isPaused

    fun publishProgress(progress: Float, status: String) {
        updateState(progress = progress, status = status, isProcessing = true, isComplete = false)
    }

    fun publishComplete(outputPath: String, durationMs: Long, fileSize: Long) {
        updateState(
            isProcessing = false,
            isComplete = true,
            progress = 1f,
            status = "Complete!",
            outputFilePath = outputPath,
            outputDurationMs = durationMs,
            outputFileSize = fileSize
        )
    }

    fun publishError(message: String) {
        updateState(
            isProcessing = false,
            isComplete = false,
            error = message,
            status = "Error: $message"
        )
    }

    fun resetState() {
        _state.value = MergeState()
        isCancelled = false
        isPaused = false
    }

    // ------------------------------------------------------------------- merge

    private data class SourceSpec(
        val uri: Uri,
        val videoFormat: MediaFormat,
        val audioFormat: MediaFormat?,
        val durationUs: Long
    )

    suspend fun merge(
        context: Context,
        videos: List<Uri>,
        outputPath: String,
        onProgressUpdate: ((Float) -> Unit)? = null
    ): Result = withContext(Dispatchers.IO) {

        isCancelled = false
        updateState(isProcessing = true, status = "Reading sources...", progress = 0f)

        if (videos.isEmpty()) return@withContext Result.Error("No videos selected")

        var muxer: MediaMuxer? = null

        try {
            // ---------- 1. inspect all inputs ----------
            val specs = ArrayList<SourceSpec>(videos.size)
            for ((i, uri) in videos.withIndex()) {
                coroutineContext.ensureActive()
                if (isCancelled) return@withContext Result.Cancelled

                updateState(status = "Analyzing video ${i + 1}/${videos.size}...")
                val spec = inspect(context, uri)
                    ?: return@withContext Result.Error("Video ${i + 1} পড়া যায়নি (corrupted?)")
                specs.add(spec)
            }

            // ---------- 2. verify stream compatibility ----------
            val first = specs[0]
            val refMime = first.videoFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val refWidth = first.videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val refHeight = first.videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val refAudio = first.audioFormat
            val refAudioMime = refAudio?.getString(MediaFormat.KEY_MIME)

            for ((i, spec) in specs.withIndex()) {
                val mime = spec.videoFormat.getString(MediaFormat.KEY_MIME) ?: ""
                val w = spec.videoFormat.getInteger(MediaFormat.KEY_WIDTH)
                val h = spec.videoFormat.getInteger(MediaFormat.KEY_HEIGHT)

                if (mime != refMime || w != refWidth || h != refHeight) {
                    return@withContext Result.Error(
                        "Video ${i + 1} lossless merge এর জন্য compatible নয় " +
                            "(${mime.substringAfterLast('/')} ${w}x$h vs " +
                            "${refMime.substringAfterLast('/')} ${refWidth}x$refHeight)। " +
                            "Re-encode mode ব্যবহার করো।"
                    )
                }

                val audioMime = spec.audioFormat?.getString(MediaFormat.KEY_MIME)
                if (refAudioMime != null && audioMime != null && audioMime != refAudioMime) {
                    return@withContext Result.Error(
                        "Video ${i + 1} এর audio codec আলাদা ($audioMime vs $refAudioMime)। " +
                            "Re-encode mode ব্যবহার করো।"
                    )
                }
            }

            // ---------- 3. create muxer ----------
            updateState(status = "Preparing output...")
            val mux = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            val videoMuxTrack = mux.addTrack(first.videoFormat)
            val audioMuxTrack = if (refAudio != null) mux.addTrack(refAudio) else -1

            mux.start()
            Log.i(TAG, "Muxer started: v=$videoMuxTrack a=$audioMuxTrack")

            // ---------- 4. copy samples ----------
            val totalDurationUs = specs.sumOf { it.durationUs }
            var timelineOffsetUs = 0L
            var totalSamples = 0L

            val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_SIZE)
            val info = MediaCodec.BufferInfo()

            for ((index, spec) in specs.withIndex()) {
                coroutineContext.ensureActive()
                if (isCancelled) return@withContext Result.Cancelled

                updateState(
                    status = "Merging video ${index + 1}/${specs.size}...",
                    progress = if (totalDurationUs > 0)
                        (timelineOffsetUs.toFloat() / totalDurationUs).coerceIn(0f, 0.99f)
                    else 0f
                )

                val pfd: ParcelFileDescriptor? = context.contentResolver
                    .openFileDescriptor(spec.uri, "r")
                if (pfd == null) {
                    return@withContext Result.Error("Video ${index + 1} খোলা যায়নি")
                }

                var extractor: MediaExtractor? = null
                try {
                    val ex = MediaExtractor()
                    extractor = ex
                    ex.setDataSource(pfd.fileDescriptor, 0, pfd.statSize)

                    // --- video ---
                    val vIdx = findTrack(ex, "video/")
                    if (vIdx < 0) {
                        return@withContext Result.Error("Video ${index + 1} এ video track নেই")
                    }

                    ex.selectTrack(vIdx)
                    totalSamples += copyTrack(
                        extractor = ex,
                        muxer = mux,
                        muxTrack = videoMuxTrack,
                        buffer = buffer,
                        info = info,
                        offsetUs = timelineOffsetUs,
                        trackDurationUs = spec.durationUs,
                        onProgress = { localUs ->
                            if (totalDurationUs > 0) {
                                val globalUs = timelineOffsetUs + localUs
                                val p = (globalUs.toFloat() / totalDurationUs)
                                    .coerceIn(0f, 0.99f)
                                updateState(progress = p)
                                onProgressUpdate?.invoke(p)
                            }
                        }
                    )
                    ex.unselectTrack(vIdx)

                    // --- audio ---
                    if (audioMuxTrack >= 0) {
                        val aIdx = findTrack(ex, "audio/")
                        if (aIdx >= 0) {
                            ex.selectTrack(aIdx)
                            totalSamples += copyTrack(
                                extractor = ex,
                                muxer = mux,
                                muxTrack = audioMuxTrack,
                                buffer = buffer,
                                info = info,
                                offsetUs = timelineOffsetUs,
                                trackDurationUs = spec.durationUs,
                                onProgress = null
                            )
                            ex.unselectTrack(aIdx)
                        } else {
                            Log.w(TAG, "Video ${index + 1} has no audio — timeline gap kept")
                        }
                    }
                } finally {
                    try { extractor?.release() } catch (_: Exception) {}
                    try { pfd.close() } catch (_: Exception) {}
                }

                timelineOffsetUs += spec.durationUs
            }

            // ---------- 5. finalize ----------
            updateState(status = "Finalizing output...", progress = 0.99f)
            mux.stop()
            mux.release()
            muxer = null

            val outFile = File(outputPath)
            if (!outFile.exists() || outFile.length() == 0L) {
                return@withContext Result.Error("Output file তৈরি হয়নি")
            }

            val fileSize = outFile.length()
            val durationMs = timelineOffsetUs / 1000

            Log.i(
                TAG,
                "Merge OK: ${specs.size} inputs, $totalSamples samples, " +
                    "$fileSize bytes, ${durationMs}ms"
            )

            publishComplete(outputPath, durationMs, fileSize)
            onProgressUpdate?.invoke(1f)

            return@withContext Result.Success(outputPath, durationMs, fileSize)

        } catch (e: MergeCancelledException) {
            Log.i(TAG, "Merge cancelled")
            try { muxer?.release() } catch (_: Exception) {}
            try { File(outputPath).delete() } catch (_: Exception) {}
            return@withContext Result.Cancelled

        } catch (e: Exception) {
            Log.e(TAG, "Merge failed", e)
            try { muxer?.release() } catch (_: Exception) {}
            try { File(outputPath).delete() } catch (_: Exception) {}
            val msg = e.message ?: "Unknown error"
            publishError(msg)
            return@withContext Result.Error(msg)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun inspect(context: Context, uri: Uri): SourceSpec? {
        var extractor: MediaExtractor? = null
        var pfd: ParcelFileDescriptor? = null
        try {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd = descriptor

            val ex = MediaExtractor()
            extractor = ex
            ex.setDataSource(descriptor.fileDescriptor, 0, descriptor.statSize)

            var vFormat: MediaFormat? = null
            var aFormat: MediaFormat? = null
            var durationUs = 0L

            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && vFormat == null) {
                    vFormat = f
                    if (f.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = f.getLong(MediaFormat.KEY_DURATION)
                    }
                } else if (mime.startsWith("audio/") && aFormat == null) {
                    aFormat = f
                }
            }

            val video = vFormat ?: return null

            if (durationUs <= 0L) {
                durationUs = scanDurationUs(ex, findTrack(ex, "video/"))
            }

            return SourceSpec(uri, video, aFormat, durationUs)
        } catch (e: Exception) {
            Log.e(TAG, "inspect failed for $uri", e)
            return null
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    /** Fallback when the container has no duration: walk the track for the last PTS. */
    private fun scanDurationUs(extractor: MediaExtractor, trackIdx: Int): Long {
        if (trackIdx < 0) return 0L
        return try {
            extractor.selectTrack(trackIdx)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            var last = 0L
            var count = 0
            while (count < 5_000_000) {
                if (!extractor.advance()) break
                last = extractor.sampleTime
                count++
            }
            extractor.unselectTrack(trackIdx)
            last.coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Copies every sample of the currently selected track into [muxTrack],
     * shifting PTS by [offsetUs]. Returns the number of samples written.
     */
    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxTrack: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        offsetUs: Long,
        trackDurationUs: Long,
        onProgress: ((Long) -> Unit)?
    ): Long {
        var count = 0L
        var lastReportedUs = -1L

        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        while (true) {
            if (isCancelled) throw MergeCancelledException("Merge cancelled")

            while (isPaused) {
                if (isCancelled) throw MergeCancelledException("Merge cancelled")
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    throw MergeCancelledException("Merge cancelled")
                }
            }

            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break

            val sampleTimeUs = extractor.sampleTime
            if (sampleTimeUs < 0) {
                if (!extractor.advance()) break
                continue
            }

            info.offset = 0
            info.size = size
            info.presentationTimeUs = sampleTimeUs + offsetUs
            info.flags = extractor.sampleFlags

            muxer.writeSampleData(muxTrack, buffer, info)
            count++

            if (onProgress != null && trackDurationUs > 0) {
                if (sampleTimeUs - lastReportedUs >= 500_000L) {
                    lastReportedUs = sampleTimeUs
                    onProgress(sampleTimeUs)
                }
            }

            if (!extractor.advance()) break
        }

        return count
    }

    private fun updateState(
        isProcessing: Boolean? = null,
        progress: Float? = null,
        status: String? = null,
        isComplete: Boolean? = null,
        error: String? = null,
        outputFilePath: String? = null,
        outputDurationMs: Long? = null,
        outputFileSize: Long? = null
    ) {
        val current = _state.value
        _state.value = current.copy(
            isProcessing = isProcessing ?: current.isProcessing,
            progress = progress ?: current.progress,
            status = status ?: current.status,
            isComplete = isComplete ?: current.isComplete,
            error = error ?: current.error,
            outputFilePath = outputFilePath ?: current.outputFilePath,
            outputDurationMs = outputDurationMs ?: current.outputDurationMs,
            outputFileSize = outputFileSize ?: current.outputFileSize
        )
    }
}

/** Thrown internally when the user cancels a merge. */
class MergeCancelledException(message: String) : Exception(message)
