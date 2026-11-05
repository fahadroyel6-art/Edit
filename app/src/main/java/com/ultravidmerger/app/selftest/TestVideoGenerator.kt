package com.ultravidmerger.app.selftest

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.ultravidmerger.app.diag.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Generates deterministic synthetic clips with FFmpeg's lavfi sources so the
 * test suite never depends on the user's own media.
 *
 * Only encoders that exist in the free FFmpeg build are used (mpeg4 / aac),
 * and every clip is written into the app cache dir.
 */
object TestVideoGenerator {

    private const val TAG = "TestVideoGen"

    data class Spec(
        val name: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val seconds: Int,
        val videoCodec: String = "mpeg4",
        val withAudio: Boolean = true,
        val audioCodec: String = "aac",
        val sampleRate: Int = 44100,
        val channels: Int = 2,
        val toneHz: Int = 440
    )

    suspend fun generate(context: Context, spec: Spec): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "selftest").apply { mkdirs() }
        val out = File(dir, "${spec.name}.mp4")
        if (out.exists() && out.length() > 1024) return@withContext out

        val args = buildList {
            add("-y")
            add("-hide_banner")
            add("-loglevel"); add("error")
            add("-f"); add("lavfi")
            add("-i"); add("testsrc2=size=${spec.width}x${spec.height}:rate=${spec.fps}:duration=${spec.seconds}")
            if (spec.withAudio) {
                add("-f"); add("lavfi")
                add("-i"); add("sine=frequency=${spec.toneHz}:sample_rate=${spec.sampleRate}:duration=${spec.seconds}")
            }
            add("-c:v"); add(spec.videoCodec)
            add("-pix_fmt"); add("yuv420p")
            if (spec.withAudio) {
                add("-c:a"); add(spec.audioCodec)
                add("-ac"); add(spec.channels.toString())
                add("-ar"); add(spec.sampleRate.toString())
            }
            add("-shortest")
            add(out.absolutePath)
        }.toTypedArray()

        val session = FFmpegKit.executeWithArguments(args)
        val ok = ReturnCode.isSuccess(session.returnCode) && out.exists() && out.length() > 1024

        DiagnosticLogger.info(
            TAG,
            "Generated ${spec.name}: ok=$ok exit=${session.returnCode?.value} bytes=${out.length()}"
        )
        if (!ok) {
            DiagnosticLogger.warn(TAG, "Generator log: ${session.output?.take(600)}")
            null
        } else out
    }

    /** A deliberately broken file used by the corrupted-input test. */
    suspend fun generateCorrupted(context: Context): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "selftest").apply { mkdirs() }
        val f = File(dir, "corrupted.mp4")
        if (!f.exists() || f.length() == 0L) {
            // Valid MP4 magic bytes followed by noise — demuxers reject this.
            val header = byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D)
            val noise = ByteArray(4096) { (it * 37 % 251).toByte() }
            f.writeBytes(header + noise)
        }
        f
    }

    fun clear(context: Context) {
        runCatching { File(context.cacheDir, "selftest").deleteRecursively() }
    }
}
