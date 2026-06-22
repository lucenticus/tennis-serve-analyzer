package com.tennis.analyzer.data

import com.tennis.analyzer.pose.PoseLandmark
import java.io.File

/**
 * Полная запись одной подачи: видео + позы с временными метками.
 * Позы хранятся в памяти и используются для overlay при воспроизведении.
 */
data class ServeRecording(
    val videoFile: File,
    val poseTimeline: List<PoseSnapshot>,  // отсортированы по timestampMs
    val startMs: Long,
    val durationMs: Long,
    val score: Float
)

data class PoseSnapshot(
    val timestampMs: Long,                 // абсолютное время записи
    val videoOffsetMs: Long,               // смещение от начала видео
    val landmarks: List<PoseLandmark>
)
