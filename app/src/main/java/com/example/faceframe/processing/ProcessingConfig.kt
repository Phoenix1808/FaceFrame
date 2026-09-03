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
    /**
     * Isse upar ka average = same insaan.
     *
     * Sample 1 ke known ground truth (5 log, 20 appearances) par calibrate kiya,
     * dono knobs ka poora grid chala kar. Is value par 5 log sahi milte hain.
     * Ye value KISI BHI video par chalti hai - ye "5" nahi jaanti, ye sirf
     * "kitna milna same maana jaye" jaanti hai.
     */
    const val SIMILARITY_THRESHOLD = 0.50f
    const val MIN_SAMPLES_PER_PERSON = 3

    const val GAP_TOLERANCE_MS = 700L

    /**
     * Do consecutive frames ke bounding boxes kitne overlap karein taaki
     * "wahi banda hai" maana jaye. IoU = intersection / union.
     * Isse tracking chalti hai - aur yahi do saath khade logon ko alag
     * rakhta hai, kyunki unke boxes screen pe alag jagah hote hain.
     */
    const val TRACK_MIN_IOU = 0.25f

    /**
     * Do consecutive detections ka embedding kitna milna chahiye taaki wo
     * ek hi tracklet mein jayein.
     *
     * Ye gate CUTS pakadta hai. Portrait video mein har banda screen ke beech
     * mein same size me framed hota hai, isliye cut ke baad bhi naye bande ka
     * box purane se ~80% overlap karta hai - IoU akela cut nahi pakad sakta.
     * Chehra badla ya nahi, ye sirf embedding bata sakta hai.
     */
    const val TRACK_MIN_SIMILARITY = 0.55f
    const val MIN_FRAMES_PER_SEGMENT = 2


    const val W_FRONTALITY = 0.30f
    const val W_SHARPNESS = 0.25f
    const val W_EYES_OPEN = 0.20f
    const val W_FACE_SIZE = 0.15f
    const val W_SMILE = 0.10f

    //condn for cropped face as a penalty
    const val CLIPPED_FACE_PENALTY = 0.35f

    const val SHARPNESS_REFERENCE = 300.0

    /** Face frame ka itna hissa ghere to size score poora. Isse bada = 1.0 hi. */
    const val FACE_AREA_REFERENCE = 0.12f

    /** Upar/neeche dekhne ki limit, frontality score normalize karne ke liye. */
    const val MAX_HEAD_PITCH = 30f

    const val COLLAGE_CROP_EXPAND = 2.6f

    const val COLLAGE_CROP_VERTICAL_BIAS = 0.12f

    //parallelism
    /** Kitne extractor threads chalein. Phone ke cores ke hisaab se, 2-4 ke beech.
     *  4 se zyada ka faayda nahi - hardware video decoder bottleneck ban jata hai. */
    val EXTRACTOR_WORKERS: Int = run {
        val heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        // Har worker apna MediaMetadataRetriever + decoder buffers rakhta hai.
        // Kam RAM wale phone pe 4 workers system ko itna dabate hain ki Android
        // background apps (photo picker tak) maarne lagta hai.
        if (heapMb < 192) 2 else Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }

    /** Kitne decoded frames memory mein queue ho sakte hain.
     *  Buffer se extractors aage kaam karte rehte hain jab tak ML Kit detect kar raha ho. */
    const val FRAME_BUFFER = 3
}
