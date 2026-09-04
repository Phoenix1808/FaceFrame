package com.example.faceframe.processing

/**
 * Every tunable number in the pipeline, in one place.
 *
 * Deliberately not scattered across the classes that use them: tuning means
 * changing numbers and re-running, and hunting through six files for the one
 * that matters gets old fast. It also means the README can point at a single
 * file when it documents the chosen threshold.
 */
object ProcessingConfig {

    // ---- frame sampling ----

    // 5 fps, i.e. one frame every 200 ms. Lower and short appearances start
    // slipping through the gaps; higher and processing time goes up for nothing,
    // since appearances here run well over a second.
    const val SAMPLE_FPS = 5
    const val FRAME_INTERVAL_MS = 1000L / SAMPLE_FPS

    // Analysis resolution. Faces come out around 150 px wide, which is plenty
    // for a 112 px model input.
    const val DECODE_MAX_HEIGHT = 720

    // ---- what counts as a usable detection ----

    const val MIN_FACE_SIZE_RATIO = 0.10f
    const val MIN_FACE_WIDTH_PX = 48

    // Laplacian variance floor. Below this the face is motion-blurred — the
    // assignment is explicit that blurred whip-pan passes count for nobody, and
    // such frames also produce garbage embeddings that split one appearance in
    // two. Measured distribution on the samples: p10 ~83, median ~230.
    const val BLUR_THRESHOLD = 55.0

    // Past this much yaw it is a profile shot and the embedding stops meaning
    // much. Also used to normalise the frontality score.
    const val MAX_HEAD_YAW = 55f
    const val MAX_HEAD_PITCH = 30f

    // How close to the frame edge counts as a clipped face.
    const val EDGE_MARGIN_PX = 6

    // ---- embedding model ----

    const val MODEL_ASSET = "mobile_face_net.tflite"
    const val MODEL_INPUT_SIZE = 112
    const val EMBEDDING_SIZE = 192

    // Swept from 1.0 to 1.9. The separation margin (same-person similarity minus
    // different-person similarity) barely moves — about 0.35 either way — so the
    // crop is not what limits accuracy here. 1.0 still came out best on the
    // clustering result, so 1.0 it is.
    const val EMBED_CROP_EXPAND = 1.0f

    // ---- clustering ----

    // Above this average similarity, two clusters are the same person.
    //
    // Calibrated on Sample 1, whose ground truth ships with the assignment
    // (5 people, 20 appearances), by sweeping the full grid of this against
    // TRACK_MIN_SIMILARITY in a single run. Note what this encodes: "how alike
    // is alike enough", not "there are five people". Nothing here is fitted to
    // a particular clip.
    const val SIMILARITY_THRESHOLD = 0.45f


    // ---- tracking ----

    // Two detections this far apart in time belong to different appearances.
    // Also used afterwards to stitch a tracklet that broke mid-shot.
    const val GAP_TOLERANCE_MS = 700L

    // Two boxes in the SAME frame overlapping this much are one face, not two.
    // ML Kit sometimes returns several boxes for one face; each would otherwise
    // become its own tracklet and get counted as a separate person.
    const val DUPLICATE_FACE_IOU = 0.45f

    // Minimum overlap for two detections in CONSECUTIVE frames to be linked.
    const val TRACK_MIN_IOU = 0.25f

    // ...and the minimum embedding similarity for the same link. This is the
    // gate that catches cuts. In a portrait video everyone is framed dead centre
    // at a similar size, so after a cut the new person's box still overlaps the
    // old one by ~80% — IoU cannot tell. Only the face can.
    const val TRACK_MIN_SIMILARITY = 0.65f

    const val MIN_FRAMES_PER_SEGMENT = 2

    // ---- representative shot scoring (weights sum to 1) ----

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

    // ---- collage ----

    // Pass 2 re-reads the chosen frames at this height. Analysis runs at 720p
    // for speed, but there are only a handful of collage frames so the extra
    // resolution is essentially free here.
    const val COLLAGE_DECODE_HEIGHT = 1440

    // The assignment warns that cropping tight to the face box gives
    // "low-resolution, poor-quality tiles", so this is deliberately loose.
    const val COLLAGE_CROP_EXPAND = 2.6f

    // Shifted down a little so shoulders make it in — centring on the face box
    // leaves dead space above the head.
    const val COLLAGE_CROP_VERTICAL_BIAS = 0.12f

    // ---- parallelism ----

    val EXTRACTOR_WORKERS: Int = run {
        val heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        // Each worker holds its own MediaMetadataRetriever and decoder buffers.
        // On a low-RAM device four of them push the system hard enough that
        // Android starts killing background apps — including, memorably, the
        // photo picker we had just launched.
        if (heapMb < 192) 2 else Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }

    // Lets the extractors run ahead while ML Kit is busy on the previous frame.
    // Each buffered frame is a live bitmap, so this stays small.
    const val FRAME_BUFFER = 3

    // ---- development aid ----

    // Renders one tile per tracklet so you can see by eye which tracklets are
    // actually the same person. Tap the collage to toggle. Off for submission.
    const val DEBUG_CONTACT_SHEET = false
    const val DEBUG_DECODE_HEIGHT = 720
}
