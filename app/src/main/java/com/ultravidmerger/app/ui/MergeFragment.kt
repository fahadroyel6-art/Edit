package com.ultravidmerger.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.ultravidmerger.app.MainActivity
import com.ultravidmerger.app.VideoInfo
import com.ultravidmerger.app.databinding.FragmentMergeBinding
import com.ultravidmerger.app.diag.DiagnosticLogger
import com.ultravidmerger.app.diag.Diagnostics
import com.ultravidmerger.app.diag.EngineMonitor
import com.ultravidmerger.app.diag.ErrorCode
import com.ultravidmerger.app.diag.SystemMonitor
import com.ultravidmerger.app.engine.CompatibilityChecker
import com.ultravidmerger.app.engine.MergeMode
import com.ultravidmerger.app.service.MergeService
import com.ultravidmerger.app.service.MergeStateHolder
import com.ultravidmerger.app.service.MergeUiState
import com.ultravidmerger.app.storage.StorageManager

class MergeFragment : Fragment() {

    private var _b: FragmentMergeBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentMergeBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        val act = activity as? MainActivity ?: return
        val videos = act.videos()
        if (videos.size < 2) { act.openHome(); return }

        val report = CompatibilityChecker.analyze(videos)

        b.tvCount.text = "${videos.size} টি ভিডিও"
        b.tvDuration.text = "মোট দৈর্ঘ্য: ${VideoInfo.formatDuration(report.totalDurationMs)}"
        b.tvSize.text = "মোট সাইজ: ${VideoInfo.formatBytes(report.totalInputBytes)}"

        // Mode is decided by the checker, never by the user — that is what keeps
        // the "never re-encode unnecessarily" promise honest.
        if (report.canMergeLossless) {
            b.tvMode.text = "MODE A · LOSSLESS STREAM COPY"
            b.tvMode.setTextColor(0xFF10B981.toInt())
            b.tvModeDetail.text =
                "কোনো re-encode নেই। FFmpeg -c copy দিয়ে bit-for-bit copy হবে — " +
                    "resolution, FPS, bitrate, audio সব অপরিবর্তিত থাকবে।"
            b.cardReencode.visibility = View.GONE
        } else {
            b.tvMode.text = "MODE B · COMPATIBILITY RE-ENCODE"
            b.tvMode.setTextColor(0xFFFBBF24.toInt())
            b.tvModeDetail.text = "Lossless সম্ভব নয় কারণ:\n" +
                report.issues.take(6).joinToString("\n") { "• ${it.field}: ${it.detail}" }
            b.cardReencode.visibility = View.VISIBLE
            b.tvReencodeTarget.text =
                "Output: ${report.recommendedWidth}×${report.recommendedHeight} @ ${report.recommendedFps}fps\n" +
                    "Audio: AAC (সর্বোচ্চ quality preserved)"
        }

        val free = SystemMonitor.snapshot.value.storageFreeBytes
        b.tvStorage.text = "ফ্রি স্টোরেজ: ${VideoInfo.formatBytes(free)} · দরকার ≈${VideoInfo.formatBytes(report.totalInputBytes)}"
        if (free in 1..(report.totalInputBytes + report.totalInputBytes / 4)) {
            b.tvStorage.setTextColor(0xFFEF4444.toInt())
        }

        val name = "Merged_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        b.etOutputName.setText(name)

        b.btnStart.setOnClickListener { start(act, report) }
        b.btnBack.setOnClickListener { act.openHome() }
    }

    private fun start(act: MainActivity, report: com.ultravidmerger.app.engine.CompatibilityReport) {
        val videos = act.videos()
        val typed = b.etOutputName.text?.toString()?.trim().orEmpty()
        val jobId = Diagnostics.newJobId()
        val temp = StorageManager.tempOutput(requireContext(), jobId)

        DiagnosticLogger.info(
            "MergeFragment",
            "User started job: ${videos.size} inputs, mode=${report.mode}, name='$typed'"
        )

        MergeStateHolder.set(
            MergeUiState(
                phase = com.ultravidmerger.app.service.JobPhase.PREPARING,
                progress = 0f,
                status = "শুরু হচ্ছে…",
                jobId = jobId,
                lossless = report.mode == MergeMode.LOSSLESS,
                outputPath = temp.absolutePath
            )
        )

        MergeService.start(requireContext(), videos, report, temp.absolutePath)
        act.openProcessing()
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
