"""
Обучает LSTM для классификации фаз подачи по последовательностям поз.
Запускать в Google Colab (бесплатный GPU T4) или локально.

Вход:  poses/*.csv  +  raw_videos/*_labels.csv
Выход: serve_phase_model.tflite

Запуск: python 04_train_lstm.py --poses ./poses --labels ./raw_videos --epochs 50
"""

import argparse
from pathlib import Path

import numpy as np
import pandas as pd
import torch
import torch.nn as nn
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import LabelEncoder
from torch.utils.data import DataLoader, Dataset

SEQUENCE_LEN = 60          # кадров на последовательность (~1 сек при 60fps)
STRIDE = 10                # шаг скользящего окна
INPUT_SIZE = 33 * 4        # 33 точки × (x, y, z, visibility)
HIDDEN_SIZE = 128
NUM_LAYERS = 2
NUM_CLASSES = 6            # 6 фаз подачи

PHASE_TO_IDX = {
    "IDLE": 0, "READY_STANCE": 1, "TOSS": 2,
    "TROPHY": 3, "ACCELERATION": 4, "FOLLOW_THROUGH": 5,
}


class ServeDataset(Dataset):
    def __init__(self, sequences, labels):
        self.X = torch.tensor(sequences, dtype=torch.float32)
        self.y = torch.tensor(labels, dtype=torch.long)

    def __len__(self): return len(self.X)
    def __getitem__(self, i): return self.X[i], self.y[i]


class ServeLSTM(nn.Module):
    def __init__(self):
        super().__init__()
        self.lstm = nn.LSTM(
            INPUT_SIZE, HIDDEN_SIZE, NUM_LAYERS,
            batch_first=True, dropout=0.3, bidirectional=True
        )
        self.classifier = nn.Sequential(
            nn.Linear(HIDDEN_SIZE * 2, 64),
            nn.ReLU(),
            nn.Dropout(0.3),
            nn.Linear(64, NUM_CLASSES)
        )

    def forward(self, x):
        out, _ = self.lstm(x)
        return self.classifier(out[:, -1, :])  # последний скрытый стейт


def load_dataset(poses_dir: Path, labels_dir: Path):
    sequences, labels = [], []

    pose_files = list(poses_dir.glob("*.csv"))
    print(f"[→] Загружаем {len(pose_files)} видео...")

    for pose_file in pose_files:
        label_file = labels_dir / f"{pose_file.stem}_labels.csv"
        if not label_file.exists():
            continue

        poses_df = pd.read_csv(pose_file)
        labels_df = pd.read_csv(label_file)

        merged = poses_df.merge(labels_df, on="frame_idx", how="inner")
        if len(merged) < SEQUENCE_LEN:
            continue

        # Берём только координаты (убираем video_id, frame_idx, timestamp_ms)
        feature_cols = [c for c in merged.columns if any(
            c.endswith(s) for s in ("_x", "_y", "_z", "_vis")
        )]
        X = merged[feature_cols].values.astype(np.float32)
        y_raw = merged["phase"].map(PHASE_TO_IDX).fillna(0).values.astype(int)

        # Скользящее окно
        for start in range(0, len(X) - SEQUENCE_LEN, STRIDE):
            seq = X[start:start + SEQUENCE_LEN]
            label = int(np.bincount(y_raw[start:start + SEQUENCE_LEN]).argmax())
            sequences.append(seq)
            labels.append(label)

    print(f"[✓] Последовательностей: {len(sequences)}")
    return np.array(sequences), np.array(labels)


def train(poses_dir: Path, labels_dir: Path, epochs: int):
    X, y = load_dataset(poses_dir, labels_dir)

    X_train, X_val, y_train, y_val = train_test_split(
        X, y, test_size=0.2, stratify=y, random_state=42
    )

    train_dl = DataLoader(ServeDataset(X_train, y_train), batch_size=32, shuffle=True)
    val_dl = DataLoader(ServeDataset(X_val, y_val), batch_size=64)

    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[→] Устройство: {device}")

    model = ServeLSTM().to(device)
    optimizer = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, epochs)
    criterion = nn.CrossEntropyLoss()

    best_val_acc = 0.0
    for epoch in range(epochs):
        model.train()
        train_loss = 0.0
        for xb, yb in train_dl:
            xb, yb = xb.to(device), yb.to(device)
            optimizer.zero_grad()
            loss = criterion(model(xb), yb)
            loss.backward()
            optimizer.step()
            train_loss += loss.item()

        model.eval()
        correct = total = 0
        with torch.no_grad():
            for xb, yb in val_dl:
                xb, yb = xb.to(device), yb.to(device)
                preds = model(xb).argmax(dim=1)
                correct += (preds == yb).sum().item()
                total += len(yb)

        val_acc = correct / total
        scheduler.step()
        print(f"Epoch {epoch+1:03d}/{epochs} | loss={train_loss/len(train_dl):.4f} | val_acc={val_acc:.3f}")

        if val_acc > best_val_acc:
            best_val_acc = val_acc
            torch.save(model.state_dict(), "serve_phase_best.pt")

    print(f"\n[✓] Лучшая точность на валидации: {best_val_acc:.3f}")
    return model


def export_tflite(model_path: str = "serve_phase_best.pt"):
    """Конвертирует PyTorch → ONNX → TFLite (запускать отдельно)."""
    import subprocess
    model = ServeLSTM()
    model.load_state_dict(torch.load(model_path, map_location="cpu"))
    model.eval()

    dummy = torch.zeros(1, SEQUENCE_LEN, INPUT_SIZE)
    torch.onnx.export(
        model, dummy, "serve_phase.onnx",
        input_names=["input"], output_names=["output"],
        dynamic_axes={"input": {0: "batch"}},
        opset_version=17
    )
    print("[✓] serve_phase.onnx сохранён")
    print("Следующий шаг: запусти convert_tflite.sh")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--poses", default="./poses", type=Path)
    parser.add_argument("--labels", default="./raw_videos", type=Path)
    parser.add_argument("--epochs", default=50, type=int)
    parser.add_argument("--export", action="store_true")
    args = parser.parse_args()

    if args.export:
        export_tflite()
    else:
        train(args.poses, args.labels, args.epochs)
