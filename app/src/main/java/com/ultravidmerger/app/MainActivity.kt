package com.ultravidmerger.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.ultravidmerger.app.databinding.ActivityMainBinding
import com.ultravidmerger.app.diag.CrashStore
import com.ultravidmerger.app.diag.DiagnosticLogger
import com.ultravidmerger.app.ui.CompletedFragment
import com.ultravidmerger.app.ui.DiagnosticCenterFragment
import com.ultravidmerger.app.ui.HomeFragment
import com.ultravidmerger.app.ui.MergeFragment
import com.ultravidmerger.app.ui.ProcessingFragment
import com.ultravidmerger.app.ui.SelfTestFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val selected = mutableListOf<VideoInfo>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        DiagnosticLogger.info("Permissions", "Result: ${granted.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissionsIfNeeded()

        // Surface an unrecovered crash on first launch after it happened
        CrashStore.lastCrash()?.let {
            DiagnosticLogger.warn("Crash", "Previous session ended with an uncaught exception (report stored)")
        }

        if (savedInstanceState == null) {
            showFragment(HomeFragment(), addToBackStack = false)
        }
    }

    private fun requestPermissionsIfNeeded() {
        val wanted = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!granted(Manifest.permission.POST_NOTIFICATIONS)) wanted += Manifest.permission.POST_NOTIFICATIONS
            if (!granted(Manifest.permission.READ_MEDIA_VIDEO)) wanted += Manifest.permission.READ_MEDIA_VIDEO
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (!granted(Manifest.permission.READ_EXTERNAL_STORAGE)) wanted += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- nav

    fun showFragment(fragment: Fragment, addToBackStack: Boolean = true) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .apply { if (addToBackStack) addToBackStack(null) }
            .commit()
    }

    fun openHome() {
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        showFragment(HomeFragment(), addToBackStack = false)
    }

    fun openMerge() = showFragment(MergeFragment())
    fun openProcessing() = showFragment(ProcessingFragment(), addToBackStack = false)
    fun openDiagnostics() = showFragment(DiagnosticCenterFragment())
    fun openSelfTest() = showFragment(SelfTestFragment())

    fun openCompleted(state: com.ultravidmerger.app.service.MergeUiState) =
        showFragment(
            CompletedFragment.newInstance(
                galleryUri = state.galleryUri,
                tempPath = state.outputPath,
                durationMs = state.durationMs,
                fileBytes = state.fileBytes,
                engineUsed = state.engineUsed,
                lossless = state.lossless
            ),
            addToBackStack = false
        )

    // ----------------------------------------------------------- selection

    fun videos(): List<VideoInfo> = selected.toList()
    fun addVideo(v: VideoInfo) { selected += v }
    fun removeVideo(index: Int) { if (index in selected.indices) selected.removeAt(index) }
    fun moveVideo(from: Int, to: Int) {
        if (from !in selected.indices || to !in selected.indices) return
        selected.add(to, selected.removeAt(from))
    }
    fun clearVideos() = selected.clear()

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (com.ultravidmerger.app.service.MergeStateHolder.state.value.isActive) openProcessing()
    }
}
