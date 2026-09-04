package com.example.faceframe.model

import android.graphics.Rect
import com.example.faceframe.processing.ProcessingConfig
import kotlin.math.min

/**
 * One detected face in one frame — everything we need about it, and nothing
 * that costs memory.
 *
 * Deliberately no bitmap here. Holding 150 decoded frames would be about
 * 1.2 GB; 200 of these is roughly 200 KB. When actual pixels are needed again,
 * the timestamp is enough to go back and re-read that one frame.
 */
data class FaceSample(
    val timestampMs: Long,
    val boundingBox: Rect,
    val frameWidth: Int,
    val frameHeight: Int,

    val embedding: FloatArray,

    // Straight from ML Kit.
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val smileProbability: Float,
    val leftEyeOpen: Float,
    val rightEyeOpen: Float,

    // Ours — ML Kit does not report sharpness.
    val sharpness: Double,

    val facesInFrame: Int = 1,

    // Nearest other face in the same frame, if any. The collage crop uses this
    // to stop before it reaches the neighbour; without it a generous crop of
    // someone who only ever appears next to somebody else pulls that somebody
    // else into the tile.
    val neighbourBox: Rect? = null
) {
    // Both eyes, so min rather than average.
    val eyesOpenScore: Float
        get() = min(leftEyeOpen, rightEyeOpen)

    val faceAreaRatio: Float
        get() = (boundingBox.width().toFloat() * boundingBox.height()) /
                (frameWidth.toFloat() * frameHeight)

    val isClipped: Boolean
        get() {
            val m = ProcessingConfig.EDGE_MARGIN_PX
            return boundingBox.left <= m ||
                    boundingBox.top <= m ||
                    boundingBox.right >= frameWidth - m ||
                    boundingBox.bottom >= frameHeight - m
        }

    // A data class with a FloatArray needs these written out: the generated
    // equals() compares arrays by reference, so two identical embeddings would
    // come back unequal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceSample) return false
        return timestampMs == other.timestampMs &&
                boundingBox == other.boundingBox &&
                embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = timestampMs.hashCode()
        result = 31 * result + boundingBox.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}
