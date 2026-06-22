"""
Прогоняет все скачанные видео через MediaPipe Pose Landmarker.
Результат: CSV файлы с координатами 33 точек для каждого кадра.

Запуск: python 02_extract_poses.py --videos ./raw_videos --output ./poses
"""

import argparse
import csv
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
from tqdm import tqdm

# Названия 33 точек MediaPipe
LANDMARK_NAMES = [
    "nose", "left_eye_inner", "left_eye", "left_eye_outer",
    "right_eye_inner", "right_eye", "right_eye_outer",
    "left_ear", "right_ear", "mouth_left", "mouth_right",
    "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
    "left_wrist", "right_wrist", "left_pinky", "right_pinky",
    "left_index", "right_index", "left_thumb", "right_thumb",
    "left_hip", "right_hip", "left_knee", "right_knee",
    "left_ankle", "right_ankle", "left_heel", "right_heel",
    "left_foot_index", "right_foot_index",
]

# CSV заголовок
CSV_HEADER = (
    ["video_id", "frame_idx", "timestamp_ms"]
    + [f"{name}_{coord}" for name in LANDMARK_NAMES for coord in ("x", "y", "z", "vis")]
)


def process_video(video_path: Path, output_path: Path, detector) -> int:
    """Возвращает количество обработанных кадров."""
    cap = cv2.VideoCapture(str(video_path))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    video_id = video_path.stem
    frames_written = 0

    with open(output_path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(CSV_HEADER)

        frame_idx = 0
        while cap.isOpened():
            ret, frame = cap.read()
            if not ret:
                break

            timestamp_ms = int(frame_idx / fps * 1000)
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)

            mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
            result = detector.detect(mp_image)

            if result.pose_landmarks:
                lms = result.pose_landmarks[0]
                row = [video_id, frame_idx, timestamp_ms]
                for lm in lms:
                    row.extend([lm.x, lm.y, lm.z, lm.visibility])
                writer.writerow(row)
                frames_written += 1

            frame_idx += 1

    cap.release()
    return frames_written


def main(videos_dir: Path, output_dir: Path):
    output_dir.mkdir(parents=True, exist_ok=True)

    # Загружаем модель один раз
    base_options = mp.tasks.BaseOptions(
        model_asset_path="pose_landmarker_full.task"
    )
    options = mp.tasks.vision.PoseLandmarkerOptions(
        base_options=base_options,
        running_mode=mp.tasks.vision.RunningMode.IMAGE,
        num_poses=1,
        min_pose_detection_confidence=0.5,
        min_pose_presence_confidence=0.5,
        min_tracking_confidence=0.5,
    )

    video_files = list(videos_dir.glob("*.mp4"))
    print(f"[→] Обрабатываем {len(video_files)} видео...")

    with mp.tasks.vision.PoseLandmarker.create_from_options(options) as detector:
        total_frames = 0
        for video_path in tqdm(video_files):
            out_csv = output_dir / f"{video_path.stem}.csv"
            if out_csv.exists():
                continue  # уже обработано

            n = process_video(video_path, out_csv, detector)
            total_frames += n

    print(f"\n[✓] Готово. Извлечено кадров с позой: {total_frames}")
    print(f"[✓] CSV файлы в: {output_dir}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--videos", default="./raw_videos", type=Path)
    parser.add_argument("--output", default="./poses", type=Path)
    args = parser.parse_args()
    main(args.videos, args.output)
