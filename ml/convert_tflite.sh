#!/bin/bash
# Конвертация ONNX → TFLite с INT8 квантизацией
# Требует: pip install onnx-tf tensorflow

set -e

echo "[1/3] ONNX → TensorFlow SavedModel"
python -c "
import onnx
from onnx_tf.backend import prepare
model = onnx.load('serve_phase.onnx')
tf_rep = prepare(model)
tf_rep.export_graph('serve_phase_tf')
print('SavedModel сохранён')
"

echo "[2/3] TF SavedModel → TFLite (INT8 quantization)"
python -c "
import tensorflow as tf
import numpy as np

converter = tf.lite.TFLiteConverter.from_saved_model('serve_phase_tf')
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.target_spec.supported_types = [tf.int8]

# Репрезентативный датасет для калибровки квантизации
def representative_dataset():
    for _ in range(100):
        yield [np.random.rand(1, 60, 132).astype(np.float32)]

converter.representative_dataset = representative_dataset
converter.inference_input_type = tf.int8
converter.inference_output_type = tf.int8

tflite_model = converter.convert()
with open('serve_phase.tflite', 'wb') as f:
    f.write(tflite_model)

size_kb = len(tflite_model) / 1024
print(f'TFLite модель: {size_kb:.1f} KB')
"

echo "[3/3] Копируем в assets Android проекта"
cp serve_phase.tflite ../app/src/main/assets/

echo "[✓] Готово: serve_phase.tflite скопирован в assets/"
