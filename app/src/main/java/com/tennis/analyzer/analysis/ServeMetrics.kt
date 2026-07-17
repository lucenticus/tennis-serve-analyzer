package com.tennis.analyzer.analysis

import com.tennis.analyzer.R
import com.tennis.analyzer.pose.HandedLandmarks
import com.tennis.analyzer.pose.LandmarkIndex
import com.tennis.analyzer.pose.PoseFrame
import kotlin.math.acos
import kotlin.math.sqrt

data class ServeMetrics(
    val elbowAngleAtContact: Float,     // градусы, норма: 160-175
    val trunkTiltAngle: Float,          // градусы назад, норма: 10-20
    val shoulderRotation: Float,        // градусы, норма: 80-100
    val contactPointHeight: Float,      // нормализованная Y (меньше = выше)
    val legDriveScore: Float,           // 0..1, насколько ноги участвуют
    val overallScore: Float             // 0..100
)

data class ServeAdvice(
    val priority: Int,                  // 1 = самый важный
    val textRu: String
)

object ServeAnalyzer {

    fun analyze(context: android.content.Context, frames: List<PoseFrame>, isLeftHanded: Boolean = false): Pair<ServeMetrics, List<ServeAdvice>> {
        val hl = HandedLandmarks(isLeftHanded)
        if (frames.isEmpty()) return ServeMetrics(0f,0f,0f,0f,0f,0f) to emptyList()
        val contactFrame = findContactFrame(frames, hl) ?: frames[frames.size / 2]
        val trophyFrame  = findTrophyFrame(frames, hl)  ?: frames[frames.size / 3]

        val elbowAngle = calcElbowAngle(contactFrame, hl)
        val trunkTilt = calcTrunkTilt(trophyFrame, hl)
        val shoulderRot = calcShoulderRotation(trophyFrame)
        val contactHeight = contactFrame.landmarks.getOrNull(hl.racketWrist)?.y ?: 0.3f
        val legDrive = calcLegDrive(frames, hl)

        val overall = calcOverallScore(elbowAngle, trunkTilt, shoulderRot, legDrive)

        val metrics = ServeMetrics(
            elbowAngleAtContact = elbowAngle,
            trunkTiltAngle = trunkTilt,
            shoulderRotation = shoulderRot,
            contactPointHeight = contactHeight,
            legDriveScore = legDrive,
            overallScore = overall
        )

        return metrics to generateAdvice(context, metrics, isLeftHanded)
    }

    // Кадр с максимальной скоростью запястья = момент контакта
    private fun findContactFrame(frames: List<PoseFrame>, hl: HandedLandmarks): PoseFrame? {
        var maxVel = 0f
        var result: PoseFrame? = null
        for (i in 1 until frames.size) {
            val w0 = frames[i - 1].landmarks.getOrNull(hl.racketWrist) ?: continue
            val w1 = frames[i].landmarks.getOrNull(hl.racketWrist) ?: continue
            val vel = dist(w0.x, w0.y, w1.x, w1.y)
            if (vel > maxVel) {
                maxVel = vel
                result = frames[i]
            }
        }
        return result
    }

    // Trophy position: минимальное расстояние между запястьями по горизонтали при поднятых руках
    private fun findTrophyFrame(frames: List<PoseFrame>, hl: HandedLandmarks): PoseFrame? {
        return frames.filter { frame ->
            val lm = frame.landmarks
            val rW = lm.getOrNull(hl.racketWrist) ?: return@filter false
            val rS = lm.getOrNull(hl.racketShoulder) ?: return@filter false
            rW.y < rS.y - 0.1f
        }.maxByOrNull { frame ->
            val rW = frame.landmarks.getOrNull(hl.racketWrist)
            val tW = frame.landmarks.getOrNull(hl.tossWrist)
            if (rW != null && tW != null) Math.abs(rW.x - tW.x) else 0f
        }
    }

    private fun calcElbowAngle(frame: PoseFrame, hl: HandedLandmarks): Float {
        val lm = frame.landmarks
        val shoulder = lm.getOrNull(hl.racketShoulder) ?: return 0f
        val elbow = lm.getOrNull(hl.racketElbow) ?: return 0f
        val wrist = lm.getOrNull(hl.racketWrist) ?: return 0f
        return angleBetween(
            shoulder.x, shoulder.y,
            elbow.x, elbow.y,
            wrist.x, wrist.y
        )
    }

