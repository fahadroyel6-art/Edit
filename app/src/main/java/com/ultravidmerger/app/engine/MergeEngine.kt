package com.ultravidmerger.app.engine

import android.content.Context
import android.net.Uri
import com.ultravidmerger.app.VideoInfo
import com.ultravidmerger.app.diag.Component
import com.ultravidmerger.app.diag.ComponentStatus
import com.ultravidmerger.app.diag.DiagnosticLogger
import com.ultravidmerger.app.diag.Diagnostics
import com.ultravidmerger.app.diag.EngineMonitor
import com.ultravidmerger.app.diag.ErrorCode
import com.ultravidmerger.app.diag.SystemMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single entry point for every merge. Owns the mode decision, the engine
 * selection, the fallback chain and the output validation.
 *
 * Only one job may run at a time — [running] is the guard (Module 4, §4:
 * "Prevent simultaneous jobs from accessing the same engine instance").
 */
object MergeEngine {

    private const val TAG = "MergeEngine"

    private val running = AtomicBoolean(false)

    @Volatile private var cancelRequested = false

    data class Request(
        val videos: List<VideoInfo>,
        val outputPath: String,
        val report: CompatibilityReport
    )

    sealed class Outcome {
        data class Success(
            val outputPath: String,
            val durationMs: Long,
            val fileBytes: Long,
            val engineUsed: String,
            val lossless: Boolean
        ) : Outcome()

        data class Failure(
            val code: ErrorCode,
            val message: String,
            val report: String
        ) : Outcome()

        data object Cancelled : Outcome()
    }

    fun isRunning(): Boolean = running.get()

    fun cancel() {
        cancelRequested = true
        FFmpegEngine.cancel()
        MediaMuxerEngine.cancel()
        ReencodeEngine.cancel()
        DiagnosticLogger.warn(TAG, "Cancel requested")
    }

