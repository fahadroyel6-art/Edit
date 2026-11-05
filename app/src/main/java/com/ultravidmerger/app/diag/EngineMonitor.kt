package com.ultravidmerger.app.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Component(val displayName: String) {
    FFMPEG("FFmpeg Engine"),
    FFPROBE("FFprobe"),
    VIDEO_ANALYZER("Video Analyzer"),
    AUDIO_ENGINE("Audio Processing"),
    RENDER_SERVICE("Background Rendering"),
    STORAGE_MANAGER("Storage Manager"),
    MEDIA_EXTRACTOR("MediaExtractor"),
    MEDIA_MUXER("MediaMuxer"),
    MEMORY("Memory"),
    CPU("CPU Load"),
    THERMAL("Thermal Status")
}

enum class ComponentStatus { OFFLINE, IDLE, BUSY, OK, WARN, ERROR }

data class ComponentState(
    val component: Component,
    val status: ComponentStatus = ComponentStatus.IDLE,
    val detail: String = "—",
    val lastUpdatedMs: Long = 0L
)

/**
 * Live status board for every subsystem the Diagnostic Center displays.
 * Event-driven: components report their own state, nothing is polled here.
 */
object EngineMonitor {

    private val lock = Any()
    private val states = linkedMapOf<Component, ComponentState>()

    private val _components = MutableStateFlow<List<ComponentState>>(emptyList())
    val components: StateFlow<List<ComponentState>> = _components

    private val _currentStage = MutableStateFlow("Idle")
    val currentStage: StateFlow<String> = _currentStage

    private val _currentJobId = MutableStateFlow<String?>(null)
    val currentJobId: StateFlow<String?> = _currentJobId

    private var lastSuccess: String = "—"
    private val _lastSuccessFlow = MutableStateFlow("—")
    val lastSuccess: StateFlow<String> = _lastSuccessFlow

    init {
        synchronized(lock) {
            Component.entries.forEach { states[it] = ComponentState(it) }
        }
        publish()
    }

    fun set(component: Component, status: ComponentStatus, detail: String = "") {
        synchronized(lock) {
            val prev = states[component] ?: ComponentState(component)
            states[component] = prev.copy(
                status = status,
                detail = detail.ifBlank { prev.detail },
                lastUpdatedMs = System.currentTimeMillis()
            )
        }
        publish()
    }

    fun setStage(stage: String) {
        _currentStage.value = stage
        DiagnosticLogger.info("Stage", stage, _currentJobId.value)
    }

    fun startJob(jobId: String) {
        _currentJobId.value = jobId
        DiagnosticLogger.info("Job", "Job started: $jobId", jobId)
    }

    fun finishJob(success: Boolean, summary: String) {
        val job = _currentJobId.value
        if (success) {
            lastSuccess = summary
            _lastSuccessFlow.value = summary
        }
        DiagnosticLogger.info("Job", "Job finished (success=$success): $summary", job)
        _currentJobId.value = null
        _currentStage.value = "Idle"
    }

    fun markSuccess(what: String) {
        lastSuccess = what
        _lastSuccessFlow.value = what
    }

    private fun publish() {
        synchronized(lock) { _components.value = states.values.toList() }
    }
}
