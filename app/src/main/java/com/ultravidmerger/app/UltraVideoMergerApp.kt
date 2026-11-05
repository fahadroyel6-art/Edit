package com.ultravidmerger.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class UltraVideoMergerApp : Application() {

    companion object {
        const val CHANNEL_MERGE = "merge_channel"
        const val CHANNEL_COMPLETE = "complete_channel"
        lateinit var instance: UltraVideoMergerApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mergeChannel = NotificationChannel(
                CHANNEL_MERGE,
                "Video Merging",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while merging videos"
                setShowBadge(false)
            }

            val completeChannel = NotificationChannel(
                CHANNEL_COMPLETE,
                "Merge Complete",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notification when merge is complete"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(mergeChannel)
            manager.createNotificationChannel(completeChannel)
        }
    }
}
