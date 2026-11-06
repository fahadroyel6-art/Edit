package com.ultravidmerger.app.diag

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Rotating, bounded diagnostic log — v3 performance rewrite.
 *
 * v2 called `_entries.value = ArrayList(buffer)` (a 3000-element copy) and did a
 * synchronous `appendText()` (open/write/close) on EVERY log call. FFmpeg on a
 * 3-hour movie emits >100k lines, all funnelled through this class, which starved
 * ffmpeg-kit's shared callback executor so the *statistics* callback (progress)
 * never got scheduled. That is why the UI froze at 1-2% on long videos.
 *
 * v3 fixes:
 *   1. UI emission is coalesced — at most one StateFlow copy per 250 ms.
 *   2. Disk writes are buffered and flushed at most every 500 ms.
 *   3. Identical consecutive messages are collapsed into one entry.
 *   4. Callers can raise the minimum level to keep hot paths silent.
 */
object DiagnosticLogger {

    private const val TAG = "DiagnosticLogger"
    private const val MAX_MEMORY_ENTRIES = 2000
    private const val MAX_FILE_BYTES = 1_500_000L
    private const val MAX_FILES = 3
    private const val EMIT_INTERVAL_MS = 250L
    private const val FLUSH_INTERVAL_MS = 500L

    private val seq = AtomicLong(0)
    private val buffer = Collections.synchronizedList(ArrayList<DiagLog>(512))

    private val _entries = MutableStateFlow<List<DiagLog>>(emptyList())
    val entries: StateFlow<List<DiagLog>> = _entries

    private val _lastError = MutableStateFlow<DiagLog?>(null)
    val lastError: StateFlow<DiagLog?> = _lastError

    /** When set, entries below this level are dropped entirely. */
    @Volatile
    var minLevel: DiagLevel = DiagLevel.INFO

    private var logDir: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // ---- coalesced emission ----
    private val emitThread = HandlerThread("diag-emit").apply { start() }
    private val emitHandler = Handler(emitThread.looper)
    private val emitScheduled = AtomicBoolean(false)

    // ---- buffered file writes ----
    private val pending = StringBuilder(8192)
    private val flushGuard = Any()
    private val flushScheduled = AtomicBoolean(false)

    // ---- duplicate suppression ----
    private var lastSignature: String? = null
    private var lastSignatureAt = 0L
    private var duplicateCount = 0

    fun init(dir: File) {
        logDir = File(dir, "diagnostics").apply { mkdirs() }
        rotateIfNeeded()
        log(DiagLevel.INFO, "System", "Diagnostics initialised (v${Diagnostics.appVersionName})")
    }

    // ------------------------------------------------------------------ write

