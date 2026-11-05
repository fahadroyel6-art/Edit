package com.ultravidmerger.app.diag

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile

/** Point-in-time snapshot of device resources. Cheap to collect (~1 ms). */
data class SystemSnapshot(
    val heapUsedBytes: Long = 0,
    val heapMaxBytes: Long = 0,
    val nativeHeapBytes: Long = 0,
    val storageFreeBytes: Long = 0,
    val storageTotalBytes: Long = 0,
    val cpuLoadPercent: Int = 0,
    val thermalStatus: Int = 0,
    val batteryTempC: Float = 0f,
    val lowMemory: Boolean = false
) {
    val heapPercent: Int get() = if (heapMaxBytes > 0) ((heapUsedBytes * 100) / heapMaxBytes).toInt() else 0

    val thermalLabel: String
        get() = when (thermalStatus) {
            0 -> "NONE"
            1 -> "LIGHT"
            2 -> "MODERATE"
            3 -> "SEVERE"
            4 -> "CRITICAL"
            5 -> "EMERGENCY"
            6 -> "SHUTDOWN"
            else -> "UNKNOWN"
        }

    fun storageFreeLabel(): String = formatBytes(storageFreeBytes)
    fun heapLabel(): String = "${formatBytes(heapUsedBytes)} / ${formatBytes(heapMaxBytes)}"

    companion object {
        fun formatBytes(b: Long): String = when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
            b < 1024L * 1024 * 1024 -> String.format("%.1f MB", b / (1024.0 * 1024))
            else -> String.format("%.2f GB", b / (1024.0 * 1024 * 1024))
        }
    }
}

/**
 * Polls memory / storage / CPU / thermal once per second while the Diagnostic
 * Center is visible. Deliberately cheap: no frame sampling, no heavy syscalls.
 */
object SystemMonitor {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null

    private val _snapshot = MutableStateFlow(SystemSnapshot())
    val snapshot: StateFlow<SystemSnapshot> = _snapshot

    @Volatile private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
        refreshOnce()
    }

    fun start(intervalMs: Long = 1000L) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                refreshOnce()
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Single cheap read — used before starting a job too. */
    fun refreshOnce() {
        val ctx = appContext ?: return
        val rt = Runtime.getRuntime()

        var free = 0L
        var total = 0L
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            free = stat.availableBlocksLong * stat.blockSizeLong
            total = stat.blockCountLong * stat.blockSizeLong
        }

        var thermal = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                thermal = pm.currentThermalStatus
            }
        }

        _snapshot.value = SystemSnapshot(
            heapUsedBytes = rt.totalMemory() - rt.freeMemory(),
            heapMaxBytes = rt.maxMemory(),
            nativeHeapBytes = android.os.Debug.getNativeHeapAllocatedSize(),
            storageFreeBytes = free,
            storageTotalBytes = total,
            cpuLoadPercent = readCpuLoadPercent(),
            thermalStatus = thermal,
            batteryTempC = readBatteryTemp(ctx),
            lowMemory = (rt.totalMemory() - rt.freeMemory()).toDouble() / rt.maxMemory() > 0.92
        )
    }

    private fun readCpuLoadPercent(): Int = runCatching {
        val load = File("/proc/loadavg").readText().split(" ").firstOrNull()?.toFloatOrNull() ?: 0f
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        ((load / cores) * 100f).toInt().coerceIn(0, 100)
    }.getOrDefault(0)

    private fun readBatteryTemp(ctx: Context): Float = runCatching {
        ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.let { if (it > 0) it / 10f else -1f } ?: -1f
    }.getOrDefault(-1f)

    /** True when we should warn the user that the device is getting hot. */
    fun shouldWarnThermal(): Boolean = _snapshot.value.thermalStatus >= 3

    fun isCriticalThermal(): Boolean = _snapshot.value.thermalStatus >= 4
}
