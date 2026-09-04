package com.example.faceframe.model

import android.graphics.Bitmap

/**
 * Where the pipeline currently is, as far as the UI is concerned.
 *
 * Sealed so that `when` stays exhaustive and forgetting a state is a compile
 * error rather than a blank screen.
 */
sealed class ProcessingState {

    data object Idle : ProcessingState()

    /** Pass 1: extract, detect, embed. By far the longest stage. */
    data class Analyzing(
        val framesDone: Int,
        val framesTotal: Int,
        val facesFound: Int
    ) : ProcessingState() {
        val percent: Int
            get() = if (framesTotal <= 0) 0
            else (framesDone * 100 / framesTotal).coerceIn(0, 100)
    }

    /** Tracking, clustering and appearance counting. Fast. */
    data object Grouping : ProcessingState()

    /** Pass 2: re-read the chosen frames and draw the poster. */
    data object BuildingCollage : ProcessingState()

    data class Done(
        val people: List<Person>,
        val collage: Bitmap,
        /** Only when ProcessingConfig.DEBUG_CONTACT_SHEET is on. */
        val debugSheet: Bitmap?,
        val elapsedMs: Long
    ) : ProcessingState() {
        val appearanceCount: Int get() = people.sumOf { it.appearanceCount }
    }

    /**
     * `where` is the first stack frame that belongs to us, as file:line. It
     * goes on screen: on a low-RAM device, finding the exception in logcat is
     * genuinely harder than it sounds.
     */
    data class Failed(val message: String, val where: String) : ProcessingState()
}
