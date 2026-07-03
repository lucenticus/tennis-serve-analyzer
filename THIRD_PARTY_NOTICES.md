# Third-Party Notices

This project (Tennis Serve Analyzer) is licensed under the **GNU Affero General
Public License v3.0** (see [LICENSE](LICENSE)). It bundles and depends on the
following third-party components, each under its own license.

## Models

- **Ultralytics YOLOv8** — `app/src/main/assets/models/yolov8n.onnx`,
  `yolov8n-pose.onnx`
  License: **AGPL-3.0** — https://github.com/ultralytics/ultralytics
  (This is the reason the whole project is distributed under AGPL-3.0. For a
  closed-source / commercial redistribution, an Ultralytics Enterprise License
  would be required instead.)
- **Google MediaPipe Pose Landmarker** — `pose_landmarker_full.task`
  License: Apache-2.0 — https://github.com/google-ai-edge/mediapipe

## Libraries

- **ONNX Runtime (onnxruntime-android-qnn)** — MIT — https://github.com/microsoft/onnxruntime
- **Qualcomm QNN / Hexagon runtime** (redistributed inside the ONNX Runtime QNN
  package) — Qualcomm proprietary redistributable
- **MediaPipe Tasks Vision** — Apache-2.0
- **TensorFlow Lite** — Apache-2.0
- **AndroidX / Jetpack Compose / CameraX / Media3 (ExoPlayer) / Room** — Apache-2.0
- **Google Play Services (Wearable)** — Google APIs Terms of Service
- **Kotlin / kotlinx.coroutines** — Apache-2.0

Full license texts for Apache-2.0 and MIT components are available in the
respective upstream repositories.
