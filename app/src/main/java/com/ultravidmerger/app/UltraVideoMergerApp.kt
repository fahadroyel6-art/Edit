package com.ultravidmerger.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.ultravidmerger.app.diag.CrashStore
import com.ultravidmerger.app.diag.Diagnostics
import com.ultravidmerger.app.diag.GlobalCrashHandler

class UltraVideoMergerApp : Application() {

    companion object {
        const val CHANNEL_MERGE = "uvm_merge"
        const val CHANNEL_COMPLETE = "uvm_complete"

        lateinit var instance: UltraVideoMergerApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        createChannels()

        // Module 15: diagnostics come up before anything else so early
        // failures are still captured.
        Diagnostics.init(this)
        CrashStore.init(filesDir)

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(prev))
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MERGE,
                "Video merging",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress while merging videos"
                setShowBadge(false)
                setSound(null, null)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_COMPLETE,
                "Merge results",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Completion and failure notifications" }
        )
    }
}
