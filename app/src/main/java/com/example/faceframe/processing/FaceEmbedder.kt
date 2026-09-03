package com.example.faceframe.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.example.faceframe.util.BitmapUtils
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Chehre ko pehchan-ne layak numbers mein badalta hai.
 *
 * Model: MobileFaceNet (InsightFace/ArcFace loss se trained).
 *   input  : 112 x 112 x 3 float32, RGB, (pixel - 127.5) / 128
 *   output : 192 float32
 *
 * Ye model is tarah train hua hai ki EK HI insaan ke do photos ke output
 * vectors paas-paas aayein aur ALAG logon ke door. Isliye "ye same banda
 * hai?" ka jawab ab ek simple math ban jata hai (cosine similarity).
 *
 * ML Kit sirf batata hai ki chehra KAHAN hai. Ye batata hai ki KISKA hai.
 *
 * WARNING: TFLite ka Interpreter thread-safe NAHI hai. Ek instance ko ek hi
 * coroutine se use karo, ya har worker ke liye alag instance banao.
 */
class FaceEmbedder(context: Context) : Closeable {

    private val interpreter = Interpreter(
        loadModelFile(context, ProcessingConfig.MODEL_ASSET),
        Interpreter.Options().setNumThreads(2)
    )

    /**
     * Model ka batch dimension. Ye model batch = 2 ke saath export hua tha,
     * kyunki jis reference app se aaya wo DO chehre ek saath compare karta tha.
     *
     * Batch ko resizeInput() se 1 karne ki koshish FAIL hoti hai:
     *   reshape.cc:92 num_input_elements != num_output_elements (192 != 384)
     *   Node number 229 (RESHAPE) failed to prepare
     * Kyunki graph ke aakhir mein ek RESHAPE node hai jisme output ka size
     * (2 x 192 = 384) hardcode hai.
     *
     * Isliye model se ladte nahi - jo maang raha hai wahi dete hain. Ek hi
     * chehra dono slots mein bhar dete hain aur output ki pehli row lete hain.
     * Compute 2x hota hai, par model badle bina yahi sabse saaf raasta hai.
     */
    private val batchSize: Int

    /** Model ka asli input side (112). Maan-ne ke bajaye model se poocha gaya. */
    private val inputSize: Int

    /** Model ka asli embedding length (192). */
    private val embeddingSize: Int

    /**
     * Output buffer dobara-dobara use hota hai taaki har call pe nayi
     * allocation na ho (150 frames x kai chehre = bahut garbage).
     * Isi wajah se `embed()` ko copy lautana ZAROORI hai - warna saare
     * FaceSample ek hi array share karenge aur sabki identity ek jaisi
     * dikhne lagegi.
     */
    private val outputBuffer: Array<FloatArray>

    init {
        val input = interpreter.getInputTensor(0).shape()      // [2, 112, 112, 3]
        val output = interpreter.getOutputTensor(0).shape()    // [2, 192]

        batchSize = input[0]
        inputSize = input[1]
        embeddingSize = output[1]
        outputBuffer = Array(output[0]) { FloatArray(embeddingSize) }

        Log.d(TAG, "model loaded: input=${input.toList()} output=${output.toList()}")

        // Config ke maane hue numbers model se match karte hain? Warna
        // sab kuch chalega par embeddings galat honge - chup-chaap.
        check(inputSize == ProcessingConfig.MODEL_INPUT_SIZE) {
            "Model input $inputSize, config me ${ProcessingConfig.MODEL_INPUT_SIZE}"
        }
        check(embeddingSize == ProcessingConfig.EMBEDDING_SIZE) {
            "Model output $embeddingSize, config me ${ProcessingConfig.EMBEDDING_SIZE}"
        }
    }

    /**
     * Frame ke andar se ek chehra nikaal kar uska embedding banata hai.
     *
     * Teen kadam:
     *   1. Aankhon ki line ke hisaab se chehra seedha karo, square crop lo
     *   2. Pixels ko model ke format mein badlo
     *   3. Inference chalao, phir L2-normalize karo
     *
     * @param frame pura video frame (crop yahan hoga)
     * @param faceRect ML Kit ka bounding box, frame coordinates mein
     */
    fun embed(
        frame: Bitmap,
        faceRect: Rect,
        leftEye: PointF?,
        rightEye: PointF?
    ): FloatArray {
        val aligned = BitmapUtils.alignedFaceCrop(
            source = frame,
            faceRect = faceRect,
            leftEye = leftEye,
            rightEye = rightEye,
            expandFactor = ProcessingConfig.EMBED_CROP_EXPAND,
            outputSize = inputSize
        )

        val input = BitmapUtils.toModelInput(aligned, inputSize, batchSize)
        aligned.recycle()

        interpreter.run(input, outputBuffer)

        // Row 0 - baaki rows wahi chehra hain (batch padding).
        // copyOf() zaroori hai - buffer agli call mein overwrite ho jayega.
        return l2Normalize(outputBuffer[0].copyOf())
    }

    override fun close() = interpreter.close()

    companion object {

        private const val TAG = "FaceFrame"

        /**
         * Do embeddings kitne milte-julte hain: -1 (bilkul alag) se +1 (same).
         *
         * Dono vectors already L2-normalized hain (length = 1), isliye
         * cosine similarity sirf dot product ban jati hai - koi division nahi.
         *
         * Practical values:
         *   ~0.85  pakka same banda
         *   ~0.62  threshold ke aas-paas   <- ProcessingConfig.SIMILARITY_THRESHOLD
         *   ~0.30  alag log
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot
        }

        /**
         * Vector ki length 1 kar deta hai.
         *
         * MobileFaceNet raw output deta hai, normalized nahi. Bina iske
         * cosine similarity ko har baar dono vectors ki length se divide
         * karna padta, aur alag-alag lambai wale vectors compare karna
         * galat nateeje deta.
         */
        private fun l2Normalize(v: FloatArray): FloatArray {
            var sumSq = 0f
            for (x in v) sumSq += x * x
            val norm = sqrt(sumSq).coerceAtLeast(1e-10f)   // divide-by-zero se bacho
            for (i in v.indices) v[i] = v[i] / norm
            return v
        }

        /**
         * Model ko memory-map karta hai (poora RAM mein load nahi karta).
         *
         * `assets.openFd()` sirf tab chalta hai jab file APK ke andar
         * UNCOMPRESSED ho. Isi liye build.gradle.kts mein likha tha:
         *     androidResources { noCompress += "tflite" }
         * Wo line hata do to yahan FileNotFoundException aayega.
         */
        private fun loadModelFile(context: Context, assetName: String): MappedByteBuffer {
            context.assets.openFd(assetName).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    return stream.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        fd.startOffset,
                        fd.declaredLength
                    )
                }
            }
        }
    }
}
