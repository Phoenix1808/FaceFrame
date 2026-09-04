package com.example.faceframe.processing

import com.example.faceframe.model.Segment

/**
 * Turns one person's tracklets into their final appearance count.
 *
 * Mostly one tracklet is one appearance. But tracking can break mid-shot on a
 * blurry frame or when someone turns away for an instant, and counting those
 * two halves separately would inflate the number. So anything within the gap
 * tolerance gets stitched back together.
 *
 * This runs after clustering, because only then do we know the two halves
 * belong to the same person.
 */
object AppearanceCounter {

    fun segmentsOf(
        tracklets: List<Tracklet>,
        gapToleranceMs: Long = ProcessingConfig.GAP_TOLERANCE_MS
    ): List<Segment> {
        if (tracklets.isEmpty()) return emptyList()

        val ordered = tracklets.sortedBy { it.startMs }
        val segments = mutableListOf<Segment>()

        var start = ordered.first().startMs
        var end = ordered.first().endMs
        var frames = ordered.first().frameCount

        for (i in 1 until ordered.size) {
            val t = ordered[i]
            if (t.startMs - end <= gapToleranceMs) {
                end = t.endMs
                frames += t.frameCount
            } else {
                segments += Segment(start, end, frames)
                start = t.startMs
                end = t.endMs
                frames = t.frameCount
            }
        }
        segments += Segment(start, end, frames)

        return segments
    }
}
