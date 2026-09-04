package com.example.faceframe.processing

import android.graphics.Rect
import com.example.faceframe.model.FaceSample
import kotlin.math.sqrt

/** One continuous visible segment of one person — one "appearance". */
class Tracklet(val samples: List<FaceSample>) {

    val startMs: Long get() = samples.first().timestampMs
    val endMs: Long get() = samples.last().timestampMs
    val frameCount: Int get() = samples.size

    // Overlapping tracklets are definitely different people.
    // Nobody is in two places at once.
    fun overlapsInTime(other: Tracklet): Boolean =
        startMs <= other.endMs && other.startMs <= endMs

    // Mean of the frames' embeddings, re-normalised. This is the whole point of
    // building tracklets: a single frame's embedding moves around with lighting,
    // pose and motion blur, and averaging seven of them cancels most of that.
    val embedding: FloatArray by lazy(LazyThreadSafetyMode.NONE) {
        val dim = samples.first().embedding.size
        val sum = FloatArray(dim)
        for (s in samples) {
            for (i in 0 until dim) sum[i] += s.embedding[i]
        }
        var sumSq = 0f
        for (x in sum) sumSq += x * x
        val norm = sqrt(sumSq).coerceAtLeast(1e-10f)
        for (i in sum.indices) sum[i] /= norm
        sum
    }
}

/** Links per-frame detections into tracklets, before any clustering happens. */
object FaceTracker {

    fun build(
        samples: List<FaceSample>,
        minIou: Float = ProcessingConfig.TRACK_MIN_IOU,
        minSimilarity: Float = ProcessingConfig.TRACK_MIN_SIMILARITY
    ): List<Tracklet> {
        if (samples.isEmpty()) return emptyList()

        // Parallel extraction hands frames back out of order.
        val byTime = samples.groupBy { it.timestampMs }.toSortedMap()

        val open = mutableListOf<MutableList<FaceSample>>()
        val finished = mutableListOf<MutableList<FaceSample>>()

        for ((time, detections) in byTime) {
            val stale = open.filter {
                time - it.last().timestampMs > ProcessingConfig.GAP_TOLERANCE_MS
            }
            finished += stale
            open.removeAll(stale.toSet())

            // Snapshot, because new tracklets get appended to `open` below.
            val candidates = open.toList()
            val taken = HashSet<Int>()

            for (detection in detections) {
                var bestIndex = -1
                var bestScore = 0f

                for (i in candidates.indices) {
                    if (i in taken) continue
                    val last = candidates[i].last()

                    val overlap = iou(last.boundingBox, detection.boundingBox)
                    if (overlap < minIou) continue

                    // Both gates matter, for opposite reasons. Position alone
                    // cannot see a cut: in a portrait video everyone is framed
                    // dead centre at a similar size, so the boxes still overlap
                    // heavily after the camera cuts to someone else. Only the
                    // embedding notices that the face changed.
                    val similarity = FaceEmbedder.cosineSimilarity(
                        last.embedding, detection.embedding
                    )
                    if (similarity < minSimilarity) continue

                    val score = 0.5f * overlap + 0.5f * similarity
                    if (score > bestScore) {
                        bestScore = score
                        bestIndex = i
                    }
                }

                if (bestIndex >= 0) {
                    candidates[bestIndex] += detection
                    taken += bestIndex
                } else {
                    open += mutableListOf(detection)
                }
            }
        }
        finished += open

        // One- or two-frame tracklets are usually a bad detection.
        return finished
            .filter { it.size >= ProcessingConfig.MIN_FRAMES_PER_SEGMENT }
            .map { Tracklet(it.sortedBy { s -> s.timestampMs }) }
            .sortedBy { it.startMs }
    }
}

// Intersection over union, 0 (disjoint) to 1 (identical).
internal fun iou(a: Rect, b: Rect): Float {
    val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
    val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
    if (w <= 0 || h <= 0) return 0f

    val intersection = w.toFloat() * h
    val union = a.width().toFloat() * a.height() +
            b.width().toFloat() * b.height() - intersection
    return if (union <= 0f) 0f else intersection / union
}
