package com.ultravidmerger.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ultravidmerger.app.MainActivity
import com.ultravidmerger.app.databinding.FragmentDiagnosticBinding
import com.ultravidmerger.app.diag.ComponentStatus
import com.ultravidmerger.app.diag.DiagnosticLogger
import com.ultravidmerger.app.diag.Diagnostics
import com.ultravidmerger.app.diag.EngineMonitor
import com.ultravidmerger.app.diag.SystemSnapshot
import com.ultravidmerger.app.diag.SystemMonitor
import kotlinx.coroutines.launch

/**
 * MODULE 15 §1 — Live Engine Monitor.
 *
 * The polling interval is 1 s and every read is a cheap counter/name lookup, so
 * the screen itself never becomes the reason a merge is slow.
 */
class DiagnosticCenterFragment : Fragment() {

    private var _b: FragmentDiagnosticBinding? = null
    private val b get() = _b!!
    private lateinit var componentAdapter: ComponentAdapter
    private var startedAt = 0L

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentDiagnosticBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        startedAt = SystemClock.elapsedRealtime()

        componentAdapter = ComponentAdapter()
        b.rvComponents.adapter = componentAdapter

        b.btnBack.setOnClickListener { (activity as? MainActivity)?.openHome() }
        b.btnLogs.setOnClickListener { (activity as? MainActivity)?.showFragment(LogViewerFragment()) }
        b.btnExportTxt.setOnClickListener { export(false) }
        b.btnExportJson.setOnClickListener { export(true) }
        b.btnCopyReport.setOnClickListener { copyLastError() }
        b.btnClearLogs.setOnClickListener { confirmClear() }
        b.btnSelfTest.setOnClickListener { (activity as? MainActivity)?.openSelfTest() }
        b.btnCrashReport.setOnClickListener { showLastCrash() }

        SystemMonitor.start(1000)

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { EngineMonitor.components.collect { componentAdapter.submit(it); renderCounters() } }
                launch { SystemMonitor.snapshot.collect { renderSystem(it) } }
                launch { EngineMonitor.currentStage.collect { b.tvStage.text = it } }
                launch { EngineMonitor.currentJobId.collect { b.tvJobId.text = it ?: "—" } }
                launch { EngineMonitor.lastSuccess.collect { b.tvLastSuccess.text = it } }
                launch { DiagnosticLogger.lastError.collect { renderLastError(it) } }
                launch { DiagnosticLogger.entries.collect { renderCounters() } }
            }
        }
    }

    private fun renderSystem(s: SystemSnapshot) {
        b.tvMemory.text = "Heap ${s.heapLabel()} (${s.heapPercent}%)"
        b.tvNativeHeap.text = "Native ${SystemSnapshot.formatBytes(s.nativeHeapBytes)}"
        b.tvCpu.text = "CPU ${s.cpuLoadPercent}%"
        b.tvStorage.text = "Free ${s.storageFreeLabel()}"
        b.tvThermal.text = "Thermal ${s.thermalLabel}"
        b.tvThermal.setTextColor(
            when {
                s.thermalStatus >= 4 -> 0xFFEF4444.toInt()
                s.thermalStatus >= 2 -> 0xFFFBBF24.toInt()
                else -> 0xFF10B981.toInt()
            }
        )
        b.tvBattery.text = if (s.batteryTempC > 0) "Battery ${s.batteryTempC}°C" else "Battery —"
        b.tvLowMem.visibility = if (s.lowMemory) View.VISIBLE else View.GONE
    }

    private fun renderCounters() {
        val logs = DiagnosticLogger.snapshot()
        b.tvCountInfo.text = "INFO ${logs.count { it.level == com.ultravidmerger.app.diag.DiagLevel.INFO }}"
        b.tvCountWarn.text = "WARN ${logs.count { it.level == com.ultravidmerger.app.diag.DiagLevel.WARNING }}"
        b.tvCountErr.text = "ERR ${logs.count { it.level == com.ultravidmerger.app.diag.DiagLevel.ERROR }}"
        b.tvCountCrit.text = "CRIT ${logs.count { it.level == com.ultravidmerger.app.diag.DiagLevel.CRITICAL }}"
        b.tvLogStorage.text = "লগ স্টোরেজ: ${SystemSnapshot.formatBytes(DiagnosticLogger.logDirSizeBytes())}"
        val errors = EngineMonitor.components.value.count { it.status == ComponentStatus.ERROR }
        b.tvHealth.text = if (errors == 0) "সব সিস্টেম ঠিক আছে" else "$errors টি কম্পোনেন্টে সমস্যা"
        b.tvHealth.setTextColor(if (errors == 0) 0xFF10B981.toInt() else 0xFFEF4444.toInt())
    }

    private fun renderLastError(e: com.ultravidmerger.app.diag.DiagLog?) {
        if (e == null) {
            b.cardLastError.visibility = View.GONE
            return
        }
        b.cardLastError.visibility = View.VISIBLE
        b.tvErrorCode.text = "${e.errorId ?: "—"}  ${e.tag}"
        b.tvErrorMsg.text = e.message.take(300)
        b.tvErrorTime.text = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(e.timestampMs))
    }

    private fun copyLastError() {
        val ctx = Diagnostics.lastErrorContext
        if (ctx == null) {
            ErrorSheet.copyLogs(requireContext()); return
        }
        ErrorSheet.copy(requireContext(), com.ultravidmerger.app.diag.ErrorReportGenerator.build(ctx), "Report copied")
    }

    private fun export(json: Boolean) {
        val f = DiagnosticLogger.writeExportFile(json)
        if (f == null) {
            android.widget.Toast.makeText(requireContext(), "Export ব্যর্থ", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", f
            )
        }.getOrNull()

        if (uri == null) {
            android.widget.Toast.makeText(requireContext(), "ফাইল তৈরি: ${f.name}", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = if (json) "application/json" else "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "Export diagnostics"))
    }

    private fun confirmClear() {
        AlertDialog.Builder(requireContext())
            .setTitle("লগ মুছে ফেলবেন?")
            .setMessage("সব diagnostic history ও ফাইল মুছে যাবে। এটি undo করা যাবে না।")
            .setPositiveButton("মুছুন") { _, _ ->
                DiagnosticLogger.clear()
                com.ultravidmerger.app.diag.CrashStore.clear()
                android.widget.Toast.makeText(requireContext(), "মুছে ফেলা হয়েছে", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun showLastCrash() {
        val crash = com.ultravidmerger.app.diag.CrashStore.lastCrash()
        if (crash == null) {
            android.widget.Toast.makeText(requireContext(), "কোনো crash report নেই", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Last crash report")
            .setMessage(crash.take(8000))
            .setPositiveButton("Copy") { _, _ -> ErrorSheet.copy(requireContext(), crash, "Crash report copied") }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onStop() { super.onStop(); SystemMonitor.stop() }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
