package com.ultravidmerger.app.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ultravidmerger.app.diag.Component
import com.ultravidmerger.app.diag.ComponentStatus
import com.ultravidmerger.app.diag.DiagnosticLogger
import com.ultravidmerger.app.diag.EngineMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

object StorageManager {

    private const val TAG = "StorageManager"
    private const val ALBUM = "UltraVideoMerger"

    /** Temp working file inside app cache — the engine always writes here first. */
    fun tempOutput(context: Context, jobId: String): File {
        val dir = File(context.cacheDir, "merge").apply { mkdirs() }
        return File(dir, "$jobId.mp4")
    }

    /** Streams [source] into the Gallery. Returns the content URI on success. */
    suspend fun saveToGallery(
        context: Context,
        source: File,
        displayName: String
    ): Uri? = withContext(Dispatchers.IO) {
        EngineMonitor.set(Component.STORAGE_MANAGER, ComponentStatus.BUSY, "Saving to gallery")
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.SIZE, source.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        try {
            val target = resolver.insert(collection, values) ?: run {
                EngineMonitor.set(Component.STORAGE_MANAGER, ComponentStatus.ERROR, "insert failed")
                return@withContext null
            }

            resolver.openOutputStream(target)?.use { out ->
                FileInputStream(source).use { input ->
                    val buf = ByteArray(256 * 1024) // 256 KB — bounded memory
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
            } ?: run {
                resolver.delete(target, null, null)
                return@withContext null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(target, values, null, null)
            }

            EngineMonitor.set(Component.STORAGE_MANAGER, ComponentStatus.OK, "Movies/$ALBUM")
            DiagnosticLogger.info(TAG, "Saved to gallery: $displayName (${source.length()} bytes)")
            target

        } catch (e: Exception) {
            DiagnosticLogger.error(
                com.ultravidmerger.app.diag.ErrorCode.INSUFFICIENT_STORAGE, TAG,
                "Gallery save failed: ${e.message}", e
            )
            EngineMonitor.set(Component.STORAGE_MANAGER, ComponentStatus.ERROR, e.message ?: "failed")
            null
        }
    }

    fun uniqueName(prefix: String = "Merged"): String {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        return "${prefix}_$ts.mp4"
    }

    fun freeBytes(): Long = runCatching {
        val stat = android.os.StatFs(Environment.getDataDirectory().absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    /** Cleans stale temp outputs older than 24h (interrupted jobs). */
    fun cleanupStaleTemps(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, "merge")
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        }
    }
}
