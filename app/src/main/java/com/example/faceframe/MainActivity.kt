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
