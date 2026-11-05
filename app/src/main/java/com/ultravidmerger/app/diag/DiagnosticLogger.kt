package com.ultravidmerger.app.diag

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Rotating, bounded diagnostic log.
 *
 * - Keeps at most [MAX_MEMORY_ENTRIES] entries in memory (ring buffer).
 * - Writes to at most [MAX_FILES] files of [MAX_FILE_BYTES] each, so diagnostics
 *   can never eat the user's storage.
 * - Safe to call from any thread.
 */
object DiagnosticLogger {

    private const val TAG = "DiagnosticLogger"
    private const val MAX_MEMORY_ENTRIES = 3000
    private const val MAX_FILE_BYTES = 1_500_000L
    private const val MAX_FILES = 3

    private val seq = AtomicLong(0)
    private val buffer = Collections.synchronizedList(ArrayList<DiagLog>(MAX_MEMORY_ENTRIES))

    private val _entries = MutableStateFlow<List<DiagLog>>(emptyList())
    val entries: StateFlow<List<DiagLog>> = _entries

    private val _lastError = MutableStateFlow<DiagLog?>(null)
    val lastError: StateFlow<DiagLog?> = _lastError

    private var logDir: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(dir: File) {
        logDir = File(dir, "diagnostics").apply { mkdirs() }
        rotateIfNeeded()
        log(DiagLevel.INFO, "System", "Diagnostics initialised (v${Diagnostics.appVersionName})")
    }

    @Synchronized
    fun log(
        level: DiagLevel,
        tag: String,
        message: String,
        errorId: String? = null,
        jobId: String? = null,
        throwable: Throwable? = null
    ) {
        val entry = DiagLog(
            seq = seq.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            errorId = errorId,
            jobId = jobId
        )

        buffer.add(entry)
        while (buffer.size > MAX_MEMORY_ENTRIES) buffer.removeAt(0)
        _entries.value = ArrayList(buffer)

        if (level == DiagLevel.ERROR || level == DiagLevel.CRITICAL) {
            _lastError.value = entry
        }

        // Mirror to logcat so adb users can see the same stream
        when (level) {
            DiagLevel.INFO -> Log.i(tag, message)
            DiagLevel.WARNING -> Log.w(tag, message)
            DiagLevel.ERROR -> Log.e(tag, message, throwable)
            DiagLevel.CRITICAL -> Log.e(tag, "CRITICAL: $message", throwable)
        }

        appendToFile(entry, throwable)
    }

    fun info(tag: String, message: String, jobId: String? = null) =
        log(DiagLevel.INFO, tag, message, jobId = jobId)

    fun warn(tag: String, message: String, jobId: String? = null) =
        log(DiagLevel.WARNING, tag, message, jobId = jobId)

    fun error(code: ErrorCode, tag: String, message: String, t: Throwable? = null, jobId: String? = null) {
        val detail = buildString {
            append(message)
            if (t != null) append(" | ${t.javaClass.simpleName}: ${t.message}")
        }
        log(DiagLevel.ERROR, tag, detail, errorId = code.id, jobId = jobId, throwable = t)
    }

    fun critical(code: ErrorCode, tag: String, message: String, t: Throwable? = null, jobId: String? = null) =
        log(DiagLevel.CRITICAL, tag, message, errorId = code.id, jobId = jobId, throwable = t)

    // ---------------------------------------------------------------- queries

    fun snapshot(): List<DiagLog> = ArrayList(buffer)

    fun filtered(
        minLevel: DiagLevel? = null,
        jobId: String? = null,
        query: String? = null
    ): List<DiagLog> {
        val q = query?.trim()?.lowercase()
        return snapshot().filter { e ->
            (minLevel == null || e.level.ordinal >= minLevel.ordinal) &&
                (jobId == null || e.jobId == jobId) &&
                (q.isNullOrEmpty() ||
                    e.message.lowercase().contains(q) ||
                    e.tag.lowercase().contains(q) ||
                    (e.errorId?.lowercase()?.contains(q) == true))
        }
    }

