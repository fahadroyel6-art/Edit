package com.ultravidmerger.app.diag

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the AI-readable diagnostic report (Module 15 §4) plus the ready-to-paste
 * repair prompt (§5). Everything is generated locally; nothing is uploaded.
 */
object ErrorReportGenerator {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // ------------------------------------------------------------- redaction

    private val URI_RE = Regex("content://[^\\s\"']+")
    private val FILE_RE = Regex("(/data/|/storage/|/sdcard/|/mnt/)[^\\s\"']*")
    private val PACKAGE_RE = Regex("com\\.[a-z0-9_.]+")

    /** Strips identifying paths while keeping enough structure to debug. */
    fun redact(input: String?): String {
        if (input.isNullOrBlank()) return ""
        return input
            .replace(URI_RE) { m -> "content://‹redacted›/${tailOf(m.value)}" }
            .replace(FILE_RE) { m -> "…/‹redacted›/${tailOf(m.value)}" }
    }

    private fun tailOf(path: String): String {
        val clean = path.substringBefore('?')
        return clean.substringAfterLast('/').ifBlank { "file" }
    }

    // --------------------------------------------------------------- report

    fun build(ctx: Diagnostics.ErrorContext, includeRawPaths: Boolean = false): String {
        val snap = SystemMonitor.snapshot.value
        val health = EngineMonitor.components.value
        val red: (String?) -> String = { s -> if (includeRawPaths) s.orEmpty() else redact(s) }

        return buildString {
            appendLine("═══════════════════════════════════════════════════════════")
            appendLine(" ULTRA VIDEO MERGER PRO — DIAGNOSTIC REPORT")
            appendLine("═══════════════════════════════════════════════════════════")
            appendLine()
            appendLine("── ERROR ─────────────────────────────────────────────────")
            appendLine("Error ID     : ${ctx.code.id}")
            appendLine("Error Code   : ${ctx.code.title}")
            appendLine("Message      : ${red(ctx.message)}")
            appendLine("Stage        : ${ctx.stage}")
            appendLine("Job ID       : ${ctx.jobId ?: "—"}")
            appendLine("When         : ${fmt.format(Date())}")
            appendLine("Description  : ${ctx.code.description}")
            appendLine("Likely cause : ${ctx.code.likelyCause}")
            appendLine()

            appendLine("── ENVIRONMENT ───────────────────────────────────────────")
            appendLine("App          : ${Diagnostics.appVersionName} (${Diagnostics.appVersionCode})")
            appendLine("Device       : ${Diagnostics.deviceSummary()}")
            appendLine("Heap         : ${snap.heapLabel()}")
            appendLine("Native heap  : ${SystemSnapshot.formatBytes(snap.nativeHeapBytes)}")
            appendLine("CPU load     : ${snap.cpuLoadPercent}%")
            appendLine("Thermal      : ${snap.thermalLabel} (raw=${snap.thermalStatus})")
            appendLine("Battery temp : ${if (snap.batteryTempC > 0) "${snap.batteryTempC}°C" else "n/a"}")
            appendLine("Storage free : ${snap.storageFreeLabel()} of ${SystemSnapshot.formatBytes(snap.storageTotalBytes)}")
            appendLine("Low memory   : ${snap.lowMemory}")
            appendLine()

            if (ctx.ffmpegCommand != null || ctx.ffmpegExitCode != null) {
                appendLine("── FFMPEG ────────────────────────────────────────────────")
                appendLine("Command      : ${red(ctx.ffmpegCommand) ?: "—"}")
                appendLine("Exit code    : ${ctx.ffmpegExitCode ?: "—"}")
                val log = red(ctx.ffmpegLog)
                if (log.isNotBlank()) {
                    appendLine("Last log (truncated to 120 lines):")
                    log.lines().takeLast(120).forEach { appendLine("  | $it") }
                }
                appendLine()
            }

            if (ctx.extra.isNotEmpty()) {
                appendLine("── JOB CONFIGURATION ─────────────────────────────────────")
                ctx.extra.toSortedMap().forEach { (k, v) -> appendLine("$k : ${red(v)}") }
                appendLine()
            }

            appendLine("── COMPONENT STATE ───────────────────────────────────────")
            health.forEach { c ->
                appendLine("${c.component.displayName.padEnd(22)} ${c.status.name.padEnd(8)} ${red(c.detail)}")
            }
            appendLine()

            appendLine("── BACKGROUND SERVICE ────────────────────────────────────")
            appendLine("Running      : ${ServiceBridge.isRunning}")
            appendLine("Stage        : ${EngineMonitor.currentStage.value}")
            appendLine("Last success : ${red(EngineMonitor.lastSuccess.value)}")
            appendLine()

            appendLine("── STACK TRACE ───────────────────────────────────────────")
            val st = red(ctx.stackTrace)
            if (st.isBlank()) appendLine("(no exception attached)")
            else st.lines().take(80).forEach { appendLine(it) }
            appendLine()

            appendLine("── RECENT LOGS (last 40, redacted) ───────────────────────")
            DiagnosticLogger.snapshot().takeLast(40).forEach {
                appendLine(red(it.format()))
            }
            appendLine()
            appendLine("═══════════════════════════════════════════════════════════")
            appendLine(" Generated locally by Ultra Video Merger Pro ${Diagnostics.appVersionName}")
            appendLine(" No video content or personal data is included.")
            appendLine("═══════════════════════════════════════════════════════════")
        }
    }

    /** §5 — the paste-into-an-AI repair prompt. */
    fun buildAiRepairPrompt(ctx: Diagnostics.ErrorContext): String = buildString {
        appendLine("I am developing ULTRA VIDEO MERGER PRO for Android using Kotlin and FFmpeg (FFmpegKit 8.1 LTS).")
        appendLine()
        appendLine("The following error occurred during video rendering.")
        appendLine()
        appendLine("Analyze the complete diagnostic report, identify the root cause, locate the problematic code and provide a permanent code-level fix.")
        appendLine()
        appendLine("Constraints:")
        appendLine("- Do not remove existing features.")
        appendLine("- Do not introduce new threading problems.")
        appendLine("- Do not switch the lossless path away from FFmpeg stream copy (-c copy).")
        appendLine("- Fix the issue and run relevant regression tests.")
        appendLine()
        appendLine("Here is the diagnostic report:")
        appendLine()
        appendLine(build(ctx))
    }

    /** Short one-liner used in toasts and the notification. */
    fun summaryLine(ctx: Diagnostics.ErrorContext): String =
        "${ctx.code.id} ${ctx.code.title}: ${redact(ctx.message).take(120)}"
}

/** Lets the diag layer ask the service whether it is alive without a hard dependency. */
object ServiceBridge {
    @Volatile var isRunning: Boolean = false
}
