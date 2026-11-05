package com.ultravidmerger.app.merger

import android.content.Context
import android.media.MediaCodecInfo
import android.net.Uri
import android.util.Log
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Re-encoding merge path, used when inputs are NOT stream-compatible.
 *
 * Builds one Media3 [Composition] holding a single [EditedMediaItemSequence]
 * with every input video. Transformer decodes all of them in order, scales each
 * to the requested output size ([Presentation]), encodes once with a fixed
 * bitrate, and muxes everything into one MP4.
 */
object ReencodeMergeEngine {

    private const val TAG = "ReencodeMerge"

    @Volatile
    private var isCancelled = false

    @Volatile
    private var activeTransformer: Transformer? = null

    fun cancel() {
        isCancelled = true
        try {
            activeTransformer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "cancel failed", e)
        }
    }

    suspend fun merge(
        context: Context,
        videos: List<Uri>,
        outputPath: String,
        targetWidth: Int,
        targetHeight: Int,
        targetFps: Int,
        bitrate: Int = 0
    ): LosslessMergeEngine.Result = withContext(Dispatchers.IO) {
        isCancelled = false

        Log.i(TAG, "Re-encode merge: ${videos.size} inputs -> ${targetWidth}x${targetHeight}@${targetFps}")

        if (videos.isEmpty()) return@withContext LosslessMergeEngine.Result.Error("No videos selected")

        // Resolve durations up-front so Transformer never has to guess them.
        val durations = videos.map { uri ->
            VideoAnalyzer.analyzeVideo(context, uri)?.durationMs ?: 0L
        }
        val totalMs = durations.sum()

        val fps = targetFps.coerceIn(24, 60)
        LosslessMergeEngine.publishProgress(0.02f, "Preparing re-encode...")

        try {
            // ---------- build composition ----------
            val presentation: Effect = Presentation.createForWidthAndHeight(
                targetWidth,
                targetHeight,
                Presentation.LAYOUT_SCALE_TO_FIT
            )

            val items: List<EditedMediaItem> = videos.mapIndexed { index, uri ->
                val builder = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setEffects(Effects(listOf(), listOf(presentation)))

                val durationMs = durations[index]
                if (durationMs > 0) builder.setDurationUs(durationMs * 1000L)

                builder.build()
            }

            val sequence = EditedMediaItemSequence(items)
            val composition = Composition.Builder(listOf(sequence)).build()

            val computedBitrate =
                if (bitrate > 0) bitrate else estimateBitrate(targetWidth, targetHeight, fps)

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setBitrate(computedBitrate)
                        .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        .build()
                )
                .build()

            val deferred = CompletableDeferred<LosslessMergeEngine.Result>()

            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    Log.i(TAG, "Re-encode completed")

                    val file = File(outputPath)
                    if (!file.exists() || file.length() == 0L) {
                        deferred.complete(
                            LosslessMergeEngine.Result.Error("Output file was not created")
                        )
                        return
                    }

                    val size = file.length()
                    LosslessMergeEngine.publishComplete(outputPath, totalMs, size)
                    deferred.complete(
                        LosslessMergeEngine.Result.Success(outputPath, totalMs, size)
                    )
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    Log.e(TAG, "Re-encode failed", exportException)
                    val msg = exportException.message ?: "Re-encode failed"
                    LosslessMergeEngine.publishError(msg)
                    deferred.complete(LosslessMergeEngine.Result.Error(msg))
                }
            }

            val transformer = Transformer.Builder(context)
                .addListener(listener)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .build()

            activeTransformer = transformer

            LosslessMergeEngine.publishProgress(0.05f, "Transcoding & merging...")
            transformer.start(composition, outputPath)

            // ---------- real progress polling (background thread) ----------
            val polling = java.util.concurrent.atomic.AtomicBoolean(true)
            val pollThread = Thread {
                val holder = ProgressHolder()
                while (polling.get() && !isCancelled) {
                    try {
                        Thread.sleep(500)
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val p = (holder.progress / 100f).coerceIn(0.05f, 0.99f)
                            LosslessMergeEngine.publishProgress(p, "Transcoding & merging...")
                        }
                    } catch (_: InterruptedException) {
                        break
                    } catch (_: Exception) {
                        break
                    }
                }
            }.apply { isDaemon = true; start() }

            val result = deferred.await()
            polling.set(false)
            return@withContext result

        } catch (e: Exception) {
            Log.e(TAG, "Re-encode exception", e)
            LosslessMergeEngine.publishError(e.message ?: "Unknown error")
            try { File(outputPath).delete() } catch (_: Exception) {}
            return@withContext LosslessMergeEngine.Result.Error(e.message ?: "Unknown error")
        } finally {
            activeTransformer = null
        }
    }

    private fun estimateBitrate(width: Int, height: Int, fps: Int): Int {
        val pixels = width.toLong() * height.toLong()
        val bits = (pixels * fps * 0.11).toLong()
        return bits.coerceIn(1_000_000L, 60_000_000L).toInt()
    }
}
