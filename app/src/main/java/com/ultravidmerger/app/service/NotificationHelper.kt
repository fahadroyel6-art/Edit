package com.ultravidmerger.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ultravidmerger.app.MainActivity
import com.ultravidmerger.app.R
import com.ultravidmerger.app.UltraVideoMergerApp
import com.ultravidmerger.app.diag.DiagnosticLogger

object NotificationHelper {

    private const val TAG = "NotificationHelper"

    private fun contentIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun action(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            ctx, code,
            Intent(ctx, MergeService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun progress(ctx: Context, state: MergeUiState): Notification {
        val pct = (state.progress * 100).toInt().coerceIn(0, 100)
        val paused = state.phase == JobPhase.PAUSED

        val b = NotificationCompat.Builder(ctx, UltraVideoMergerApp.CHANNEL_MERGE)
            .setContentTitle(if (paused) "Paused — Ultra Video Merger Pro" else "Merging — Ultra Video Merger Pro")
            .setContentText("${state.status}  ($pct%)")
            .setSmallIcon(R.drawable.ic_merge)
            .setContentIntent(contentIntent(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, pct, false)

        if (paused) {
            b.addAction(0, "Resume", action(ctx, MergeService.ACTION_RESUME, 101))
        } else {
            b.addAction(0, "Pause", action(ctx, MergeService.ACTION_PAUSE, 102))
        }
        b.addAction(0, "Cancel", action(ctx, MergeService.ACTION_CANCEL, 103))

        return b.build()
    }

    fun completed(ctx: Context, name: String, sizeLabel: String): Notification =
        NotificationCompat.Builder(ctx, UltraVideoMergerApp.CHANNEL_COMPLETE)
            .setContentTitle("✅ মার্জ সম্পন্ন")
            .setContentText("$name · $sizeLabel — Gallery তে সেভ হয়েছে")
            .setSmallIcon(R.drawable.ic_merge)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(ctx))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

    fun failed(ctx: Context, errorId: String, message: String): Notification =
        NotificationCompat.Builder(ctx, UltraVideoMergerApp.CHANNEL_COMPLETE)
            .setContentTitle("❌ ব্যর্থ — $errorId")
            .setContentText(message.take(120))
            .setSmallIcon(R.drawable.ic_merge)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(ctx))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

    fun thermalWarning(ctx: Context, text: String) {
        notify(ctx, 3001, NotificationCompat.Builder(ctx, UltraVideoMergerApp.CHANNEL_COMPLETE)
            .setContentTitle("🌡️ ডিভাইস গরম হচ্ছে")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_merge)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build())
    }

    fun post(ctx: Context, id: Int, n: Notification) = notify(ctx, id, n)

    private fun notify(ctx: Context, id: Int, n: Notification) {
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
        }.onFailure { DiagnosticLogger.warn(TAG, "notify failed: ${it.message}") }
    }
}
