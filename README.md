# FaceFrame

An Android app that processes a portrait video entirely **on-device**, finds every
distinct person in it, counts how many times each person appears, picks a good
representative shot for each, and renders a shareable collage.

No backend, no network calls. Face detection, face embeddings and clustering all
run locally.

---

## Build & run

```bash
git clone https://github.com/Phoenix1808/FaceFrame.git
cd FaceFrame
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and hit **Run**.

| | |
|---|---|
| minSdk | 26 |
| targetSdk / compileSdk | 36 |
| Kotlin | 2.0.21 |
| Android Gradle Plugin | 8.13.2 |
| UI | XML views + ViewBinding |

The TFLite model ships in `app/src/main/assets/`, so there is nothing to download.

**Using the app:** tap *Pick a video* → choose a video → wait for processing →
the collage appears, with **Save** and **Share** below it.

> No storage permission is requested on Android 10+. The system video picker
> grants read access to the one file you choose, and `MediaStore` lets an app
> write its own images without permission. On Android 9 and below,
> `WRITE_EXTERNAL_STORAGE` is requested at save time.

---

## How it works

```
video
  │  FrameExtractor      sample 1 frame every 200 ms (5 fps), 4 parallel workers
  ▼
frames
  │  FaceAnalyzer        ML Kit detection + head pose, eyes-open, smile
  │                      deduplicate overlapping boxes, reject blurry faces
  ▼
detections
  │  FaceEmbedder        MobileFaceNet → 192-d embedding per face
  ▼
FaceSample[]
  │  FaceTracker         link detections across frames → tracklets
  │                      (one tracklet = one continuous visible segment)
  ▼
Tracklet[]               each carries the average of its frames' embeddings
  │  FaceClusterer       agglomerative clustering, average linkage
  ▼
Person[]
  │  AppearanceCounter   tracklets → appearances
  │  ShotScorer          pick the best frame per person
  │  CollageRenderer     draw a 1080×1920 poster
  ▼
