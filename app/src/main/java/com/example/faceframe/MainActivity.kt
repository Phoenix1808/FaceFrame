package com.example.faceframe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.faceframe.collage.MediaSaver
import com.example.faceframe.databinding.ActivityMainBinding
import com.example.faceframe.model.ProcessingState
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole app is one screen: pick a video, watch it work, look at the
 * collage, save or share it.
 *
 * No processing happens here — that is VideoProcessor, driven by
 * MainViewModel. This class only turns ProcessingState into pixels.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.analyze(it) } }

    // Held between asking for the permission and getting an answer.
    private var pendingSave: (() -> Unit)? = null

    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingSave?.invoke() else snack(getString(R.string.permission_denied))
        pendingSave = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pickButton.setOnClickListener { pickVideo.launch("video/*") }

        // repeatOnLifecycle stops collecting while the screen is in the
        // background and picks up again on return, so we are not updating views
        // nobody is looking at.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: ProcessingState) = when (state) {
        is ProcessingState.Idle -> Unit

        is ProcessingState.Analyzing -> showProgress(
            percent = state.percent,
            text = getString(
                R.string.frame_progress,
                state.framesDone, state.framesTotal, state.facesFound
            )
        )

        // Both of these are quick, and there is no sensible percentage for
        // them, so the bar just spins instead of pretending to know.
        is ProcessingState.Grouping ->
            showProgress(null, getString(R.string.processing))

        is ProcessingState.BuildingCollage ->
            showProgress(null, getString(R.string.building_collage))

        is ProcessingState.Done -> showResult(state)

        is ProcessingState.Failed -> {
            binding.progress.isVisible = false
            binding.pickButton.isEnabled = true
            binding.status.text = "${state.message}\n(${state.where})"
        }
    }

    private fun showProgress(percent: Int?, text: String) {
        binding.pickButton.isEnabled = false
        binding.actions.isVisible = false
        binding.progress.isVisible = true
        binding.status.text = text

        if (percent == null) {
            binding.progress.isIndeterminate = true
        } else {
            binding.progress.isIndeterminate = false
            binding.progress.setProgressCompat(percent, true)
        }
    }

    private fun showResult(state: ProcessingState.Done) {
        binding.progress.isVisible = false
        binding.pickButton.isEnabled = true
        binding.pickButton.setText(R.string.pick_another)
        binding.emptyState.isVisible = false
        binding.collage.isVisible = true
        binding.actions.isVisible = true

        binding.status.text = getString(
            R.string.result_summary,
            state.people.size,
            state.appearanceCount,
            "%.1fs".format(state.elapsedMs / 1000.0)
        )

        binding.collage.setImageBitmap(state.collage)

        // Development aid, off by default. Tap the collage to compare it against
        // one tile per tracklet.
        state.debugSheet?.let { sheet ->
            var showingCollage = true
            binding.collage.setOnClickListener {
                showingCollage = !showingCollage
                binding.collage.setImageBitmap(if (showingCollage) state.collage else sheet)
            }
        }

        val name = "FaceFrame_${System.currentTimeMillis()}"
        binding.saveButton.setOnClickListener {
            withStoragePermission { save(state.collage, name) }
        }
        binding.shareButton.setOnClickListener { share(state.collage, name) }
    }

    // Encoding a 1080x1920 PNG is disk work, so not on the main thread.
    private fun save(collage: Bitmap, name: String) {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                MediaSaver(applicationContext).saveToGallery(collage, name)
            }
            snack(
                getString(
                    if (uri != null) R.string.saved_to_gallery else R.string.save_failed
                )
            )
        }
    }

    private fun share(collage: Bitmap, name: String) {
        lifecycleScope.launch {
            val intent = withContext(Dispatchers.IO) {
                MediaSaver(applicationContext).shareIntent(collage, name)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_collage)))
        }
    }

    // From Android 10 an app can write its own images through MediaStore with no
    // permission at all. Below that MediaStore writes to a real file path, which
    // does need one.
    private fun withStoragePermission(action: () -> Unit) {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED

        if (!needsPermission) {
            action()
        } else {
            pendingSave = action
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun snack(message: String) =
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
}
