package com.example.faceframe

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.faceframe.databinding.ActivityMainBinding
import com.example.faceframe.processing.FaceAnalyzer
import com.example.faceframe.processing.FrameExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { analyze(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pickButton.setOnClickListener { pickVideo.launch("video/*") }
    }


    private fun analyze(uri: Uri) {
        lifecycleScope.launch { //main thread par hai abhi to initially
            binding.pickButton.isEnabled = false
            binding.progress.isVisible = true
            binding.progress.progress = 0

            val startedAt = System.currentTimeMillis()

            // Saara bhaari kaam background thread pe — main thread free rehta hai
            val summary = withContext(Dispatchers.Default) { //switched to the background
                val extractor = FrameExtractor(applicationContext)
                val analyzer = FaceAnalyzer()

                val info = extractor.readInfo(uri)
                Log.d(
                    TAG, "video: ${info.durationMs}ms  " +
                            "${info.displayWidth}x${info.displayHeight}  " +
                            "expecting ${info.expectedFrameCount} frames"
                )

                var framesDone = 0
                var facesFound = 0

                // ---- TEMPORARY instrumentation: time kahan ja raha hai? ----
                var extractNs = 0L
                var detectNs = 0L
                var uiNs = 0L
                var lastBlockEndNs = System.nanoTime()
                var loggedAttributes = false
                // ------------------------------------------------------------

                try {
                    extractor.parallelFrames(uri).collect { frame ->
                        // Pichhla block khatam hone se ab tak ka waqt =
                        // extractor ko agla frame banane mein laga waqt
                        val blockStartNs = System.nanoTime()
                        extractNs += blockStartNs - lastBlockEndNs

                        val detectStartNs = System.nanoTime()
                        val faces = analyzer.detect(frame.bitmap)
                            .filter { analyzer.isUsable(it) }
                        detectNs += System.nanoTime() - detectStartNs

                        framesDone++
                        facesFound += faces.size

                        // Ek baar check: ML Kit attributes aa bhi rahe hain?
                        // -1.00 dikhe to CLASSIFICATION_MODE_ALL kaam nahi kar raha.
                        if (!loggedAttributes && faces.isNotEmpty()) {
                            loggedAttributes = true
                            val f = faces.first()
                            Log.d(
                                TAG, "attr check @${frame.timestampMs}ms  " +
                                        "box=${f.boundingBox.width()}x${f.boundingBox.height()}  " +
                                        "yaw=%.1f roll=%.1f  smile=%.2f  eyeL=%.2f eyeR=%.2f".format(
                                            f.headEulerAngleY,
                                            f.headEulerAngleZ,
                                            f.smilingProbability ?: -1f,
                                            f.leftEyeOpenProbability ?: -1f,
                                            f.rightEyeOpenProbability ?: -1f
                                        )
                            )
                        }

                        // Do ya zyada log ek saath = Sample 1 ke A+B / C+D moments
                        if (faces.size >= 2) {
                            Log.d(TAG, "multi-face @${frame.timestampMs}ms  count=${faces.size}")
                        }

                        frame.bitmap.recycle()

                        val uiStartNs = System.nanoTime()
                        withContext(Dispatchers.Main) {  ///again on Main ffor UI
                            binding.progress.progress =
                                framesDone * 100 / info.expectedFrameCount.coerceAtLeast(1)
                            binding.status.text = "Frame $framesDone / ${info.expectedFrameCount}"
                        }
                        uiNs += System.nanoTime() - uiStartNs

                        lastBlockEndNs = System.nanoTime()
                    }
                } finally {
                    analyzer.close()
                }

                Log.d(
                    TAG, "TIMING  extract=${extractNs / 1_000_000}ms  " +
                            "detect=${detectNs / 1_000_000}ms  " +
                            "ui=${uiNs / 1_000_000}ms"
                )
                Log.d(
                    TAG, "PER-FRAME  extract=${extractNs / 1_000_000 / framesDone.coerceAtLeast(1)}ms  " +
                            "detect=${detectNs / 1_000_000 / framesDone.coerceAtLeast(1)}ms"
                )

                Triple(info.expectedFrameCount, framesDone, facesFound)
            }

            val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
            val (expected, done, faces) = summary

            binding.progress.isVisible = false
            binding.pickButton.isEnabled = true
            binding.status.text = "$done frames · $faces faces · ${"%.1f".format(seconds)}s"

            Log.d(TAG, "expected=$expected done=$done faces=$faces in ${seconds}s")
        }
    }

    private companion object {
        const val TAG = "FaceFrame"
    }
}
