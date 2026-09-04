package com.example.faceframe.processing

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.tasks.await
import java.io.Closeable
import kotlin.math.abs

val Face.leftEyePosition: PointF?
    get() = getLandmark(FaceLandmark.LEFT_EYE)?.position

val Face.rightEyePosition: PointF?
    get() = getLandmark(FaceLandmark.RIGHT_EYE)?.position

/**
 * ML Kit wrapper. Says where a face is and what it looks like — never whose
 * it is; that is FaceEmbedder's job.
 *
 * Closeable because the detector holds native resources.
 */
class FaceAnalyzer : Closeable {

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            // This runs offline over a file, not on a camera preview, so accuracy
            // beats speed. FAST mode drops small and side-facing faces, and every
            // miss is a wrong appearance count.
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)

            // Eye positions, used to straighten the face before embedding.
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)

            // Without this, smilingProbability and eyeOpenProbability come back
            // null — and the assignment asks for both by name.
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)

            // 130-odd contour points we would never look at, and slow.
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)

            .setMinFaceSize(ProcessingConfig.MIN_FACE_SIZE_RATIO)
            .build()
    )

    // rotation = 0 because MediaMetadataRetriever already applies the video's
    // rotation metadata to the bitmap it hands back.
    suspend fun detect(bitmap: Bitmap): List<Face> {
        val image = InputImage.fromBitmap(bitmap, 0)
        return detector.process(image).await()
    }

    // Cheap geometric filter, run before spending 35 ms on an embedding.
    // Too small and there are not enough pixels to identify anyone; too much
    // yaw and it is a profile shot the embedding cannot be trusted on.
    fun isUsable(face: Face): Boolean {
        if (face.boundingBox.width() < ProcessingConfig.MIN_FACE_WIDTH_PX) return false
        if (abs(face.headEulerAngleY) > ProcessingConfig.MAX_HEAD_YAW) return false
        return true
    }

    /**
     * Drops duplicate boxes on the same face.
     *
     * ML Kit occasionally returns two or three overlapping boxes for one face.
     * Each becomes its own detection, then its own tracklet, and because those
     * tracklets run at the same instant the clustering constraint decides they
     * must be different people — so one person shows up two or three times in
     * the collage. Took a while to work that one out.
     *
     * Keeps the largest box, which is most likely to hold the whole face.
     */
    fun deduplicate(faces: List<Face>): List<Face> {
        if (faces.size < 2) return faces

        val kept = mutableListOf<Face>()
        for (face in faces.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }) {
            val isDuplicate = kept.any {
                iou(it.boundingBox, face.boundingBox) > ProcessingConfig.DUPLICATE_FACE_IOU
            }
            if (!isDuplicate) kept += face
        }
        return kept
    }

    override fun close() = detector.close()
}
