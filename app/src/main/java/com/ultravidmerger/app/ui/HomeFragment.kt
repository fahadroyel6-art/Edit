package com.ultravidmerger.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ultravidmerger.app.MainActivity
import com.ultravidmerger.app.VideoInfo
import com.ultravidmerger.app.databinding.FragmentHomeBinding
import com.ultravidmerger.app.diag.DiagnosticLogger
import com.ultravidmerger.app.engine.VideoAnalyzer
import com.ultravidmerger.app.service.MergeStateHolder
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: VideoListAdapter

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = res.data ?: return@registerForActivityResult

        val uris = mutableListOf<Uri>()
        data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri }
        data.data?.let { if (uris.isEmpty()) uris += it }

        uris.forEach { addUri(it) }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentHomeBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        adapter = VideoListAdapter(mutableListOf()) { pos ->
            (activity as? MainActivity)?.removeVideo(pos)
            refresh()
        }

        b.rvVideos.layoutManager = LinearLayoutManager(requireContext())
        b.rvVideos.adapter = adapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = t.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                (activity as? MainActivity)?.moveVideo(from, to)
                adapter.moveItem(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, d: Int) = Unit
        }).attachToRecyclerView(b.rvVideos)

        b.btnSelectVideos.setOnClickListener { launchPicker() }
        b.btnAddMore.setOnClickListener { launchPicker() }
        b.btnStartMerge.setOnClickListener { startMerge() }
        b.btnDiagnostics.setOnClickListener { (activity as? MainActivity)?.openDiagnostics() }
        b.btnSelfTest.setOnClickListener { (activity as? MainActivity)?.openSelfTest() }
        b.btnResume.setOnClickListener { (activity as? MainActivity)?.openProcessing() }

        refresh()
    }

    private fun launchPicker() {
        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        })
    }

    private fun addUri(uri: Uri) {
        val act = activity as? MainActivity ?: return
        if (act.videos().any { it.uri == uri }) {
            toast("এই ভিডিও আগে থেকেই আছে"); return
        }
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        lifecycleScope.launch {
            val info = VideoAnalyzer.analyze(requireContext(), uri)
            if (_b == null) return@launch
            if (info == null) toast("ভিডিও পড়া যায়নি — Diagnostic Center দেখো")
            else { act.addVideo(info); refresh(); toast("যোগ হয়েছে: ${info.fileName}") }
        }
    }

    private fun startMerge() {
        val act = activity as? MainActivity ?: return
        if (act.videos().size < 2) { toast("কমপক্ষে ২টি ভিডিও দরকার"); return }
        if (MergeStateHolder.state.value.isActive) { act.openProcessing(); return }
        act.openMerge()
    }

    private fun refresh() {
        val act = activity as? MainActivity ?: return
        val videos = act.videos()
        adapter.update(videos)

        val totalMs = videos.sumOf { it.durationMs }
        val totalSize = videos.sumOf { it.fileSize }

        b.tvVideoCount.text = "${videos.size} টি ভিডিও · ${VideoInfo.formatDuration(totalMs)} · ${VideoInfo.formatBytes(totalSize)}"
        b.btnStartMerge.isEnabled = videos.size >= 2
        b.btnStartMerge.alpha = if (videos.size >= 2) 1f else 0.5f
        b.tvEmpty.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
        b.rvVideos.visibility = if (videos.isEmpty()) View.GONE else View.VISIBLE

        val st = MergeStateHolder.state.value
        b.cardResume.visibility = if (st.isActive) View.VISIBLE else View.GONE
        if (st.isActive) b.tvResume.text = "${st.status} · ${(st.progress * 100).toInt()}%"
    }

    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()

    override fun onResume() { super.onResume(); if (_b != null) refresh() }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