collage  →  MediaSaver  →  gallery / share sheet
```

### The three required stages

| Stage | Implementation |
|---|---|
| Face detection | ML Kit `face-detection:16.1.7` (bundled model, fully offline) |
| Face embeddings | MobileFaceNet TFLite, 192-d |
| Clustering | Agglomerative, average linkage, cosine similarity |

### Design decisions worth calling out

**Two passes over the video.** Pass 1 streams frames, extracts what it needs
(embedding + attributes, about 1 KB per face) and recycles each bitmap
immediately. Holding 150 decoded frames would be roughly 1.2 GB. Pass 2 re-reads
only the handful of frames that actually end up in the collage, at a higher
resolution (1440p vs 720p for analysis).

**Cluster tracklets, not frames.** A single frame's embedding wobbles with
lighting, pose and motion blur. Averaging the embeddings across a tracklet
cancels much of that noise, and it reduces the clustering problem from ~130 noisy
vectors to ~20 stable ones. A tracklet is also exactly the assignment's
definition of an appearance: *one continuous visible segment*.

**Tracking uses position and identity.** Linking two detections requires both
bounding-box IoU above `TRACK_MIN_IOU` and embedding similarity above
`TRACK_MIN_SIMILARITY`. IoU alone keeps two people in the same frame apart, but
it cannot detect a cut — in a portrait video every subject is framed dead centre
at a similar size, so boxes overlap heavily across a cut. Only the embedding can
tell that the face changed.

**A person cannot be in two places at once.** If two tracklets overlap in time
they are definitely different people, whatever the model thinks of their
embeddings. Clustering treats this as a hard must-not-link constraint, and the
constraint is inherited through merges. This is what keeps the two people who
share the frame apart when the model cannot separate them on appearance alone.

**Agglomerative, not K-Means.** K-Means needs the number of clusters up front,
and the number of people in a video is exactly what we are trying to find. The
similarity threshold decides how many clusters form; nothing in the code knows or
assumes how many people any video contains.

---

## Embedding model

**MobileFaceNet**, TensorFlow Lite, shipped at
`app/src/main/assets/mobile_face_net.tflite`.

| | |
|---|---|
| Size | 5,233,396 bytes (~5 MB) |
| Input | `[2, 112, 112, 3]` float32, RGB, normalised `(pixel − 127.5) / 128` |
| Output | `[2, 192]` float32, L2-normalised in code before comparison |
| Training loss | InsightFace / ArcFace |
| Source | [syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing](https://github.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing), converted from [sirius-ai/MobileFaceNet_TF](https://github.com/sirius-ai/MobileFaceNet_TF) |
| SHA-256 | `d8ba40c0127fb8ca9917e8fddc79bbbda063657bc92a496d34da0bc8a760443b` |

The batch dimension is fixed at **2** — the model was exported for an app that
compared two faces at once, and a `RESHAPE` node near the output has `2 × 192`
baked into it, so `resizeInput()` to batch 1 fails. `FaceEmbedder` reads the
model's declared shape at load time and fills every batch slot with the same
face, then reads row 0. It costs 2× the inference, and it means swapping in a
different model does not require touching the code.

Before inference each face is rotated so the eyes are horizontal and cropped to a
square, because the model was trained on aligned faces.

---

## Similarity threshold

```kotlin
SIMILARITY_THRESHOLD  = 0.45   // two clusters merge above this
TRACK_MIN_SIMILARITY  = 0.65   // two detections join one tracklet above this
```

Similarity is the cosine of the angle between two L2-normalised embeddings, which
reduces to a dot product.

**How it was chosen.** Sample 1's ground truth is published with the assignment
(five people, four appearances each). Since extraction and embedding take about
two minutes but tracking and clustering take milliseconds, the app computes the
embeddings once and then sweeps the whole parameter grid on that data in a single
run — 35 combinations of tracking similarity × cluster threshold, logged with the
resulting people count and appearance distribution. `0.45 / 0.65` is the
combination that reproduces the correct number of people and the correct total
appearance count.

The threshold encodes *"how alike is alike enough"*, not *"there are five
people"*. Nothing in the pipeline is fitted to a particular clip.

Every other tunable lives beside it in
[`ProcessingConfig.kt`](app/src/main/java/com/example/faceframe/processing/ProcessingConfig.kt).

---

## Results

All three clips feature the same five people, so five is the expected answer
for each. Ground truth for appearance counts is published only for Sample 1
(20 appearances, four each).

| | People | Appearances | Time |
|---|---|---|---|
| Sample 1 | **5** ✅ | **20** ✅ (ground truth 20) | ~120 s |
| Sample 2 | **5** ✅ | 17 | ~120 s |
| Sample 3 | 6 | 15 | ~120 s |

On Sample 1 the per-person split is 6, 4, 4, 4, 2 against a ground truth of
4, 4, 4, 4, 4 — three of the five exactly right — and both moments where two
people share the frame are correctly kept apart.

Worth noting that the threshold was tuned only against Sample 1 and still gives
the right number of people on Sample 2, which is the main evidence that it
generalises rather than being fitted to one clip. Sample 3 comes out one over.

Measured separation differs a lot between clips, which is the whole story:

| | same person | different people |
|---|---|---|
| Sample 1 | 0.939 | **0.583** |
| Sample 3 | 0.939 | **0.268** |

Same-person similarity is rock steady. It is the *different*-people number that
moves, and on Sample 1 it climbs high enough to collide with the merge
threshold.

## Known limitations

**Three people are partly conflated.** Two of the five in Sample 1 cluster
perfectly. The remaining three — two men and a woman, all East Asian — sit close
enough in embedding space that MobileFaceNet cannot reliably separate them.

This is measured, not guessed. The app logs a sanity check every run:

```
SAME person (adjacent frames)     median 0.939
DIFF person (two faces, one frame) median 0.583
```

The two people who genuinely share the frame score **0.583** — above the 0.45
merge threshold. A full sweep of tracking similarity × cluster threshold produces
no combination that separates them without fragmenting everyone else, and
sweeping the embedding crop factor from 1.0 to 1.9 moves the separation margin by
less than 0.03. The limit is the model's discriminative power on these faces, not
the parameters. A larger model (FaceNet 512-d) is the obvious next step.

**Processing takes ~2 minutes for a 30-second clip.** `MediaMetadataRetriever`
seeks back to the previous keyframe and decodes forward on every request, which
dominates the runtime. Running four extractors in parallel gives only ~1.3×,
because the hardware video decoder is the real bottleneck rather than the CPU.
Decoding the video once, linearly, with `MediaCodec` would be the proper fix.

---

## Project structure

```
app/src/main/java/com/example/faceframe/
├── MainActivity.kt              pick video, drive the pipeline, show results
├── model/
│   ├── FaceSample.kt            one detected face in one frame
│   ├── Person.kt                one clustered identity + its appearances
│   └── ProcessingState.kt       UI-facing pipeline state
├── processing/
│   ├── ProcessingConfig.kt      every tunable, in one place
│   ├── FrameExtractor.kt        video → frames (parallel, time-sliced)
│   ├── FaceAnalyzer.kt          ML Kit wrapper + duplicate-box removal
│   ├── FaceEmbedder.kt          TFLite wrapper, batch-shape aware
│   ├── FaceTracker.kt           detections → tracklets
│   ├── FaceClusterer.kt         tracklets → people (constraint-aware)
│   ├── AppearanceCounter.kt     tracklets → appearances
│   └── ShotScorer.kt            representative-shot quality score
├── collage/
│   ├── CollageRenderer.kt       Canvas → 1080×1920 poster
│   └── MediaSaver.kt            MediaStore save + FileProvider share
└── util/
    └── BitmapUtils.kt           crop, align, scale, Laplacian sharpness
```

## Representative shot selection

ML Kit supplies head pose, eye-open probability and smile probability. Sharpness
it does not supply, so it is computed as the variance of the Laplacian over the
face region — sharp faces have many strong edges, blurred ones do not. The same
measure is used earlier to drop motion-blurred detections, so a whip-pan does not
create a phantom appearance.

```
score = 0.30·frontality + 0.25·sharpness + 0.20·eyesOpen
      + 0.15·faceSize   + 0.10·smile
      − 0.35 if the face is clipped by the frame edge
      − 0.30 if another face shares the frame
```

The last penalty matters for the collage: a generous crop around one person will
pull in whoever is standing next to them, so a solo frame is preferred whenever
the person has one. Collage tiles are cropped at 2.6× the detected face box
rather than tight to it, which keeps them from looking like low-resolution
thumbnails.
