package com.example.faceframe.processing

import com.example.faceframe.model.FaceSample
import kotlin.math.abs

/**
 * Scores each detection 0..1 so we can pick the shot that goes in the collage.
 *
 * The assignment asks for frontal, crisp, eyes-open, pleasant shots with the
 * full face visible, so those are the five terms. Weights live in
 * ProcessingConfig and add up to 1.
 */
object ShotScorer {

    fun score(sample: FaceSample): Float {
        val score =
            ProcessingConfig.W_FRONTALITY * frontality(sample) +
                    ProcessingConfig.W_SHARPNESS * sharpness(sample) +
                    ProcessingConfig.W_EYES_OPEN * sample.eyesOpenScore +
                    ProcessingConfig.W_FACE_SIZE * faceSize(sample) +
                    ProcessingConfig.W_SMILE * sample.smileProbability

        // A face cut off by the frame edge looks bad in a tile however sharp it is.
        var penalty = if (sample.isClipped) ProcessingConfig.CLIPPED_FACE_PENALTY else 0f

        // Two people in one frame means the generous collage crop will pull in
        // the neighbour too. Prefer a solo frame if this person has one.
        if (sample.facesInFrame > 1) penalty += ProcessingConfig.SHARED_FRAME_PENALTY

        return (score - penalty).coerceIn(0f, 1f)
    }

    fun bestOf(samples: List<FaceSample>): FaceSample = samples.maxBy { score(it) }

    // Yaw hurts more than pitch — in profile half the face is simply gone,
    // whereas looking a bit up or down still reads fine. Roll is ignored: it
    // does not look bad in a photo, and the embedding crop already corrects it.
    private fun frontality(sample: FaceSample): Float {
        val yawOff = (abs(sample.yaw) / ProcessingConfig.MAX_HEAD_YAW).coerceIn(0f, 1f)
        val pitchOff = (abs(sample.pitch) / ProcessingConfig.MAX_HEAD_PITCH).coerceIn(0f, 1f)
        return 1f - (0.7f * yawOff + 0.3f * pitchOff)
    }

    // Capped: past the reference value the difference stops being visible.
    private fun sharpness(sample: FaceSample): Float =
        (sample.sharpness / ProcessingConfig.SHARPNESS_REFERENCE)
            .coerceIn(0.0, 1.0)
            .toFloat()

    // Bigger face, more pixels, better tile. The assignment specifically warns
    // against tiles that end up looking like low-resolution thumbnails.
    private fun faceSize(sample: FaceSample): Float =
        (sample.faceAreaRatio / ProcessingConfig.FACE_AREA_REFERENCE).coerceIn(0f, 1f)
}
