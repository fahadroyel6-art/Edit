package com.ultravidmerger.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ultravidmerger.app.MainActivity
import com.ultravidmerger.app.R
import com.ultravidmerger.app.UltraVideoMergerApp
import com.ultravidmerger.app.merger.LosslessMergeEngine
import com.ultravidmerger.app.merger.ReencodeMergeEngine
import com.ultravidmerger.app.storage.StorageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MergeService : Service() {

    companion object {
        private const val TAG = "MergeService"
        const val NOTIFICATION_ID = 2001
        const val COMPLETE_NOTIFICATION_ID = 2002

        const val ACTION_CANCEL = "com.ultravidmerger.CANCEL"
        const val ACTION_PAUSE = "com.ultravidmerger.PAUSE"
        const val ACTION_RESUME = "com.ultravidmerger.RESUME"

        @Volatile
        var currentState: LosslessMergeEngine.MergeState = LosslessMergeEngine.MergeState()

        @Volatile
        var isServiceRunning = false
            private set

        @Volatile
        var savedGalleryUri: String? = null
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var processingJob: Job? = null
    private var stateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_CANCEL -> {
                LosslessMergeEngine.cancel()
                ReencodeMergeEngine.cancel()
                processingJob?.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                LosslessMergeEngine.setPaused(true)
                NotificationHelper.updatePaused(this, currentState)
                return START_STICKY
            }
            ACTION_RESUME -> {
                LosslessMergeEngine.setPaused(false)
                return START_STICKY
            }
        }

        try {
            startForeground(NOTIFICATION_ID, NotificationHelper.build(this, "মার্জ শুরু হচ্ছে...", 0))
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }

        acquireWakeLock()

        val videoUris = intent?.getParcelableArrayListExtra<Uri>("video_uris")
        if (videoUris.isNullOrEmpty()) {
            Log.e(TAG, "No videos provided")
            stopSelf()
            return START_NOT_STICKY
        }

        val outputPath = intent.getStringExtra("output_path") ?: run {
            Log.e(TAG, "No output path")
            stopSelf()
            return START_NOT_STICKY
        }

        val mergeMode = intent.getStringExtra("merge_mode") ?: "LOSSLESS"
        val targetWidth = intent.getIntExtra("target_width", 1920)
        val targetHeight = intent.getIntExtra("target_height", 1080)
        val targetFps = intent.getIntExtra("target_fps", 30)

        Log.i(TAG, "Merge start: ${videoUris.size} videos, mode=$mergeMode, out=$outputPath")

        LosslessMergeEngine.resetState()

        // Mirror engine state into the static holder the UI polls
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            LosslessMergeEngine.state.collectLatest { s ->
                currentState = s
                if (s.isProcessing) {
                    NotificationHelper.update(this@MergeService, s)
                }
            }
        }

        processingJob?.cancel()
        processingJob = serviceScope.launch {
            performMerge(videoUris, outputPath, mergeMode, targetWidth, targetHeight, targetFps)
        }

        return START_STICKY
    }

    private suspend fun performMerge(
        videoUris: List<Uri>,
        outputPath: String,
        mergeMode: String,
        targetWidth: Int,
        targetHeight: Int,
        targetFps: Int
    ) {
        try {
            val result = when (mergeMode) {
                "LOSSLESS" -> LosslessMergeEngine.merge(this, videoUris, outputPath)
                "REENCODE" -> ReencodeMergeEngine.merge(
                    this, videoUris, outputPath, targetWidth, targetHeight, targetFps
                )
                else -> LosslessMergeEngine.Result.Error("Unknown merge mode: $mergeMode")
            }

            when (result) {
                is LosslessMergeEngine.Result.Success -> {
                    Log.i(TAG, "Merge success: ${result.outputPath}")

                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .format(Date())
                    val displayName = "Merged_$timestamp.mp4"

                    val uri = withContext(Dispatchers.IO) {
                        StorageManager.saveVideoToGallery(
                            this@MergeService,
                            File(result.outputPath),
                            displayName
                        )
                    }

                    if (uri != null) {
                        savedGalleryUri = uri.toString()
                        Log.i(TAG, "Saved to gallery: $uri")
                        NotificationHelper.complete(this, displayName)
                        // Keep the temp file so the completion screen can play / share it
                    } else {
                        Log.e(TAG, "Gallery save failed — keeping temp file")
                        NotificationHelper.failed(this, "Gallery তে সেভ করা যায়নি")
                    }
                }

                is LosslessMergeEngine.Result.Error -> {
                    Log.e(TAG, "Merge error: ${result.message}")
                    NotificationHelper.failed(this, result.message)
                }

                is LosslessMergeEngine.Result.Cancelled -> {
                    Log.i(TAG, "Merge cancelled by user")
                    try { File(outputPath).delete() } catch (_: Exception) {}
                    NotificationHelper.cancelled(this)
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Merge job cancelled")
            try { File(outputPath).delete() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "Merge failed", e)
            NotificationHelper.failed(this, e.message ?: "Unknown error")
        } finally {
            releaseWakeLock()
            isServiceRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "UltraVideoMerger::Merge"
            ).apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        stateJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        isServiceRunning = false
        super.onDestroy()
    }
}

/** All notification building lives here so the service file stays readable. */
internal object NotificationHelper {

    private fun contentIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun actionIntent(ctx: Context, action: String): PendingIntent =
        PendingIntent.getService(
            ctx, action.hashCode(),
            Intent(ctx, MergeService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun build(ctx: Context, text: String, progress: Int): Notification {
        val builder = NotificationCompat.Builder(ctx, UltraVideoMergerApp.CHANNEL_MERGE)
            .setContentTitle("Ultra Video Merger")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_merge)
            .setContentIntent(contentIntent(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress in 0..100) {
            builder.setProgress(100, progress, progress == 0)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun update(ctx: Context, state: LosslessMergeEngine.MergeState) {
        val pct = (state.progress * 100).toInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(ctx, UltraVideoMergerApp.CHANNEL_MERGE)
            .setContentTitle("Ultra Video Merger")
            .setContentText("${state.status} — $pct%")
            .setSmallIcon(R.drawable.ic_merge)
            .setContentIntent(contentIntent(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Pause", actionIntent(ctx, MergeService.ACTION_PAUSE))
            .addAction(0, "Cancel", actionIntent(ctx, MergeService.ACTION_CANCEL))
            .build()

        notify(ctx, MergeService.NOTIFICATION_ID, notification)
    }

    fun updatePaused(ctx: Context, state: LosslessMergeEngine.MergeState) {
        val pct = (state.progress * 100).toInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(ctx, UltraVideoMergerApp.CHANNEL_MERGE)
            .setContentTitle("Ultra Video Merger")
            .setContentText("Paused — $pct%")
            .setSmallIcon(R.drawable.ic_merge)
            .setContentIntent(contentIntent(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Resume", actionIntent(ctx, MergeService.ACTION_RESUME))
            .addAction(0, "Cancel", actionIntent(ctx, MergeService.ACTION_CANCEL))
            .build()

        notify(ctx, MergeService.NOTIFICATION_ID, notification)
    }

    fun complete(ctx: Context, fileName: String) {
        val notification = NotificationCompat.Builder(ctx, UltraVideoMergerApp.CHANNEL_COMPLETE)
            .setContentTitle("✅ মার্জ সম্পন্ন")
            .setContentText("$fileName Gallery তে সেভ হয়েছে")
            .setSmallIcon(R.drawable.ic_merge)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(ctx))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notify(ctx, MergeService.COMPLETE_NOTIFICATION_ID, notification)
    }

    fun failed(ctx: Context, message: String) {
        val notification = NotificationCompat.Builder(ctx, UltraVideoMergerApp.CHANNEL_COMPLETE)
            .setContentTitle("❌ মার্জ ব্যর্থ")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_merge)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(ctx))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notify(ctx, MergeService.COMPLETE_NOTIFICATION_ID, notification)
    }

    fun cancelled(ctx: Context) {
        val notification = NotificationCompat.Builder(ctx, UltraVideoMergerApp.CHANNEL_COMPLETE)
            .setContentTitle("মার্জ বাতিল হয়েছে")
            .setContentText("কোনো ফাইল তৈরি হয়নি")
            .setSmallIcon(R.drawable.ic_merge)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notify(ctx, MergeService.COMPLETE_NOTIFICATION_ID, notification)
    }

    private fun notify(ctx: Context, id: Int, n: Notification) {
        try {
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(id, n)
        } catch (e: Exception) {
            Log.w("NotificationHelper", "notify failed", e)
        }
    }
}
