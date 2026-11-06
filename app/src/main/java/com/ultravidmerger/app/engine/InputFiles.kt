package com.ultravidmerger.app.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.ultravidmerger.app.diag.DiagnosticLogger

/**
 * RC-3 fix — replaces ffmpeg-kit's `saf:` pseudo-protocol with real file
 * descriptors.
 *
 * Why this matters for long videos: the `concat` demuxer and the MP4 demuxer
 * both need to SEEK (a phone-recorded MP4 usually has its `moov` atom at the
 * END of the file). ffmpeg-kit's `saf:` handler is a Java-side stream wrapper
 * that is frequently non-seekable and is pathologically slow for multi-GB
 * input. Symptoms: the job either fails outright or crawls.
 *
 * FFmpegKit's native code runs *inside our process*, so `/proc/self/fd/<n>` is
 * a genuinely valid, seekable path to the same inode — zero copying, and the
 * kernel hands back a real file handle. This is how professional Android media
 * apps feed SAF content to FFmpeg.
 *
 * The descriptors stay open for the entire job and are closed in [closeAll].
 */
object InputFiles {

    data class Handle(
        val uri: Uri,
        val pfd: ParcelFileDescriptor,
        val path: String,
        val seekable: Boolean,
        val sizeBytes: Long
    )

    private val openHandles = mutableListOf<Handle>()

    /**
     * Opens every URI read-only and returns the `/proc/self/fd/N` paths.
     * Throws [IllegalStateException] with a user-readable message on failure;
     * already-opened descriptors are closed before throwing.
     */
    @Synchronized
    fun openAll(context: Context, uris: List<Uri>): List<Handle> {
        closeAll()
        val result = ArrayList<Handle>(uris.size)

        for ((index, uri) in uris.withIndex()) {
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                closeAll()
                throw IllegalStateException(
                    "Input ${index + 1} খোলা যায়নি (${e.javaClass.simpleName})। " +
                        "ফাইলটা কি local storage-এ আছে?"
                )
            } ?: run {
                closeAll()
                throw IllegalStateException("Input ${index + 1} খোলা যায়নি — URI grant নেই")
            }

            val seekable = probeSeekable(pfd)
            val size = runCatching { pfd.statSize }.getOrDefault(-1L)

            val handle = Handle(
                uri = uri,
                pfd = pfd,
                path = "/proc/self/fd/${pfd.fd}",
                seekable = seekable,
                sizeBytes = size
            )

            if (!seekable) {
                DiagnosticLogger.warn(
                    "InputFiles",
                    "Input ${index + 1} is NOT seekable (pipe). Provider likely streams " +
                        "from cloud. Long-video merge will be unreliable for this input."
                )
            }

            result += handle
            openHandles += handle

            DiagnosticLogger.info(
                "InputFiles",
                "Opened input ${index + 1}: fd=${pfd.fd} path=${handle.path} " +
                    "seekable=$seekable size=${size / (1024 * 1024)} MB"
            )
        }

        if (result.any { !it.seekable }) {
            DiagnosticLogger.warn(
                "InputFiles",
                "${result.count { !it.seekable }} of ${result.size} inputs are non-seekable"
            )
        }

        return result
    }

    @Synchronized
    fun closeAll() {
        for (h in openHandles) {
            runCatching { h.pfd.close() }
        }
        openHandles.clear()
    }

    @Synchronized
    fun isAnyNonSeekable(): Boolean = openHandles.any { !it.seekable }

    /**
     * F_DUPFD-independent check: `lseek(fd, 0, SEEK_CUR)` succeeds on a regular
     * file and fails with ESPIPE on a pipe/socket.
     */
    private fun probeSeekable(pfd: ParcelFileDescriptor): Boolean = try {
        Os.lseek(pfd.fd, 0L, OsConstants.SEEK_CUR)
        true
    } catch (e: ErrnoException) {
        false
    } catch (e: Exception) {
        false
    }
}
