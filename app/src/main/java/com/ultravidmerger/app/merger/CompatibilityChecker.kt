package com.ultravidmerger.app.merger

import android.util.Log
import com.ultravidmerger.app.VideoInfo

object CompatibilityChecker {

    private const val TAG = "CompatChecker"

    enum class MergeMode {
        LOSSLESS,      // All videos are compatible, no re-encoding needed
        REENCODE,      // Need to re-encode to merge
        UNSUPPORTED    // Cannot merge at all
    }

    data class MergeAnalysis(
        val mode: MergeMode,
        val reason: String,
        val recommendedResolution: Pair<Int, Int>,
        val recommendedFps: Int
    )

    fun analyzeCompatibility(videos: List<VideoInfo>): MergeAnalysis {
        if (videos.size < 2) {
            return MergeAnalysis(
                mode = MergeMode.UNSUPPORTED,
                reason = "Need at least 2 videos to merge",
                recommendedResolution = Pair(1920, 1080),
                recommendedFps = 30
            )
        }

        // Check if all videos have the same properties
        val first = videos[0]
        val allSameResolution = videos.all { it.width == first.width && it.height == first.height }
        val allSameFps = videos.all { it.fps == first.fps }
        val allSameVideoCodec = videos.all { it.videoCodec == first.videoCodec }
        val allSameAudioCodec = videos.all { it.audioCodec == first.audioCodec || it.audioCodec == "none" }

        Log.i(TAG, "Video compatibility analysis:")
        Log.i(TAG, "  Same resolution: $allSameResolution")
        Log.i(TAG, "  Same FPS: $allSameFps")
        Log.i(TAG, "  Same video codec: $allSameVideoCodec")
        Log.i(TAG, "  Same audio codec: $allSameAudioCodec")

        // Lossless merge is possible if all videos have the same properties
        if (allSameResolution && allSameFps && allSameVideoCodec && allSameAudioCodec) {
            return MergeAnalysis(
                mode = MergeMode.LOSSLESS,
                reason = "All videos are compatible for lossless merge",
                recommendedResolution = Pair(first.width, first.height),
                recommendedFps = first.fps
            )
        }

        // Determine the best output resolution (highest resolution among all videos)
        val maxWidth = videos.maxOf { it.width }
        val maxHeight = videos.maxOf { it.height }
        val maxFps = videos.maxOf { it.fps }

        // Re-encode is needed
        val reasons = mutableListOf<String>()
        if (!allSameResolution) reasons.add("Different resolutions")
        if (!allSameFps) reasons.add("Different FPS")
        if (!allSameVideoCodec) reasons.add("Different video codecs")
        if (!allSameAudioCodec) reasons.add("Different audio codecs")

        return MergeAnalysis(
            mode = MergeMode.REENCODE,
            reason = "Incompatible videos: ${reasons.joinToString(", ")}. Will re-encode to ${maxWidth}x${maxHeight} @ ${maxFps}fps",
            recommendedResolution = Pair(maxWidth, maxHeight),
            recommendedFps = maxFps
        )
    }
}
