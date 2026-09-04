package com.example.faceframe.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import com.example.faceframe.collage.CollageRenderer
import com.example.faceframe.model.FaceSample
import com.example.faceframe.model.Person
import com.example.faceframe.model.ProcessingState
import com.example.faceframe.util.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Runs the whole thing, from a video URI to a finished collage.
 *
 * Every other class here does one job and knows nothing about the others.
 * This is the only place that puts them in order, which is what makes them
 * testable in isolation and lets the whole flow be read in one file.
 *
 * Returns a Flow of states rather than a list of people because the work takes
 * around two minutes and the UI has to show something while it happens. Cold,
 * and flowOn(Default) keeps the heavy work off whatever thread collects it.
 */
class VideoProcessor(private val context: Context) {

    fun process(uri: Uri): Flow<ProcessingState> = flow {
        val startedAt = System.currentTimeMillis()

        val extractor = FrameExtractor(context)
        val info = extractor.readInfo(uri)
        Log.d(
            TAG, "video: ${info.durationMs}ms  ${info.displayWidth}x${info.displayHeight}  " +
                    "expecting ${info.expectedFrameCount} frames  " +
                    "workers=${ProcessingConfig.EXTRACTOR_WORKERS}"
        )

        emit(ProcessingState.Analyzing(0, info.expectedFrameCount, 0))
        val samples = analyzeFrames(uri, extractor, info)

        emit(ProcessingState.Grouping)
        val people = groupIntoPeople(samples)

        emit(ProcessingState.BuildingCollage)
        val withShots = people.map { attachShot(extractor, uri, it) }
        val collage = CollageRenderer.render(withShots)
        val debugSheet = if (ProcessingConfig.DEBUG_CONTACT_SHEET) {
            CollageRenderer.renderDebugSheet(
                FaceTracker.build(samples).map { trackletThumbnail(extractor, uri, it) }
            )
        } else {
            null
        }

        emit(
            ProcessingState.Done(
                people = withShots,
                collage = collage,
                debugSheet = debugSheet,
                elapsedMs = System.currentTimeMillis() - startedAt
            )
        )
    }.flowOn(Dispatchers.Default)

    // ---- pass 1: take what we need from each frame, then let it go ----

    // Keeping 150 decoded frames around is roughly 1.2 GB, so every bitmap is
    // recycled the moment we are done with it. What survives is one FaceSample
    // per face, about a kilobyte each.
    private suspend fun kotlinx.coroutines.flow.FlowCollector<ProcessingState>.analyzeFrames(
        uri: Uri,
        extractor: FrameExtractor,
        info: VideoInfo
    ): List<FaceSample> {
        val analyzer = FaceAnalyzer()
        val embedder = FaceEmbedder(context)

        val samples = mutableListOf<FaceSample>()
        var framesDone = 0
        var facesBlurred = 0

        try {
            extractor.parallelFrames(uri).collect { frame ->
                val faces = analyzer.deduplicate(
                    analyzer.detect(frame.bitmap).filter { analyzer.isUsable(it) }
                )

                for (face in faces) {
                    // Measure sharpness on the face, not the whole frame — a
                    // crisp background behind a blurred face is common.
                    val faceCrop = BitmapUtils.cropGenerously(
                        source = frame.bitmap,
                        faceRect = face.boundingBox,
                        expandFactor = 1f
                    )
                    val sharpness = BitmapUtils.laplacianVariance(faceCrop)
                    faceCrop.recycle()

                    // The assignment is explicit that blurred whip-pan passes
                    // count for nobody. They also embed badly, and a bad
                    // embedding can split one appearance into two.
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
                        sharpness = sharpness,
                        facesInFrame = faces.size,
                        neighbourBox = faces
                            .filter { it !== face }
                            .minByOrNull {
                                kotlin.math.abs(
                                    it.boundingBox.exactCenterX() -
                                            face.boundingBox.exactCenterX()
                                )
                            }
                            ?.boundingBox
                    )
                }

                frame.bitmap.recycle()
                framesDone++
                emit(
                    ProcessingState.Analyzing(
                        framesDone, info.expectedFrameCount, samples.size
                    )
                )
            }
        } finally {
            analyzer.close()
            embedder.close()
        }

