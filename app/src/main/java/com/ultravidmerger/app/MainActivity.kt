package com.ultravidmerger.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.ultravidmerger.app.databinding.ActivityMainBinding
import com.ultravidmerger.app.service.MergeService
import com.ultravidmerger.app.ui.CompletedFragment
import com.ultravidmerger.app.ui.HomeFragment
import com.ultravidmerger.app.ui.MergeFragment
import com.ultravidmerger.app.ui.ProcessingFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedVideos: MutableList<VideoInfo> = mutableListOf()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Continue regardless of permission result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()

        if (savedInstanceState == null) {
            showFragment(HomeFragment())
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    fun showFragment(fragment: Fragment, addToBackStack: Boolean = true) {
        val transaction = supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
        if (addToBackStack) {
            transaction.addToBackStack(null)
        }
        transaction.commit()
    }

    fun getSelectedVideos(): List<VideoInfo> = selectedVideos.toList()

    fun addVideo(video: VideoInfo) {
        selectedVideos.add(video)
    }

    fun removeVideo(position: Int) {
        if (position in selectedVideos.indices) {
            selectedVideos.removeAt(position)
        }
    }

    fun moveVideo(from: Int, to: Int) {
        if (from in selectedVideos.indices && to in selectedVideos.indices) {
            val item = selectedVideos.removeAt(from)
            selectedVideos.add(to, item)
        }
    }

    fun clearVideos() {
        selectedVideos.clear()
    }

    fun startMerge(videos: List<VideoInfo>, mergeMode: String, outputPath: String, targetWidth: Int, targetHeight: Int, targetFps: Int) {
        val uris = ArrayList(videos.map { it.uri })
        
        val intent = Intent(this, MergeService::class.java).apply {
            putParcelableArrayListExtra("video_uris", uris)
            putExtra("output_path", outputPath)
            putExtra("merge_mode", mergeMode)
            putExtra("target_width", targetWidth)
            putExtra("target_height", targetHeight)
            putExtra("target_fps", targetFps)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        showFragment(ProcessingFragment())
    }

    fun showCompleted(outputPath: String, durationMs: Long, fileSize: Long) {
        showFragment(CompletedFragment.newInstance(outputPath, durationMs, fileSize))
    }
}
