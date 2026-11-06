package com.ultravidmerger.app.engine

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.ultravidmerger.app.diag.Component
import com.ultravidmerger.app.diag.ComponentStatus
import com.ultravidmerger.app.diag.DiagnosticLogger
import com.ultravidmerger.app.diag.EngineMonitor
import com.ultravidmerger.app.diag.SystemMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque

/**
 * MODE A — lossless merge via FFmpeg's concat demuxer with `-c copy`.
 *
 * v3 rewrite. Every change here is tied to a defect found in the v2 code:
 *
 *  RC-1  no more per-line logging work. Log volume is capped with a ring
 *        buffer (O(1) eviction), duplicate lines are collapsed, and nothing is
 *        pushed into the diagnostic stream from inside the callback.
 *  RC-2  `-movflags +faststart` is only applied to small outputs. For a
 *        multi-gigabyte movie it forces a full second pass over the file with
 *        zero progress feedback — indistinguishable from a hang.
 *  RC-3  input paths are `/proc/self/fd/N` (see [InputFiles]).
 *  RC-4  `-fflags +genpts` removed; `-avoid_negative_ts make_zero` is enough.
 *  RC-5  explicit `0:v:0?` / `0:a:0?` so a movie with multiple audio tracks
 *        does not break concat.
 *  RC-6  real progress from `-progress <file>` (`out_time_us`), a stall
 *        watchdog, and free-space monitoring during the run.
 */
object FFmpegEngine {

    private const val TAG = "FFmpegEngine"

    /** Outputs above this size skip `+faststart` (it rewrites the whole file). */
    private const val FASTSTART_MAX_BYTES = 384L * 1024 * 1024

    /** No progress for this long → surface a warning. */
    private const val STALL_WARN_MS = 60_000L

    /** No progress for this long → abort with a diagnostic. */
    private const val STALL_ABORT_MS = 360_000L

    /** Abort if free space drops below this. */
    private const val MIN_FREE_BYTES = 192L * 1024 * 1024

    data class Result(
        val success: Boolean,
        val cancelled: Boolean,
        val stalled: Boolean,
        val exitCode: Int,
        val command: String,
        val outputLog: String,
        val elapsedMs: Long,
        val outputBytes: Long,
        val lastOutTimeUs: Long,
        val stallSeconds: Long
    )

