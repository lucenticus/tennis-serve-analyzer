package com.tennis.analyzer.analysis

import com.tennis.analyzer.detection.ServePhase
import com.tennis.analyzer.pose.HandedLandmarks
import com.tennis.analyzer.pose.PoseFrame
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.sqrt

data class FrameFeedback(
    val phase: ServePhase,
    val metrics: Map<String, String>,   // "Угол локтя" → "142°"
    val advice: List<String>            // советы для текущего положения
)

object FrameAdvisor {

    fun analyze(frame: PoseFrame, phase: ServePhase, isLeftHanded: Boolean = false): FrameFeedback {
        val hl = HandedLandmarks(isLeftHanded)
        val lm = frame.landmarks
        val metrics = mutableMapOf<String, String>()
        val advice = mutableListOf<String>()

        val shoulder = lm.getOrNull(hl.racketShoulder)
        val elbow    = lm.getOrNull(hl.racketElbow)
        val wrist    = lm.getOrNull(hl.racketWrist)
        val tossWrist = lm.getOrNull(hl.tossWrist)
        val hip      = lm.getOrNull(hl.racketHip)
        val knee     = lm.getOrNull(hl.racketKnee)

        // Угол локтя
        if (shoulder != null && elbow != null && wrist != null &&
            elbow.visibility > 0.4f) {
            val angle = angleBetween(
                shoulder.x, shoulder.y,
                elbow.x, elbow.y,
                wrist.x, wrist.y
            )
            metrics["Угол локтя"] = "${angle.toInt()}°"
            when (phase) {
                ServePhase.ACCELERATION -> when {
                    angle < 140f -> advice += "Разогните локоть сильнее — сейчас ${angle.toInt()}°, нужно 160-175°"
                    angle > 180f -> advice += "Слегка согните локоть при ударе"
                    angle in 160f..175f -> advice += "✓ Отличный угол локтя при ударе"
                }
                ServePhase.TROPHY -> when {
                    angle > 100f -> advice += "Согните локоть в позиции трофея — рука должна быть как буква L"
                    else -> advice += "✓ Хорошее положение локтя в трофее"
                }
                else -> {}
            }
        }

        // Высота запястья относительно плеча
        if (shoulder != null && wrist != null && wrist.visibility > 0.4f) {
            val heightDiff = shoulder.y - wrist.y   // положительное = запястье выше плеча
            val heightPct = (heightDiff * 100).toInt()
            metrics["Высота руки"] = if (heightDiff > 0) "+${heightPct}% выше плеча" else "${heightPct}% ниже плеча"
            when (phase) {
                ServePhase.TROPHY, ServePhase.ACCELERATION ->
                    if (heightDiff < 0.05f) advice += "Поднимите ракетку выше — запястье должно быть над плечом"
                    else if (heightDiff > 0.15f && phase == ServePhase.ACCELERATION)
                        advice += "✓ Хорошая высота контакта"
                else -> {}
            }
        }

        // Наклон туловища
        if (shoulder != null && hip != null && shoulder.visibility > 0.4f) {
            val dx = shoulder.x - hip.x
            val dy = hip.y - shoulder.y
            val tilt = Math.toDegrees(Math.atan2(dx.toDouble(), dy.toDouble())).toFloat()
            metrics["Наклон тела"] = "${abs(tilt.toInt())}°"
            when (phase) {
                ServePhase.TROPHY, ServePhase.ACCELERATION -> when {
                    tilt < 5f -> advice += "Прогнитесь назад — это добавит мощь подаче"
                    tilt in 10f..20f -> advice += "✓ Хороший прогиб туловища"
                    tilt > 30f -> advice += "Слишком сильный прогиб — можно потерять контроль"
                }
                else -> {}
            }
        }

        // Сгиб колена
        if (knee != null && hip != null && knee.visibility > 0.4f) {
            val kneeFlexion = knee.y - hip.y   // у Android y растёт вниз, колено ниже таза
            metrics["Положение колена"] = if (kneeFlexion > 0.1f) "Согнуто" else "Прямое"
            when (phase) {
                ServePhase.READY_STANCE, ServePhase.TOSS ->
                    if (kneeFlexion < 0.05f) advice += "Согните колени — это даст взрывное отталкивание"
                    else advice += "✓ Хорошее сгибание ног"
                else -> {}
            }
        }

        // Фазово-специфичные подсказки
        val phaseHint = when (phase) {
            ServePhase.IDLE        -> "Встаньте боком к сетке, ноги на ширине плеч"
            ServePhase.READY_STANCE -> "Согните колени, перенесите вес на переднюю ногу"
            ServePhase.TOSS        -> "Рука с мячом тянется вертикально вверх без вращения"
            ServePhase.TROPHY      -> "Обе руки вверх, локоть рабочей руки согнут под 90°"
            ServePhase.BACKSCRATCH -> "Ракетка опускается за голову — накопите энергию перед ударом"
            ServePhase.ACCELERATION -> "Резко распрямляйте локоть, тяните запястье к мячу"
            ServePhase.CONTACT -> "Рука полностью выпрямлена, пронируйте кисть в момент удара"
            ServePhase.FOLLOW_THROUGH -> "Ракетка продолжает движение вниз к противоположному бедру"
        }

        if (advice.isEmpty()) advice += phaseHint
        else advice.add(0, phaseHint)

        return FrameFeedback(phase, metrics, advice.take(3))
    }

    private fun angleBetween(
        ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float
    ): Float {
        val v1x = ax - bx; val v1y = ay - by
        val v2x = cx - bx; val v2y = cy - by
        val dot = v1x * v2x + v1y * v2y
        val mag = dist(v1x, v1y) * dist(v2x, v2y)
        if (mag == 0f) return 0f
        return Math.toDegrees(acos((dot / mag).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    private fun dist(x: Float, y: Float) = sqrt(x * x + y * y)
}
