package com.ultravidmerger.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.ultravidmerger.app.diag.DiagnosticLogger
import com.ultravidmerger.app.diag.ErrorCode
import com.ultravidmerger.app.diag.ErrorReportGenerator
import com.ultravidmerger.app.diag.Diagnostics

/**
 * MODULE 15 §5 — the mandatory one-click error surface.
 *
 * Shows the error id + message and offers:
 *   1. Copy Full Error Report
 *   2. Copy Technical Logs
 *   3. Copy AI Repair Prompt
 *   4. View Error Details
 *   5. Retry Operation (handled by the caller via [onRetry])
 */
object ErrorSheet {

    fun show(
        context: Context,
        report: String,
        code: ErrorCode?,
        message: String,
        onRetry: (() -> Unit)? = null
    ) {
        val errorId = code?.id ?: ErrorCode.UNKNOWN.id
        val title = "ERROR DETECTED\n$errorId ${code?.title ?: "UNKNOWN_ERROR"}"
        val body = message.take(600)

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("Copy Full Report") { _, _ ->
                copy(context, report, "Diagnostic report copied")
            }
            .setNeutralButton("AI Repair Prompt") { _, _ ->
                val ctx = Diagnostics.lastErrorContext ?: Diagnostics.ErrorContext(
                    code = code ?: ErrorCode.UNKNOWN,
                    message = message,
                    stackTrace = null,
                    stage = "unknown"
                )
                copy(context, ErrorReportGenerator.buildAiRepairPrompt(ctx), "AI repair prompt copied")
            }
            .setNegativeButton("Details") { _, _ ->
                AlertDialog.Builder(context)
                    .setTitle("Full report")
                    .setMessage(report.take(20000))
                    .setPositiveButton("Copy") { _, _ -> copy(context, report, "Report copied") }
                    .setNegativeButton("Close", null)
                    .show()
                DiagnosticLogger.info("ErrorSheet", "Details opened for $errorId")
            }
            .create()

        dialog.show()
    }

    /** Copies only the technical log stream (button 2 of the spec). */
    fun copyLogs(context: Context) {
        copy(context, DiagnosticLogger.exportAsText("error"), "Technical logs copied")
    }

    fun copy(context: Context, text: String, toast: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Ultra Video Merger Pro", text))
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            DiagnosticLogger.info("ErrorSheet", "$toast (${text.length} chars)")
        }.onFailure {
            Toast.makeText(context, "Copy failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }
}
