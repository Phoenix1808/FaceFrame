package com.example.faceframe

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.faceframe.databinding.ActivityMainBinding
import com.example.faceframe.model.FaceSample
import com.example.faceframe.model.Person
import com.example.faceframe.processing.AppearanceCounter
import com.example.faceframe.processing.FaceAnalyzer
import com.example.faceframe.processing.FaceClusterer
import com.example.faceframe.processing.FaceEmbedder
import com.example.faceframe.processing.FaceTracker
import com.example.faceframe.processing.FrameExtractor
import com.example.faceframe.processing.ProcessingConfig
import com.example.faceframe.processing.ShotScorer
import com.example.faceframe.processing.Tracklet
import com.example.faceframe.processing.leftEyePosition
import com.example.faceframe.processing.rightEyePosition
import com.example.faceframe.util.BitmapUtils
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

            val result = try {
                runPipeline(uri)
            } catch (t: Throwable) {
                showFailure(t)
                return@launch
            }

            val seconds = (System.currentTimeMillis() - startedAt) / 1000.0

            binding.progress.isVisible = false
            binding.pickButton.isEnabled = true
            binding.status.text = buildString {
                append(result.people.size).append(" people").append('\n')
                result.people.forEach { p ->
                    append(p.label).append(" — ")
                        .append(p.appearanceCount).append(" appearances").append('\n')
                }
                append("%.1fs".format(seconds))
            }
        }
    }

    /** Pipeline ka ek run ka nateeja. */
    private data class Result(
        val framesDone: Int,
        val facesKept: Int,
        val facesBlurred: Int,
        val people: List<Person>
    )

    /** Saara bhaari kaam background thread pe — main thread free rehta hai. */
    private suspend fun runPipeline(uri: Uri): Result = withContext(Dispatchers.Default) {
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

        val samples = mutableListOf<FaceSample>()
        var framesDone = 0
        var facesBlurred = 0

        try {
            extractor.parallelFrames(uri).collect { frame ->
                val faces = analyzer.detect(frame.bitmap).filter { analyzer.isUsable(it) }

                for (face in faces) {
                    // Sharpness face ke area pe naapo, poore frame pe nahi -
                    // background sharp ho aur chehra blurry, ye aksar hota hai.
                    val faceCrop = BitmapUtils.cropGenerously(
                        source = frame.bitmap,
                        faceRect = face.boundingBox,
                        expandFactor = 1f
                    )
                    val sharpness = BitmapUtils.laplacianVariance(faceCrop)
                    faceCrop.recycle()

                    // Assignment: "Blurred whip-pan passes count for nobody."
                    // Ye frame yahin chhod do - iska embedding bhi kachra hoga
                    // aur ye ek appearance ko do tukdon mein tod sakta hai.
                    if (sharpness < ProcessingConfig.BLUR_THRESHOLD) {
                        facesBlurred++
                        continue
                    }

                    samples += FaceSample(
                        timestampMs = frame.timestampMs,
                        boundingBox = face.boundingBox,
                        frameWidth = frame.bitmap.width,
                        frameHeight = frame.bitmap.height,
                        embedding = embedder.embed(
                            frame = frame.bitmap,
                            faceRect = face.boundingBox,
                            leftEye = face.leftEyePosition,
                            rightEye = face.rightEyePosition
                        ),
                        yaw = face.headEulerAngleY,
                        pitch = face.headEulerAngleX,
                        roll = face.headEulerAngleZ,
                        smileProbability = face.smilingProbability ?: 0f,
                        leftEyeOpen = face.leftEyeOpenProbability ?: 1f,
                        rightEyeOpen = face.rightEyeOpenProbability ?: 1f,
                        sharpness = sharpness
                    )
                }

                frame.bitmap.recycle()
                framesDone++

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

        logSanityTest(samples)
        logSharpness(samples)

        // ---- STAGE 3a: per-frame detections -> tracklets ----
        // Ek tracklet = ek insaan ka ek continuous visible segment.
        // Clustering ab in par chalegi, per-frame embeddings par nahi.
        val tracklets = FaceTracker.build(samples)
        logTracklets(tracklets)
        logTuningGrid(samples)

        // ---- STAGE 3b: tracklets -> unique log ----
        val people = FaceClusterer.cluster(tracklets) { it.embedding }
            .sortedBy { group -> group.minOf { it.startMs } }
            .mapIndexed { index, group ->
                val all = group.flatMap { it.samples }.sortedBy { it.timestampMs }
                Person(
                    id = index + 1,
                    samples = all,
                    segments = AppearanceCounter.segmentsOf(group),
                    bestSample = ShotScorer.bestOf(all)
                )
            }

        logResult(samples.size, facesBlurred, people)

        Result(framesDone, samples.size, facesBlurred, people)
    }

    /**
     * Ek hi run se saare thresholds ka nateeja.
     *
     * Extraction + detection + embedding = 100 second. Clustering = millisecond.
     * Isliye 100 second ka data ek baar nikaal kar usi par 17 thresholds
     * chala lete hain - warna har threshold ke liye poora video dobara
     * process karna padta.
     *
     * Sample 1 ka expected: 5 people, har ek 4 appearances (kul 20).
     * Jis threshold par "5 people" aur saare "4" aayein - wahi sahi hai.
     */
    /**
     * Dono tuning knobs ka poora grid, ek hi run me.
     *
     * Video se frames nikalna + detect + embed = ~100 second. Tracking aur
     * clustering wahi memory me pade samples par chalti hain - millisecond ka
     * kaam. Isliye 100 second ka data ek baar nikaal kar uspar 35 combinations
     * chala lete hain. Warna 35 x 100s = ek ghanta.
     *
     * Sample 1 ka expected: 5 people, har ek 4 appearances (total 20).
     */
    private fun logTuningGrid(samples: List<FaceSample>) {
        Log.d(TAG, "===== TRACKING x CLUSTERING GRID  (target: 5 people, [4,4,4,4,4]) =====")
        for (trackSim in listOf(0.55f, 0.65f, 0.70f, 0.75f, 0.80f)) {
            val tracklets = FaceTracker.build(samples, minSimilarity = trackSim)
            Log.d(TAG, "-- trackSim=%.2f -> %d tracklets".format(trackSim, tracklets.size))

            var threshold = 0.45f
            while (threshold <= 0.76f) {
                val clusters = FaceClusterer
                    .cluster(tracklets, threshold = threshold) { it.embedding }
                val appearances = clusters
                    .map { AppearanceCounter.segmentsOf(it).size }
                    .sortedDescending()
                Log.d(
                    TAG, "      t=%.2f -> %d people %s total=%d".format(
                        threshold, clusters.size, appearances, appearances.sum()
                    )
                )
                threshold += 0.05f
            }
        }
    }

    /** Tracklets khud sahi bane? Sample 1 mein ~20 aane chahiye. */
    private fun logTracklets(tracklets: List<Tracklet>) {
        Log.d(TAG, "===== TRACKLETS (${tracklets.size}) =====")
        for (t in tracklets) {
            Log.d(TAG, "   ${t.startMs}-${t.endMs}ms   ${t.frameCount} frames")
        }
    }

    /**
     * Sharpness ki distribution - BLUR_THRESHOLD sahi jagah par hai?
     * Abhi 24 detections blurry maan kar hataye gaye. Agar wo asli chehre the
     * to appearances toot sakti hain.
     */
    private fun logSharpness(samples: List<FaceSample>) {
        if (samples.isEmpty()) return
        val v = samples.map { it.sharpness }.sorted()
        Log.d(
            TAG, "sharpness (kept): min=%.0f p10=%.0f median=%.0f p90=%.0f max=%.0f".format(
                v.first(), v[v.size / 10], v[v.size / 2], v[v.size * 9 / 10], v.last()
            )
        )
    }

    private fun logResult(facesKept: Int, facesBlurred: Int, people: List<Person>) {
        Log.d(TAG, "===== RESULT =====")
        Log.d(TAG, "$facesKept faces kept, $facesBlurred rejected as blurry")
        Log.d(TAG, "${people.size} unique people")
        for (p in people) {
            Log.d(
                TAG, "${p.label}: ${p.appearanceCount} appearances  " +
                        "(${p.samples.size} frames)  " +
                        "best@${p.bestSample.timestampMs}ms " +
                        "score=%.2f".format(ShotScorer.score(p.bestSample))
            )
            for (s in p.segments) {
                Log.d(TAG, "      ${s.startMs}-${s.endMs}ms  ${s.frameCount} frames")
            }
        }
    }

    /**
     * Kya embeddings sach mein kaam kar rahe hain?
     *
     * Sample video khud apna ground truth deta hai:
     *   SAME  = aas-paas ke frames (200ms fark) -> aksar wahi banda -> HIGH
     *   DIFF  = ek hi frame ke do chehre        -> pakka alag log   -> LOW
     * Dono ke beech ka gap hi SIMILARITY_THRESHOLD decide karta hai.
     */
    private fun logSanityTest(samples: List<FaceSample>) {
        val byTime = samples.groupBy { it.timestampMs }

        val singles = byTime.filterValues { it.size == 1 }
        val times = singles.keys.sorted()
        val sameSims = mutableListOf<Float>()
        for (i in 0 until times.size - 1) {
            if (times[i + 1] - times[i] != ProcessingConfig.FRAME_INTERVAL_MS) continue
            sameSims += FaceEmbedder.cosineSimilarity(
                singles.getValue(times[i]).first().embedding,
                singles.getValue(times[i + 1]).first().embedding
            )
        }

        val diffSims = byTime.values.filter { it.size == 2 }.map {
            FaceEmbedder.cosineSimilarity(it[0].embedding, it[1].embedding)
        }

        Log.d(TAG, "===== EMBEDDING SANITY TEST =====")
        Log.d(TAG, "SAME person (aas-paas ke frames)   ${stats(sameSims)}")
        Log.d(TAG, "DIFF person (ek frame ke 2 chehre) ${stats(diffSims)}")
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
