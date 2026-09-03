package com.example.faceframe.processing

import com.example.faceframe.model.Segment

/**
 * Ek person ke tracklets ko final "appearances" mein badalta hai.
 *
 * Assignment: "An appearance is one continuous visible segment: it starts when
 * a person's face becomes clearly visible and ends when it is no longer
 * clearly visible."
 *
 * Zyadatar ek tracklet = ek appearance. Par tracking beech mein toot sakti hai
 * (ek blurry frame, ya banda ek pal ke liye mud gaya). Aise do tukde time mein
 * bilkul paas hote hain, aur unhe alag ginna appearance count badha deta hai.
 *
 *   tracklet A: 5800-6800ms  ]
 *                            ]- gap sirf 400ms -> ek hi appearance
 *   tracklet B: 7200-8000ms  ]
 *
 * Ye jodna clustering ke BAAD hota hai, kyunki tabhi pakka pata hota hai ki
 * dono tukde ek hi insaan ke hain.
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
                // Tooti hui tracking - wahi appearance aage badh rahi hai
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
