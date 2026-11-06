package com.ultravidmerger.app.selftest

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.ultravidmerger.app.diag.DiagnosticLogger
import com.ultravidmerger.app.diag.SystemMonitor
import com.ultravidmerger.app.engine.FFmpegEngine
import com.ultravidmerger.app.engine.InputFiles
import com.ultravidmerger.app.engine.VideoAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Long-video regression suite — the tests that actually reproduce the
 * "stuck at 1-2%" report.
 *
 * Strategy: generate very low resolution synthetic media at 1 fps. A 3-hour
 * clip at 128x72 / 1 fps is only a few MB and takes seconds to create, yet its
 * *timeline* is a genuine 10 800 000 000 µs — which is exactly what exercises
 * timestamp handling, 64-bit math and progress arithmetic. High-bitrate
 * representative files are a separate, manual test (see TEST_REPORT.md §9).
 *
 * Each test measures: wall-clock duration, output size, peak heap delta,
 * thermal state, FFmpeg exit code, and probes the merged file.
 */
object LongVideoTestSuite {

    private const val TAG = "LongVideoTests"

    private fun t(id: String, title: String, desc: String) = TestCase(id, title, desc)

    val T_10MIN = t("L01", "10-minute merge", "2 × 5 min @1fps 128x72 → expect ≈600 s")
    val T_30MIN = t("L02", "30-minute merge", "2 × 15 min → expect ≈1800 s")
    val T_60MIN = t("L03", "1-hour merge", "2 × 30 min → expect ≈3600 s")
    val T_120MIN = t("L04", "2-hour merge", "2 × 60 min → expect ≈7200 s")
    val T_180MIN = t("L05", "3-hour merge", "2 × 90 min → expect ≈10800 s")
    val T_PROGRESS = t("L06", "Progress never stalls on long input", "3-hour input must emit >5 monotonic progress samples")
    val T_STORAGE = t("L07", "2x storage pre-flight", "Pre-flight must demand peak (temp + gallery) space")
    val T_SEEKABLE = t("L08", "Inputs open as seekable fds", "/proc/self/fd path must be lseek-able")

    val ALL = listOf(T_10MIN, T_30MIN, T_60MIN, T_120MIN, T_180MIN, T_PROGRESS, T_STORAGE, T_SEEKABLE)

    // --------------------------------------------------------------- generate

    /** Builds a low-res, 1 fps clip of [seconds] duration. Cached on disk. */
    private fun makeClip(context: Context, name: String, seconds: Int): File? {
        val dir = File(context.cacheDir, "longtest").apply { mkdirs() }
        val out = File(dir, "$name.mp4")
        if (out.exists() && out.length() > 1024) return out

        val args = arrayOf(
            "-y", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi",
            "-i", "testsrc2=size=128x72:rate=1:duration=$seconds",
            "-f", "lavfi",
            "-i", "sine=frequency=440:sample_rate=22050:duration=$seconds",
            "-c:v", "mpeg4", "-q:v", "31", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "32k", "-ac", "1", "-ar", "22050",
            "-shortest",
            out.absolutePath
        )

        val session = FFmpegKit.executeWithArguments(args)
        val ok = ReturnCode.isSuccess(session.returnCode) && out.exists() && out.length() > 1024
        DiagnosticLogger.info(
            TAG,
            "makeClip $name (${seconds}s): ok=$ok exit=${session.returnCode?.value} " +
                "bytes=${out.length()}"
        )
        if (!ok) DiagnosticLogger.warn(TAG, "makeClip failed: ${session.output?.take(500)}")
        return if (ok) out else null
    }

    // ------------------------------------------------------------------- run

