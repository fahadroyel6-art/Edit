package com.ultravidmerger.app.diag

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Facade that every engine/service/UI file talks to. Keeps the rest of the app
 * free of logging plumbing.
 */
object Diagnostics {

    private val jobCounter = AtomicLong(0)

    @Volatile var appVersionName: String = "2.0.0"
        private set
    @Volatile var appVersionCode: Long = 2L
        private set

    /** Context of the most recent failed operation, read by the report builder. */
    @Volatile var lastErrorContext: ErrorContext? = null

    data class ErrorContext(
        val code: ErrorCode,
        val message: String,
        val stackTrace: String?,
        val stage: String,
        val ffmpegCommand: String? = null,
        val ffmpegExitCode: Int? = null,
        val ffmpegLog: String? = null,
        val jobId: String? = null,
        val extra: Map<String, String> = emptyMap()
    )

    fun init(context: Context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            appVersionName = info.versionName ?: "2.0.0"
            appVersionCode = if (Build.VERSION.SDK_INT >= 28)
                info.longVersionCode else info.versionCode.toLong()
        }
        DiagnosticLogger.init(File(context.filesDir.absolutePath))
        SystemMonitor.attach(context)
        DiagnosticLogger.info(
            "System",
            "Device: ${deviceSummary()} | heap ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB"
        )
    }

    fun newJobId(): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "JOB-$ts-${jobCounter.incrementAndGet()}"
    }

    fun deviceSummary(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) | Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}) | ${Build.SUPPORTED_ABIS.joinToString(",")}"

    fun report(
        code: ErrorCode,
        stage: String,
        message: String,
        throwable: Throwable? = null,
        jobId: String? = null,
        ffmpegCommand: String? = null,
        ffmpegExitCode: Int? = null,
        ffmpegLog: String? = null,
        extra: Map<String, String> = emptyMap()
    ): ErrorContext {
        val ctx = ErrorContext(
            code = code,
            message = message,
            stackTrace = throwable?.stackTraceToString(),
            stage = stage,
            ffmpegCommand = ffmpegCommand,
            ffmpegExitCode = ffmpegExitCode,
            ffmpegLog = ffmpegLog,
            jobId = jobId ?: EngineMonitor.currentJobId.value,
            extra = extra
        )
        lastErrorContext = ctx

        DiagnosticLogger.log(
            DiagLevel.ERROR,
            stage,
            "${code.id} ${code.title}: $message",
            errorId = code.id,
            jobId = ctx.jobId,
            throwable = throwable
        )

        EngineMonitor.set(componentForStage(stage), ComponentStatus.ERROR, "${code.id}: $message")
        SystemMonitor.refreshOnce()
        return ctx
    }

    private fun componentForStage(stage: String): Component = when {
        stage.contains("ffmpeg", true) -> Component.FFMPEG
        stage.contains("probe", true) -> Component.FFPROBE
        stage.contains("analyz", true) -> Component.VIDEO_ANALYZER
        stage.contains("audio", true) -> Component.AUDIO_ENGINE
        stage.contains("mux", true) -> Component.MEDIA_MUXER
        stage.contains("extract", true) -> Component.MEDIA_EXTRACTOR
        stage.contains("storage", true) -> Component.STORAGE_MANAGER
        stage.contains("render", true) || stage.contains("service", true) -> Component.RENDER_SERVICE
        else -> Component.FFMPEG
    }

    /** True when the current thermal state should surface a UI warning. */
    fun thermalWarning(): String? = when {
        SystemMonitor.snapshot.value.thermalStatus >= 5 ->
            "ডিভাইস অনেক গরম — কাজ ধীর হবে বা থেমে যেতে পারে। ঠান্ডা হতে দাও।"
        SystemMonitor.snapshot.value.thermalStatus >= 3 ->
            "ডিভাইস গরম হচ্ছে — প্রসেসিং গতি কমানো হয়েছে।"
        else -> null
    }

    fun canWrite(context: Context, requiredBytes: Long): Boolean {
        val free = SystemMonitor.snapshot.value.storageFreeBytes
        return free > requiredBytes * 1.15
    }

    fun hasPermission(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
