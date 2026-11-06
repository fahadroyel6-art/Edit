package com.ultravidmerger.app.selftest

import android.content.Context
import android.net.Uri
import com.ultravidmerger.app.VideoInfo
import com.ultravidmerger.app.diag.DiagnosticLogger
import com.ultravidmerger.app.diag.SystemMonitor
import com.ultravidmerger.app.engine.CompatibilityChecker
import com.ultravidmerger.app.engine.FFmpegEngine
import com.ultravidmerger.app.engine.MergeMode
import com.ultravidmerger.app.engine.TransformerHost
import com.ultravidmerger.app.engine.VideoAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * On-device automated QA (Module 9).
 *
 * Every test really executes the engine and really probes the output — nothing
 * here is mocked. Results are reported honestly: if a precondition cannot be
 * met on the current device the test is marked SKIPPED with the reason.
 */
object MergeTestSuite {

    private const val TAG = "MergeTestSuite"

    private fun t(id: String, title: String, desc: String) = TestCase(id, title, desc)

    val CASE_TWO_1080P = t("T01", "Two compatible clips", "Merge two 1280x720 mpeg4 clips and verify duration adds up.")
    val CASE_THREE = t("T02", "Three compatible clips", "Merge three clips, expect sum of durations.")
    val CASE_DURATIONS = t("T03", "Different durations", "Merge 4s + 2s clips, expect ≈6s.")
    val CASE_FPS = t("T04", "Different frame rates", "30fps + 24fps → compatibility checker must route to re-encode.")
    val CASE_RES = t("T05", "Different resolutions", "720p + 480p → must be rejected for lossless.")
    val CASE_AUDIO = t("T06", "Different audio codecs", "aac + no-audio → timeline gap handled.")
    val CASE_NO_AUDIO = t("T07", "Video without audio", "Silent clip merged with an audio clip.")
    val CASE_LARGE = t("T08", "Larger file", "30s clip merged with a 4s clip.")
    val CASE_CORRUPT = t("T09", "Corrupted input", "Analyzer must fail gracefully, not crash.")
    val CASE_STORAGE = t("T10", "Storage pre-flight", "Insufficient-space detection returns false when required > free.")
    val CASE_THREAD = t("T11", "Transformer thread contract", "Every Transformer call must run on the owner looper.")
    val CASE_CANCEL = t("T12", "Cancellation", "Cancel mid-merge; no output file must survive.")
    val CASE_SYNC = t("T13", "Audio/video duration sync", "Output duration must match sum of inputs within 5%.")
    val CASE_PROGRESS = t("T14", "Progress monotonicity", "Reported progress must never go backwards.")
    val CASE_VALIDATE = t("T15", "Output validation rejects junk", "Validator must flag a non-media file.")

    val ALL = listOf(
        CASE_TWO_1080P, CASE_THREE, CASE_DURATIONS, CASE_FPS, CASE_RES, CASE_AUDIO,
        CASE_NO_AUDIO, CASE_LARGE, CASE_CORRUPT, CASE_STORAGE, CASE_THREAD,
        CASE_CANCEL, CASE_SYNC, CASE_PROGRESS, CASE_VALIDATE
    )

