package com.example.faceframe.processing


object ProcessingConfig {

    // frame sampling

    // 5 fps, i.e. one frame every 200 ms. Lower and short appearances start
    const val SAMPLE_FPS = 5
    const val FRAME_INTERVAL_MS = 1000L / SAMPLE_FPS

    // Analysis resolution. Faces come out around 150 px wide, which is plenty
    // for a 112 px model input.
    const val DECODE_MAX_HEIGHT = 720

    const val MIN_FACE_SIZE_RATIO = 0.10f
    const val MIN_FACE_WIDTH_PX = 48
    const val BLUR_THRESHOLD = 55.0
    
    const val MAX_HEAD_YAW = 55f
    const val MAX_HEAD_PITCH = 30f

    // How close to the frame edge counts as a clipped face.
    const val EDGE_MARGIN_PX = 6

    // embedding model

    const val MODEL_ASSET = "mobile_face_net.tflite"
    const val MODEL_INPUT_SIZE = 112
    const val EMBEDDING_SIZE = 192
    const val EMBED_CROP_EXPAND = 1.0f

    // clustering
    const val SIMILARITY_THRESHOLD = 0.45f


    // tracking 
    const val GAP_TOLERANCE_MS = 700L
    const val DUPLICATE_FACE_IOU = 0.45f

    // Minimum overlap for two detections in CONSECUTIVE frames to be linked.
    const val TRACK_MIN_IOU = 0.25f

    const val TRACK_MIN_SIMILARITY = 0.65f

    const val MIN_FRAMES_PER_SEGMENT = 2

    // representative shot scoring (weights sum to 1)

    const val W_FRONTALITY = 0.30f
    const val W_SHARPNESS = 0.25f
    const val W_EYES_OPEN = 0.20f
    const val W_FACE_SIZE = 0.15f
    const val W_SMILE = 0.10f

    const val CLIPPED_FACE_PENALTY = 0.35f

    // Prefer a frame where this person is alone. A generous crop of someone
    // standing next to somebody else drags that somebody else into the tile.
    const val SHARED_FRAME_PENALTY = 0.30f

    // Laplacian variance at which the sharpness score saturates — beyond this
    // the difference stops being visible.
    const val SHARPNESS_REFERENCE = 300.0

    // Face area, as a fraction of the frame, at which the size score saturates.
    const val FACE_AREA_REFERENCE = 0.12f

    // collage
    const val COLLAGE_DECODE_HEIGHT = 1440

    const val COLLAGE_CROP_EXPAND = 2.6f

    const val COLLAGE_CROP_VERTICAL_BIAS = 0.12f

    // ---- parallelism ----

    val EXTRACTOR_WORKERS: Int = run {
        val heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        if (heapMb < 192) 2 else Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
    const val FRAME_BUFFER = 3
    const val DEBUG_CONTACT_SHEET = false
    const val DEBUG_DECODE_HEIGHT = 720
}
