package com.ultravidmerger.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ultravidmerger.app.MainActivity
import com.ultravidmerger.app.databinding.FragmentLogsBinding
import com.ultravidmerger.app.diag.DiagLevel
import com.ultravidmerger.app.diag.DiagLog
import com.ultravidmerger.app.diag.DiagnosticLogger
import kotlinx.coroutines.launch

/**
 * MODULE 15 §7 — searchable live log viewer with category filters and export.
 */
class LogViewerFragment : Fragment() {

    private var _b: FragmentLogsBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: LogAdapter

    private var minLevel: DiagLevel? = null
    private var query: String = ""

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentLogsBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        adapter = LogAdapter { entry ->
            ErrorSheet.copy(requireContext(), entry.format(), "Log line copied")
        }
        b.rvLogs.adapter = adapter

        b.btnBack.setOnClickListener { (activity as? MainActivity)?.openDiagnostics() }
        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { query = e?.toString().orEmpty(); reload() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
        })

        b.chipAll.setOnClickListener { minLevel = null; reload() }
        b.chipInfo.setOnClickListener { minLevel = DiagLevel.INFO; reload() }
        b.chipWarn.setOnClickListener { minLevel = DiagLevel.WARNING; reload() }
        b.chipError.setOnClickListener { minLevel = DiagLevel.ERROR; reload() }
        b.chipCritical.setOnClickListener { minLevel = DiagLevel.CRITICAL; reload() }

        b.btnCopyAll.setOnClickListener {
            ErrorSheet.copy(requireContext(), DiagnosticLogger.exportAsText(), "Full session copied")
        }
        b.btnExportTxt.setOnClickListener {
            DiagnosticLogger.writeExportFile(false)?.let { f ->
                ErrorSheet.copy(
                    requireContext(),
                    DiagnosticLogger.exportAsText(),
                    "Saved ${f.name} + copied"
                )
            }
        }
        b.btnExportJson.setOnClickListener {
            DiagnosticLogger.writeExportFile(true)?.let { f ->
                ErrorSheet.copy(requireContext(), DiagnosticLogger.exportAsJson(), "JSON saved ${f.name} + copied")
            }
        }

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DiagnosticLogger.entries.collect { reload() }
            }
        }
        reload()
    }

    private fun reload() {
        val list = DiagnosticLogger.filtered(minLevel, jobId = null, query = query)
        adapter.submit(list)
        b.tvCount.text = "${list.size} / ${DiagnosticLogger.snapshot().size} entries"
        if (list.isNotEmpty()) b.rvLogs.scrollToPosition(list.size - 1)
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

class LogAdapter(private val onClick: (DiagLog) -> Unit) :
    androidx.recyclerview.widget.RecyclerView.Adapter<LogAdapter.VH>() {

    private val items = mutableListOf<DiagLog>()
    class VH(val b: com.ultravidmerger.app.databinding.ItemLogBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

    fun submit(list: List<DiagLog>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(com.ultravidmerger.app.databinding.ItemLogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.b.tvLevel.text = e.level.name.take(4)
        h.b.tvLevel.setTextColor(
            when (e.level) {
                DiagLevel.INFO -> 0xFF9CA3AF.toInt()
                DiagLevel.WARNING -> 0xFFFBBF24.toInt()
                DiagLevel.ERROR -> 0xFFEF4444.toInt()
                DiagLevel.CRITICAL -> 0xFFFF2D55.toInt()
            }
        )
        h.b.tvTag.text = e.tag
        h.b.tvMsg.text = (e.errorId?.let { "[$it] " } ?: "") + e.message
        h.b.tvTime.text = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(e.timestampMs))
        h.itemView.setOnClickListener { onClick(e) }
    }

    override fun getItemCount() = items.size
}