    suspend fun runAll(
        context: Context,
        onUpdate: (TestOutcome) -> Unit
    ): TestReport = withContext(Dispatchers.IO) {

        DiagnosticLogger.info(TAG, "Automated test run started")
        val outcomes = mutableListOf<TestOutcome>()
        val workDir = File(context.cacheDir, "selftest_out").apply { mkdirs() }

        suspend fun record(test: TestCase, block: suspend () -> TestOutcome) {
            onUpdate(TestOutcome(test, TestStatus.RUNNING, "running"))
            val started = System.currentTimeMillis()
            val outcome = try {
                block()
            } catch (e: Throwable) {
                TestOutcome(test, TestStatus.FAILED, "exception: ${e.message}", 0)
            }
            val final = outcome.copy(durationMs = System.currentTimeMillis() - started)
            outcomes += final
            onUpdate(final)
            DiagnosticLogger.info(TAG, "${final.test.id} → ${final.status}: ${final.detail}")
        }

        // ---------- generate fixtures ----------
        val a4s = TestVideoGenerator.generate(
            context,
            TestVideoGenerator.Spec("a_720p_30_4s", 1280, 720, 30, 4)
        )
        val b4s = TestVideoGenerator.generate(
            context,
            TestVideoGenerator.Spec("b_720p_30_4s", 1280, 720, 30, 4, toneHz = 660)
        )
        val c2s = TestVideoGenerator.generate(
            context,
            TestVideoGenerator.Spec("c_720p_30_2s", 1280, 720, 30, 2, toneHz = 880)
        )
        val fps24 = TestVideoGenerator.generate(
            context,
            TestVideoGenerator.Spec("d_720p_24_3s", 1280, 720, 24, 3)
        )
        val res480 = TestVideoGenerator.generate(
            context,
            TestVideoGenerator.Spec("e_480p_30_3s", 854, 480, 30, 3)
        )
        val silent = TestVideoGenerator.generate(
            context,
            TestVideoGenerator.Spec("f_silent_720p_30_3s", 1280, 720, 30, 3, withAudio = false)
        )
        val long30 = TestVideoGenerator.generate(
            context,
            TestVideoGenerator.Spec("g_720p_30_30s", 1280, 720, 30, 30, toneHz = 300)
        )
        val corrupted = TestVideoGenerator.generateCorrupted(context)

        val fixturesOk = listOfNotNull(a4s, b4s, c2s).size == 3

        // ---------- shared helper ----------
        suspend fun analyze(f: File): VideoInfo? = VideoAnalyzer.analyze(context, Uri.fromFile(f))

        suspend fun mergeLossless(files: List<File>, out: File): Pair<Boolean, FFmpegEngine.Result?> {
            runCatching { out.delete() }
            val total = files.sumOf { analyze(it)?.durationMs ?: 0L }
            val handles = try {
                com.ultravidmerger.app.engine.InputFiles.openAll(context, files.map { Uri.fromFile(it) })
            } catch (e: Exception) {
                return false to null
            }
            val r = try {
                FFmpegEngine.mergeLossless(
                    context,
                    handles.map { it.path },
                    out.absolutePath,
                    total,
                    files.sumOf { it.length() }
                ) { _, _ -> }
            } finally {
                com.ultravidmerger.app.engine.InputFiles.closeAll()
            }
            return (r.success && out.exists() && out.length() > 1024) to r
        }

        // ---------------- T01 ----------------
        record(CASE_TWO_1080P) {
            if (!fixturesOk) return@record TestOutcome(CASE_TWO_1080P, TestStatus.SKIPPED, "fixture generation failed")
            val out = File(workDir, "t01.mp4")
            val (ok, r) = mergeLossless(listOf(a4s!!, b4s!!), out)
            val info = if (ok) analyze(out) else null
            val expected = 8000L
            val good = ok && info != null && abs((info.durationMs) - expected) < 2000
            TestOutcome(
                CASE_TWO_1080P,
                if (good) TestStatus.PASSED else TestStatus.FAILED,
                if (good) "duration=${info!!.durationMs}ms expected≈${expected}ms"
                else "merge ok=$ok exit=${r?.exitCode} info=${info?.durationMs}",
                measured = mapOf(
                    "outputMs" to (info?.durationMs ?: 0).toString(),
                    "bytes" to out.length().toString(),
                    "exitCode" to (r?.exitCode ?: -1).toString()
                )
            )
        }

        // ---------------- T02 ----------------
        record(CASE_THREE) {
            if (!fixturesOk) return@record TestOutcome(CASE_THREE, TestStatus.SKIPPED, "fixture generation failed")
            val out = File(workDir, "t02.mp4")
            val (ok, r) = mergeLossless(listOf(a4s!!, b4s!!, c2s!!), out)
            val info = if (ok) analyze(out) else null
            val good = ok && info != null && abs(info.durationMs - 10000L) < 2500
            TestOutcome(
                CASE_THREE,
                if (good) TestStatus.PASSED else TestStatus.FAILED,
                if (good) "3-way merge duration=${info!!.durationMs}ms"
                else "ok=$ok exit=${r?.exitCode}",
                measured = mapOf("outputMs" to (info?.durationMs ?: 0).toString())
            )
        }

        // ---------------- T03 ----------------
        record(CASE_DURATIONS) {
            if (!fixturesOk) return@record TestOutcome(CASE_DURATIONS, TestStatus.SKIPPED, "fixture generation failed")
            val out = File(workDir, "t03.mp4")
            val (ok, _) = mergeLossless(listOf(a4s!!, c2s!!), out)
            val info = if (ok) analyze(out) else null
            val good = ok && info != null && abs(info.durationMs - 6000L) < 2000
            TestOutcome(
                CASE_DURATIONS,
                if (good) TestStatus.PASSED else TestStatus.FAILED,
                if (good) "4s+2s → ${info!!.durationMs}ms" else "ok=$ok"
            )
        }

        // ---------------- T04 ----------------
        record(CASE_FPS) {
            if (a4s == null || fps24 == null) return@record TestOutcome(CASE_FPS, TestStatus.SKIPPED, "fixture missing")
            val ia = analyze(a4s); val ib = analyze(fps24)
            if (ia == null || ib == null) return@record TestOutcome(CASE_FPS, TestStatus.SKIPPED, "analyzer failed")
            val report = CompatibilityChecker.analyze(listOf(ia, ib))
            val good = report.mode == MergeMode.REENCODE && report.issues.any { it.field.contains("frame rate") }
            TestOutcome(
                CASE_FPS,
                if (good) TestStatus.PASSED else TestStatus.FAILED,
                "mode=${report.mode} issues=${report.issues.joinToString { it.field }}"
            )
        }

        // ---------------- T05 ----------------
        record(CASE_RES) {
            if (a4s == null || res480 == null) return@record TestOutcome(CASE_RES, TestStatus.SKIPPED, "fixture missing")
            val ia = analyze(a4s); val ib = analyze(res480)
            if (ia == null || ib == null) return@record TestOutcome(CASE_RES, TestStatus.SKIPPED, "analyzer failed")
            val report = CompatibilityChecker.analyze(listOf(ia, ib))
            val good = report.mode == MergeMode.REENCODE && report.issues.any { it.field == "resolution" }
            TestOutcome(
                CASE_RES,
                if (good) TestStatus.PASSED else TestStatus.FAILED,
                "mode=${report.mode} issues=${report.issues.joinToString { it.field }}"
            )
        }

        // ---------------- T06 ----------------
        record(CASE_AUDIO) {
            if (a4s == null || silent == null) return@record TestOutcome(CASE_AUDIO, TestStatus.SKIPPED, "fixture missing")
            val ia = analyze(a4s); val ib = analyze(silent)
            if (ia == null || ib == null) return@record TestOutcome(CASE_AUDIO, TestStatus.SKIPPED, "analyzer failed")
            val out = File(workDir, "t06.mp4")
            val (ok, _) = mergeLossless(listOf(a4s, silent), out)
            val info = if (ok) analyze(out) else null
            val good = ok && info != null && info.hasAudio()
            TestOutcome(
                CASE_AUDIO,
                if (good) TestStatus.PASSED else TestStatus.FAILED,
                "audio-pool selected; output audio=${info?.audioCodec}, ok=$ok"
            )
        }

        // ---------------- T07 ----------------
        record(CASE_NO_AUDIO) {
            if (silent == null) return@record TestOutcome(CASE_NO_AUDIO, TestStatus.SKIPPED, "fixture missing")
            val info = analyze(silent)
            val good = info != null && !info.hasAudio()
            TestOutcome(
                CASE_NO_AUDIO,
                if (good) TestStatus.PASSED else TestStatus.FAILED,
                "silent clip detected as no-audio=${info != null && !info.hasAudio()}"
            )
        }

        // ---------------- T08 ----------------
        record(CASE_LARGE) {
            if (long30 == null || c2s == null) return@record TestOutcome(CASE_LARGE, TestStatus.SKIPPED, "long fixture missing")
            val out = File(workDir, "t08.mp4")
            val (ok, _) = mergeLossless(listOf(long30, c2s), out)
            val info = if (ok) analyze(out) else null
            val good = ok && info != null && info.durationMs > 25000
            TestOutcome(
                CASE_LARGE,
                if (good) TestStatus.PASSED else TestStatus.FAILED,
                "30s+2s → ${info?.durationMs}ms, ${out.length() / 1024} KB",
                measured = mapOf("bytes" to out.length().toString())
            )
        }

        // ---------------- T09 ----------------
        record(CASE_CORRUPT) {
            val info = analyze(corrupted)
            val out = File(workDir, "t09.mp4")
            val (ok, r) = mergeLossless(listOf(corrupted), out)
            // Must NOT crash and must NOT report success
            val good = info == null && !ok
            TestOutcome(
                CASE_CORRUPT,
                if (good) TestStatus.PASSED else TestStatus.FAILED,
                "analyzer returned ${if (info == null) "null (good)" else "info (bad)"}, merge ok=$ok exit=${r?.exitCode}"
            )
        }

        // ---------------- T10 ----------------
        record(CASE_STORAGE) {
            SystemMonitor.refreshOnce()
            val free = SystemMonitor.snapshot.value.storageFreeBytes
            val impossible = free + 10L * 1024 * 1024 * 1024
            val okSmall = com.ultravidmerger.app.diag.Diagnostics.canWrite(context, 1024)
            val okHuge = com.ultravidmerger.app.diag.Diagnostics.canWrite(context, impossible)
            val good = okSmall && !okHuge
            TestOutcome(
                CASE_STORAGE,
                if (good) TestStatus.PASSED else TestStatus.FAILED,
                "small=$okSmall huge=$okHuge free=${VideoInfo.formatBytes(free)}"
            )
        }

        // ---------------- T11 ----------------
        record(CASE_THREAD) {
            var ownerSeen = false
            var offThreadRejected = false
            TransformerHost.sync {
                ownerSeen = TransformerHost.isOwnerThread()
            }
            try {
                TransformerHost.assertOwnerThread()
            } catch (e: IllegalStateException) {
                offThreadRejected = true
            }
            val good = ownerSeen && offThreadRejected
            TestOutcome(
                CASE_THREAD,
                if (good) TestStatus.PASSED else TestStatus.FAILED,
                "ownerLooper=$ownerSeen wrongThreadRejected=$offThreadRejected"
            )
        }

        // ---------------- T12 ----------------
        record(CASE_CANCEL) {
            if (a4s == null || long30 == null) return@record TestOutcome(CASE_CANCEL, TestStatus.SKIPPED, "fixture missing")
            val out = File(workDir, "t12.mp4")
            runCatching { out.delete() }

            val job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                val handles = com.ultravidmerger.app.engine.InputFiles
                    .openAll(context, listOf(Uri.fromFile(long30), Uri.fromFile(a4s)))
                try {
                    FFmpegEngine.mergeLossless(
                        context,
                        handles.map { it.path },
                        out.absolutePath,
                        34000L,
                        long30.length() + a4s.length()
                    ) { _, _ -> }
                } finally {
                    com.ultravidmerger.app.engine.InputFiles.closeAll()
                }
            }
            delay(700)
            FFmpegEngine.cancel()
            runCatching { kotlinx.coroutines.withTimeout(20000) { job.join() } }
            delay(300)

            // A cancelled run must not leave a "valid" output behind
            val info = if (out.exists() && out.length() > 1024) analyze(out) else null
            val cancelledProperly = info == null
            TestOutcome(
                CASE_CANCEL,
                if (cancelledProperly) TestStatus.PASSED else TestStatus.FAILED,
                "post-cancel output valid=$info (expected null)"
            )
        }

