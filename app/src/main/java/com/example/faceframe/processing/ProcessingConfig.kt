package com.example.faceframe.processing


//Saare tunable numbers ek jagah: SIMILARITY_THRESHOLD, SAMPLE_FPS, GAP_TOLERANCE_MS, BLUR_THRESHOLD, MIN_FRAMES_PER_SEGMENT, scoring weights

object ProcessingConfig {

    const val SAMPLE_FPS = 5
    const val FRAME_INTERVAL_MS = 1000L/SAMPLE_FPS

    const val DECODE_MAX_HEIGHT = 720
    const val MIN_FACE_SIZE_RATIO = 0.10f
    const val MIN_FACE_WIDTH_PX = 48
    const val BLUR_THRESHOLD = 55.0

    const val MAX_HEAD_YAW = 55f
    const val EDGE_MARGIN_PX = 6

    //embedding model
    const val MODEL_ASSET = "mobile_face_net.tflite"
    const val MODEL_INPUT_SIZE = 112
    const val EMBEDDING_SIZE = 192

    const val EMBED_CROP_EXPAND = 1.35f

    //clustering
    const val SIMILARITY_THRESHOLD = 0.62f
    const val MIN_SAMPLES_PER_PERSON = 3

    const val GAP_TOLERANCE_MS = 700L
    const val MIN_FRAMES_PER_SEGMENT = 2


    const val W_FRONTALITY = 0.30f
    const val W_SHARPNESS = 0.25f
    const val W_EYES_OPEN = 0.20f
    const val W_FACE_SIZE = 0.15f
    const val W_SMILE = 0.10f

    //condn for cropped face as a penalty
    const val CLIPPED_FACE_PENALTY = 0.35f

    const val SHARPNESS_REFERENCE = 300.0

    const val COLLAGE_CROP_EXPAND = 2.6f

    const val COLLAGE_CROP_VERTICAL_BIAS = 0.12f

    //parallelism
    /** Kitne extractor threads chalein. Phone ke cores ke hisaab se, 2-4 ke beech.
     *  4 se zyada ka faayda nahi - hardware video decoder bottleneck ban jata hai. */
    val EXTRACTOR_WORKERS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    /** Kitne decoded frames memory mein queue ho sakte hain.
     *  Buffer se extractors aage kaam karte rehte hain jab tak ML Kit detect kar raha ho. */
    const val FRAME_BUFFER = 6
}
