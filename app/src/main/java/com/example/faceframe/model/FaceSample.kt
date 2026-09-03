package com.example.faceframe.model

import android.graphics.Rect
import com.example.faceframe.processing.ProcessingConfig
import kotlin.math.min

//complete record of the face in frame
//timestamp,boundingBox,FloatArray,Embedding,yaw/roll/pitch, smileProb, eyeOpenProb , sharpness, faceArea


data class FaceSample(
    val timestampMs : Long,
    val boundingBox : Rect,
    val frameWidth : Int,
    val frameHeight : Int,

    val embedding : FloatArray,

    //ml kit attributes
    val yaw : Float,
    val pitch  : Float,
    val roll : Float,
    val smileProbability: Float,
    val leftEyeOpen: Float,
    val rightEyeOpen: Float,

    val sharpness : Double
    ){
    val eyesOpenScore: Float
        get() = min(leftEyeOpen,rightEyeOpen)

    val faceAreaRatio : Float
    get() = (boundingBox.width().toFloat() * boundingBox.height()) / (frameWidth.toFloat() * frameHeight)

    val isClipped: Boolean
        get() {
            val m = ProcessingConfig.EDGE_MARGIN_PX
            return boundingBox.left <= m ||
                    boundingBox.top <= m ||
                    boundingBox.right >= frameWidth - m ||
                    boundingBox.bottom >= frameHeight - m
        }

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