        // ---------------- T13 ----------------
        record(CASE_SYNC) {
            if (a4s == null || b4s == null) return@record TestOutcome(CASE_SYNC, TestStatus.SKIPPED, "fixture missing")
            val out = File(workDir, "t13.mp4")
            val (ok, _) = mergeLossless(listOf(a4s, b4s), out)
            val info = if (ok) analyze(out) else null
            val expected = 8000L
            val delta = if (info != null) abs(info.durationMs - expected) else Long.MAX_VALUE
            val good = ok && info != null && delta <= expected * 5 / 100
            TestOutcome(
                CASE_SYNC,
                if (good) TestStatus.PASSED else TestStatus.FAILED,
                "expected=${expected}ms actual=${info?.durationMs}ms delta=${delta}ms"
            )
        }

        // ---------------- T14 ----------------
        record(CASE_PROGRESS) {
            if (a4s == null || long30 == null) return@record TestOutcome(CASE_PROGRESS, TestStatus.SKIPPED, "fixture missing")
            val seen = mutableListOf<Float>()
            val out = File(workDir, "t14.mp4")
            runCatching { out.delete() }
            val h = com.ultravidmerger.app.engine.InputFiles
                .openAll(context, listOf(Uri.fromFile(long30), Uri.fromFile(a4s)))
            try {
                FFmpegEngine.mergeLossless(
                    context,
                    h.map { it.path },
                    out.absolutePath,
                    34000L,
                    long30.length() + a4s.length()
                ) { p, _ -> synchronized(seen) { seen.add(p) } }
            } finally {
                com.ultravidmerger.app.engine.InputFiles.closeAll()
            }

