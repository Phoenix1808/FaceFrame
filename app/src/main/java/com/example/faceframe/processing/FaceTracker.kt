package com.example.faceframe.processing

import android.graphics.Rect
import com.example.faceframe.model.FaceSample
import kotlin.math.sqrt

/**
 * Ek insaan ka ek continuous visible segment - yaani ek "appearance".
 *
 * Assignment ki definition se seedha match: "An appearance is one continuous
 * visible segment: it starts when a person's face becomes clearly visible and
 * ends when it is no longer clearly visible."
 */
class Tracklet(val samples: List<FaceSample>) {

    val startMs: Long get() = samples.first().timestampMs
    val endMs: Long get() = samples.last().timestampMs
    val frameCount: Int get() = samples.size

    /**
     * Kya ye do tracklets ek hi samay chal rahe the?
     *
     * Agar haan, to ye pakka DO ALAG log hain - ek insaan ek waqt me do
     * jagah nahi ho sakta. Clustering ise ek hard rule ki tarah use karti hai.
     */
    fun overlapsInTime(other: Tracklet): Boolean =
        startMs <= other.endMs && other.startMs <= endMs

    /**
     * Saare frames ke embeddings ka average (L2-normalized).
     *
     * YAHI is poore design ka faayda hai. Ek frame ka embedding lighting,
     * pose aur blur se hilta rehta hai. Saat frames ka average lene par
     * wo random galtiyan aapas mein cancel ho jaati hain, aur us insaan ki
     * asli pehchaan bachi rehti hai.
     */
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

/**
 * Frame-dar-frame detections ko tracklets mein jodta hai.
 *
 * Ye clustering se PEHLE chalta hai, aur do bade kaam karta hai:
 *
 *   1. Shor kam karta hai - 137 dagmagate per-frame embeddings ki jagah
 *      ~20 stable averaged embeddings bachte hain. Clustering ke liye
 *      zameen-aasman ka farq.
 *
 *   2. Saath khade logon ko alag rakhta hai - matching sirf embedding se
 *      nahi, bounding box ki JAGAH se bhi hoti hai. 10.2s par jo do chehre
 *      hain wo screen ke alag hisson mein hain, isliye do alag tracklets
 *      banenge - chahe unke embeddings thode mile-jule hon.
 */
object FaceTracker {

    fun build(
        samples: List<FaceSample>,
        minIou: Float = ProcessingConfig.TRACK_MIN_IOU,
        minSimilarity: Float = ProcessingConfig.TRACK_MIN_SIMILARITY
    ): List<Tracklet> {
        if (samples.isEmpty()) return emptyList()

        // Frames parallel extraction se bina order ke aate hain.
        val byTime = samples.groupBy { it.timestampMs }.toSortedMap()

        val open = mutableListOf<MutableList<FaceSample>>()
        val finished = mutableListOf<MutableList<FaceSample>>()

        for ((time, detections) in byTime) {
            // Jo tracklets bahut der se nahi dikhe, unhe band kar do.
            // Ye wahi gap tolerance hai jo appearance boundary decide karti hai.
            val stale = open.filter {
                time - it.last().timestampMs > ProcessingConfig.GAP_TOLERANCE_MS
            }
            finished += stale
            open.removeAll(stale.toSet())

            // Snapshot lete hain kyunki neeche `open` mein naye tracklets add honge
            val candidates = open.toList()
            val taken = HashSet<Int>()

            for (detection in detections) {
                var bestIndex = -1
                var bestScore = 0f

                for (i in candidates.indices) {
                    if (i in taken) continue
                    val last = candidates[i].last()

                    // Gate 1 - position: do alag log ek hi jagah nahi ho sakte.
                    // Yahi ek frame ke do chehron ko alag rakhta hai.
                    val overlap = iou(last.boundingBox, detection.boundingBox)
                    if (overlap < minIou) continue

                    // Gate 2 - identity: yahi CUT pakadta hai.
                    // Portrait video me har banda beech me same size me framed
                    // hota hai, to cut ke baad bhi IoU high aata hai. Chehra
                    // badla ya nahi, ye sirf embedding bata sakta hai.
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
                    candidates[bestIndex] += detection      // chalte tracklet mein jodo
                    taken += bestIndex
                } else {
                    open += mutableListOf(detection)        // naya tracklet
                }
            }
        }
        finished += open

        // Ek-do frame ka tracklet aksar false detection hota hai.
        return finished
            .filter { it.size >= ProcessingConfig.MIN_FRAMES_PER_SEGMENT }
            .map { Tracklet(it.sortedBy { s -> s.timestampMs }) }
            .sortedBy { it.startMs }
    }

}

/**
 * Intersection over Union - do boxes kitne overlap karte hain (0..1).
 * 0 = bilkul alag jagah, 1 = bilkul same jagah.
 */
internal fun iou(a: Rect, b: Rect): Float {
    val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
    val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
    if (w <= 0 || h <= 0) return 0f

    val intersection = w.toFloat() * h
    val union = a.width().toFloat() * a.height() +
            b.width().toFloat() * b.height() - intersection
    return if (union <= 0f) 0f else intersection / union
}