    fun log(
        level: DiagLevel,
        tag: String,
        message: String,
        errorId: String? = null,
        jobId: String? = null,
        throwable: Throwable? = null
    ) {
        if (level.ordinal < minLevel.ordinal) return

        val now = System.currentTimeMillis()

        // Collapse floods (e.g. "Non-monotonous DTS" repeated 50k times).
        val sig = "$level|$tag|$message"
        if (sig == lastSignature && now - lastSignatureAt < 3000L) {
            lastSignatureAt = now
            duplicateCount++
            return
        }
        if (duplicateCount > 0) {
            duplicateCount = 0
        }
        lastSignature = sig
        lastSignatureAt = now

        val entry = DiagLog(
            seq = seq.incrementAndGet(),
            timestampMs = now,
            level = level,
            tag = tag,
            message = message,
            errorId = errorId,
            jobId = jobId
        )

        buffer.add(entry)
        while (buffer.size > MAX_MEMORY_ENTRIES) buffer.removeAt(0)

        if (level == DiagLevel.ERROR || level == DiagLevel.CRITICAL) {
            _lastError.value = entry
            scheduleEmit(immediate = true)
        } else {
            scheduleEmit(immediate = false)
        }

        when (level) {
            DiagLevel.INFO -> Log.i(tag, message)
            DiagLevel.WARNING -> Log.w(tag, message)
            DiagLevel.ERROR -> Log.e(tag, message, throwable)
            DiagLevel.CRITICAL -> Log.e(tag, "CRITICAL: $message", throwable)
        }

        appendToBuffer(entry, throwable)
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

    // --------------------------------------------------------- emit pipeline

    private fun scheduleEmit(immediate: Boolean) {
        if (immediate) {
            if (emitScheduled.compareAndSet(false, true)) {
                emitHandler.postDelayed({
                    emitScheduled.set(false)
                    publishSnapshot()
                }, 0)
            }
            return
        }
        if (emitScheduled.compareAndSet(false, true)) {
            emitHandler.postDelayed({
                emitScheduled.set(false)
                publishSnapshot()
            }, EMIT_INTERVAL_MS)
        }
    }

    private fun publishSnapshot() {
        // One copy per interval instead of one per log line.
        _entries.value = synchronized(buffer) { ArrayList(buffer) }
    }

    private fun appendToBuffer(entry: DiagLog, throwable: Throwable?) {
        val line = buildString {
            append('[').append(fmt.format(Date(entry.timestampMs))).append("] ")
            append(entry.level).append(' ').append(entry.tag).append(": ").append(entry.message)
            append('\n')
            throwable?.let { append(it.stackTraceToString().take(6000)).append('\n') }
        }

        synchronized(flushGuard) {
            pending.append(line)
            if (pending.length > 256 * 1024) {
                // hard ceiling — drop the oldest half rather than growing forever
                pending.delete(0, pending.length / 2)
            }
        }

        if (flushScheduled.compareAndSet(false, true)) {
            emitHandler.postDelayed({
                flushScheduled.set(false)
                flushPending()
            }, FLUSH_INTERVAL_MS)
        }
    }

    private fun flushPending() {
        val chunk = synchronized(flushGuard) {
            if (pending.isEmpty()) return
            val s = pending.toString()
            pending.setLength(0)
            s
        }
        runCatching {
            val f = currentFile() ?: return
            f.appendText(chunk)
            rotateIfNeeded()
        }
    }

    /** Forces any buffered log text to disk. Call before exporting a report. */
    fun flushNow() = flushPending()

    // ---------------------------------------------------------------- queries

    fun snapshot(): List<DiagLog> = synchronized(buffer) { ArrayList(buffer) }

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

    fun exportAsText(sessionName: String = "session"): String {
        flushNow()
        return buildString {
            appendLine("=========== ULTRA VIDEO MERGER PRO — DIAGNOSTIC LOG ===========")
            appendLine("Session   : $sessionName")
            appendLine("Exported  : ${fmt.format(Date())}")
            appendLine("App       : ${Diagnostics.appVersionName} (${Diagnostics.appVersionCode})")
            appendLine("Device    : ${Diagnostics.deviceSummary()}")
            appendLine("Entries   : ${buffer.size}")
            appendLine("==============================================================")
            snapshot().forEach { appendLine(it.format()) }
        }
    }

    fun exportAsJson(): String {
        flushNow()
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

    fun writeExportFile(json: Boolean): File? {
        val dir = logDir ?: return null
        flushNow()
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

    fun clear() {
        flushNow()
        synchronized(buffer) { buffer.clear() }
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

    private fun rotateIfNeeded() {
        val dir = logDir ?: return
        val files = dir.listFiles { f -> f.name.startsWith("diag_") }
            ?.sortedBy { it.lastModified() } ?: return

        files.lastOrNull()?.let { f ->
            if (f.length() > MAX_FILE_BYTES) {
                val keepFrom = ((f.length() - MAX_FILE_BYTES / 2).coerceAtLeast(0L)).toInt()
                runCatching {
                    val text = f.readText()
                    f.writeText(text.substring(keepFrom.coerceAtMost(text.length)))
                }
            }
        }

        if (files.size > MAX_FILES) {
            files.take(files.size - MAX_FILES).forEach { it.delete() }
        }

        val total = dir.listFiles()?.sumOf { it.length() } ?: 0L
        if (total > MAX_FILE_BYTES * MAX_FILES) {
            files.firstOrNull()?.delete()
        }
    }

    fun deleteAllHistory(): Boolean = runCatching {
        flushNow()
        logDir?.listFiles()?.forEach { it.delete() }
        synchronized(buffer) { buffer.clear() }
        _entries.value = emptyList()
        true
    }.getOrDefault(false)

    fun logDirSizeBytes(): Long = logDir?.listFiles()?.sumOf { it.length() } ?: 0L
}
