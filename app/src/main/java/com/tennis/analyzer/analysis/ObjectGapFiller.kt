package com.tennis.analyzer.analysis

import com.tennis.analyzer.pose.DetectedObject
import com.tennis.analyzer.pose.PoseFrame

/**
 * Достраивает пропуски в траектории мяча и ракетки.
 *
 * В момент ускорения замаха и в верхней точке подброса объект смазан и YOLO его теряет
 * на несколько кадров. Если объект был уверенно найден ДО и ПОСЛЕ короткого пропуска,
 * интерполируем bbox по этим двум опорным точкам — бокс перестаёт мигать/исчезать.
 *
 * Работает по уже плотным кадрам (файн-пасс ~15мс вокруг контакта), поэтому пропуски
 * короткие и линейная интерполяция визуально точна.
 */
object ObjectGapFiller {

    /** Максимальная длительность пропуска, который достраиваем (мс). Дольше — не выдумываем. */
    private const val MAX_GAP_MS = 250L

    fun fill(frames: List<PoseFrame>): List<PoseFrame> {
        if (frames.size < 3) return frames
        // Мутируемые списки объектов по кадрам
        val perFrame = frames.map { it.objects.toMutableList() }
        fillClass(frames, perFrame, DetectedObject.CLASS_BALL)
        fillClass(frames, perFrame, DetectedObject.CLASS_RACKET)
        return frames.mapIndexed { i, f -> f.copy(objects = perFrame[i]) }
    }

    private fun fillClass(
        frames: List<PoseFrame>,
        perFrame: List<MutableList<DetectedObject>>,
        classId: Int
    ) {
        // Опорные кадры, где класс реально найден (берём самый уверенный бокс)
        val anchorIdx = ArrayList<Int>()
        val anchorObj = ArrayList<DetectedObject>()
        for (i in frames.indices) {
            val best = frames[i].objects.filter { it.classId == classId }.maxByOrNull { it.confidence }
            if (best != null) { anchorIdx.add(i); anchorObj.add(best) }
        }
        if (anchorIdx.size < 2) return

        for (a in 0 until anchorIdx.size - 1) {
            val iA = anchorIdx[a]; val iB = anchorIdx[a + 1]
            if (iB <= iA + 1) continue                      // нет пропуска между опорами
            val tA = frames[iA].timestampMs
            val tB = frames[iB].timestampMs
            if (tB - tA > MAX_GAP_MS) continue              // слишком длинный разрыв — пропускаем
            val oA = anchorObj[a]; val oB = anchorObj[a + 1]
            val span = (tB - tA).toFloat().coerceAtLeast(1f)

            for (k in iA + 1 until iB) {
                // На случай если в кадре уже есть этот класс — не дублируем
                if (perFrame[k].any { it.classId == classId }) continue
                val f = (frames[k].timestampMs - tA) / span
                perFrame[k].add(
                    DetectedObject(
                        classId    = classId,
                        confidence = lerp(oA.confidence, oB.confidence, f),
                        cx = lerp(oA.cx, oB.cx, f),
                        cy = lerp(oA.cy, oB.cy, f),
                        w  = lerp(oA.w,  oB.w,  f),
                        h  = lerp(oA.h,  oB.h,  f),
                        interpolated = true
                    )
                )
            }
        }
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
}
