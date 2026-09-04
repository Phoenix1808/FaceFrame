package com.example.faceframe.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt

/** Pure bitmap helpers. No Context, so these are unit-testable. */
object BitmapUtils {

    // Sharpness is measured on a shrunk copy; it is about ten times faster and
    // the number barely moves.
    private const val SHARPNESS_SAMPLE_WIDTH = 160

    /**
     * Variance of the Laplacian, i.e. how sharp the image is.
     *
     * A sharp photo is full of strong edges and scores high; a blurred one is
     * smooth and scores low. ML Kit does not report this, so we compute it, and
     * it is what lets us throw away whip-pan frames.
     */
    fun laplacianVariance(bitmap: Bitmap): Double {
        val scaled = scaleToWidth(bitmap, SHARPNESS_SAMPLE_WIDTH)
        val w = scaled.width
        val h = scaled.height

        if (w < 3 || h < 3) {
            if (scaled !== bitmap) scaled.recycle()
            return 0.0
        }

        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        val gray = DoubleArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = 0.299 * ((p shr 16) and 0xFF) +
                    0.587 * ((p shr 8) and 0xFF) +
                    0.114 * (p and 0xFF)
        }

        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = -4.0 * gray[i] +
                        gray[i - 1] + gray[i + 1] +
                        gray[i - w] + gray[i + w]
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sumSq / n - mean * mean          // variance = E[x²] − E[x]²
    }

    /**
     * Square crop with the eyes levelled, ready for the embedding model.
     *
     * MobileFaceNet was trained on aligned faces; feed it a tilted one and the
     * embedding drifts far enough to cost you an identity match. One Matrix
     * does all four steps, and anything falling outside the frame comes back
     * black rather than throwing.
     */
    fun alignedFaceCrop(
        source: Bitmap,
        faceRect: Rect,
        leftEye: PointF?,
        rightEye: PointF?,
        expandFactor: Float,
        outputSize: Int
    ): Bitmap {
        val cx = faceRect.exactCenterX()
        val cy = faceRect.exactCenterY()
        val side = max(faceRect.width(), faceRect.height()) * expandFactor
        val scale = outputSize / side

        // Angle of the line between the eyes.
        val angle = if (leftEye != null && rightEye != null) {
            Math.toDegrees(
                atan2(
                    (rightEye.y - leftEye.y).toDouble(),
                    (rightEye.x - leftEye.x).toDouble()
                )
            ).toFloat()
        } else {
            0f
        }

        val matrix = Matrix().apply {
            postTranslate(-cx, -cy)                          // face centre to origin
            postRotate(-angle)                               // level the eyes
            postScale(scale, scale)                          // down to model size
            postTranslate(outputSize / 2f, outputSize / 2f)  // back to centre
        }

        val out = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(
                source,
                matrix,
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            )
        }
        return out
    }

    /**
     * The collage tile crop, deliberately much wider than the face box - the
     * assignment is explicit that tight crops give poor, low-resolution tiles.
     */
    fun cropGenerously(
        source: Bitmap,
        faceRect: Rect,
        expandFactor: Float,
        verticalBias: Float = 0f,
        avoid: Rect? = null
    ): Bitmap {
        var halfW = faceRect.width() * expandFactor / 2f
        val halfH = faceRect.height() * expandFactor / 2f

        val cx = faceRect.exactCenterX()
        val cy = faceRect.exactCenterY() + faceRect.height() * verticalBias

        // If somebody else is standing next to this face, stop the crop at the
        // midpoint between the two rather than sailing past into their face.
        if (avoid != null) {
            val gap = avoid.exactCenterX() - faceRect.exactCenterX()
            val limit = kotlin.math.abs(gap) / 2f
            if (limit > 0f) halfW = minOf(halfW, limit)
        }

        var left = (cx - halfW).roundToInt().coerceIn(0, source.width - 1)
        var top = (cy - halfH).roundToInt().coerceIn(0, source.height - 1)
        var right = (cx + halfW).roundToInt().coerceIn(left + 1, source.width)
        var bottom = (cy + halfH).roundToInt().coerceIn(top + 1, source.height)

        // Ran off the edge: slide the other way instead of losing the width.
        val wantedW = (halfW * 2).roundToInt()
        val wantedH = (halfH * 2).roundToInt()
        if (right - left < wantedW) {
            if (left == 0) right = (left + wantedW).coerceAtMost(source.width)
            else left = (right - wantedW).coerceAtLeast(0)
        }
        if (bottom - top < wantedH) {
            if (top == 0) bottom = (top + wantedH).coerceAtMost(source.height)
            else top = (bottom - wantedH).coerceAtLeast(0)
        }

        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    /**
     * Bitmap to a float32 buffer, RGB, normalised to [-1, +1].
     *
     * MobileFaceNet expects (pixel - 127.5) / 128 exactly. Get the
     * normalisation wrong and you still get embeddings, they are just useless,
     * with nothing anywhere to tell you why.
     *
     * batchSize is here because some models are exported with a fixed batch;
     * the same face then goes into every slot.
     */
    fun toModelInput(bitmap: Bitmap, inputSize: Int, batchSize: Int = 1): ByteBuffer {
        val buffer = ByteBuffer
            .allocateDirect(4 * batchSize * inputSize * inputSize * 3)   // 4 bytes per float
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        repeat(batchSize) {
            for (p in pixels) {
                buffer.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f)   // R
                buffer.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)    // G
                buffer.putFloat(((p and 0xFF) - 127.5f) / 128f)            // B
            }
        }
        buffer.rewind()
        return buffer
    }

    // Both scale helpers return the ORIGINAL bitmap when it is already small
    // enough. Check with !== before recycling the result, or you will recycle
    // something the caller still needs.
    fun scaleToWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
        if (bitmap.width <= targetWidth) return bitmap
        val ratio = targetWidth.toFloat() / bitmap.width
        val h = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, h, true)
    }

    fun scaleToHeight(bitmap: Bitmap, targetHeight: Int): Bitmap {
        if (bitmap.height <= targetHeight) return bitmap
        val ratio = targetHeight.toFloat() / bitmap.height
        val w = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, targetHeight, true)
    }
}