    suspend fun runAll(
        context: Context,
        onUpdate: (TestOutcome) -> Unit
    ): TestReport = withContext(Dispatchers.IO) {

        val outcomes = mutableListOf<TestOutcome>()
        val workDir = File(context.cacheDir, "longtest_out").apply { mkdirs() }

        suspend fun record(test: TestCase, block: suspend () -> TestOutcome) {
            onUpdate(TestOutcome(test, TestStatus.RUNNING, "running"))
            val started = System.currentTimeMillis()
            val result = try {
                block()
            } catch (e: Throwable) {
                TestOutcome(test, TestStatus.FAILED, "exception: ${e.message}", 0)
            }
            val final = result.copy(durationMs = System.currentTimeMillis() - started)
            outcomes += final
            onUpdate(final)
            DiagnosticLogger.info(TAG, "${final.test.id} → ${final.status}: ${final.detail}")
        }

        /**
         * The core long-video check: merge N clips of [clipSeconds] each and
         * verify the merged timeline equals the sum, that progress advanced
         * monotonically, and that the engine did not stall.
         */
        suspend fun longMerge(
            test: TestCase,
            label: String,
            clipSeconds: Int,
            clipCount: Int = 2
        ): TestOutcome {
            val clips = (1..clipCount).mapNotNull { i ->
                makeClip(context, "${label}_${i}", clipSeconds)
            }
            if (clips.size != clipCount) {
                return TestOutcome(test, TestStatus.SKIPPED, "could not generate ${clipSeconds}s fixture")
            }

            val expectedMs = clipSeconds * 1000L * clipCount
            val out = File(workDir, "${test.id}_$label.mp4")
            runCatching { out.delete() }

            // Runtime environment before the run
            SystemMonitor.refreshOnce()
            val before = SystemMonitor.snapshot.value

            val handles = try {
                InputFiles.openAll(context, clips.map { Uri.fromFile(it) })
            } catch (e: Exception) {
                return TestOutcome(test, TestStatus.FAILED, "openAll failed: ${e.message}")
            }

            val samples = mutableListOf<Float>()
            val ff = try {
                FFmpegEngine.mergeLossless(
                    context,
                    handles.map { it.path },
                    out.absolutePath,
                    expectedMs,
                    clips.sumOf { it.length() }
                ) { p, _ -> synchronized(samples) { samples.add(p) } }
            } finally {
                InputFiles.closeAll()
            }

            if (!ff.success) {
                return TestOutcome(
                    test, TestStatus.FAILED,
                    "merge failed: exit=${ff.exitCode} stalled=${ff.stalled} " +
                        "lastOut=${ff.lastOutTimeUs / 1_000_000}s log=${ff.outputLog.take(200)}",
                    measured = mapOf(
                        "exitCode" to ff.exitCode.toString(),
                        "stalled" to ff.stalled.toString()
                    )
                )
            }

            SystemMonitor.refreshOnce()
            val after = SystemMonitor.snapshot.value
            val validation = FFmpegEngine.validate(context, out.absolutePath)

            val delta = abs(validation.durationMs - expectedMs)
            val durationOk = validation.valid && delta <= expectedMs / 100   // 1 %
            val progressOk = samples.size >= 5 && samples.zipWithNext().all { (a, b) -> b >= a - 0.001f }

            val ok = durationOk && progressOk && !ff.stalled

            val outcome = TestOutcome(
                test = test,
                status = if (ok) TestStatus.PASSED else TestStatus.FAILED,
                detail = buildString {
                    append("expected=${expectedMs / 1000}s actual=${validation.durationMs / 1000}s ")
                    append("delta=${delta / 1000}s samples=${samples.size} ")
                    append("stalled=${ff.stalled} exit=${ff.exitCode}")
                    if (!durationOk) append(" | DURATION MISMATCH")
                    if (!progressOk) append(" | PROGRESS BROKEN")
                },
                measured = mapOf(
                    "clipSeconds" to clipSeconds.toString(),
                    "inputCount" to clipCount.toString(),
                    "expectedMs" to expectedMs.toString(),
                    "actualMs" to validation.durationMs.toString(),
                    "outputBytes" to validation.fileBytes.toString(),
                    "wallClockMs" to ff.elapsedMs.toString(),
                    "speedup" to String.format(
                        java.util.Locale.US, "%.1fx",
                        expectedMs.toDouble() / ff.elapsedMs.coerceAtLeast(1)
                    ),
                    "progressSamples" to samples.size.toString(),
                    "heapBeforeMB" to (before.heapUsedBytes / (1024 * 1024)).toString(),
                    "heapAfterMB" to (after.heapUsedBytes / (1024 * 1024)).toString(),
                    "thermal" to after.thermalLabel,
                    "exitCode" to ff.exitCode.toString()
                )
            )

            runCatching { out.delete() }
            return outcome
        }

        // ---------------- the five duration tiers ----------------
        record(T_10MIN) { longMerge(T_10MIN, "10m", 300) }      // 2 × 5 min
        record(T_30MIN) { longMerge(T_30MIN, "30m", 900) }      // 2 × 15 min
        record(T_60MIN) { longMerge(T_60MIN, "60m", 1800) }     // 2 × 30 min
        record(T_120MIN) { longMerge(T_120MIN, "120m", 3600) }  // 2 × 60 min
        record(T_180MIN) { longMerge(T_180MIN, "180m", 5400) }  // 2 × 90 min

        // ---------------- L06: explicit progress-stall regression ----------------
        record(T_PROGRESS) {
            val clip = makeClip(context, "progress_90m", 5400)
                ?: return@record TestOutcome(T_PROGRESS, TestStatus.SKIPPED, "fixture failed")

            val out = File(workDir, "l06.mp4")
            runCatching { out.delete() }

            val handles = InputFiles.openAll(context, listOf(Uri.fromFile(clip)))
            val samples = mutableListOf<Float>()
            val ff = try {
                FFmpegEngine.mergeLossless(
                    context, handles.map { it.path }, out.absolutePath,
                    5_400_000L, clip.length()
                ) { p, _ -> synchronized(samples) { samples.add(p) } }
            } finally {
                InputFiles.closeAll()
            }

            val monotonic = samples.zipWithNext().all { (a, b) -> b >= a - 0.001f }
            val advanced = (samples.maxOrNull() ?: 0f) > 0.5f
            val ok = ff.success && monotonic && advanced && samples.size >= 5

            runCatching { out.delete() }
            TestOutcome(
                T_PROGRESS,
                if (ok) TestStatus.PASSED else TestStatus.FAILED,
                "samples=${samples.size} monotonic=$monotonic reached=${samples.maxOrNull()} " +
                    "success=${ff.success}",
                measured = mapOf(
                    "samples" to samples.size.toString(),
                    "maxProgress" to (samples.maxOrNull() ?: 0f).toString(),
                    "wallClockMs" to ff.elapsedMs.toString()
                )
            )
        }

        // ---------------- L07: 2x storage rule ----------------
        record(T_STORAGE) {
            SystemMonitor.refreshOnce()
            val free = SystemMonitor.snapshot.value.storageFreeBytes
            // If the rule is right, asking for free/3 must pass and 2*free must fail.
            val smallPass = com.ultravidmerger.app.diag.Diagnostics.canWrite(context, free / 3)
            val hugeFail = !com.ultravidmerger.app.diag.Diagnostics.canWrite(context, free * 2)
            val ok = smallPass && hugeFail
            TestOutcome(
                T_STORAGE,
                if (ok) TestStatus.PASSED else TestStatus.FAILED,
                "free=${free / (1024 * 1024)}MB smallPass=$smallPass hugeFail=$hugeFail"
            )
        }

        // ---------------- L08: seekable descriptors ----------------
        record(T_SEEKABLE) {
            val clip = makeClip(context, "seek_60", 60)
                ?: return@record TestOutcome(T_SEEKABLE, TestStatus.SKIPPED, "fixture failed")
            val handles = try {
                InputFiles.openAll(context, listOf(Uri.fromFile(clip)))
            } catch (e: Exception) {
                return@record TestOutcome(T_SEEKABLE, TestStatus.FAILED, "openAll: ${e.message}")
            }
            val seekable = handles.all { it.seekable }
            val localPath = handles.all { it.path.startsWith("/proc/self/fd/") }
            InputFiles.closeAll()

            val ok = seekable && localPath
            TestOutcome(
                T_SEEKABLE,
                if (ok) TestStatus.PASSED else TestStatus.FAILED,
                "seekable=$seekable procFdPath=$localPath path=${handles.firstOrNull()?.path}"
            )
        }

        val report = TestReport(
            finishedAtMs = System.currentTimeMillis(),
            outcomes = outcomes
        )
        DiagnosticLogger.info(TAG, "Long-video suite finished: ${report.summaryLine()}")
        report
    }

    fun clearCache(context: Context) {
        runCatching { File(context.cacheDir, "longtest").deleteRecursively() }
        runCatching { File(context.cacheDir, "longtest_out").deleteRecursively() }
    }
}
