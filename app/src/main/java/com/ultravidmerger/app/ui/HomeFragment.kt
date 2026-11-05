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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ultravidmerger.app.MainActivity
import com.ultravidmerger.app.VideoInfo
import com.ultravidmerger.app.databinding.FragmentHomeBinding
import com.ultravidmerger.app.merger.VideoAnalyzer

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var videoAdapter: VideoListAdapter

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data?.clipData != null) {
                for (i in 0 until data.clipData!!.itemCount) {
                    val uri = data.clipData!!.getItemAt(i).uri
                    processSelectedVideo(uri)
                }
            } else if (data?.data != null) {
                processSelectedVideo(data.data!!)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()

        binding.btnSelectVideos.setOnClickListener { openVideoPicker() }
        binding.btnAddMore.setOnClickListener { openVideoPicker() }
        binding.btnStartMerge.setOnClickListener { startMerge() }

        updateUI()
    }

    private fun setupRecyclerView() {
        val activity = activity as? MainActivity ?: return
        val videos = activity.getSelectedVideos()

        videoAdapter = VideoListAdapter(videos.toMutableList()) { position ->
            activity.removeVideo(position)
            updateUI()
        }

        binding.rvVideos.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = videoAdapter
        }

        attachDragReorder(activity)
    }

    private fun attachDragReorder(activity: MainActivity) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                activity.moveVideo(from, to)
                videoAdapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.rvVideos)
    }

    private fun openVideoPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "video/mp4", "video/mkv", "video/quicktime",
                    "video/x-msvideo", "video/webm", "video/3gpp", "video/*"
                )
            )
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        videoPickerLauncher.launch(intent)
    }

    private fun processSelectedVideo(uri: Uri) {
        val activity = activity as? MainActivity ?: return

        if (activity.getSelectedVideos().any { it.uri == uri }) {
            Toast.makeText(requireContext(), "এই ভিডিওটি আগে থেকেই আছে", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }

        val videoInfo = VideoAnalyzer.analyzeVideo(requireContext(), uri)
        if (videoInfo != null) {
            activity.addVideo(videoInfo)
            updateUI()
            Toast.makeText(requireContext(), "যোগ হয়েছে: ${videoInfo.fileName}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "ভিডিও পড়া যায়নি (corrupted?)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMerge() {
        val activity = activity as? MainActivity ?: return
        val videos = activity.getSelectedVideos()

        if (videos.size < 2) {
            Toast.makeText(requireContext(), "কমপক্ষে ২টি ভিডিও সিলেক্ট করো", Toast.LENGTH_SHORT).show()
            return
        }

        activity.showFragment(MergeFragment())
    }

    private fun updateUI() {
        val activity = activity as? MainActivity ?: return
        val videos = activity.getSelectedVideos()

        videoAdapter.updateVideos(videos)

        binding.tvVideoCount.text = "${videos.size} টি ভিডিও সিলেক্ট করা হয়েছে"
        binding.btnStartMerge.isEnabled = videos.size >= 2
        binding.btnStartMerge.alpha = if (videos.size >= 2) 1f else 0.5f
        binding.tvEmpty.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
        binding.rvVideos.visibility = if (videos.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
