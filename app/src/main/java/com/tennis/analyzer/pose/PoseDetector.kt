package com.tennis.analyzer.pose

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock

/**
 * Обёртка над YoloPoseDetector с той же сигнатурой, что была у MediaPipe PoseDetector.
 * Синхронный вызов detect() выполняется на том потоке, что и detectAsync() — вызывающий
 * обязан не блокировать UI поток (использовать background executor, как раньше).
 */
class PoseDetector(
    private val context: Context,
    private val onResult: (PoseFrame) -> Unit,
    private val onError: (String) -> Unit
) {
    private val yolo    = YoloPoseDetector(context)
    private val smoother = LandmarkSmoother()

    fun setup() {
        if (!yolo.setup()) onError("YoloPoseDetector init failed")
    }

    fun detectAsync(bitmap: Bitmap, frameTimeMs: Long) {
        val lms = yolo.detect(bitmap)
        if (lms.isEmpty()) {
            smoother.reset()
            return
        }
        onResult(PoseFrame(smoother.smooth(lms), SystemClock.uptimeMillis()))
    }

    fun close() {
        yolo.close()
    }
}
