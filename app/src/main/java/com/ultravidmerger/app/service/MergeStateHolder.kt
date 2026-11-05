package com.ultravidmerger.app.service

import com.ultravidmerger.app.diag.ErrorCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class JobPhase { IDLE, PREPARING, RUNNING, PAUSED, SAVING, DONE, FAILED, CANCELLED }

data class MergeUiState(
    val phase: JobPhase = JobPhase.IDLE,
    val progress: Float = 0f,
    val status: String = "প্রস্তুত",
    val elapsedMs: Long = 0L,
    val remainingMs: Long = 0L,
    val jobId: String? = null,
    val lossless: Boolean = true,
    val engineUsed: String = "",
    val outputPath: String? = null,
    val galleryUri: String? = null,
    val durationMs: Long = 0L,
    val fileBytes: Long = 0L,
    val errorCode: ErrorCode? = null,
    val errorMessage: String? = null,
    val errorReport: String? = null,
    val thermalWarning: String? = null
) {
    val isTerminal: Boolean get() = phase in setOf(JobPhase.DONE, JobPhase.FAILED, JobPhase.CANCELLED)
    val isActive: Boolean get() = phase in setOf(JobPhase.PREPARING, JobPhase.RUNNING, JobPhase.PAUSED, JobPhase.SAVING)
}

/**
 * Single source of truth for the render UI. Written by the service, read by
 * fragments. Using a StateFlow instead of a static field means the UI cannot
 * observe a torn state.
 */
object MergeStateHolder {

    private val _state = MutableStateFlow(MergeUiState())
    val state: StateFlow<MergeUiState> = _state

    fun update(transform: (MergeUiState) -> MergeUiState) {
        _state.value = transform(_state.value)
    }

    fun set(newState: MergeUiState) {
        _state.value = newState
    }

    fun reset() {
        _state.value = MergeUiState()
    }
}