    suspend fun execute(
        context: Context,
        request: Request,
        onProgress: (Float, String) -> Unit
    ): Outcome = withContext(Dispatchers.IO) {

        if (!running.compareAndSet(false, true)) {
            val ctx = Diagnostics.report(
                ErrorCode.JOB_ALREADY_RUNNING, "engine",
                "A merge is already running; refusing to start a second one."
            )
            return@withContext Outcome.Failure(
                ErrorCode.JOB_ALREADY_RUNNING,
                "আগের কাজ এখনো চলছে।",
                com.ultravidmerger.app.diag.ErrorReportGenerator.build(ctx)
            )
        }

        cancelRequested = false
        SystemMonitor.refreshOnce()

        val jobId = EngineMonitor.currentJobId.value ?: Diagnostics.newJobId()

        try {
            // ---------- storage pre-flight ----------
            val needed = request.report.totalInputBytes
            val snap = SystemMonitor.snapshot.value
            val free = snap.storageFreeBytes

            // copy mode ≈ input size; re-encode can be smaller, but be conservative
            if (free in 1..(needed + needed / 4)) {
                val ctx = Diagnostics.report(
                    ErrorCode.INSUFFICIENT_STORAGE, "storage",
                    "Need ≈${VideoInfo.formatBytes(needed)} free, only " +
                        "${VideoInfo.formatBytes(free)} available.",
                    extra = mapOf(
                        "requiredBytes" to needed.toString(),
                        "freeBytes" to free.toString()
                    )
                )
                return@withContext Outcome.Failure(
                    ErrorCode.INSUFFICIENT_STORAGE,
                    "যথেষ্ট জায়গা নেই। দরকার ≈${VideoInfo.formatBytes(needed)}, আছে ${VideoInfo.formatBytes(free)}",
                    com.ultravidmerger.app.diag.ErrorReportGenerator.build(ctx)
                )
            }

            // ---------- thermal advisory ----------
            Diagnostics.thermalWarning()?.let {
                DiagnosticLogger.warn(TAG, "Thermal: $it")
                EngineMonitor.set(Component.THERMAL, ComponentStatus.WARN, snap.thermalLabel)
            }

            val outFile = File(request.outputPath)
            outFile.parentFile?.mkdirs()
            runCatching { outFile.delete() }

            var engineUsed = ""
            var lossless = false

            if (request.report.canMergeLossless) {
                // ============ MODE A ============
                EngineMonitor.setStage("Lossless stream copy (FFmpeg concat)")
                onProgress(0.01f, "Lossless merge শুরু হচ্ছে…")

                val ff = FFmpegEngine.mergeLossless(
                    context,
                    request.videos.map { it.uri },
                    request.outputPath,
                    request.report.totalDurationMs
                ) { p -> onProgress(p, "Lossless copy… ${(p * 100).toInt()}%") }

                if (cancelRequested || ff.cancelled) return@withContext Outcome.Cancelled

                if (ff.success) {
                    engineUsed = "FFmpeg concat -c copy"
                    lossless = true
                } else {
                    DiagnosticLogger.warn(
                        TAG,
                        "FFmpeg path failed (exit=${ff.exitCode}) — trying MediaMuxer fallback"
                    )
                    onProgress(0.05f, "FFmpeg ব্যর্থ — Muxer fallback…")

                    val fb = MediaMuxerEngine.merge(
                        context,
                        request.videos.map { it.uri },
                        request.outputPath
                    ) { p -> onProgress(p, "Muxer copy… ${(p * 100).toInt()}%") }

                    if (cancelRequested || fb.cancelled) return@withContext Outcome.Cancelled

                    if (!fb.success) {
                        val ctx = Diagnostics.report(
                            ErrorCode.FFMPEG_FAILURE, "ffmpeg",
                            "Both lossless engines failed. FFmpeg exit=${ff.exitCode}; " +
                                "MediaMuxer: ${fb.error}",
                            jobId = jobId,
                            ffmpegCommand = ff.command,
                            ffmpegExitCode = ff.exitCode,
                            ffmpegLog = ff.outputLog,
                            extra = mapOf(
                                "inputCount" to request.videos.size.toString(),
                                "totalDurationMs" to request.report.totalDurationMs.toString()
                            )
                        )
                        runCatching { outFile.delete() }
                        return@withContext Outcome.Failure(
                            ErrorCode.FFMPEG_FAILURE,
                            "Lossless merge ব্যর্থ: ${fb.error ?: "FFmpeg exit ${ff.exitCode}"}",
                            com.ultravidmerger.app.diag.ErrorReportGenerator.build(ctx)
                        )
                    }
                    engineUsed = "MediaMuxer (fallback)"
                    lossless = true
                }

            } else {
                // ============ MODE B ============
                EngineMonitor.setStage("Compatibility merge (re-encode)")
                onProgress(0.02f, "Re-encode merge শুরু হচ্ছে…")

                val rr = ReencodeEngine.merge(
                    context,
                    request.videos.map { ReencodeEngine.GpuInput(it.uri, it.durationMs) },
                    request.outputPath,
                    request.report.recommendedWidth,
                    request.report.recommendedHeight,
                    request.report.recommendedFps
                ) { p -> onProgress(p, "Re-encoding… ${(p * 100).toInt()}%") }

                if (cancelRequested || rr.cancelled) return@withContext Outcome.Cancelled

                if (!rr.success) {
                    val ctx = Diagnostics.report(
                        ErrorCode.VIDEO_EXPORT_FAILED, "render",
                        "Re-encode merge failed: ${rr.error}",
                        jobId = jobId,
                        extra = mapOf(
                            "incompatibilities" to request.report.issues.joinToString { "${it.field}: ${it.detail}" },
                            "target" to "${request.report.recommendedWidth}x${request.report.recommendedHeight}"
                        )
                    )
                    runCatching { outFile.delete() }
                    return@withContext Outcome.Failure(
                        ErrorCode.VIDEO_EXPORT_FAILED,
                        "Re-encode ব্যর্থ: ${rr.error}",
                        com.ultravidmerger.app.diag.ErrorReportGenerator.build(ctx)
                    )
                }
                engineUsed = "Media3 Transformer (re-encode)"
                lossless = false
            }

            if (cancelRequested) return@withContext Outcome.Cancelled

            // ---------- validation before declaring success ----------
            onProgress(0.985f, "Output যাচাই হচ্ছে…")
            EngineMonitor.setStage("Validating output")

            val validation = FFmpegEngine.validate(context, request.outputPath)
            if (!validation.valid) {
                val ctx = Diagnostics.report(
                    ErrorCode.OUTPUT_FILE_CORRUPTED, "validate",
                    "Output validation failed: ${validation.reason}",
                    jobId = jobId,
                    extra = mapOf(
                        "outputBytes" to validation.fileBytes.toString(),
                        "durationMs" to validation.durationMs.toString()
                    )
                )
                runCatching { outFile.delete() }
                return@withContext Outcome.Failure(
                    ErrorCode.OUTPUT_FILE_CORRUPTED,
                    "আউটপুট ফাইল যাচাইয়ে ব্যর্থ",
                    com.ultravidmerger.app.diag.ErrorReportGenerator.build(ctx)
                )
            }

            // Duration sanity: must be within 5% of the sum of inputs
            val expected = request.report.totalDurationMs
            if (expected > 5000 && validation.durationMs > 0) {
                val delta = kotlin.math.abs(validation.durationMs - expected)
                if (delta > expected * 0.05) {
                    DiagnosticLogger.warn(
                        TAG,
                        "Duration mismatch: expected ${expected}ms, got ${validation.durationMs}ms"
                    )
                }
            }

            EngineMonitor.set(Component.STORAGE_MANAGER, ComponentStatus.OK, "output ready")
            onProgress(1f, "সম্পন্ন")

            Outcome.Success(
                outputPath = request.outputPath,
                durationMs = if (validation.durationMs > 0) validation.durationMs else expected,
                fileBytes = validation.fileBytes,
                engineUsed = engineUsed,
                lossless = lossless
            )

        } catch (e: Throwable) {
            val code = when {
                e is OutOfMemoryError -> ErrorCode.MEMORY_LIMIT_EXCEEDED
                e.message?.contains("thread", true) == true -> ErrorCode.TRANSFORMER_THREAD_ERROR
                else -> ErrorCode.UNKNOWN
            }
            val ctx = Diagnostics.report(code, "engine", e.message ?: "Unhandled", e, jobId)
            runCatching { File(request.outputPath).delete() }
            Outcome.Failure(code, e.message ?: "Unknown", com.ultravidmerger.app.diag.ErrorReportGenerator.build(ctx))
        } finally {
            running.set(false)
        }
    }
}
