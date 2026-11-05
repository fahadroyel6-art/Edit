package com.ultravidmerger.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.ultravidmerger.app.MainActivity
import com.ultravidmerger.app.databinding.FragmentCompletedBinding
import com.ultravidmerger.app.service.MergeService
import java.io.File

class CompletedFragment : Fragment() {

    private var _binding: FragmentCompletedBinding? = null
    private val binding get() = _binding!!

    private var outputPath: String = ""
    private var durationMs: Long = 0L
    private var fileSize: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outputPath = arguments?.getString(ARG_PATH) ?: ""
        durationMs = arguments?.getLong(ARG_DURATION) ?: 0L
        fileSize = arguments?.getLong(ARG_SIZE) ?: 0L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCompletedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvDuration.text = "দৈর্ঘ্য: ${formatTime(durationMs)}"
        binding.tvFileSize.text = "সাইজ: ${formatSize(fileSize)}"

        val galleryUri = MergeService.savedGalleryUri
        binding.tvSavedAs.text = if (galleryUri != null) {
            "Gallery তে সেভ হয়েছে (Movies/UltraVideoMerger)"
        } else {
            "⚠️ Gallery তে সেভ করা যায়নি"
        }

        binding.btnPlay.setOnClickListener { playVideo(galleryUri) }
        binding.btnShare.setOnClickListener { shareVideo(galleryUri) }
        binding.btnNewMerge.setOnClickListener {
            val activity = activity as? MainActivity ?: return@setOnClickListener
            activity.clearVideos()
            activity.showFragment(HomeFragment(), addToBackStack = false)
        }
        binding.btnBackHome.setOnClickListener {
            val activity = activity as? MainActivity ?: return@setOnClickListener
            activity.clearVideos()
            activity.showFragment(HomeFragment(), addToBackStack = false)
        }
    }

    private fun resolveUri(galleryUri: String?): Uri? {
        galleryUri?.let { return Uri.parse(it) }
        val file = File(outputPath)
        if (!file.exists()) return null
        return try {
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun playVideo(galleryUri: String?) {
        val uri = resolveUri(galleryUri) ?: run {
            android.widget.Toast.makeText(
                requireContext(), "ফাইল পাওয়া যায়নি", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Play with"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                requireContext(), "Play করা যায়নি: ${e.message}", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun shareVideo(galleryUri: String?) {
        val uri = resolveUri(galleryUri) ?: run {
            android.widget.Toast.makeText(
                requireContext(), "ফাইল পাওয়া যায়নি", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                requireContext(), "Share করা যায়নি: ${e.message}", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun formatTime(ms: Long): String {
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

    companion object {
        private const val ARG_PATH = "path"
        private const val ARG_DURATION = "duration"
        private const val ARG_SIZE = "size"

        fun newInstance(path: String, durationMs: Long, fileSize: Long): CompletedFragment {
            val f = CompletedFragment()
            f.arguments = Bundle().apply {
                putString(ARG_PATH, path)
                putLong(ARG_DURATION, durationMs)
                putLong(ARG_SIZE, fileSize)
            }
            return f
        }
    }
}
