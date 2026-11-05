package com.ultravidmerger.app.engine

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.ReturnCode
import com.ultravidmerger.app.diag.Component
import com.ultravidmerger.app.diag.ComponentStatus
import com.ultravidmerger.app.diag.DiagnosticLogger
import com.ultravidmerger.app.diag.EngineMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * MODE A — lossless merge via FFmpeg's concat demuxer with `-c copy`.
 *
 * No decoding, no encoding. The compressed packets are copied verbatim, so the
 * output keeps the exact bitstream, resolution, frame rate, bitrate, audio codec,
 * sample rate and channel layout of the inputs.
 *
 * SAF content:// URIs are handled through FFmpegKit's `saf:` pseudo-protocol, so
 * nothing is copied to local storage — critical for multi-gigabyte inputs.
 */
object FFmpegEngine {

    private const val TAG = "FFmpegEngine"

    data class Result(
        val success: Boolean,
        val cancelled: Boolean,
        val exitCode: Int,
        val command: String,
        val outputLog: String,
        val elapsedMs: Long,
        val outputBytes: Long
    )

    @Volatile private var activeSession: FFmpegSession? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        runCatching { activeSession?.let { FFmpegKit.cancel(it.sessionId) } }
        runCatching { FFmpegKit.cancel() }
    }

    /**
     * @param totalDurationMs sum of input durations; drives the progress bar.
     */
    suspend fun mergeLossless(
        context: Context,
        uris: List<Uri>,
        outputPath: String,
        totalDurationMs: Long,
        onProgress: (Float) -> Unit
    ): Result = withContext(Dispatchers.IO) {

        cancelled = false
        val startedAt = System.currentTimeMillis()

        if (uris.isEmpty()) {
            return@withContext Result(false, false, -1, "", "No inputs", 0, 0)
        }

        EngineMonitor.set(Component.FFMPEG, ComponentStatus.BUSY, "Building concat list")
        EngineMonitor.set(Component.MEDIA_MUXER, ComponentStatus.BUSY, "mp4 muxer (ffmpeg)")

        // ---- 1. Build the concat demuxer list using SAF parameters ----
        val listFile = File(context.cacheDir, "concat_${System.currentTimeMillis()}.txt")
        try {
            val sb = StringBuilder()
            for (uri in uris) {
                val saf = FFmpegKitConfig.getSafParameterForRead(context, uri)
                // Paths are single-quoted by the concat demuxer spec.
                sb.append("file '").append(saf.replace("'", "'\\''")).append("'\n")
            }
            listFile.writeText(sb.toString())
        } catch (e: Exception) {
            DiagnosticLogger.error(
                com.ultravidmerger.app.diag.ErrorCode.CORRUPTED_INPUT, TAG,
                "Could not build concat list: ${e.message}", e
            )
            return@withContext Result(false, false, -1, "", e.message ?: "list error", 0, 0)
        }

        DiagnosticLogger.info(TAG, "Concat list:\n${listFile.readText()}", EngineMonitor.currentJobId.value)

        // ---- 2. Command ----
        //  -safe 0        : allow the saf: pseudo-paths
        //  -c copy        : THE lossless guarantee
        //  -map 0:v? -map 0:a?
        //                 : keep video + first audio, tolerate missing audio
        //  -movflags +faststart
        //                 : move moov to the front (playable while copying)
        //  -avoid_negative_ts make_zero
        //                 : normalise start timestamps across segments
        //  -fflags +genpts
        //                 : regenerate missing PTS instead of duping frames
        val args = arrayOf(
            "-y",
            "-hide_banner",
            "-loglevel", "info",
            "-f", "concat",
            "-safe", "0",
            "-fflags", "+genpts",
            "-i", listFile.absolutePath,
            "-c", "copy",
            "-map", "0:v?",
            "-map", "0:a?",
            "-avoid_negative_ts", "make_zero",
            "-movflags", "+faststart",
            outputPath
        )

        val commandLine = "ffmpeg " + args.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }
        DiagnosticLogger.info(TAG, "Executing: $commandLine", EngineMonitor.currentJobId.value)

        val logBuffer = Collections.synchronizedList(ArrayList<String>())
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_INFO)

        val deferred = CompletableDeferred<FFmpegSession>()

        val session = FFmpegKit.executeWithArgumentsAsync(
            args,
            { completed -> deferred.complete(completed) },
            { log ->
                val line = log.message ?: ""
                if (line.isNotBlank()) {
                    logBuffer.add(line)
                    if (logBuffer.size > 4000) logBuffer.removeAt(0) // bounded
                }
                // Surface meaningful lines into the diagnostic stream
                val lower = line.lowercase()
                if (lower.contains("error") || lower.contains("failed") ||
                    lower.contains("invalid") || lower.contains("corrupt")
                ) {
                    DiagnosticLogger.warn("FFmpeg", line)
                }
            },
            { stats ->
                if (totalDurationMs > 0) {
                    val progress = (stats.time / totalDurationMs.toDouble())
                        .coerceIn(0.0, 0.99)
                        .toFloat()
                    onProgress(progress)
                    EngineMonitor.set(
                        Component.FFMPEG, ComponentStatus.BUSY,
                        "${(progress * 100).toInt()}%  ${stats.videoFrameNumber} frames  ${stats.videoFps} fps"
                    )
                }
            }
        )

        activeSession = session

        if (cancelled) {
            runCatching { FFmpegKit.cancel(session.sessionId) }
        }

        val completed = deferred.await()
        activeSession = null
        val returnCode = completed.returnCode
        val code = returnCode?.value ?: -1
        val elapsed = System.currentTimeMillis() - startedAt
        val log = logBuffer.joinToString("\n")

        try { listFile.delete() } catch (_: Exception) {}

        val isCancel = ReturnCode.isCancel(returnCode) || cancelled
        val isSuccess = ReturnCode.isSuccess(returnCode)

        val outBytes = File(outputPath).takeIf { it.exists() }?.length() ?: 0L

        if (isSuccess && outBytes > 0) {
            EngineMonitor.set(Component.FFMPEG, ComponentStatus.OK, "copy OK (${elapsed}ms)")
            EngineMonitor.set(Component.MEDIA_MUXER, ComponentStatus.OK, "moov written")
            EngineMonitor.markSuccess("FFmpeg concat copy → ${outBytes / (1024 * 1024)} MB")
            DiagnosticLogger.info(TAG, "FFmpeg success in ${elapsed}ms, $outBytes bytes")
        } else {
            EngineMonitor.set(
                Component.FFMPEG, if (isCancel) ComponentStatus.IDLE else ComponentStatus.ERROR,
                "exit=$code"
            )
            DiagnosticLogger.warn(
                TAG,
                "FFmpeg finished exit=$code cancel=$isCancel outputBytes=$outBytes"
            )
        }

        Result(
            success = isSuccess && outBytes > 0,
            cancelled = isCancel,
            exitCode = code,
            command = commandLine,
            outputLog = log,
            elapsedMs = elapsed,
            outputBytes = outBytes
        )
    }

    /** Verifies the produced file actually contains readable streams. */
    suspend fun validate(context: Context, path: String): VideoValidation =
        withContext(Dispatchers.IO) {
            val f = File(path)
            if (!f.exists() || f.length() < 1024) {
                return@withContext VideoValidation(false, 0L, 0, 0, 0L, "file missing or too small")
            }
            val info = VideoAnalyzer.analyze(context, Uri.fromFile(f))
                ?: return@withContext VideoValidation(false, 0L, 0, 0, f.length(), "file not demuxable")

            val ok = info.width > 0 && info.height > 0 && info.durationMs > 0
            VideoValidation(
                valid = ok,
                durationMs = info.durationMs,
                width = info.width,
                height = info.height,
                fileBytes = f.length(),
                reason = if (ok) "ok" else "streams unreadable"
            )
        }

    data class VideoValidation(
        val valid: Boolean,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val fileBytes: Long,
        val reason: String
    )
}