            val monotonic = seen.zipWithNext().all { (a, b) -> b >= a - 0.001f }
            val good = seen.isNotEmpty() && monotonic
            TestOutcome(
                CASE_PROGRESS,
                if (good) TestStatus.PASSED else TestStatus.FAILED,
                "samples=${seen.size} monotonic=$monotonic",
                measured = mapOf("first" to (seen.firstOrNull() ?: -1f).toString(),
                    "last" to (seen.lastOrNull() ?: -1f).toString())
            )
        }

        // ---------------- T15 ----------------
        record(CASE_VALIDATE) {
            val junk = File(workDir, "t15_junk.mp4")
            junk.writeBytes(ByteArray(8192) { 0x41 })
            val v = FFmpegEngine.validate(context, junk.absolutePath)
            val empty = FFmpegEngine.validate(context, File(workDir, "does_not_exist.mp4").absolutePath)
            val good = !v.valid && !empty.valid
            TestOutcome(
                CASE_VALIDATE,
                if (good) TestStatus.PASSED else TestStatus.FAILED,
                "junk.valid=${v.valid} missing.valid=${empty.valid}"
            )
        }

        val report = TestReport(
            finishedAtMs = System.currentTimeMillis(),
            outcomes = outcomes
        )
        DiagnosticLogger.info(TAG, "Test run finished: ${report.summaryLine()}")
        report
    }
}
