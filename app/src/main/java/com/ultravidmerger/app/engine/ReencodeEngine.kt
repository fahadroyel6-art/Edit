package com.ultravidmerger.app.engine

import android.content.Context
import android.media.MediaCodecInfo
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.ultravidmerger.app.diag.Component
import com.ultravidmerger.app.diag.ComponentStatus
import com.ultravidmerger.app.diag.DiagnosticLogger
import com.ultravidmerger.app.diag.EngineMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MODE B — compatibility merge. Re-encodes only when the inputs genuinely cannot
 * be stream-copied.
 *
 * Every single Media3 call (build, start, cancel) and every callback runs on
 * [TransformerHost]'s looper, which is what eliminates the
 * "Transformer is accessed on the wrong thread" crash reported earlier.
 */
object ReencodeEngine {

    private const val TAG = "ReencodeEngine"

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        TransformerHost.post {
            runCatching { active?.cancel() }
        }
    }

    @Volatile private var active: Transformer? = null

    data class Result(
        val success: Boolean,
        val cancelled: Boolean,
        val durationMs: Long,
        val outputBytes: Long,
        val error: String? = null
    )

    suspend fun merge(
        context: Context,
        videos: List<GpuInput>,
        outputPath: String,
        targetWidth: Int,
        targetHeight: Int,
        targetFps: Int,
        onProgress: (Float) -> Unit
    ): Result = withContext(Dispatchers.IO) {

        cancelled = false
        if (videos.isEmpty()) return@withContext Result(false, false, 0, 0, "No inputs")

        EngineMonitor.set(Component.FFMPEG, ComponentStatus.IDLE, "not used (re-encode)")
        EngineMonitor.setStage("Re-encoding ${videos.size} inputs → ${targetWidth}x$targetHeight@$targetFps")

        val totalMs = videos.sumOf { it.durationMs }
        val fps = targetFps.coerceIn(24, 60)
        val bitrate = estimateBitrate(targetWidth, targetHeight, fps)

        val deferred = CompletableDeferred<Result>()

        try {
            TransformerHost.sync {
                // -------- build composition on the owning looper --------
                val presentation: Effect = Presentation.createForWidthAndHeight(
                    targetWidth, targetHeight, Presentation.LAYOUT_SCALE_TO_FIT
                )

                val items: List<EditedMediaItem> = videos.map { v ->
                    val b = EditedMediaItem.Builder(MediaItem.fromUri(v.uri))
                        .setEffects(Effects(listOf(), listOf(presentation)))
                    if (v.durationMs > 0) b.setDurationUs(v.durationMs * 1000L)
                    b.build()
                }

                val composition = Composition.Builder(
                    listOf(EditedMediaItemSequence(items))
                ).build()

                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(bitrate)
                            .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                            .build()
                    )
                    .build()

                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        val f = File(outputPath)
                        val ok = f.exists() && f.length() > 0
                        EngineMonitor.set(Component.FFMPEG, ComponentStatus.OK, "re-encode done")
                        EngineMonitor.markSuccess("Re-encoded merge → ${f.length() / (1024 * 1024)} MB")
                        DiagnosticLogger.info(TAG, "Re-encode completed, ${f.length()} bytes")
                        deferred.complete(
                            Result(ok, false, totalMs, f.length(),
                                if (ok) null else "Output not created")
                        )
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        val msg = exportException.message ?: "Re-encode failed"
                        EngineMonitor.set(Component.FFMPEG, ComponentStatus.ERROR, msg)
                        DiagnosticLogger.error(
                            com.ultravidmerger.app.diag.ErrorCode.VIDEO_EXPORT_FAILED,
                            TAG, "Re-encode failed: $msg", exportException
                        )
                        deferred.complete(Result(false, false, 0, 0, msg))
                    }
                }

                val transformer = Transformer.Builder(context)
                    .addListener(listener)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .build()

                active = transformer

                transformer.start(composition, outputPath)
                DiagnosticLogger.info(TAG, "Transformer.start() on tid=${Thread.currentThread().id}")
            }

            // -------- progress polling on the same looper --------
            val holder = ProgressHolder()
            var polls = 0
            while (!deferred.isCompleted) {
                kotlinx.coroutines.delay(500)
                if (cancelled) {
                    cancel()
                    return@withContext Result(false, true, 0, 0, "cancelled")
                }
                runCatching {
                    TransformerHost.post {
                        val state = active?.getProgress(holder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val p = (holder.progress / 100f).coerceIn(0.02f, 0.985f)
                            onProgress(p)
                            EngineMonitor.set(
                                Component.FFMPEG, ComponentStatus.BUSY,
                                "re-encode ${(p * 100).toInt()}%"
                            )
                        }
                    }
                }
                if (++polls > 8 * 60 * 60 * 4) break // ~8h hard stop, never a real limit
            }

            val result = deferred.await()
            active = null
            result

        } catch (e: Exception) {
            active = null
            DiagnosticLogger.error(
                com.ultravidmerger.app.diag.ErrorCode.VIDEO_EXPORT_FAILED, TAG,
                "Re-encode exception: ${e.message}", e
            )
            runCatching { File(outputPath).delete() }
            Result(false, false, 0, 0, e.message ?: "unknown")
        }
    }

    data class GpuInput(val uri: Uri, val durationMs: Long)

    private fun estimateBitrate(w: Int, h: Int, fps: Int): Int {
        val pixels = w.toLong() * h.toLong()
        val bits = (pixels * fps * 0.11).toLong()
        return bits.coerceIn(1_000_000L, 60_000_000L).toInt()
    }
}