        Log.d(TAG, "${samples.size} faces kept, $facesBlurred rejected as blurry")
        logSeparation(samples)
        return samples
    }

    // ---- identity grouping ----

    // Clustering runs on tracklets, not on individual frames: a tracklet's
    // embedding is the average over its frames, which is far steadier than any
    // single one of them.
    private fun groupIntoPeople(samples: List<FaceSample>): List<Person> {
        val tracklets = FaceTracker.build(samples)
        Log.d(TAG, "${samples.size} detections -> ${tracklets.size} tracklets")

        return FaceClusterer.cluster(
            tracklets,
            // Two tracklets running at the same instant are two people.
            cannotMerge = { a, b -> a.overlapsInTime(b) }
        ) { it.embedding }
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
            .also(::logPeople)
    }

    // ---- pass 2: re-read just the frames that make it into the collage ----

    // Pass 1 threw every bitmap away, so the chosen frame has to be fetched
    // again. There are only a handful, so this time we can afford the
    // resolution, and a sharper tile makes a visibly better collage.
    private fun attachShot(extractor: FrameExtractor, uri: Uri, person: Person): Person {
        val best = person.bestSample
        val frame = extractor.frameAt(
            uri, best.timestampMs, ProcessingConfig.COLLAGE_DECODE_HEIGHT
        ) ?: return person

        // The assignment says not to crop tight to the box, so this is
        // deliberately generous - but if someone else was standing next to
        // them, `avoid` stops the crop before it reaches that face.
        val shot = BitmapUtils.cropGenerously(
            source = frame,
            faceRect = best.boundingBox.scaledTo(frame.height, best.frameHeight),
            expandFactor = ProcessingConfig.COLLAGE_CROP_EXPAND,
            verticalBias = ProcessingConfig.COLLAGE_CROP_VERTICAL_BIAS,
            avoid = best.neighbourBox?.scaledTo(frame.height, best.frameHeight)
        )
        frame.recycle()
        return person.copy(representativeShot = shot)
    }

    // One tile for the debug contact sheet: a tracklet's best frame, labelled
    // with when it happened.
    private fun trackletThumbnail(
        extractor: FrameExtractor,
        uri: Uri,
        tracklet: Tracklet
    ): Pair<Bitmap?, String> {
        val best = ShotScorer.bestOf(tracklet.samples)
        val caption = "%.1f-%.1fs".format(tracklet.startMs / 1000f, tracklet.endMs / 1000f)

        val frame = extractor.frameAt(
            uri, best.timestampMs, ProcessingConfig.DEBUG_DECODE_HEIGHT
        ) ?: return null to caption

        val shot = BitmapUtils.cropGenerously(
            frame, best.boundingBox.scaledTo(frame.height, best.frameHeight), 1.9f, 0.08f
        )
        frame.recycle()
        return BitmapUtils.scaleToWidth(shot, 320) to caption
    }

    // The box was measured on the 720p analysis frame; pass 2 hands back a
    // bigger one, so scale it to match.
    private fun Rect.scaledTo(newHeight: Int, oldHeight: Int): Rect {
        val scale = newHeight.toFloat() / oldHeight
        return Rect(
            (left * scale).toInt(),
            (top * scale).toInt(),
            (right * scale).toInt(),
            (bottom * scale).toInt()
        )
    }

    // ---- diagnostics ----

    /**
     * Are the embeddings actually separating people?
     *
     * The video supplies its own ground truth, no labelling needed. Two frames
     * 200 ms apart are almost always the same person; two faces in one frame
     * are definitely not. The gap between those two numbers is the room
     * clustering has to work in — if they meet, no threshold can help.
     */
    private fun logSeparation(samples: List<FaceSample>) {
        val byTime = samples.groupBy { it.timestampMs }

        val singles = byTime.filterValues { it.size == 1 }
        val times = singles.keys.sorted()
        val same = mutableListOf<Float>()
        for (i in 0 until times.size - 1) {
            if (times[i + 1] - times[i] != ProcessingConfig.FRAME_INTERVAL_MS) continue
            same += FaceEmbedder.cosineSimilarity(
                singles.getValue(times[i]).first().embedding,
                singles.getValue(times[i + 1]).first().embedding
            )
        }
        val diff = byTime.values.filter { it.size == 2 }.map {
            FaceEmbedder.cosineSimilarity(it[0].embedding, it[1].embedding)
        }

        fun describe(values: List<Float>): String {
            if (values.isEmpty()) return "n=0"
            val s = values.sorted()
            return "n=%d median=%.3f min=%.3f max=%.3f".format(
                s.size, s[s.size / 2], s.first(), s.last()
            )
        }
        Log.d(TAG, "SAME person (adjacent frames)      ${describe(same)}")
        Log.d(TAG, "DIFF person (two faces, one frame) ${describe(diff)}")
    }

    private fun logPeople(people: List<Person>) {
        Log.d(TAG, "${people.size} unique people, ${people.sumOf { it.appearanceCount }} appearances")
        for (p in people) {
            Log.d(
                TAG, "${p.label}: ${p.appearanceCount} appearances (${p.samples.size} frames) " +
                        "best@${p.bestSample.timestampMs}ms score=%.2f"
                            .format(ShotScorer.score(p.bestSample))
            )
            for (s in p.segments) {
                Log.d(TAG, "      ${s.startMs}-${s.endMs}ms  ${s.frameCount} frames")
            }
        }
    }

    private companion object {
        const val TAG = "FaceFrame"
    }
}
