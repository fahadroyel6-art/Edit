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
import com.ultravidmerger.app.VideoInfo
import com.ultravidmerger.app.databinding.FragmentCompletedBinding
import java.io.File

class CompletedFragment : Fragment() {

    private var _b: FragmentCompletedBinding? = null
    private val b get() = _b!!

    private var galleryUri: String? = null
    private var tempPath: String? = null
    private var durationMs = 0L
    private var fileBytes = 0L
    private var engineUsed = ""
    private var lossless = true

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val a = arguments ?: return
        galleryUri = a.getString(A_URI); tempPath = a.getString(A_TMP)
        durationMs = a.getLong(A_DUR); fileBytes = a.getLong(A_BYTES)
        engineUsed = a.getString(A_ENGINE) ?: ""; lossless = a.getBoolean(A_LOSSLESS, true)
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentCompletedBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        b.tvDuration.text = "দৈর্ঘ্য: ${VideoInfo.formatDuration(durationMs)}"
        b.tvSize.text = "সাইজ: ${VideoInfo.formatBytes(fileBytes)}"
        b.tvEngine.text = "ইঞ্জিন: $engineUsed"
        b.tvMode.text = if (lossless) "✅ Lossless — কোনো quality loss হয়নি" else "⚠️ Re-encode করা হয়েছে"
        b.tvSavedTo.text = if (galleryUri != null) "সেভ: Movies/UltraVideoMerger" else "সেভ হয়নি"

        b.btnPlay.setOnClickListener { open(true) }
        b.btnShare.setOnClickListener { open(false) }
        b.btnHome.setOnClickListener { (activity as? MainActivity)?.openHome() }
        b.btnNew.setOnClickListener {
            (activity as? MainActivity)?.let { it.clearVideos(); it.openHome() }
        }
        b.btnDiagnostics.setOnClickListener { (activity as? MainActivity)?.openDiagnostics() }
    }

    private fun resolve(): Uri? {
        galleryUri?.let { return Uri.parse(it) }
        val f = tempPath?.let { File(it) } ?: return null
        if (!f.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", f)
        }.getOrNull()
    }

    private fun open(play: Boolean) {
        val uri = resolve() ?: run {
            android.widget.Toast.makeText(requireContext(), "ফাইল পাওয়া যায়নি", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val intent = if (play) Intent(Intent.ACTION_VIEW) else Intent(Intent.ACTION_SEND)
        intent.apply {
            setDataAndType(uri, "video/mp4")
            if (!play) putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, if (play) "Play" else "Share")) }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    companion object {
        private const val A_URI = "gallery_uri"
        private const val A_TMP = "temp_path"
        private const val A_DUR = "duration"
        private const val A_BYTES = "bytes"
        private const val A_ENGINE = "engine"
        private const val A_LOSSLESS = "lossless"

        fun newInstance(
            galleryUri: String?, tempPath: String?, durationMs: Long,
            fileBytes: Long, engineUsed: String, lossless: Boolean
        ) = CompletedFragment().apply {
            arguments = Bundle().apply {
                putString(A_URI, galleryUri)
                putString(A_TMP, tempPath)
                putLong(A_DUR, durationMs)
                putLong(A_BYTES, fileBytes)
                putString(A_ENGINE, engineUsed)
                putBoolean(A_LOSSLESS, lossless)
            }
        }
    }
}
