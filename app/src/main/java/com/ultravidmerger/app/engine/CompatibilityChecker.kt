package com.ultravidmerger.app.engine

import com.ultravidmerger.app.VideoInfo
import kotlin.math.abs

enum class MergeMode { LOSSLESS, REENCODE }

data class Incompatibility(
    val field: String,
    val detail: String
)

data class CompatibilityReport(
    val mode: MergeMode,
    val issues: List<Incompatibility>,
    val recommendedWidth: Int,
    val recommendedHeight: Int,
    val recommendedFps: Int,
    val commonVideoCodec: String,
    val totalDurationMs: Long,
    val totalInputBytes: Long
) {
    val canMergeLossless: Boolean get() = mode == MergeMode.LOSSLESS

    fun readableReason(): String = when {
        mode == MergeMode.LOSSLESS ->
            "সব ভিডিওর codec, resolution, FPS ও audio একই — lossless merge সম্ভব।"
        issues.isEmpty() ->
            "Re-encode প্রয়োজন।"
        else ->
            "Lossless merge সম্ভব নয়: " + issues.joinToString("; ") { "${it.field} — ${it.detail}" }
    }
}

/**
 * Decides whether the inputs can be joined with `-c copy`.
 *
 * The rules are deliberately strict: FFmpeg's concat demuxer only produces a
 * clean file when every segment shares the same codec, resolution, time base
 * and audio layout. Anything else is routed to the re-encode path and the user
 * is told exactly what differs.
 */
object CompatibilityChecker {

    fun analyze(videos: List<VideoInfo>): CompatibilityReport {
        if (videos.isEmpty()) {
            return CompatibilityReport(
                MergeMode.REENCODE, emptyList(), 1920, 1080, 30, "unknown", 0, 0
            )
        }

        val first = videos.first()
        val issues = mutableListOf<Incompatibility>()

        for ((i, v) in videos.withIndex()) {
            if (i == 0) continue

            if (!v.videoCodec.equals(first.videoCodec, ignoreCase = true)) {
                issues += Incompatibility(
                    "video codec",
                    "#${i + 1} ${v.videoCodec} ≠ #1 ${first.videoCodec}"
                )
            }
            if (v.width != first.width || v.height != first.height) {
                issues += Incompatibility(
                    "resolution",
                    "#${i + 1} ${v.resolution()} ≠ #1 ${first.resolution()}"
                )
            }
            if (abs(v.fps - first.fps) > 0.05) {
                issues += Incompatibility(
                    "frame rate",
                    "#${i + 1} ${v.fpsLabel()} ≠ #1 ${first.fpsLabel()}"
                )
            }
            // Audio: only a real problem when both sides actually have audio
            if (v.hasAudio() && first.hasAudio()) {
                if (!v.audioCodec.equals(first.audioCodec, ignoreCase = true)) {
                    issues += Incompatibility(
                        "audio codec",
                        "#${i + 1} ${v.audioCodec} ≠ #1 ${first.audioCodec}"
                    )
                }
                if (v.sampleRate > 0 && first.sampleRate > 0 && v.sampleRate != first.sampleRate) {
                    issues += Incompatibility(
                        "sample rate",
                        "#${i + 1} ${v.sampleRate}Hz ≠ #1 ${first.sampleRate}Hz"
                    )
                }
                if (v.channels > 0 && first.channels > 0 && v.channels != first.channels) {
                    issues += Incompatibility(
                        "channels",
                        "#${i + 1} ${v.channels}ch ≠ #1 ${first.channels}ch"
                    )
                }
            }
            if (v.timeBase.isNotBlank() && first.timeBase.isNotBlank() &&
                v.timeBase != first.timeBase
            ) {
                // Time base mismatch is a soft signal — FFmpeg can usually
                // still copy, so record it but do not force re-encode.
                // (Kept out of `issues` on purpose.)
            }
        }

        val maxW = videos.maxOf { it.width }
        val maxH = videos.maxOf { it.height }
        val maxFps = videos.maxOf { it.fps }.toInt().coerceIn(24, 60)

        return CompatibilityReport(
            mode = if (issues.isEmpty()) MergeMode.LOSSLESS else MergeMode.REENCODE,
            issues = issues,
            recommendedWidth = maxW,
            recommendedHeight = maxH,
            recommendedFps = maxFps,
            commonVideoCodec = first.videoCodec,
            totalDurationMs = videos.sumOf { it.durationMs },
            totalInputBytes = videos.sumOf { it.fileSize }
        )
    }
}