    @Volatile private var activeSession: FFmpegSession? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        runCatching { activeSession?.let { FFmpegKit.cancel(it.sessionId) } }
        runCatching { FFmpegKit.cancel() }
    }

    // ---------------------------------------------------------------- progress

    private data class Progress(
        val outTimeUs: Long = -1L,
        val sizeBytes: Long = 0L,
        val speed: String = "",
        val ended: Boolean = false
    )

    /** Parses FFmpeg's `-progress` key=value file. Tolerates torn lines. */
    private fun readProgress(file: File): Progress {
        if (!file.exists()) return Progress()
        var outUs = -1L
        var size = 0L
        var speed = ""
        var ended = false

        runCatching {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachLine
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEachLine
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                when (key) {
                    "out_time_us" -> value.toLongOrNull()?.let { outUs = it }
                    // `out_time_ms` is microseconds in FFmpeg despite the name;
                    // only trust it when out_time_us is absent.
                    "out_time_ms" -> if (outUs < 0) value.toLongOrNull()?.let { outUs = it }
                    "out_time" -> if (outUs < 0) outUs = parseClock(value)
                    "total_size" -> value.toLongOrNull()?.let { size = it }
                    "speed" -> speed = value
                    "progress" -> if (value == "end") ended = true
                }
            }
        }
        return Progress(outUs, size, speed, ended)
    }

    /** Parses `HH:MM:SS.microseconds` → microseconds. */
    private fun parseClock(v: String): Long {
        return runCatching {
            val parts = v.split(':')
            if (parts.size != 3) return -1L
            val h = parts[0].toLongOrNull() ?: return -1L
            val m = parts[1].toLongOrNull() ?: return -1L
            val sec = parts[2].toDoubleOrNull() ?: return -1L
            // 64-bit throughout — a 3-hour movie is 10_800_000_000 µs
            ((h * 3600L + m * 60L) * 1_000_000L) + (sec * 1_000_000.0).toLong()
        }.getOrDefault(-1L)
    }

    // ----------------------------------------------------------------- merge

    /**
     * @param inputPaths `/proc/self/fd/N` strings from [InputFiles]
     * @param totalDurationMs sum of input durations, used only for the ratio
     */
    suspend fun mergeLossless(
        context: Context,
        inputPaths: List<String>,
        outputPath: String,
        totalDurationMs: Long,
        estimatedOutputBytes: Long,
        onProgress: (Float, String) -> Unit
    ): Result = withContext(Dispatchers.IO) {

        cancelled = false
        val startedAt = System.currentTimeMillis()

        if (inputPaths.isEmpty()) {
            return@withContext Result(
                false, false, false, -1, "", "No inputs", 0, 0, 0, 0
            )
        }

        EngineMonitor.set(Component.FFMPEG, ComponentStatus.BUSY, "Building concat list")
        EngineMonitor.set(Component.MEDIA_MUXER, ComponentStatus.BUSY, "mp4 muxer (ffmpeg)")

        val stamp = System.currentTimeMillis()
        val listFile = File(context.cacheDir, "concat_$stamp.txt")
        val progressFile = File(context.cacheDir, "progress_$stamp.txt")
        runCatching { progressFile.delete() }

        try {
            // ---- concat list: single-quoted /proc/self/fd paths ----
            listFile.writeText(
                buildString {
                    for (p in inputPaths) {
                        append("file '").append(p.replace("'", "'\\''")).append("'\n")
                    }
                }
            )
        } catch (e: Exception) {
            return@withContext Result(
                false, false, false, -1, "", "concat list failed: ${e.message}", 0, 0, 0, 0
            )
        }

        // ---- faststart only for outputs small enough to rewrite cheaply ----
        val useFaststart = estimatedOutputBytes in 1..FASTSTART_MAX_BYTES

        val args = buildList {
            add("-y"); add("-hide_banner")
            add("-loglevel"); add("warning")     // RC-1: huge log-volume cut
            add("-nostats")
            add("-progress"); add(progressFile.absolutePath)
            add("-f"); add("concat")
            add("-safe"); add("0")
            add("-i"); add(listFile.absolutePath)
            add("-map"); add("0:v:0?")           // RC-5: first video only
            add("-map"); add("0:a:0?")           // RC-5: first audio only
            add("-c"); add("copy")               // ← the lossless guarantee
            add("-map_metadata"); add("0")
            add("-avoid_negative_ts"); add("make_zero")
            add("-max_muxing_queue_size"); add("4096")
            if (useFaststart) { add("-movflags"); add("+faststart") }
            add("-f"); add("mp4")
            add(outputPath)
        }.toTypedArray()

        val commandLine = "ffmpeg " + args.joinToString(" ")
        DiagnosticLogger.info(
            TAG,
            "Executing: $commandLine | inputs=${inputPaths.size} " +
                "totalMs=$totalDurationMs est=${estimatedOutputBytes / (1024 * 1024)}MB " +
                "faststart=$useFaststart",
            EngineMonitor.currentJobId.value
        )

        // Bounded ring buffer — O(1) eviction, no arraycopy storm (RC-1).
        val logRing = ArrayDeque<String>(256)
        var suppressedLines = 0

        val deferred = CompletableDeferred<FFmpegSession>()

        val session = FFmpegKit.executeWithArgumentsAsync(
            args,
            { completed -> deferred.complete(completed) },
            { log ->
                val line = log.message ?: ""
                if (line.isNotBlank()) {
                    if (logRing.size >= 256) logRing.removeFirst()
                    logRing.addLast(line)
                    // Deliberately NO DiagnosticLogger call here: this callback
                    // shares its executor with the statistics callback (RC-1).
                    suppressedLines++
                }
            },
            null // no statistics callback — progress comes from the -progress file
        )

        activeSession = session
        if (cancelled) runCatching { FFmpegKit.cancel(session.sessionId) }

        // ---------- progress / watchdog loop ----------
        var lastOutUs = -1L
        var lastAdvanceAt = System.currentTimeMillis()
        var stallWarned = false
        var stallAbort = false
        var lastFreeCheck = 0L
        var storageAbort = false
        var lastEmittedPct = -1

        while (!deferred.isCompleted) {
            delay(400L)

            if (cancelled) {
                runCatching { FFmpegKit.cancel(session.sessionId) }
                break
            }

            val p = readProgress(progressFile)

            if (p.outTimeUs > lastOutUs) {
                lastOutUs = p.outTimeUs
                lastAdvanceAt = System.currentTimeMillis()
                if (stallWarned) {
                    stallWarned = false
                    DiagnosticLogger.info(TAG, "Progress resumed after stall")
                }
            }

            // progress ratio — clamp below 1.0 until the file is validated
            if (totalDurationMs > 0 && lastOutUs > 0) {
                val ratio = (lastOutUs / (totalDurationMs * 1000.0)).coerceIn(0.0, 0.995)
                val pct = (ratio * 100).toInt()
                if (pct != lastEmittedPct) {
                    lastEmittedPct = pct
                    val speed = if (p.speed.isNotBlank()) " · ${p.speed}" else ""
                    onProgress(ratio.toFloat(), "Lossless copy… $pct%$speed")
                    EngineMonitor.set(
                        Component.FFMPEG, ComponentStatus.BUSY,
                        "$pct%  out=${lastOutUs / 1_000_000}s${speed}"
                    )
                }
            } else if (totalDurationMs <= 0 && p.sizeBytes > 0) {
                // No duration known: report bytes written as an indeterminate hint
                onProgress(0f, "Lossless copy… ${p.sizeBytes / (1024 * 1024)} MB")
            }

            // ---- stall detection (RC-6) ----
            val stalledFor = System.currentTimeMillis() - lastAdvanceAt
            if (!p.ended && stalledFor > STALL_WARN_MS && !stallWarned) {
                stallWarned = true
                DiagnosticLogger.warn(
                    TAG,
                    "No progress for ${stalledFor / 1000}s " +
                        "(out=${lastOutUs / 1_000_000}s, written=${p.sizeBytes / (1024 * 1024)}MB)"
                )
                onProgress(
                    (lastOutUs / (totalDurationMs * 1000.0).coerceAtLeast(1.0)).toFloat(),
                    "ধীর গতি — ${stalledFor / 1000}s ধরে অগ্রগতি নেই…"
                )
            }
            if (!p.ended && stalledFor > STALL_ABORT_MS) {
                stallAbort = true
                DiagnosticLogger.error(
                    com.ultravidmerger.app.diag.ErrorCode.LONG_VIDEO_TIMEOUT, TAG,
                    "Aborting: no progress for ${stalledFor / 1000}s. lastOutTimeUs=$lastOutUs"
                )
                runCatching { FFmpegKit.cancel(session.sessionId) }
                break
            }

            // ---- free-space guard during the run ----
            val now = System.currentTimeMillis()
            if (now - lastFreeCheck > 5000L) {
                lastFreeCheck = now
                SystemMonitor.refreshOnce()
                val free = SystemMonitor.snapshot.value.storageFreeBytes
                if (free in 1..MIN_FREE_BYTES) {
                    storageAbort = true
                    DiagnosticLogger.error(
                        com.ultravidmerger.app.diag.ErrorCode.INSUFFICIENT_STORAGE, TAG,
                        "Aborting: only ${free / (1024 * 1024)}MB free during merge"
                    )
                    runCatching { FFmpegKit.cancel(session.sessionId) }
                    break
                }
            }
        }

        val completed = deferred.await()
        activeSession = null

        val returnCode = completed.returnCode
        val code = returnCode?.value ?: -1
        val elapsed = System.currentTimeMillis() - startedAt
        val log = logRing.joinToString("\n")
        val outBytes = File(outputPath).takeIf { it.exists() }?.length() ?: 0L

        runCatching { listFile.delete() }
        runCatching { progressFile.delete() }

        val isCancel = ReturnCode.isCancel(returnCode) || (cancelled && stallAbort.not() && storageAbort.not())
        val isSuccess = ReturnCode.isSuccess(returnCode)

        DiagnosticLogger.info(
            TAG,
            "FFmpeg finished: exit=$code success=$isSuccess cancel=$isCancel " +
                "stall=$stallAbort storageAbort=$storageAbort elapsed=${elapsed}ms " +
                "bytes=$outBytes lastOutUs=$lastOutUs logLines=$suppressedLines"
        )

        if (isSuccess && outBytes > 0) {
            EngineMonitor.set(Component.FFMPEG, ComponentStatus.OK, "copy OK (${elapsed}ms)")
            EngineMonitor.set(Component.MEDIA_MUXER, ComponentStatus.OK, "moov written")
            EngineMonitor.markSuccess("FFmpeg concat copy → ${outBytes / (1024 * 1024)} MB")
        } else {
            EngineMonitor.set(
                Component.FFMPEG,
                if (isCancel) ComponentStatus.IDLE else ComponentStatus.ERROR,
                "exit=$code"
            )
        }

        Result(
            success = isSuccess && outBytes > 0,
            cancelled = isCancel,
            stalled = stallAbort,
            exitCode = code,
            command = commandLine,
            outputLog = log,
            elapsedMs = elapsed,
            outputBytes = outBytes,
            lastOutTimeUs = lastOutUs,
            stallSeconds = if (stallAbort) (System.currentTimeMillis() - lastAdvanceAt) / 1000 else 0
        )
    }

    // -------------------------------------------------------------- validate

    data class VideoValidation(
        val valid: Boolean,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val fileBytes: Long,
        val reason: String
    )

    /** Probes the result; nothing is ever reported as success without this. */
    suspend fun validate(context: Context, path: String): VideoValidation =
        withContext(Dispatchers.IO) {
            val f = File(path)
            if (!f.exists() || f.length() < 1024) {
                return@withContext VideoValidation(
                    false, 0L, 0, 0, 0L, "file missing or too small"
                )
            }
            val info = VideoAnalyzer.analyze(context, Uri.fromFile(f))
                ?: return@withContext VideoValidation(
                    false, 0L, 0, 0, f.length(), "file not demuxable"
                )

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
}
