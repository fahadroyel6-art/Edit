package com.ultravidmerger.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.ultravidmerger.app.MainActivity
import com.ultravidmerger.app.databinding.FragmentMergeBinding
import com.ultravidmerger.app.merger.CompatibilityChecker
import com.ultravidmerger.app.storage.StorageManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MergeFragment : Fragment() {

    private var _binding: FragmentMergeBinding? = null
    private val binding get() = _binding!!

    private var analysis: CompatibilityChecker.MergeAnalysis? = null
    private var useLossless = true
    private var outputWidth = 1920
    private var outputHeight = 1080
    private var outputFps = 30

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMergeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = activity as? MainActivity ?: return
        val videos = activity.getSelectedVideos()

        analysis = CompatibilityChecker.analyzeCompatibility(videos)
        val a = analysis!!

        // Show analysis
        binding.tvTotalVideos.text = "${videos.size} টি ভিডিও"
        val totalDuration = videos.sumOf { it.durationMs }
        val totalSize = videos.sumOf { it.fileSize }
        binding.tvTotalDuration.text = "মোট দৈর্ঘ্য: ${formatDuration(totalDuration)}"
        binding.tvTotalSize.text = "মোট সাইজ: ${formatSize(totalSize)}"

        // Default filename
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        binding.etOutputName.setText("Merged_$timestamp")

        // Mode chips
        if (a.mode == CompatibilityChecker.MergeMode.LOSSLESS) {
            useLossless = true
            binding.chipLossless.isChecked = true
            binding.chipReencode.isEnabled = false
            binding.tvLosslessNote.text = "✅ সব ভিডিও compatible — কোনো quality loss হবে না"
            binding.tvLosslessNote.visibility = View.VISIBLE
            binding.cardReencodeOptions.visibility = View.GONE
        } else {
            useLossless = false
            binding.chipReencode.isChecked = true
            binding.chipLossless.isEnabled = false
            binding.tvLosslessNote.text = "⚠️ ${a.reason}"
            binding.tvLosslessNote.visibility = View.VISIBLE
            binding.cardReencodeOptions.visibility = View.VISIBLE

            outputWidth = a.recommendedResolution.first
            outputHeight = a.recommendedResolution.second
            outputFps = a.recommendedFps

            binding.chipRes1080.isChecked = outputHeight <= 1080
            binding.chipRes4K.isChecked = outputHeight > 1080
            binding.chipFps30.isChecked = outputFps <= 30
            binding.chipFps60.isChecked = outputFps > 30
        }

        binding.chipGroupMode.setOnCheckedStateChangeListener { _, checkedIds ->
            useLossless = checkedIds.contains(binding.chipLossless.id)
            binding.cardReencodeOptions.visibility =
                if (useLossless) View.GONE else View.VISIBLE
        }

        binding.chipGroupResolution.setOnCheckedStateChangeListener { _, checkedIds ->
            when {
                checkedIds.contains(binding.chipRes720.id) -> {
                    outputWidth = 1280; outputHeight = 720
                }
                checkedIds.contains(binding.chipRes1080.id) -> {
                    outputWidth = 1920; outputHeight = 1080
                }
                checkedIds.contains(binding.chipRes4K.id) -> {
                    outputWidth = 3840; outputHeight = 2160
                }
            }
        }

        binding.chipGroupFps.setOnCheckedStateChangeListener { _, checkedIds ->
            outputFps = if (checkedIds.contains(binding.chipFps60.id)) 60 else 30
        }

        binding.btnStart.setOnClickListener { startMerge() }
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
    }

    private fun startMerge() {
        val activity = activity as? MainActivity ?: return
        val videos = activity.getSelectedVideos()

        val name = binding.etOutputName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), "Output file name দাও", Toast.LENGTH_SHORT).show()
            return
        }

        // Storage check
        val estimated = videos.sumOf { it.fileSize }
        if (!StorageManager.hasEnoughSpace(requireContext(), estimated)) {
            Toast.makeText(
                requireContext(),
                "যথেষ্ট storage নেই। দরকার: ${formatSize(estimated)}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val cacheDir = File(requireContext().cacheDir, "merger").apply { mkdirs() }
        val outputPath = File(cacheDir, "$name.mp4").absolutePath

        // Delete stale output
        File(outputPath).takeIf { it.exists() }?.delete()

        val mode = if (useLossless) "LOSSLESS" else "REENCODE"

        activity.startMerge(
            videos,
            mode,
            outputPath,
            outputWidth,
            outputHeight,
            outputFps
        )
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
