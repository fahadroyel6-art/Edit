package com.ultravidmerger.app.diag

import android.util.Log

/**
 * Captures uncaught exceptions, writes a full diagnostic report to disk so the
 * user can still copy it after a restart, then hands control to the platform.
 */
class GlobalCrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            val ctx = Diagnostics.report(
                code = ErrorCode.NATIVE_ENGINE_FAILURE,
                stage = "crash",
                message = "Uncaught exception on thread '${thread.name}': ${throwable.message}",
                throwable = throwable
            )
            val report = ErrorReportGenerator.build(ctx)
            CrashStore.save(report)
            DiagnosticLogger.critical(
                ErrorCode.NATIVE_ENGINE_FAILURE, "Crash",
                "FATAL on ${thread.name}: ${throwable.message}", throwable
            )
        }
        Log.e("CrashHandler", "Uncaught", throwable)
        previous?.uncaughtException(thread, throwable)
    }
}

/** Persists the last crash report so the Diagnostic Center can surface it later. */
object CrashStore {

    private var dir: java.io.File? = null

    fun init(baseDir: java.io.File) {
        dir = java.io.File(baseDir, "crash").apply { mkdirs() }
    }

    fun save(report: String) {
        runCatching {
            dir?.let { d ->
                java.io.File(d, "last_crash.txt").writeText(report)
                java.io.File(d, "crash_${System.currentTimeMillis()}.txt").writeText(report)
                // keep at most 3 crash files
                d.listFiles { f -> f.name.startsWith("crash_") }
                    ?.sortedBy { it.lastModified() }
                    ?.dropLast(3)
                    ?.forEach { it.delete() }
            }
        }
    }

    fun lastCrash(): String? = runCatching {
        dir?.let { java.io.File(it, "last_crash.txt").takeIf { f -> f.exists() }?.readText() }
    }.getOrNull()

    fun clear() {
        dir?.listFiles()?.forEach { it.delete() }
    }
}
