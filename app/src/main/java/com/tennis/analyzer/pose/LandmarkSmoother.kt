package com.tennis.analyzer.pose

import kotlin.math.sqrt

/**
 * Адаптивный EMA для живой камеры.
 *
 * При медленном движении alpha → ALPHA_MIN (сильное сглаживание).
 * При быстром движении alpha → ALPHA_MAX (почти без сглаживания) — пик удара сохраняется.
 */
class LandmarkSmoother {

    private var prev: List<PoseLandmark>? = null

    fun smooth(landmarks: List<PoseLandmark>): List<PoseLandmark> {
        val p = prev
        if (p == null || p.size != landmarks.size) {
            prev = landmarks
            return landmarks
        }
        val smoothed = landmarks.mapIndexed { i, lm ->
            val s = p[i]
            val dx = lm.x - s.x
            val dy = lm.y - s.y
            val speed = sqrt(dx * dx + dy * dy)
            // Чем быстрее движение — тем больше alpha (меньше сглаживания)
            val a = (ALPHA_MIN + speed * SPEED_SCALE).coerceAtMost(ALPHA_MAX)
            PoseLandmark(
                x          = a * lm.x + (1f - a) * s.x,
                y          = a * lm.y + (1f - a) * s.y,
                z          = a * lm.z + (1f - a) * s.z,
                visibility = lm.visibility
            )
        }
        prev = smoothed
        return smoothed
    }

    fun reset() { prev = null }

    companion object {
        private const val ALPHA_MIN   = 0.25f  // спокойное стояние
        private const val ALPHA_MAX   = 0.95f  // быстрый удар
        private const val SPEED_SCALE = 8f     // 0.12 нормализованных единиц/кадр → alpha ~1.0

        /**
         * Гауссово сглаживание для видеокадров.
         *
         * Центральный кадр имеет максимальный вес (1.0), соседние убывают по гауссу —
         * пики быстрых движений не срезаются в отличие от равномерного SMA.
         * sigma = 1.0 при windowRadius = 2.
         */
        fun smoothFrames(frames: List<PoseFrame>, windowRadius: Int = 2): List<PoseFrame> {
            if (frames.size < 3 || windowRadius == 0) return frames

            // Гауссовы веса: w[d] = exp(-d² / (2 * sigma²)), sigma = windowRadius / 2
            val sigma2 = (windowRadius / 2f).let { it * it * 2f }.coerceAtLeast(0.5f)
            val weights = (-windowRadius..windowRadius).map { d ->
                kotlin.math.exp(-(d * d) / sigma2).toFloat()
            }

            val n = frames.size
            return frames.mapIndexed { i, frame ->
                val smoothedLandmarks = List(frame.landmarks.size) { li ->
                    var sx = 0f; var sy = 0f; var sz = 0f; var totalW = 0f
                    for (d in -windowRadius..windowRadius) {
                        val j = i + d
                        if (j < 0 || j >= n) continue
                        val lm = frames[j].landmarks.getOrNull(li) ?: continue
                        val w = weights[d + windowRadius]
                        sx += lm.x * w; sy += lm.y * w; sz += lm.z * w
                        totalW += w
                    }
                    if (totalW == 0f) frame.landmarks[li]
                    else PoseLandmark(sx / totalW, sy / totalW, sz / totalW,
                        frame.landmarks[li].visibility)
                }
                PoseFrame(smoothedLandmarks, frame.timestampMs, frame.objects)
            }
        }
    }
}
