# ML Pipeline — Tennis Serve Analyzer

## Порядок запуска

```bash
# 1. Установить зависимости
pip install -r requirements.txt

# 2. Скачать модель MediaPipe (нужна и для Python, и для Android)
wget -O pose_landmarker_full.task \
  https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task
cp pose_landmarker_full.task ../app/src/main/assets/

# 3. Скачать видео подач (≈ 2-3 часа, ~5 GB)
python 01_download_videos.py --output ./raw_videos --count 15

# 4. Извлечь позы из видео (≈ 1 час с GPU)
python 02_extract_poses.py --videos ./raw_videos --output ./poses

# 5. Разметить фазы вручную (самый трудоёмкий шаг — ~2-3 часа)
#    Запускать для каждого видео отдельно
for f in raw_videos/*.mp4; do
    python 03_label_serves.py --video "$f"
done

# 6. Обучить LSTM (Google Colab рекомендован, ~30 мин на T4 GPU)
python 04_train_lstm.py --poses ./poses --labels ./raw_videos --epochs 50

# 7. Экспорт в TFLite
python 04_train_lstm.py --export
bash convert_tflite.sh
```

## Структура данных

```
poses/
  VIDEO_ID.csv       # кадр × 33_точки × (x,y,z,vis)

raw_videos/
  VIDEO_ID.mp4
  VIDEO_ID_labels.csv   # frame_idx, phase
```

## Целевые метрики

| Метрика          | Цель   |
|------------------|--------|
| Точность фаз     | > 85%  |
| Размер модели    | < 2 MB |
| Inference на S25 | < 10ms |
