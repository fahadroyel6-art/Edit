package com.ultravidmerger.app.service

import android.content.Context
import com.ultravidmerger.app.diag.DiagnosticLogger

/**
 * Crash/interruption recovery (§5: "Persistent job state", "Recovery after
 * process interruption"). Written before work starts, cleared on clean finish.
 */
object MergeJobState {

    private const val PREFS = "uvm_job_state"
    private const val K_JOB = "job_id"
    private const val K_STAGE = "stage"
    private const val K_STARTED = "started_at"
    private const val K_TEMP = "temp_path"
    private const val K_ACTIVE = "active"

    data class Snapshot(
        val jobId: String,
        val stage: String,
        val startedAtMs: Long,
        val tempPath: String
    )

    fun begin(context: Context, jobId: String, tempPath: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_ACTIVE, true)
            .putString(K_JOB, jobId)
            .putString(K_STAGE, "starting")
            .putLong(K_STARTED, System.currentTimeMillis())
            .putString(K_TEMP, tempPath)
            .apply()
    }

    fun updateStage(context: Context, stage: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_STAGE, stage)
            .apply()
    }

    fun finish(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_ACTIVE, false)
            .apply()
    }

    /** Returns a snapshot if the previous run never finished. */
    fun interrupted(context: Context): Snapshot? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(K_ACTIVE, false)) return null
        return Snapshot(
            jobId = p.getString(K_JOB, "unknown") ?: "unknown",
            stage = p.getString(K_STAGE, "unknown") ?: "unknown",
            startedAtMs = p.getLong(K_STARTED, 0L),
            tempPath = p.getString(K_TEMP, "") ?: ""
        ).also {
            DiagnosticLogger.warn(
                "Recovery",
                "Interrupted job detected: ${it.jobId} at stage '${it.stage}'"
            )
        }
    }

    fun clearInterrupted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_ACTIVE, false)
            .apply()
    }
}
