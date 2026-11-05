package com.ultravidmerger.app.selftest

enum class TestStatus { PENDING, RUNNING, PASSED, FAILED, SKIPPED }

data class TestCase(
    val id: String,
    val title: String,
    val description: String
)

data class TestOutcome(
    val test: TestCase,
    val status: TestStatus = TestStatus.PENDING,
    val detail: String = "",
    val durationMs: Long = 0L,
    val measured: Map<String, String> = emptyMap()
)

data class TestReport(
    val startedAtMs: Long = System.currentTimeMillis(),
    val finishedAtMs: Long = 0L,
    val outcomes: List<TestOutcome> = emptyList()
) {
    val passed: Int get() = outcomes.count { it.status == TestStatus.PASSED }
    val failed: Int get() = outcomes.count { it.status == TestStatus.FAILED }
    val skipped: Int get() = outcomes.count { it.status == TestStatus.SKIPPED }
    val total: Int get() = outcomes.size

    fun summaryLine(): String = "$passed/$total passed, $failed failed, $skipped skipped"

    fun toText(): String = buildString {
        appendLine("ULTRA VIDEO MERGER PRO — AUTOMATED TEST REPORT")
        appendLine("Generated on-device · ${java.util.Date(startedAtMs)}")
        appendLine("Device: ${com.ultravidmerger.app.diag.Diagnostics.deviceSummary()}")
        appendLine("Result: ${summaryLine()}")
        appendLine("=".repeat(60))
        outcomes.forEach { o ->
            appendLine()
            appendLine("[${o.status}] ${o.test.id} — ${o.test.title}")
            appendLine("  ${o.test.description}")
            if (o.detail.isNotBlank()) appendLine("  detail: ${o.detail}")
            if (o.measured.isNotEmpty()) {
                o.measured.forEach { (k, v) -> appendLine("  $k = $v") }
            }
            appendLine("  duration: ${o.durationMs} ms")
        }
    }
}
