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
 * One frame out of the video.
 *
 * Whoever collects this owns the bitmap and must recycle it. 150 frames at
 * ~2 MB each is not something you want to find out about the hard way.
 */
data class VideoFrame(
    val bitmap: Bitmap,
    val timestampMs: Long,
    val index: Int
)

data class VideoInfo(
    val durationMs: Long,
    val displayWidth: Int,
    val displayHeight: Int,
    val expectedFrameCount: Int
)

/**
 * Cuts a video into frames at a fixed interval.
 *
 * Frames arrive one at a time through a Flow rather than as a list, so the
 * consumer can finish with each bitmap and free it before the next one shows up.
 */
class FrameExtractor(private val context: Context) {

    fun readInfo(uri: Uri): VideoInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            readInfoFrom(retriever)
        } finally {
            retriever.release()
        }
    }

   
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

            // Timestamps come off one global grid (0, 200, 400, ...) rather than
            // counting up from fromMs. Otherwise the slices drift apart at the
            // seams and you get overlaps or holes between workers.
            var index = ((fromMs + interval - 1) / interval).toInt()
            var timeMs = index * interval

            while (timeMs < endMs) {
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
    }.buffer(ProcessingConfig.FRAME_BUFFER)   // so extractors do not idle on the consumer

    /**
     * Pass 2: one frame, at whatever resolution the caller wants.
     *
     * Analysis runs at 720p because it has 150 frames to get through. The
     * collage only needs one frame per person, so it can afford better.
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

    private fun readInfoFrom(retriever: MediaMetadataRetriever): VideoInfo {
        fun meta(key: Int): Long =
            retriever.extractMetadata(key)?.toLongOrNull() ?: 0L

        val duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val rawW = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
        val rawH = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
        val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()

        // Portrait clips are usually stored landscape with a 90 or 270 degree
        // rotation flag, so the displayed dimensions are the stored ones swapped.
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

    // null means "already small enough, leave it alone".
    private fun targetSize(
        width: Int,
        height: Int,
        maxHeight: Int = ProcessingConfig.DECODE_MAX_HEIGHT
    ): Size? {
        if (width <= 0 || height <= 0) return null
        if (height <= maxHeight) return null
        val ratio = maxHeight.toFloat() / height
        return Size((width * ratio).toInt().coerceAtLeast(1), maxHeight)
    }

    // OPTION_CLOSEST, not OPTION_CLOSEST_SYNC. SYNC only lands on keyframes,
    // which is much faster but happily returns the same frame several times in
    // a row — and appearance boundaries would go with it.
    private fun grabFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        target: Size?
    ): Bitmap? {
        // API 27+ can scale during decode, which is both faster and lighter.
        if (target != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                target.width,
                target.height
            )
        }

        val full = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: return null
        if (target == null) return full

        val scaled = BitmapUtils.scaleToHeight(full, target.height)
        if (scaled !== full) full.recycle()
        return scaled
    }
}
