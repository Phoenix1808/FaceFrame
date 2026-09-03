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


object BitmapUtils {


    private const val SHARPNESS_SAMPLE_WIDTH = 160


    // 1. Sharpness — Laplacian variance

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

    // ------------------------------------------------------------------
    // 2. Aligned square crop — embedding model ke liye
    // ------------------------------------------------------------------

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

        // Aankhon ki line kitni tirchi hai
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
            postTranslate(-cx, -cy)               // face center → origin
            postRotate(-angle)                    // aankhein horizontal
            postScale(scale, scale)               // model input size pe
            postTranslate(outputSize / 2f, outputSize / 2f)  // wapas center
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

    // ------------------------------------------------------------------
    // 3. Generous crop — collage tile ke liye
    // ------------------------------------------------------------------

    fun cropGenerously(
        source: Bitmap,
        faceRect: Rect,
        expandFactor: Float,
        verticalBias: Float = 0f
    ): Bitmap {
        val halfW = faceRect.width() * expandFactor / 2f
        val halfH = faceRect.height() * expandFactor / 2f

        val cx = faceRect.exactCenterX()
        val cy = faceRect.exactCenterY() + faceRect.height() * verticalBias

        var left = (cx - halfW).roundToInt().coerceIn(0, source.width - 1)
        var top = (cy - halfH).roundToInt().coerceIn(0, source.height - 1)
        var right = (cx + halfW).roundToInt().coerceIn(left + 1, source.width)
        var bottom = (cy + halfH).roundToInt().coerceIn(top + 1, source.height)

        // Frame ke bahar nikal gaya toh doosri taraf shift karke width bachao
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

    // ------------------------------------------------------------------
    // 4. TFLite input buffer
    // ------------------------------------------------------------------

    fun toModelInput(bitmap: Bitmap, inputSize: Int): ByteBuffer {
        val buffer = ByteBuffer
            .allocateDirect(4 * inputSize * inputSize * 3)   // 4 bytes per float
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (p in pixels) {
            buffer.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f)   // R
            buffer.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)    // G
            buffer.putFloat(((p and 0xFF) - 127.5f) / 128f)            // B
        }
        buffer.rewind()
        return buffer
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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
