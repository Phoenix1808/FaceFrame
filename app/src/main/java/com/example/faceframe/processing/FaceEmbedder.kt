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
 * Turns a face into 192 numbers you can compare.
 *
 * ML Kit tells us where a face is; this tells us whose it is.
 * Not thread-safe — TFLite's Interpreter isn't. One instance per coroutine.
 */
class FaceEmbedder(context: Context) : Closeable {

    private val interpreter = Interpreter(
        loadModelFile(context, ProcessingConfig.MODEL_ASSET),
        Interpreter.Options().setNumThreads(2)
    )

    // This model was exported with batch = 2, because the app it came from
    // compared two faces in one call. resizeInput() to batch 1 does not work:
    //
    //     reshape.cc:92 num_input_elements != num_output_elements (192 != 384)
    //     Node number 229 (RESHAPE) failed to prepare
    //
    // There is a RESHAPE near the output with 2 x 192 baked into it. So rather
    // than fight the model, feed it what it asks for — same face in both slots,
    // read row 0. Costs 2x the inference, but dropping in a different model
    // then needs no code change here.
    private val batchSize: Int
    private val inputSize: Int
    private val embeddingSize: Int

    // Reused across calls to avoid allocating on every face. Which is exactly
    // why embed() has to return a copy.
    private val outputBuffer: Array<FloatArray>

    init {
        val input = interpreter.getInputTensor(0).shape()      // [2, 112, 112, 3]
        val output = interpreter.getOutputTensor(0).shape()    // [2, 192]

        batchSize = input[0]
        inputSize = input[1]
        embeddingSize = output[1]
        outputBuffer = Array(output[0]) { FloatArray(embeddingSize) }

        Log.d(TAG, "model loaded: input=${input.toList()} output=${output.toList()}")

        // Fail loudly on a mismatch. A wrong shape would otherwise run fine and
        // just produce nonsense embeddings.
        check(inputSize == ProcessingConfig.MODEL_INPUT_SIZE) {
            "Model wants input $inputSize, config says ${ProcessingConfig.MODEL_INPUT_SIZE}"
        }
        check(embeddingSize == ProcessingConfig.EMBEDDING_SIZE) {
            "Model gives $embeddingSize dims, config says ${ProcessingConfig.EMBEDDING_SIZE}"
        }
    }

    fun embed(
        frame: Bitmap,
        faceRect: Rect,
        leftEye: PointF?,
        rightEye: PointF?,
        expandFactor: Float = ProcessingConfig.EMBED_CROP_EXPAND
    ): FloatArray {
        // Straighten the eyes first — the model was trained on aligned faces.
        val aligned = BitmapUtils.alignedFaceCrop(
            source = frame,
            faceRect = faceRect,
            leftEye = leftEye,
            rightEye = rightEye,
            expandFactor = expandFactor,
            outputSize = inputSize
        )

        val input = BitmapUtils.toModelInput(aligned, inputSize, batchSize)
        aligned.recycle()

        interpreter.run(input, outputBuffer)

        // copyOf() is not optional: the buffer is overwritten on the next call,
        // and without it every FaceSample ends up sharing one array.
        return l2Normalize(outputBuffer[0].copyOf())
    }

    override fun close() = interpreter.close()

    companion object {

        private const val TAG = "FaceFrame"

        // Both vectors are unit length, so cosine similarity is just the dot
        // product. Roughly 0.9 for the same person, 0.3 for two different ones,
        // and the interesting cases sit in between.
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot
        }

        // MobileFaceNet's output isn't normalised, so we do it here, once.
        private fun l2Normalize(v: FloatArray): FloatArray {
            var sumSq = 0f
            for (x in v) sumSq += x * x
            val norm = sqrt(sumSq).coerceAtLeast(1e-10f)
            for (i in v.indices) v[i] = v[i] / norm
            return v
        }

        // Memory-maps the model rather than reading 5 MB into the heap.
        // openFd() only works if the asset is stored uncompressed, which is what
        // `noCompress += "tflite"` in build.gradle.kts is for. Remove that and
        // this throws FileNotFoundException.
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
