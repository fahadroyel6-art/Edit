package com.ultravidmerger.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ultravidmerger.app.MainActivity
import com.ultravidmerger.app.databinding.FragmentSelftestBinding
import com.ultravidmerger.app.databinding.ItemTestBinding
import com.ultravidmerger.app.diag.DiagnosticLogger
import com.ultravidmerger.app.selftest.LongVideoTestSuite
import com.ultravidmerger.app.selftest.MergeTestSuite
import com.ultravidmerger.app.selftest.TestOutcome
import com.ultravidmerger.app.selftest.TestReport
import com.ultravidmerger.app.selftest.TestStatus
import kotlinx.coroutines.launch

/**
 * Module 9 — runs the real automated suite on-device and shows honest results.
 * Nothing is marked passed unless the engine actually ran and the output was probed.
 */
class SelfTestFragment : Fragment() {

    private var _b: FragmentSelftestBinding? = null
    private val b get() = _b!!
    private val adapter = TestAdapter()
    private var running = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentSelftestBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        b.rvTests.adapter = adapter
        b.btnBack.setOnClickListener { (activity as? MainActivity)?.openHome() }
        b.btnRun.setOnClickListener { if (!running) runSuite() }
        b.btnRunLong.setOnClickListener { if (!running) runLongSuite() }
        b.btnCopyReport.setOnClickListener {
            val r = lastReport ?: return@setOnClickListener
            ErrorSheet.copy(requireContext(), r.toText(), "Test report copied")
        }
        b.tvSummary.text = "এখনো চালানো হয়নি — ${MergeTestSuite.ALL.size} টি টেস্ট"
        b.tvNote.text =
            "এই টেস্টগুলো সত্যিই FFmpeg দিয়ে synthetic ক্লিপ বানায়, merge চালায় এবং FFprobe দিয়ে আউটপুট যাচাই করে। " +
                "ফিজিক্যাল ডিভাইসে চালানো ফলাফল সবচেয়ে নির্ভরযোগ্য।"
    }

    private var lastReport: TestReport? = null

    private fun runLongSuite() {
        running = true
        b.btnRun.isEnabled = false
        b.btnRunLong.isEnabled = false
        b.btnCopyReport.isEnabled = false
        b.progress.visibility = View.VISIBLE
        b.tvSummary.text = "Long-video suite চলছে (10m → 3h)…"
        b.tvSummary.setTextColor(0xFF38BDF8.toInt())

        lifecycleScope.launch {
            try {
                val report = LongVideoTestSuite.runAll(requireContext()) { outcome ->
                    lifecycleScope.launch { adapter.upsertLong(outcome) }
                }
                lastReport = report
                b.tvSummary.text = report.summaryLine()
                b.tvSummary.setTextColor(
                    if (report.failed == 0) 0xFF10B981.toInt() else 0xFFEF4444.toInt()
                )
                b.btnCopyReport.isEnabled = true
            } catch (e: Exception) {
                b.tvSummary.text = "Long suite crashed: ${e.message}"
                b.tvSummary.setTextColor(0xFFEF4444.toInt())
            } finally {
                running = false
                b.btnRun.isEnabled = true
                b.btnRunLong.isEnabled = true
                b.progress.visibility = View.GONE
            }
        }
    }

    private fun runSuite() {
        running = true
        b.btnRun.isEnabled = false
        b.btnRunLong.isEnabled = false
        b.btnCopyReport.isEnabled = false
        b.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val report = MergeTestSuite.runAll(requireContext()) { outcome ->
                    lifecycleScope.launch { adapter.upsert(outcome) }
                }
                lastReport = report
                b.tvSummary.text = report.summaryLine()
                b.tvSummary.setTextColor(
                    if (report.failed == 0) 0xFF10B981.toInt() else 0xFFEF4444.toInt()
                )
                b.btnCopyReport.isEnabled = true
                DiagnosticLogger.info("SelfTest", "Report: ${report.summaryLine()}")
            } catch (e: Exception) {
                b.tvSummary.text = "Suite crashed: ${e.message}"
                b.tvSummary.setTextColor(0xFFEF4444.toInt())
                DiagnosticLogger.error(
                    com.ultravidmerger.app.diag.ErrorCode.UNKNOWN, "SelfTest",
                    "Suite crash: ${e.message}", e
                )
            } finally {
                running = false
                b.btnRun.isEnabled = true
                b.btnRunLong.isEnabled = true
                b.progress.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

class TestAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<TestAdapter.VH>() {

    private val items = mutableListOf<TestOutcome>()

    init {
        MergeTestSuite.ALL.forEach { items += TestOutcome(it) }
    }

    class VH(val b: ItemTestBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

    fun upsert(o: TestOutcome) {
        val idx = items.indexOfFirst { it.test.id == o.test.id }
        if (idx < 0) return
        items[idx] = o
        notifyItemChanged(idx)
    }

    /** Long-video suite runs into the same list; append if not present. */
    fun upsertLong(o: TestOutcome) {
        val idx = items.indexOfFirst { it.test.id == o.test.id }
        if (idx >= 0) {
            items[idx] = o
            notifyItemChanged(idx)
        } else {
            items.add(o)
            notifyItemInserted(items.size - 1)
        }
    }

    fun clearAll() {
        items.clear()
        MergeTestSuite.ALL.forEach { items += TestOutcome(it) }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTestBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val o = items[position]
        h.b.tvId.text = o.test.id
        h.b.tvTitle.text = o.test.title
        h.b.tvDetail.text = if (o.detail.isBlank()) o.test.description else o.detail
        h.b.tvStatus.text = o.status.name
        h.b.tvStatus.setTextColor(
            when (o.status) {
                TestStatus.PASSED -> 0xFF10B981.toInt()
                TestStatus.FAILED -> 0xFFEF4444.toInt()
                TestStatus.RUNNING -> 0xFF38BDF8.toInt()
                TestStatus.SKIPPED -> 0xFFFBBF24.toInt()
                TestStatus.PENDING -> 0xFF6B7280.toInt()
            }
        )
        h.b.tvDuration.text = if (o.durationMs > 0) "${o.durationMs} ms" else ""
    }

    override fun getItemCount() = items.size
}
