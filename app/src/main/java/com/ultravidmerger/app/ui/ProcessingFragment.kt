package com.ultravidmerger.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.ultravidmerger.app.MainActivity
import com.ultravidmerger.app.databinding.FragmentProcessingBinding
import com.ultravidmerger.app.merger.LosslessMergeEngine
import com.ultravidmerger.app.service.MergeService

class ProcessingFragment : Fragment() {

    private var _binding: FragmentProcessingBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler(Looper.getMainLooper())
    private var startTime = 0L
    private var lastProgress = -1f
    private var completedNavigated = false
    private var paused = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollState()
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProcessingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        startTime = SystemClock.elapsedRealtime()

        binding.btnPause.setOnClickListener {
            val activity = activity as? MainActivity ?: return@setOnClickListener
            val intent = Intent(activity, MergeService::class.java).apply {
                action = if (paused) MergeService.ACTION_RESUME else MergeService.ACTION_PAUSE
            }
            activity.startService(intent)
            paused = !paused
            binding.btnPause.text = if (paused) "Resume" else "Pause"
        }

        binding.btnCancel.setOnClickListener {
            val activity = activity as? MainActivity ?: return@setOnClickListener
            val intent = Intent(activity, MergeService::class.java).apply {
                action = MergeService.ACTION_CANCEL
            }
            activity.startService(intent)
            LosslessMergeEngine.cancel()
            Toast.makeText(requireContext(), "Cancel করা হচ্ছে...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pollState() {
        val state = MergeService.currentState
        val progress = (state.progress * 100).toInt()

        binding.progressBar.progress = progress
        binding.tvPercent.text = "$progress%"
        binding.tvStatus.text = state.status

        val elapsed = SystemClock.elapsedRealtime() - startTime
        binding.tvElapsed.text = "সময়: ${formatTime(elapsed)}"

        if (state.progress > 0.02f) {
            val estimatedTotal = (elapsed / state.progress).toLong()
            val remaining = (estimatedTotal - elapsed).coerceAtLeast(0)
            binding.tvRemaining.text = "বাকি: ~${formatTime(remaining)}"
        } else {
            binding.tvRemaining.text = "বাকি: হিসাব হচ্ছে..."
        }

        lastProgress = state.progress

        if (state.isComplete && state.outputFilePath != null && !completedNavigated) {
            completedNavigated = true
            val activity = activity as? MainActivity ?: return
            handler.removeCallbacks(pollRunnable)
            activity.showCompleted(
                state.outputFilePath ?: "",
                state.outputDurationMs,
                state.outputFileSize
            )
        }

        if (state.error != null && !completedNavigated && !MergeService.isServiceRunning) {
            binding.tvStatus.text = "ব্যর্থ: ${state.error}"
            binding.tvStatus.setTextColor(0xFFFF5252.toInt())
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    override fun onResume() {
        super.onResume()
        handler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(pollRunnable)
        _binding = null
    }
}
