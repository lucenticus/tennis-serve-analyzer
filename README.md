# Tennis Serve Analyzer

An on-device Android app that analyzes tennis serve technique using pose estimation
and object detection, running entirely on the phone's NPU. Built and tuned for the
Samsung Galaxy S25 Ultra (Snapdragon 8 Elite / Hexagon NPU).

Everything runs locally — no network, no cloud. Pose and object-detection models
execute on the Qualcomm Hexagon NPU via ONNX Runtime's QNN execution provider
(~4 ms/inference), with a CPU fallback when the NPU is unavailable.

## Features

- **Two modes**, selectable on the start screen:
  - **Offline Analysis** — record a serve (or pick a video from the gallery), then
    get a frame-accurate breakdown.
  - **Real-time coaching** — live phase tracking with a pose skeleton, on-screen
    cues for each phase, and spoken feedback after each serve.
- **Serve phase detection** — segments each serve into Ready Stance → Toss → Trophy →
  Backscratch → Acceleration → Contact → Follow-through, shown on a colored timeline.
- **Multi-serve support** — a single recording can contain several serves; each is
  detected and segmented independently with its own phases and score.
- **Ball & racket detection** with motion-blur handling — trajectory-guided thresholds,
  ROI-zoom (re-running detection on a high-res crop around the predicted position so a
  ~6 px ball becomes detectable), and gap interpolation across blurred frames.
- **Video export** — saves the clip to the gallery with the pose skeleton and
  ball/racket bounding boxes burned in.
- **Voice feedback** — Russian TTS coaching tips and per-serve scores.
- **Handedness** — manual toggle or automatic detection from swing kinematics.

## How it works

```
Camera / video ──► MediaCodec frame decode
                        │
        ┌───────────────┴───────────────┐
        ▼                               ▼
  YOLOv8-pose (NPU)             YOLOv8 object detection (NPU)
   33-slot landmarks              ball + racket bboxes
        │                               │
        └───────────────┬───────────────┘
                        ▼
     Two-pass analysis: coarse pass (100 ms steps, full video)
     + fine pass (15 ms steps, ROI-zoom) around each detected contact
                        ▼
     Smoothing → trajectory gap-filling → phase detection → timeline
```

- **Pose** uses a YOLOv8-pose ONNX model; COCO-17 keypoints are mapped into a
  33-slot layout for compatibility with MediaPipe-style indexing.
- **Phases** are derived from the racket bounding-box height trajectory (more reliable
  than the pose wrist during fast, motion-blurred swings): contact = the racket's
  highest point, with the loop phases located by windowed extrema around it.
- **Multi-serve** is handled by clustering high-velocity wrist peaks into separate
  serves, then running an independent fine pass per serve.

## Tech stack

- **Language / UI:** Kotlin, Jetpack Compose, Material 3
- **Camera & video:** CameraX 1.4.1, Media3/ExoPlayer 1.4.1, MediaCodec + MediaMuxer
- **ML runtime:** ONNX Runtime (QNN) 1.26.0 on the Hexagon NPU, MediaPipe Tasks Vision,
  TensorFlow Lite (GPU)
- **Other:** Room, Kotlin Coroutines, Accompanist Permissions, Android TextToSpeech
- **Min SDK 26, target/compile SDK 35**

## NPU acceleration notes

Getting the Snapdragon 8 Elite (SM8750) HTP to engage required three things, all
necessary together:

1. `onnxruntime-android-qnn` **≥ 1.24** (the project uses 1.26) — earlier versions
   don't recognize the Hexagon V79/V81 architecture.
2. A `<uses-native-library android:name="libcdsprpc.so" android:required="false" />`
   entry in the manifest — on Android 12+ apps can't `dlopen` the vendor FastRPC
   library otherwise, and HTP device creation fails with `INVALID_CONFIG`.
3. `extractNativeLibs` / `useLegacyPackaging` so the QNN skel libraries land on disk.

`OrtManager` warms up each session and falls back QNN → XNNPACK → CPU so the app keeps
working on devices without a supported NPU.

## Build & run

```bash
# Requires Android SDK; set sdk.dir in local.properties (gitignored)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Grant camera and microphone permissions on first launch. The ONNX / MediaPipe model
files ship in `app/src/main/assets/models/`.

## Project structure

```
app/src/main/java/com/tennis/analyzer/
├── ui/            MainActivity (modes, camera, phase logic), AnalysisActivity,
│                  PlaybackOverlay, PhaseTimelineView, SkeletonOverlay
├── analysis/      VideoPoseAnalyzer, ServeAnalyzer, PhaseAnalyzer,
│                  ObjectGapFiller, VideoExporter, VideoFrameExtractor
├── detection/     OrtManager, TennisObjectDetector, ServePhaseDetector
├── pose/          YoloPoseDetector, PoseLandmark, LandmarkSmoother
├── camera/        CameraManager, ServeRecorder
└── feedback/      VoiceFeedback (Russian TTS)
ml/                Python scripts for dataset prep / LSTM experiments
```

## Status

Working prototype, actively tuned on real serves. Phase-detection accuracy depends on
clear racket visibility through the swing; the offline analysis mode is the most
accurate, while real-time mode trades some precision for live feedback.
