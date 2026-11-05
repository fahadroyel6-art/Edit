package com.ultravidmerger.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import com.ultravidmerger.app.VideoInfo
import com.ultravidmerger.app.diag.Component
import com.ultravidmerger.app.diag.ComponentStatus
import com.ultravidmerger.app.diag.DiagnosticLogger
import com.ultravidmerger.app.diag.Diagnostics
import com.ultravidmerger.app.diag.EngineMonitor
import com.ultravidmerger.app.diag.ErrorCode
import com.ultravidmerger.app.diag.ServiceBridge
import com.ultravidmerger.app.diag.SystemMonitor
import com.ultravidmerger.app.engine.CompatibilityChecker
import com.ultravidmerger.app.engine.CompatibilityReport
import com.ultravidmerger.app.engine.MergeEngine
import com.ultravidmerger.app.storage.StorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground render service (Module 5).
 *
 * Responsibilities: own the job lifecycle, hold a wake lock, keep the
 * notification in sync, persist job state for crash recovery, and hand the
 * finished file to MediaStore after validation.
 */
class MergeService : Service() {

    companion object {
        private const val TAG = "MergeService"
        const val NOTIFICATION_ID = 2001
        const val ACTION_PAUSE = "com.ultravidmerger.PAUSE"
        const val ACTION_RESUME = "com.ultravidmerger.RESUME"
        const val ACTION_CANCEL = "com.ultravidmerger.CANCEL"

        const val EXTRA_URIS = "video_uris"
        const val EXTRA_OUTPUT = "output_path"
        const val EXTRA_MODE = "mode"
        const val EXTRA_W = "target_width"
        const val EXTRA_H = "target_height"
        const val EXTRA_FPS = "target_fps"
        const val EXTRA_TOTAL_MS = "total_duration_ms"
        const val EXTRA_TOTAL_BYTES = "total_bytes"
        const val EXTRA_ISSUES = "issues"

        fun start(
            context: Context,
            videos: List<VideoInfo>,
            report: CompatibilityReport,
            tempPath: String
        ) {
            val intent = Intent(context, MergeService::class.java).apply {
                putParcelableArrayListExtra(EXTRA_URIS, ArrayList(videos.map { it.uri }))
                putExtra(EXTRA_OUTPUT, tempPath)
                putExtra(EXTRA_MODE, report.mode.name)
                putExtra(EXTRA_W, report.recommendedWidth)
                putExtra(EXTRA_H, report.recommendedHeight)
                putExtra(EXTRA_FPS, report.recommendedFps)
                putExtra(EXTRA_TOTAL_MS, report.totalDurationMs)
                putExtra(EXTRA_TOTAL_BYTES, report.totalInputBytes)
                putExtra(
                    EXTRA_ISSUES,
                    report.issues.joinToString("; ") { "${it.field}: ${it.detail}" }
                )
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private var ticker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var startedAtMs = 0L
    private var pausedAgain = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceBridge.isRunning = true
        EngineMonitor.set(Component.RENDER_SERVICE, ComponentStatus.BUSY, "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                pausedAgain = true
                MergeStateHolder.update {
                    it.copy(phase = JobPhase.PAUSED, status = "Paused")
                }
                NotificationHelper.post(this, NOTIFICATION_ID, NotificationHelper.progress(this, MergeStateHolder.state.value))
                return START_STICKY
            }
            ACTION_RESUME -> {
                pausedAgain = false
                MergeStateHolder.update {
                    it.copy(phase = JobPhase.RUNNING, status = "Resuming…")
                }
                NotificationHelper.post(this, NOTIFICATION_ID, NotificationHelper.progress(this, MergeStateHolder.state.value))
                return START_STICKY
            }
            ACTION_CANCEL -> {
                DiagnosticLogger.warn(TAG, "Cancel pressed on notification")
                MergeEngine.cancel()
                MergeStateHolder.update { it.copy(phase = JobPhase.CANCELLED, status = "বাতিল হয়েছে") }
                return START_STICKY
            }
        }

        val uris = intent?.getParcelableArrayListExtra<Uri>(EXTRA_URIS)
        val outputPath = intent?.getStringExtra(EXTRA_OUTPUT)
        if (uris.isNullOrEmpty() || outputPath.isNullOrEmpty()) {
            DiagnosticLogger.warn(TAG, "Service started without a job; stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            NotificationHelper.progress(this, MergeStateHolder.state.value)
        )

        acquireWakeLock()
        StorageManager.cleanupStaleTemps(this)

        val jobId = MergeStateHolder.state.value.jobId ?: Diagnostics.newJobId()
        EngineMonitor.startJob(jobId)

        if (MergeEngine.isRunning()) {
            val ctx = Diagnostics.report(
                ErrorCode.JOB_ALREADY_RUNNING, "service",
                "A job is already running; second request ignored.", jobId = jobId
            )
            MergeStateHolder.update {
                it.copy(
                    phase = JobPhase.FAILED,
                    errorCode = ErrorCode.JOB_ALREADY_RUNNING,
                    errorMessage = "আগের কাজ এখনো চলছে",
                    errorReport = com.ultravidmerger.app.diag.ErrorReportGenerator.build(ctx)
                )
            }
            stopSelf()
            return START_NOT_STICKY
        }

        startedAtMs = System.currentTimeMillis()
        pausedAgain = false
        MergeJobState.begin(this, jobId, outputPath)

        MergeStateHolder.update {
            it.copy(
                phase = JobPhase.PREPARING,
                progress = 0f,
                status = "প্রস্তুত হচ্ছে…",
                jobId = jobId,
                elapsedMs = 0,
                outputPath = outputPath,
                errorCode = null,
                errorMessage = null,
                errorReport = null
            )
        }

        runJob(uris, outputPath, intent, jobId)
        return START_STICKY
    }

    private fun runJob(
        uris: List<Uri>,
        outputPath: String,
        intent: Intent,
        jobId: String
    ) {
        job = scope.launch {
            // elapsed/ETA ticker
            ticker = launch {
                while (isActive) {
                    val elapsed = System.currentTimeMillis() - startedAtMs
                    val p = MergeStateHolder.state.value.progress
                    val remaining = if (p > 0.02f) ((elapsed / p) * (1 - p)).toLong() else 0L
                    MergeStateHolder.update {
                        it.copy(
                            elapsedMs = elapsed,
                            remainingMs = remaining,
                            thermalWarning = Diagnostics.thermalWarning()
                        )
                    }
                    delay(500)
                }
            }

            try {
                // Build lightweight VideoInfo objects from the URIs for the engine
                val infos = uris.mapIndexed { i, uri ->
                    VideoInfo(
                        uri = uri,
                        fileName = "input_${i + 1}",
                        durationMs = 0L,
                        width = 0,
                        height = 0,
                        fps = 0.0,
                        videoCodec = "",
                        audioCodec = "",
                        fileSize = 0L,
                        videoBitrate = 0,
                        audioBitrate = 0,
                        sampleRate = 0,
                        channels = 0
                    )
                }

                // Prefer the report handed over by the UI; fall back to recomputing
                val report = CompatibilityReport(
                    mode = runCatching {
                        com.ultravidmerger.app.engine.MergeMode.valueOf(
                            intent.getStringExtra(EXTRA_MODE) ?: "LOSSLESS"
                        )
                    }.getOrDefault(com.ultravidmerger.app.engine.MergeMode.LOSSLESS),
                    issues = emptyList(),
                    recommendedWidth = intent.getIntExtra(EXTRA_W, 1920),
                    recommendedHeight = intent.getIntExtra(EXTRA_H, 1080),
                    recommendedFps = intent.getIntExtra(EXTRA_FPS, 30),
                    commonVideoCodec = "",
                    totalDurationMs = intent.getLongExtra(EXTRA_TOTAL_MS, 0L),
                    totalInputBytes = intent.getLongExtra(EXTRA_TOTAL_BYTES, 0L)
                )

                MergeStateHolder.update {
                    it.copy(
                        phase = JobPhase.RUNNING,
                        lossless = report.mode == com.ultravidmerger.app.engine.MergeMode.LOSSLESS,
                        status = if (report.mode == com.ultravidmerger.app.engine.MergeMode.LOSSLESS)
                            "Lossless merge চলছে…" else "Re-encode merge চলছে…"
                    )
                }

                val outcome = MergeEngine.execute(
                    this@MergeService,
                    MergeEngine.Request(infos, outputPath, report)
                ) { progress, status ->
                    if (pausedAgain) return@execute
                    MergeStateHolder.update {
                        it.copy(
                            phase = JobPhase.RUNNING,
                            progress = progress.coerceIn(0f, 1f),
                            status = status
                        )
                    }
                    NotificationHelper.post(
                        this@MergeService, NOTIFICATION_ID,
                        NotificationHelper.progress(this@MergeService, MergeStateHolder.state.value)
                    )
                }

                handleOutcome(outcome, outputPath, jobId)

            } catch (e: CancellationException) {
                DiagnosticLogger.warn(TAG, "Job cancelled by coroutine")
                MergeStateHolder.update { it.copy(phase = JobPhase.CANCELLED, status = "বাতিল হয়েছে") }
            } catch (e: Exception) {
                val ctx = Diagnostics.report(
                    ErrorCode.UNKNOWN, "service", "Service crash: ${e.message}", e, jobId
                )
                MergeStateHolder.update {
                    it.copy(
                        phase = JobPhase.FAILED,
                        errorCode = ErrorCode.UNKNOWN,
                        errorMessage = e.message,
                        errorReport = com.ultravidmerger.app.diag.ErrorReportGenerator.build(ctx)
                    )
                }
                NotificationHelper.post(
                    this@MergeService, NOTIFICATION_ID + 1,
                    NotificationHelper.failed(this@MergeService, "UVM-1999", e.message ?: "unknown")
                )
            } finally {
                ticker?.cancel()
                MergeJobState.finish(this@MergeService)
                releaseWakeLock()
                ServiceBridge.isRunning = false
                EngineMonitor.set(Component.RENDER_SERVICE, ComponentStatus.IDLE, "stopped")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun handleOutcome(
        outcome: MergeEngine.Outcome,
        outputPath: String,
        jobId: String
    ) {
        when (outcome) {
            is MergeEngine.Outcome.Success -> {
                MergeStateHolder.update {
                    it.copy(
                        phase = JobPhase.SAVING,
                        progress = 0.99f,
                        status = "Gallery তে সেভ হচ্ছে…",
                        outputPath = outcome.outputPath,
                        durationMs = outcome.durationMs,
                        fileBytes = outcome.fileBytes,
                        engineUsed = outcome.engineUsed,
                        lossless = outcome.lossless
                    )
                }

                val name = StorageManager.uniqueName()
                val galleryUri = withContext(Dispatchers.IO) {
                    StorageManager.saveToGallery(this@MergeService, File(outputPath), name)
                }

                if (galleryUri == null) {
                    val ctx = Diagnostics.report(
                        ErrorCode.INSUFFICIENT_STORAGE, "storage",
                        "Merged file was produced but could not be written to the Gallery.",
                        jobId = jobId
                    )
                    MergeStateHolder.update {
                        it.copy(
                            phase = JobPhase.FAILED,
                            errorCode = ErrorCode.INSUFFICIENT_STORAGE,
                            errorMessage = "Gallery তে সেভ করা যায়নি",
                            errorReport = com.ultravidmerger.app.diag.ErrorReportGenerator.build(ctx)
                        )
                    }
                    NotificationHelper.post(
                        this, NOTIFICATION_ID + 1,
                        NotificationHelper.failed(this, "UVM-1004", "Gallery তে সেভ করা যায়নি")
                    )
                } else {
                    MergeStateHolder.update {
                        it.copy(
                            phase = JobPhase.DONE,
                            progress = 1f,
                            status = "সম্পন্ন",
                            galleryUri = galleryUri.toString()
                        )
                    }
                    EngineMonitor.finishJob(true, "${outcome.engineUsed} → ${VideoInfo.formatBytes(outcome.fileBytes)}")
                    NotificationHelper.post(
                        this, NOTIFICATION_ID + 1,
                        NotificationHelper.completed(this, name, VideoInfo.formatBytes(outcome.fileBytes))
                    )
                    // temp copy no longer needed; the Gallery copy is authoritative
                    runCatching { File(outputPath).delete() }
                }
            }

            is MergeEngine.Outcome.Failure -> {
                MergeStateHolder.update {
                    it.copy(
                        phase = JobPhase.FAILED,
                        progress = 0f,
                        status = "ব্যর্থ",
                        errorCode = outcome.code,
                        errorMessage = outcome.message,
                        errorReport = outcome.report
                    )
                }
                EngineMonitor.finishJob(false, "${outcome.code.id} ${outcome.message}")
                NotificationHelper.post(
                    this, NOTIFICATION_ID + 1,
                    NotificationHelper.failed(this, outcome.code.id, outcome.message)
                )
            }

            MergeEngine.Outcome.Cancelled -> {
                MergeStateHolder.update { it.copy(phase = JobPhase.CANCELLED, status = "বাতিল হয়েছে") }
                runCatching { File(outputPath).delete() }
                EngineMonitor.finishJob(false, "cancelled")
            }
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UVM::merge").apply {
                setReferenceCounted(false)
                acquire(12L * 60 * 60 * 1000) // 12h ceiling; not a processing limit
            }
            DiagnosticLogger.info(TAG, "Wake lock acquired")
        }.onFailure { DiagnosticLogger.warn(TAG, "Wake lock failed: ${it.message}") }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    override fun onDestroy() {
        DiagnosticLogger.info(TAG, "Service destroyed")
        runCatching { MergeEngine.cancel() }
        ticker?.cancel()
        scope.cancel()
        releaseWakeLock()
        ServiceBridge.isRunning = false
        EngineMonitor.set(Component.RENDER_SERVICE, ComponentStatus.OFFLINE, "destroyed")
        super.onDestroy()
    }
}