    private fun calcTrunkTilt(frame: PoseFrame, hl: HandedLandmarks): Float {
        val lm = frame.landmarks
        val shoulder = lm.getOrNull(hl.racketShoulder) ?: return 0f
        val hip = lm.getOrNull(hl.racketHip) ?: return 0f
        val dx = shoulder.x - hip.x
        val dy = hip.y - shoulder.y  // y растёт вниз
        return Math.toDegrees(Math.atan2(dx.toDouble(), dy.toDouble())).toFloat()
    }

    private fun calcShoulderRotation(frame: PoseFrame): Float {
        val lm = frame.landmarks
        val lS = lm.getOrNull(LandmarkIndex.LEFT_SHOULDER) ?: return 0f
        val rS = lm.getOrNull(LandmarkIndex.RIGHT_SHOULDER) ?: return 0f
        // Разница Z координат (глубина) как прокси для поворота
        val dz = Math.abs(lS.z - rS.z)
        return (dz * 300f).coerceIn(0f, 120f)
    }

    private fun calcLegDrive(frames: List<PoseFrame>, hl: HandedLandmarks): Float {
        if (frames.size < 10) return 0f
        val early = frames.take(5)
        val late = frames.takeLast(5)

        fun avgKneeY(list: List<PoseFrame>): Float {
            return list.mapNotNull { it.landmarks.getOrNull(hl.racketKnee)?.y }.average().toFloat()
        }

        // Колено сгибается (y уменьшается = выше на экране) в начале подачи
        val kneeDropEarly = avgKneeY(early)
        val kneeLate = avgKneeY(late)
        val drop = (kneeLate - kneeDropEarly).coerceIn(0f, 0.15f)
        return drop / 0.15f
    }

    private fun calcOverallScore(
        elbow: Float, trunk: Float, shoulder: Float, legs: Float
    ): Float {
        val elbowScore = when {
            elbow in 160f..175f -> 1f
            elbow in 150f..160f || elbow in 175f..185f -> 0.7f
            else -> 0.4f
        }
        val trunkScore = when {
            trunk in 10f..20f -> 1f
            trunk in 5f..25f -> 0.7f
            else -> 0.4f
        }
        val shoulderScore = when {
            shoulder in 80f..100f -> 1f
            shoulder in 60f..110f -> 0.7f
            else -> 0.4f
        }
        return ((elbowScore * 0.3f + trunkScore * 0.2f + shoulderScore * 0.3f + legs * 0.2f) * 100f)
    }

    fun generateAdvice(context: android.content.Context, metrics: ServeMetrics, isLeftHanded: Boolean = false): List<ServeAdvice> {
        fun s(id: Int, vararg args: Any) = context.getString(id, *args)
        val tossShoulderName = s(if (isLeftHanded) R.string.shoulder_right else R.string.shoulder_left)
        val advice = mutableListOf<ServeAdvice>()

        if (metrics.elbowAngleAtContact < 150f) {
            advice += ServeAdvice(1, s(R.string.adv_straighten_arm))
        } else if (metrics.elbowAngleAtContact > 180f) {
            advice += ServeAdvice(1, s(R.string.adv_bend_elbow))
        }

        if (metrics.trunkTiltAngle < 8f) {
            advice += ServeAdvice(2, s(R.string.adv_arch_back))
        }

        if (metrics.shoulderRotation < 60f) {
            advice += ServeAdvice(2, s(R.string.adv_turn_shoulders, tossShoulderName))
        }

        if (metrics.legDriveScore < 0.4f) {
            advice += ServeAdvice(3, s(R.string.adv_use_legs))
        }

        if (metrics.contactPointHeight > 0.35f) {
            advice += ServeAdvice(3, s(R.string.adv_reach_higher))
        }

        return advice.sortedBy { it.priority }.take(2)
    }

    private fun angleBetween(
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float
    ): Float {
        val v1x = ax - bx; val v1y = ay - by
        val v2x = cx - bx; val v2y = cy - by
        val dot = v1x * v2x + v1y * v2y
        val mag = dist(0f, 0f, v1x, v1y) * dist(0f, 0f, v2x, v2y)
        if (mag == 0f) return 0f
        return Math.toDegrees(acos((dot / mag).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }
}
