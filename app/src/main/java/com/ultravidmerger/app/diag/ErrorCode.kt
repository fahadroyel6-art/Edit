package com.ultravidmerger.app.diag

/**
 * Central registry of every diagnosable failure. Every error raised by the app
 * carries exactly one of these so reports are machine-readable and stable.
 */
enum class ErrorCode(
    val id: String,
    val title: String,
    val description: String,
    val likelyCause: String
) {
    VIDEO_EXPORT_FAILED(
        "UVM-1001",
        "VIDEO_EXPORT_FAILED",
        "The video export/merge operation did not produce a valid output file.",
        "Encoder/muxer rejected the stream, or the output file system failed mid-write."
    ),
    AUDIO_STREAM_ERROR(
        "UVM-1002",
        "AUDIO_STREAM_ERROR",
        "An audio track could not be read, copied or written.",
        "Missing audio track, unsupported audio codec, or mismatched sample rate across inputs."
    ),
    TRANSFORMER_THREAD_ERROR(
        "UVM-1003",
        "TRANSFORMER_THREAD_ERROR",
        "Media3 Transformer was accessed from a thread other than its owner looper.",
        "Transformer instance created or called from a background executor instead of the dedicated HandlerThread."
    ),
    INSUFFICIENT_STORAGE(
        "UVM-1004",
        "INSUFFICIENT_STORAGE",
        "Not enough free space to write the merged output.",
        "Output size is close to input size; free space check failed before or during processing."
    ),
    MEMORY_LIMIT_EXCEEDED(
        "UVM-1005",
        "MEMORY_LIMIT_EXCEEDED",
        "The process exceeded its available heap or native memory.",
        "A whole file or full-resolution frame buffer was loaded into RAM."
    ),
    BACKGROUND_SERVICE_INTERRUPTED(
        "UVM-1006",
        "BACKGROUND_SERVICE_INTERRUPTED",
        "The foreground rendering service was stopped before the job finished.",
        "OS killed the service, user swiped it away, or the app was force-stopped."
    ),
    VIDEO_CODEC_INCOMPATIBLE(
        "UVM-1007",
        "VIDEO_CODEC_INCOMPATIBLE",
        "Input streams cannot be joined without re-encoding.",
        "Different codec, resolution, frame rate, time base or audio parameters between inputs."
    ),
    OUTPUT_FILE_CORRUPTED(
        "UVM-1008",
        "OUTPUT_FILE_CORRUPTED",
        "The produced file failed post-processing validation.",
        "Muxer finalisation was interrupted, or the moov atom was never written."
    ),
    LONG_VIDEO_TIMEOUT(
        "UVM-1009",
        "LONG_VIDEO_TIMEOUT",
        "A long-running job stopped making progress and was treated as stalled.",
        "Progress watchdog fired; 32-bit timestamp overflow or a blocked native call."
    ),
    AUDIO_VIDEO_SYNC_ERROR(
        "UVM-1010",
        "AUDIO_VIDEO_SYNC_ERROR",
        "Audio and video timelines diverged beyond tolerance.",
        "Timestamps were not shifted per input, or audio started before/after video."
    ),
    FFMPEG_FAILURE(
        "UVM-1011",
        "FFMPEG_FAILURE",
        "FFmpeg returned a non-zero exit code.",
        "Unsupported container/codec, unreadable SAF URI, or malformed concat list."
    ),
    CORRUPTED_INPUT(
        "UVM-1012",
        "CORRUPTED_INPUT",
        "An input file could not be demuxed.",
        "Truncated download, bad container, or zero-length file."
    ),
    PERMISSION_DENIED(
        "UVM-1013",
        "PERMISSION_DENIED",
        "A required permission or persistable URI grant is missing.",
        "User revoked access, or the SAF grant was not persisted."
    ),
    NATIVE_ENGINE_FAILURE(
        "UVM-1014",
        "NATIVE_ENGINE_FAILURE",
        "A native media component crashed or failed to load.",
        "ABI mismatch, missing .so, or SIGSEGV inside libavcodec."
    ),
    JOB_ALREADY_RUNNING(
        "UVM-1015",
        "JOB_ALREADY_RUNNING",
        "A second merge was requested while one was already active.",
        "Concurrent job start; the engine is single-owner by design."
    ),
    VALIDATION_FAILED(
        "UVM-1016",
        "VALIDATION_FAILED",
        "Post-processing stream validation did not match expectations.",
        "Output duration or track count differs from the sum of the inputs."
    ),
    THERMAL_THROTTLE(
        "UVM-1017",
        "THERMAL_THROTTLE",
        "The device reported a critical thermal state during rendering.",
        "Sustained software encoding on a device with limited thermal headroom."
    ),
    UNKNOWN(
        "UVM-1999",
        "UNKNOWN_ERROR",
        "An unclassified failure occurred.",
        "No matching rule; inspect the attached stack trace."
    );

    companion object {
        fun fromId(id: String?): ErrorCode =
            entries.firstOrNull { it.id == id } ?: UNKNOWN

        fun fromTitle(title: String?): ErrorCode =
            entries.firstOrNull { it.title == title } ?: UNKNOWN
    }
}
