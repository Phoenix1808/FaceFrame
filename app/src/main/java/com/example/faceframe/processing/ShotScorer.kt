package com.example.faceframe.processing

import com.example.faceframe.model.FaceSample
import kotlin.math.abs

/**
 * Har detection ko 0..1 ka quality score deta hai. Person ka sabse zyada
 * score wala shot collage mein jaata hai.
 *
 * Assignment: "Judge each candidate shot using face attributes such as
 * frontality, sharpness, eyes open, and smiling / expression probability -
 * favouring frontal, crisp, eyes-open, pleasant shots. Prefer a source frame
 * where the full face is visible; avoid clipped faces and closed eyes."
 *
 * Weights ProcessingConfig mein hain (jod = 1.0), taaki tuning ek jagah ho.
 */
object ShotScorer {

    fun score(sample: FaceSample): Float {
        val score =
            ProcessingConfig.W_FRONTALITY * frontality(sample) +
                    ProcessingConfig.W_SHARPNESS * sharpness(sample) +
                    ProcessingConfig.W_EYES_OPEN * sample.eyesOpenScore +
                    ProcessingConfig.W_FACE_SIZE * faceSize(sample) +
                    ProcessingConfig.W_SMILE * sample.smileProbability

        // Kata hua chehra kitna bhi sharp ho, collage tile mein bura lagta hai.
        var penalty = if (sample.isClipped) ProcessingConfig.CLIPPED_FACE_PENALTY else 0f

        // Do log ek frame me = tile me dono aa jayenge. Agar is insaan ka
        // koi akela frame hai, wahi chuno.
        if (sample.facesInFrame > 1) penalty += ProcessingConfig.SHARED_FRAME_PENALTY

        return (score - penalty).coerceIn(0f, 1f)
    }

    /** Poore cluster ka sabse acha shot. */
    fun bestOf(samples: List<FaceSample>): FaceSample = samples.maxBy { score(it) }

    // ------------------------------------------------------------------

    /**
     * Kitna seedha camera ki taraf dekh raha hai. 1.0 = bilkul saamne.
     *
     * Yaw (left/right mudna) pitch (upar/neeche) se zyada kharab karta hai -
     * side profile mein aadha chehra hi nahi dikhta - isliye 70/30 weight.
     * Roll (sar tilt) count nahi hota: wo photo mein bura nahi lagta, aur
     * embedding ke liye humne pehle hi align kar diya tha.
     */
    private fun frontality(sample: FaceSample): Float {
        val yawOff = (abs(sample.yaw) / ProcessingConfig.MAX_HEAD_YAW).coerceIn(0f, 1f)
        val pitchOff = (abs(sample.pitch) / ProcessingConfig.MAX_HEAD_PITCH).coerceIn(0f, 1f)
        return 1f - (0.7f * yawOff + 0.3f * pitchOff)
    }

    /**
     * Laplacian variance ko 0..1 mein badalta hai.
     * SHARPNESS_REFERENCE pe pahunchte hi poora score - usse aage farq
     * aankhon ko dikhta nahi, isliye cap kar dete hain.
     */
    private fun sharpness(sample: FaceSample): Float =
        (sample.sharpness / ProcessingConfig.SHARPNESS_REFERENCE)
            .coerceIn(0.0, 1.0)
            .toFloat()

    /**
     * Bada chehra = zyada pixels = behtar collage tile.
     * Assignment: tight crop se "low-resolution, poor-quality tiles" banti hain,
     * isliye aisa frame chuno jahan banda camera ke paas ho.
     */
    private fun faceSize(sample: FaceSample): Float =
        (sample.faceAreaRatio / ProcessingConfig.FACE_AREA_REFERENCE).coerceIn(0f, 1f)
}
