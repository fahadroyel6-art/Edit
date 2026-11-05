package com.ultravidmerger.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ultravidmerger.app.MainActivity
import com.ultravidmerger.app.VideoInfo
import com.ultravidmerger.app.databinding.FragmentProcessingBinding
import com.ultravidmerger.app.service.JobPhase
import com.ultravidmerger.app.service.MergeService
import com.ultravidmerger.app.service.MergeStateHolder
import kotlinx.coroutines.launch

class ProcessingFragment : Fragment() {

    private var _b: FragmentProcessingBinding? = null
    private val b get() = _b!!
    private var navigated = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentProcessingBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        b.btnCancel.setOnClickListener {
            MergeServiceAction.cancel(requireContext())
        }
        b.btnPause.setOnClickListener {
            val st = MergeStateHolder.state.value
            if (st.phase == JobPhase.PAUSED) MergeServiceAction.resume(requireContext())
            else MergeServiceAction.pause(requireContext())
        }
        b.btnError.setOnClickListener { showError() }

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                MergeStateHolder.state.collect { render(it) }
            }
        }
    }

    private fun render(st: com.ultravidmerger.app.service.MergeUiState) {
        val pct = (st.progress * 100).toInt().coerceIn(0, 100)
        b.progressBar.progress = pct
        b.tvPercent.text = "$pct%"
        b.tvStatus.text = st.status
        b.tvMode.text = if (st.lossless) "Lossless stream copy" else "Compatibility re-encode"
        b.tvElapsed.text = "সময়: ${VideoInfo.formatDuration(st.elapsedMs)}"
        b.tvRemaining.text =
            if (st.remainingMs > 0) "বাকি: ~${VideoInfo.formatDuration(st.remainingMs)}" else "বাকি: হিসাব হচ্ছে…"

        b.tvThermal.visibility = if (st.thermalWarning != null) View.VISIBLE else View.GONE
        st.thermalWarning?.let { b.tvThermal.text = "🌡️ $it" }

        b.btnPause.text = if (st.phase == JobPhase.PAUSED) "Resume" else "Pause"
        b.btnError.visibility =
            if (st.phase == JobPhase.FAILED && st.errorReport != null) View.VISIBLE else View.GONE
        b.tvErrorCode.visibility = b.btnError.visibility
        st.errorCode?.let { b.tvErrorCode.text = "${it.id} ${it.title}" }

        if (navigated) return
        val act = activity as? MainActivity ?: return

        when (st.phase) {
            JobPhase.DONE -> { navigated = true; act.openCompleted(st) }
            JobPhase.FAILED, JobPhase.CANCELLED -> {
                navigated = true
                if (st.phase == JobPhase.CANCELLED) act.openHome()
            }
            else -> Unit
        }
    }

    private fun showError() {
        val st = MergeStateHolder.state.value
        val report = st.errorReport ?: return
        ErrorSheet.show(requireContext(), report, st.errorCode, st.errorMessage ?: "")
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

object MergeServiceAction {
    fun pause(ctx: android.content.Context) = send(ctx, MergeService.ACTION_PAUSE)
    fun resume(ctx: android.content.Context) = send(ctx, MergeService.ACTION_RESUME)
    fun cancel(ctx: android.content.Context) = send(ctx, MergeService.ACTION_CANCEL)

    private fun send(ctx: android.content.Context, action: String) {
        runCatching {
            ctx.startService(
                android.content.Intent(ctx, MergeService::class.java).setAction(action)
            )
        }
    }
}
