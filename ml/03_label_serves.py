"""
Интерактивная разметка фаз подачи по видео.
Показывает видео + текущие координаты. Горячие клавиши для разметки.

Запуск: python 03_label_serves.py --video ./raw_videos/VIDEO_ID.mp4

Горячие клавиши:
  0 — IDLE / не подача
  1 — READY_STANCE (стойка)
  2 — TOSS (подброс)
  3 — TROPHY (позиция трофея)
  4 — ACCELERATION (удар)
  5 — FOLLOW_THROUGH (завершение)
  s — пропустить кадр (предыдущая метка)
  q — сохранить и выйти

Результат: {video_id}_labels.csv с колонками [frame_idx, phase]
"""

import argparse
import csv
from pathlib import Path

import cv2

PHASES = {
    ord("0"): "IDLE",
    ord("1"): "READY_STANCE",
    ord("2"): "TOSS",
    ord("3"): "TROPHY",
    ord("4"): "ACCELERATION",
    ord("5"): "FOLLOW_THROUGH",
}

PHASE_COLORS = {
    "IDLE": (128, 128, 128),
    "READY_STANCE": (0, 255, 0),
    "TOSS": (0, 255, 255),
    "TROPHY": (255, 255, 0),
    "ACCELERATION": (0, 0, 255),
    "FOLLOW_THROUGH": (255, 0, 255),
}


def label_video(video_path: Path):
    cap = cv2.VideoCapture(str(video_path))
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0

    labels = {}
    current_phase = "IDLE"
    frame_idx = 0

    print(f"[→] Разметка {video_path.name} ({total_frames} кадров, {fps:.1f} fps)")
    print("     Клавиши: 0=IDLE 1=STANCE 2=TOSS 3=TROPHY 4=ACCEL 5=FOLLOW  s=пропуск  q=сохранить")

    while cap.isOpened():
        ret, frame = cap.read()
        if not ret:
            break

        # Наложить информацию
        color = PHASE_COLORS.get(current_phase, (255, 255, 255))
        cv2.putText(frame, f"[{frame_idx}/{total_frames}] {current_phase}",
                    (20, 50), cv2.FONT_HERSHEY_SIMPLEX, 1.2, color, 2)
        cv2.putText(frame, "0-5: фаза  s: пропуск  q: сохранить",
                    (20, 90), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (200, 200, 200), 1)

        cv2.imshow("Разметка подачи", frame)
        key = cv2.waitKey(0) & 0xFF

        if key == ord("q"):
            break
        elif key == ord("s"):
            labels[frame_idx] = current_phase  # наследуем предыдущую фазу
        elif key in PHASES:
            current_phase = PHASES[key]
            labels[frame_idx] = current_phase
        else:
            labels[frame_idx] = current_phase

        frame_idx += 1

    cap.release()
    cv2.destroyAllWindows()

    # Сохранить разметку
    out_path = video_path.parent / f"{video_path.stem}_labels.csv"
    with open(out_path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["frame_idx", "phase"])
        for idx in sorted(labels):
            writer.writerow([idx, labels[idx]])

    labeled = sum(1 for v in labels.values() if v != "IDLE")
    print(f"\n[✓] Сохранено {len(labels)} кадров ({labeled} с подачей) → {out_path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", required=True, type=Path)
    args = parser.parse_args()
    label_video(args.video)
