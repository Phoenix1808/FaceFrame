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
import com.example.faceframe.processing.FaceEmbedder
import com.example.faceframe.processing.FrameExtractor
import com.example.faceframe.processing.ProcessingConfig
import com.example.faceframe.processing.leftEyePosition
import com.example.faceframe.processing.rightEyePosition
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
            binding.status.text = getString(R.string.processing)

            val startedAt = System.currentTimeMillis()

            val summary = try {
                runPipeline(uri)
            } catch (t: Throwable) {
                showFailure(t)
                return@launch
            }

            val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
            val (expected, done, faces) = summary

            binding.progress.isVisible = false
            binding.pickButton.isEnabled = true
            binding.status.text = "$done frames · $faces faces · ${"%.1f".format(seconds)}s"

            Log.d(TAG, "expected=$expected done=$done faces=$faces in ${seconds}s")
        }
    }

    /** Saara bhaari kaam background thread pe — main thread free rehta hai. */
    private suspend fun runPipeline(uri: Uri): Triple<Int, Int, Int> =
        withContext(Dispatchers.Default) {
            val extractor = FrameExtractor(applicationContext)
            val analyzer = FaceAnalyzer()
            val embedder = FaceEmbedder(applicationContext)

            val info = extractor.readInfo(uri)
            Log.d(
                TAG, "video: ${info.durationMs}ms  " +
                        "${info.displayWidth}x${info.displayHeight}  " +
                        "expecting ${info.expectedFrameCount} frames  " +
                        "workers=${ProcessingConfig.EXTRACTOR_WORKERS}"
            )

            var framesDone = 0
            var facesFound = 0
            var embedNs = 0L

            // ---- SANITY TEST data ----
            // Sirf ek chehre wale frames: (timestamp, embedding).
            val singleFace = mutableListOf<Pair<Long, FloatArray>>()
            // Ek hi frame mein do chehre = pakka DO ALAG log
            val sameFrameSims = mutableListOf<Float>()

            try {
                extractor.parallelFrames(uri).collect { frame ->
                    val faces = analyzer.detect(frame.bitmap)
                        .filter { analyzer.isUsable(it) }

                    // Embedding frame recycle karne se PEHLE — pixels chahiye
                    val embedStartNs = System.nanoTime()
                    val embeddings = faces.map { face ->
                        embedder.embed(
                            frame = frame.bitmap,
                            faceRect = face.boundingBox,
                            leftEye = face.leftEyePosition,
                            rightEye = face.rightEyePosition
                        )
                    }
                    embedNs += System.nanoTime() - embedStartNs

                    frame.bitmap.recycle()

                    framesDone++
                    facesFound += faces.size

                    when (embeddings.size) {
                        1 -> singleFace += frame.timestampMs to embeddings[0]
                        2 -> sameFrameSims += FaceEmbedder.cosineSimilarity(
                            embeddings[0], embeddings[1]
                        )
                    }

                    withContext(Dispatchers.Main) {  ///again on Main for UI
                        binding.progress.progress =
                            framesDone * 100 / info.expectedFrameCount.coerceAtLeast(1)
                        binding.status.text = "Frame $framesDone / ${info.expectedFrameCount}"
                    }
                }
            } finally {
                analyzer.close()
                embedder.close()
            }

            logSanityTest(singleFace, sameFrameSims, embedNs, facesFound)

            Triple(info.expectedFrameCount, framesDone, facesFound)
        }

    /**
     * Kya embeddings sach mein kaam kar rahe hain?
     *
     * Sample video khud apna ground truth deta hai:
     *   SAME  = aas-paas ke frames (200ms fark) -> aksar wahi banda -> HIGH
     *   DIFF  = ek hi frame ke do chehre        -> pakka alag log   -> LOW
     * Dono ke beech ka gap hi SIMILARITY_THRESHOLD decide karta hai.
     */
    private fun logSanityTest(
        singleFace: List<Pair<Long, FloatArray>>,
        sameFrameSims: List<Float>,
        embedNs: Long,
        facesFound: Int
    ) {
        // Parallel extraction ki wajah se frames bina order ke aaye the.
        val sorted = singleFace.sortedBy { it.first }
        val neighbourSims = mutableListOf<Float>()
        for (i in 0 until sorted.size - 1) {
            val (t1, e1) = sorted[i]
            val (t2, e2) = sorted[i + 1]
            if (t2 - t1 == ProcessingConfig.FRAME_INTERVAL_MS) {
                neighbourSims += FaceEmbedder.cosineSimilarity(e1, e2)
            }
        }

        Log.d(TAG, "===== EMBEDDING SANITY TEST =====")
        Log.d(TAG, "SAME person (aas-paas ke frames)   ${stats(neighbourSims)}")
        Log.d(TAG, "DIFF person (ek frame ke 2 chehre) ${stats(sameFrameSims)}")
        Log.d(TAG, "embed avg = ${embedNs / 1_000_000 / facesFound.coerceAtLeast(1)}ms/face")
    }

    /** min / median / max — distribution dekhne ke liye, sirf average kaafi nahi. */
    private fun stats(values: List<Float>): String {
        if (values.isEmpty()) return "n=0  (koi data nahi)"
        val s = values.sorted()
        return "n=%d  min=%.3f  median=%.3f  max=%.3f  avg=%.3f".format(
            s.size, s.first(), s[s.size / 2], s.last(), s.average()
        )
    }

    /**
     * Crash ke bajaye error SCREEN pe dikhao.
     * Is device pe logcat mein exception dhoondhna bahut mushkil hai.
     */
    private fun showFailure(t: Throwable) {
        Log.e(TAG, "processing failed", t)

        // Pehli stack line jo HUMARE code se hai — wahi asli jagah batati hai
        val where = t.stackTrace
            .firstOrNull { it.className.startsWith("com.example.faceframe") }
            ?.let { "${it.fileName}:${it.lineNumber}" }
            ?: "unknown"

        binding.progress.isVisible = false
        binding.pickButton.isEnabled = true
        binding.status.text = buildString {
            append("FAIL @ ").append(where).append('\n')
            append(t::class.java.simpleName).append('\n')
            append(t.message ?: "no message")
        }
    }

    private companion object {
        const val TAG = "FaceFrame"
    }
}
