package com.example.faceframe.processing

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import com.example.faceframe.util.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Video se nikala hua ek frame.
 *
 * ⚠️ CONTRACT: collect karne wala `bitmap.recycle()` karne ke liye zimmedar hai.
 * Nahi kiya toh 150 frames × ~2 MB = OOM.
 */
data class VideoFrame(
    val bitmap: Bitmap,
    val timestampMs: Long,
    val index: Int
)

/** Progress bar ka total isi se aata hai. */
data class VideoInfo(
    val durationMs: Long,
    val displayWidth: Int,
    val displayHeight: Int,
    val expectedFrameCount: Int
)

/**
 * Video ko fixed interval pe frames mein todta hai.
 *
 * Frames ek-ek karke `Flow` se aate hain (saare ek saath nahi) — ye
 * jaan-boojh ke hai: pura video memory mein nahi aata, aur consumer
 * har frame ko process karke turant free kar sakta hai.
 */
class FrameExtractor(private val context: Context) {

    /** Frames nikale bina sirf metadata padho. */
    fun readInfo(uri: Uri): VideoInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            readInfoFrom(retriever)
        } finally {
            retriever.release()
        }
    }

    /**
     * Ek time-slice ke frames emit karta hai.
     *
     * Cold flow hai - jab tak koi collect na kare, kuch nahi hota.
     * Retriever `finally` mein release hota hai, chahe flow cancel ho jaye.
     *
     * `fromMs`/`toMs` isliye hain taaki kai workers video ke alag-alag
     * hisse ek saath process kar sakein. Default = pura video.
     */
    fun frames(
        uri: Uri,
        fromMs: Long = 0L,
        toMs: Long = Long.MAX_VALUE
    ): Flow<VideoFrame> = flow {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val info = readInfoFrom(retriever)
            val target = targetSize(info.displayWidth, info.displayHeight)

            val interval = ProcessingConfig.FRAME_INTERVAL_MS
            val endMs = minOf(toMs, info.durationMs)

            // Timestamps ko ek hi global grid pe align karo (0, 200, 400...).
            // Warna workers ke beech overlap ya gap ban jayega aur
            // appearance segmentation galat ho jayegi.
            var index = ((fromMs + interval - 1) / interval).toInt()
            var timeMs = index * interval

            while (timeMs < endMs) {
                // User ne cancel kiya ya screen band ki toh yahin ruk jao
                currentCoroutineContext().ensureActive()

                grabFrame(retriever, timeMs * 1000L, target)?.let { bitmap ->
                    emit(VideoFrame(bitmap, timeMs, index))
                }

                index++
                timeMs = index * interval
            }
        } finally {
            retriever.release()
        }
    }

    /**
     * `frames()` ka parallel version - production mein yahi use hota hai.
     *
     * Video ko `workers` barabar hisson mein baant deta hai; har worker apna
     * MediaMetadataRetriever leke alag thread pe chalta hai, aur sab ek hi
     * Flow mein bhejte hain.
     *
     * Kyun madad karta hai: har getScaledFrameAtTime() call pichle keyframe pe
     * jump karke wahan se decode karti hai - bahut mehanga. Ek thread pe ye 150
     * baar hone mein ~110s lagte the; chaar threads pe wahi kaam ~28s.
     *
     * `flow{}` ki jagah `channelFlow{}` isliye ki normal flow mein sirf ek
     * coroutine emit kar sakta hai - kai threads se emit karne pe
     * IllegalStateException aata hai.
     *
     * WARNING: frames ab TIME ORDER MEIN NAHI aayenge. Jise order chahiye wo
     * `timestampMs` se sort kar le (AppearanceCounter wahi karta hai).
     */
    fun parallelFrames(
        uri: Uri,
        workers: Int = ProcessingConfig.EXTRACTOR_WORKERS
    ): Flow<VideoFrame> = channelFlow {
        val info = readInfo(uri)
        val sliceMs = (info.durationMs + workers - 1) / workers   // ceiling division

        (0 until workers).map { w ->
            launch(Dispatchers.Default) {
                val from = w * sliceMs
                val to = minOf((w + 1) * sliceMs, info.durationMs)
                frames(uri, from, to).collect { send(it) }
            }
        }.joinAll()
    }.buffer(ProcessingConfig.FRAME_BUFFER)   // extractors consumer ka intezaar na karein

    /**
     * PASS 2 - ek hi frame, high resolution me.
     *
     * Analysis (pass 1) 720p par hoti hai kyunki 150 frames nikalne hain.
     * Collage ke liye sirf ek frame per person chahiye, isliye wahan
     * resolution ka kharcha uthaya ja sakta hai - aur tile jitni sharp
     * hogi, collage utna acha lagega.
     */
    fun frameAt(uri: Uri, timestampMs: Long, maxHeight: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val info = readInfoFrom(retriever)
            grabFrame(
                retriever,
                timestampMs * 1000L,
                targetSize(info.displayWidth, info.displayHeight, maxHeight)
            )
        } finally {
            retriever.release()
        }
    }

    // ------------------------------------------------------------------

    private fun readInfoFrom(retriever: MediaMetadataRetriever): VideoInfo {
        fun meta(key: Int): Long =
            retriever.extractMetadata(key)?.toLongOrNull() ?: 0L

        val duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val rawW = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
        val rawH = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
        val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()

        // Portrait videos usually 90°/270° rotation metadata ke saath aati hain,
        // matlab stored frame landscape hai. Display dimensions swap ho jaate hain.
        val portraitRotated = rotation == 90 || rotation == 270
        val dispW = if (portraitRotated) rawH else rawW
        val dispH = if (portraitRotated) rawW else rawH

        return VideoInfo(
            durationMs = duration,
            displayWidth = dispW,
            displayHeight = dispH,
            expectedFrameCount = (duration / ProcessingConfig.FRAME_INTERVAL_MS).toInt()
        )
    }

    /** Aspect ratio bachate hue maxHeight tak chhota karo. */
    private fun targetSize(
        width: Int,
        height: Int,
        maxHeight: Int = ProcessingConfig.DECODE_MAX_HEIGHT
    ): Size? {
        if (width <= 0 || height <= 0) return null
        if (height <= maxHeight) return null                 // already chhota
        val ratio = maxHeight.toFloat() / height
        return Size((width * ratio).toInt().coerceAtLeast(1), maxHeight)
    }

    /**
     * OPTION_CLOSEST use kiya hai, OPTION_CLOSEST_SYNC nahi.
     *
     * SYNC sirf keyframes pe jump karta hai — tez hai, par lagatar wahi frame
     * de sakta hai. Hume exact timestamps chahiye, warna appearance boundaries
     * galat aayengi. Thoda slow hai, par accuracy 50% marks hai.
     */
    private fun grabFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        target: Size?
    ): Bitmap? {
        // API 27+ decode ke waqt hi scale kar deta hai — kaafi tez aur kam memory
        if (target != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                target.width,
                target.height
            )
        }

        // API 26 fallback: pura frame lo, phir chhota karo
        val full = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: return null
        if (target == null) return full

        val scaled = BitmapUtils.scaleToHeight(full, target.height)
        if (scaled !== full) full.recycle()
        return scaled
    }
}
