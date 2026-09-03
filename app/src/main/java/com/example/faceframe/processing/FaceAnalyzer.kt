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

/** Aankhon ke coordinates — face alignment ke liye. */
val Face.leftEyePosition: PointF?
    get() = getLandmark(FaceLandmark.LEFT_EYE)?.position

val Face.rightEyePosition: PointF?
    get() = getLandmark(FaceLandmark.RIGHT_EYE)?.position

/**
 * ML Kit face detection ka wrapper.
 *
 * Ye sirf batata hai ki chehra KAHAN hai aur kaisa dikh raha hai —
 * chehra KISKA hai ye nahi. Wo kaam FaceEmbedder ka hai.
 *
 * `Closeable` isliye ki detector native resources rakhta hai;
 * `close()` na karo toh memory leak.
 */
class FaceAnalyzer : Closeable {

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            // Offline processing hai, real-time nahi — accuracy chuni.
            // FAST mode chhote aur side-facing faces miss karta hai,
            // aur har miss = galat appearance count.
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)

            // Aankhon ke points → face alignment → better embeddings
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)

            // ⭐ Iske bina smilingProbability aur eyeOpenProbability NULL aate hain.
            // Assignment inhe explicitly maangta hai.
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)

            // 130+ contour points chahiye nahi, aur ye slow hai
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)

            // Background ke chhote-dhundhle chehre yahin filter ho jaate hain
            .setMinFaceSize(ProcessingConfig.MIN_FACE_SIZE_RATIO)
            .build()
    )

    /**
     * Ek frame mein saare chehre dhoondo.
     *
     * rotation = 0 kyunki MediaMetadataRetriever already rotation
     * metadata apply karke bitmap deta hai.
     */
    suspend fun detect(bitmap: Bitmap): List<Face> {
        val image = InputImage.fromBitmap(bitmap, 0)
        return detector.process(image).await()
    }

    /**
     * Sasta geometric filter — embedding nikalne se PEHLE chalao.
     *
     * Bahut chhota face = pixels hi nahi hain identity ke liye.
     * Bahut zyada yaw = profile view, embedding bharosemand nahi rehta.
     */
    fun isUsable(face: Face): Boolean {
        if (face.boundingBox.width() < ProcessingConfig.MIN_FACE_WIDTH_PX) return false
        if (abs(face.headEulerAngleY) > ProcessingConfig.MAX_HEAD_YAW) return false
        return true
    }

    override fun close() = detector.close()
}