    fun countByLevel(level: DiagLevel): Int = snapshot().count { it.level == level }

    // ---------------------------------------------------------------- export

    fun exportAsText(sessionName: String = "session"): String = buildString {
        appendLine("=========== ULTRA VIDEO MERGER PRO — DIAGNOSTIC LOG ===========")
        appendLine("Session   : $sessionName")
        appendLine("Exported  : ${fmt.format(Date())}")
        appendLine("App       : ${Diagnostics.appVersionName} (${Diagnostics.appVersionCode})")
        appendLine("Device    : ${Diagnostics.deviceSummary()}")
        appendLine("Entries   : ${buffer.size}")
        appendLine("==============================================================")
        snapshot().forEach { appendLine(it.format()) }
    }

    fun exportAsJson(): String {
        val arr = org.json.JSONArray()
        snapshot().forEach { e ->
            arr.put(org.json.JSONObject().apply {
                put("seq", e.seq)
                put("time", fmt.format(Date(e.timestampMs)))
                put("epochMs", e.timestampMs)
                put("level", e.level.name)
                put("tag", e.tag)
                put("errorId", e.errorId ?: "")
                put("jobId", e.jobId ?: "")
                put("message", ErrorReportGenerator.redact(e.message))
            })
        }
        return org.json.JSONObject().apply {
            put("app", Diagnostics.appVersionName)
            put("device", Diagnostics.deviceSummary())
            put("exportedAt", fmt.format(Date()))
            put("entries", arr)
        }.toString(2)
    }

    /** Writes the export to a shareable file and returns it. */
    fun writeExportFile(json: Boolean): File? {
        val dir = logDir ?: return null
        val name = "uvm_diagnostics_${System.currentTimeMillis()}.${if (json) "json" else "txt"}"
        return try {
            File(dir, name).apply {
                writeText(if (json) exportAsJson() else exportAsText())
            }
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            null
        }
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
        _lastError.value = null
        logDir?.listFiles()?.forEach { it.delete() }
        log(DiagLevel.INFO, "System", "Diagnostic history cleared by user")
    }

    // ---------------------------------------------------------------- files

    private fun currentFile(): File? {
        val dir = logDir ?: return null
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return File(dir, "diag_$day.log")
    }

    @Synchronized
    private fun appendToFile(entry: DiagLog, throwable: Throwable?) {
        runCatching {
            val f = currentFile() ?: return
            val line = buildString {
                appendLine("[${fmt.format(Date(entry.timestampMs))}] ${entry.level} ${entry.tag}: ${entry.message}")
                throwable?.let { t ->
                    appendLine(t.stackTraceToString().take(8000))
                }
            }
            f.appendText(line)
            rotateIfNeeded()
        }
    }

    @Synchronized
    private fun rotateIfNeeded() {
        val dir = logDir ?: return
        val files = dir.listFiles { f -> f.name.startsWith("diag_") }?.sortedBy { it.lastModified() } ?: return

        // Trim the active file
        files.lastOrNull()?.let { f ->
            if (f.length() > MAX_FILE_BYTES) {
                val keepFrom = f.length() - (MAX_FILE_BYTES / 2)
                runCatching {
                    val text = f.readText()
                    f.writeText(text.substring(keepFrom.coerceAtMost(text.length)))
                }
            }
        }

        // Drop the oldest beyond MAX_FILES
        if (files.size > MAX_FILES) {
            files.take(files.size - MAX_FILES).forEach { it.delete() }
        }

        // Hard cap on the whole directory
        val total = dir.listFiles()?.sumOf { it.length() } ?: 0L
        if (total > MAX_FILE_BYTES * MAX_FILES) {
            files.firstOrNull()?.delete()
        }
    }

    fun deleteAllHistory(): Boolean = runCatching {
        logDir?.listFiles()?.forEach { it.delete() }
        buffer.clear()
        _entries.value = emptyList()
        true
    }.getOrDefault(false)

    fun logDirSizeBytes(): Long = logDir?.listFiles()?.sumOf { it.length() } ?: 0L
}
