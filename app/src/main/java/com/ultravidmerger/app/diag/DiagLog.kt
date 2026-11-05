package com.ultravidmerger.app.diag

enum class DiagLevel { INFO, WARNING, ERROR, CRITICAL }

data class DiagLog(
    val seq: Long,
    val timestampMs: Long,
    val level: DiagLevel,
    val tag: String,
    val message: String,
    val errorId: String? = null,
    val jobId: String? = null
) {
    fun format(): String {
        val t = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(timestampMs))
        val idPart = errorId?.let { "[$it] " } ?: ""
        return "$t ${level.name.padEnd(8)} $tag: $idPart$message"
    }
}
