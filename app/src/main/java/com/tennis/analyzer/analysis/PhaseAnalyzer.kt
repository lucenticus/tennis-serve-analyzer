package com.tennis.analyzer.analysis

import android.content.Context
import com.tennis.analyzer.R
import com.tennis.analyzer.data.PhaseMarker
import com.tennis.analyzer.detection.ServePhase
import com.tennis.analyzer.pose.HandedLandmarks
import com.tennis.analyzer.pose.LandmarkIndex
import com.tennis.analyzer.pose.PoseFrame
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Одно замечание по технике. [key] — стабильный идентификатор самого замечания
 * (не зависит от конкретного угла/процента), нужен чтобы агрегировать одинаковые
 * замечания с разных подач одного видео (иначе "прямой локоть 166°" и "прямой
 * локоть 159°" считаются разными строками и не схлопываются в одну).
 */
data class Issue(val key: String, val text: String)

data class PhaseReport(
    val phase: ServePhase,
    val goods: List<String>,
    val issues: List<Issue>
)

data class ServeReport(
    val phases: List<PhaseReport>,
    val overallScore: Float
)

object PhaseAnalyzer {

    fun analyze(
        context: Context,
        frames: List<PoseFrame>,
        phaseMarkers: List<PhaseMarker>,
        isLeftHanded: Boolean
    ): ServeReport {
        if (frames.isEmpty() || phaseMarkers.isEmpty()) return ServeReport(emptyList(), 0f)
        val hl = HandedLandmarks(isLeftHanded)
        val c = context

        val reports = mutableListOf<PhaseReport>()

        // Для каждой фазы — находим её кадры и анализируем
        for (i in phaseMarkers.indices) {
            val phase = phaseMarkers[i].phase
            if (phase == ServePhase.IDLE) continue

            val startMs = phaseMarkers[i].timeMs
            val endMs   = phaseMarkers.getOrNull(i + 1)?.timeMs ?: Long.MAX_VALUE
            val phaseFrames = frames.filter { it.timestampMs in startMs until endMs }
            if (phaseFrames.isEmpty()) continue

            val report = when (phase) {
                ServePhase.READY_STANCE   -> analyzeReadyStance(c, phaseFrames, hl)
                ServePhase.TOSS           -> analyzeToss(c, phaseFrames, hl)
                ServePhase.TROPHY         -> analyzeTrophy(c, phaseFrames, hl)
                ServePhase.BACKSCRATCH    -> analyzeBackscratch(c, phaseFrames, hl)
                ServePhase.ACCELERATION   -> analyzeAcceleration(c, phaseFrames, hl)
                ServePhase.CONTACT        -> analyzeContact(c, phaseFrames, hl)
                ServePhase.FOLLOW_THROUGH -> analyzeFollowThrough(c, phaseFrames, hl)
                else -> null
            }
            if (report != null) reports.add(report)
        }

        // Итоговый балл: % фаз где плюсов больше чем минусов
        val goodPhases = reports.count { it.goods.size >= it.issues.size }
        val score = if (reports.isEmpty()) 0f
                    else (goodPhases.toFloat() / reports.size * 100f)

        return ServeReport(reports, score)
    }

    // ── Стойка ────────────────────────────────────────────────────────────────

    private fun analyzeReadyStance(context: Context, frames: List<PoseFrame>, hl: HandedLandmarks): PhaseReport {
        val frame = bestFrame(frames, hl)
        val lm = frame.landmarks
        val goods = mutableListOf<String>()
        val issues = mutableListOf<Issue>()

        val knee = lm.getOrNull(hl.racketKnee)
        val hip  = lm.getOrNull(hl.racketHip)
        val shoulder = lm.getOrNull(hl.racketShoulder)
        val tossKnee = lm.getOrNull(hl.tossKnee)

        // Сгиб колен
        if (knee != null && hip != null) {
            val flex = knee.y - hip.y
            if (flex > 0.08f) goods += context.getString(R.string.rs_knees_good)
            else issues += Issue("rs_knees_bad", context.getString(R.string.rs_knees_bad))
        }

        // Плечи боком к сетке (разница Z между плечами)
        val lSh = lm.getOrNull(LandmarkIndex.LEFT_SHOULDER)
        val rSh = lm.getOrNull(LandmarkIndex.RIGHT_SHOULDER)
        if (lSh != null && rSh != null) {
            val sideTurn = abs(lSh.z - rSh.z)
            if (sideTurn > 0.08f) goods += context.getString(R.string.rs_side_good)
            else issues += Issue("rs_side_bad", context.getString(R.string.rs_side_bad))
        }

        return PhaseReport(ServePhase.READY_STANCE, goods, issues)
    }

    // ── Подброс ───────────────────────────────────────────────────────────────

    private fun analyzeToss(context: Context, frames: List<PoseFrame>, hl: HandedLandmarks): PhaseReport {
        // Лучший кадр — когда рука с мячом на максимальной высоте
        val frame = frames.minByOrNull { f ->
            f.landmarks.getOrNull(hl.tossWrist)?.y ?: 1f
        } ?: frames.first()
        val lm = frame.landmarks
        val goods = mutableListOf<String>()
        val issues = mutableListOf<Issue>()

        val tossWrist    = lm.getOrNull(hl.tossWrist)
        val tossShoulder = lm.getOrNull(hl.tossShoulder)
        val tossElbow    = lm.getOrNull(hl.tossElbow)

        // Высота подброса
        if (tossWrist != null && tossShoulder != null) {
            val aboveShoulder = tossShoulder.y - tossWrist.y
            when {
                aboveShoulder > 0.20f -> goods += context.getString(R.string.toss_high_good)
                aboveShoulder > 0.05f -> goods += context.getString(R.string.toss_ok)
                else -> issues += Issue("toss_low", context.getString(R.string.toss_low))
            }
        }

        // Прямота руки при подбросе
        if (tossShoulder != null && tossElbow != null && tossWrist != null) {
            val angle = angleBetween(tossShoulder, tossElbow, tossWrist)
            when {
                angle > 155f -> goods += context.getString(R.string.toss_arm_straight)
                angle > 130f -> {} // приемлемо, не комментируем
                else -> issues += Issue("toss_arm_bent", context.getString(R.string.toss_arm_bent, angle.toInt()))
            }
        }

        return PhaseReport(ServePhase.TOSS, goods, issues)
    }

    // ── Трофей ────────────────────────────────────────────────────────────────

    private fun analyzeTrophy(context: Context, frames: List<PoseFrame>, hl: HandedLandmarks): PhaseReport {
        // Лучший кадр — когда оба локтя максимально высоко
        val frame = frames.minByOrNull { f ->
            val lm = f.landmarks
            val le = lm.getOrNull(LandmarkIndex.LEFT_ELBOW)?.y ?: 1f
            val re = lm.getOrNull(LandmarkIndex.RIGHT_ELBOW)?.y ?: 1f
            le + re
        } ?: frames.first()
        val lm = frame.landmarks
        val goods = mutableListOf<String>()
        val issues = mutableListOf<Issue>()

        val racketShoulder = lm.getOrNull(hl.racketShoulder)
        val racketElbow    = lm.getOrNull(hl.racketElbow)
        val racketWrist    = lm.getOrNull(hl.racketWrist)
        val tossShoulder   = lm.getOrNull(hl.tossShoulder)
        val tossElbow      = lm.getOrNull(hl.tossElbow)

        // Локоть руки с ракеткой выше плеча
        if (racketElbow != null && racketShoulder != null) {
            if (racketElbow.y < racketShoulder.y - 0.03f)
                goods += context.getString(R.string.trophy_elbow_up)
            else
                issues += Issue("trophy_elbow_low", context.getString(R.string.trophy_elbow_low))
        }

        // Угол локтя рабочей руки в трофее (~90°)
        if (racketShoulder != null && racketElbow != null && racketWrist != null) {
            val angle = angleBetween(racketShoulder, racketElbow, racketWrist)
            when {
                angle in 75f..110f -> goods += context.getString(R.string.trophy_angle_good, angle.toInt())
                angle < 75f -> issues += Issue("trophy_angle_bent", context.getString(R.string.trophy_angle_bent, angle.toInt()))
                else -> issues += Issue("trophy_angle_straight", context.getString(R.string.trophy_angle_straight, angle.toInt()))
            }
        }

        // Рука подброса тоже вверх
        if (tossElbow != null && tossShoulder != null) {
            if (tossElbow.y < tossShoulder.y)
                goods += context.getString(R.string.trophy_both_up)
            else
                issues += Issue("trophy_toss_higher", context.getString(R.string.trophy_toss_higher))
        }

        // Поворот плеч
        val lSh = lm.getOrNull(LandmarkIndex.LEFT_SHOULDER)
        val rSh = lm.getOrNull(LandmarkIndex.RIGHT_SHOULDER)
        if (lSh != null && rSh != null) {
            val rotation = abs(lSh.z - rSh.z)
            if (rotation > 0.1f) goods += context.getString(R.string.trophy_rot_good)
            else issues += Issue("trophy_rot_bad", context.getString(R.string.trophy_rot_bad))
        }

        return PhaseReport(ServePhase.TROPHY, goods, issues)
    }

    // ── Бэкскрэтч ─────────────────────────────────────────────────────────────

    private fun analyzeBackscratch(context: Context, frames: List<PoseFrame>, hl: HandedLandmarks): PhaseReport {
        // Лучший кадр — запястье ракетки максимально опущено
        val frame = frames.maxByOrNull { f ->
            f.landmarks.getOrNull(hl.racketWrist)?.y ?: 0f
        } ?: frames.first()
        val lm = frame.landmarks
        val goods = mutableListOf<String>()
        val issues = mutableListOf<Issue>()

        val wrist    = lm.getOrNull(hl.racketWrist)
        val shoulder = lm.getOrNull(hl.racketShoulder)
        val elbow    = lm.getOrNull(hl.racketElbow)

        // Запястье опустилось ниже плеча
        if (wrist != null && shoulder != null) {
            val drop = wrist.y - shoulder.y
            when {
                drop > 0.15f -> goods += context.getString(R.string.bs_deep)
                drop > 0.05f -> goods += context.getString(R.string.bs_ok)
                else -> issues += Issue("bs_low", context.getString(R.string.bs_low))
            }
        }

        // Сгиб локтя в бэкскрэтче
        if (shoulder != null && elbow != null && wrist != null) {
            val angle = angleBetween(shoulder, elbow, wrist)
            when {
                angle in 80f..130f -> goods += context.getString(R.string.bs_elbow_good, angle.toInt())
                angle > 150f -> issues += Issue("bs_elbow_straight", context.getString(R.string.bs_elbow_straight, angle.toInt()))
                else -> {}
            }
        }

        return PhaseReport(ServePhase.BACKSCRATCH, goods, issues)
    }

    // ── Разгон ────────────────────────────────────────────────────────────────

    private fun analyzeAcceleration(context: Context, frames: List<PoseFrame>, hl: HandedLandmarks): PhaseReport {
        // Лучший кадр — пик скорости запястья
        val frame = peakVelocityFrame(frames, hl) ?: bestFrame(frames, hl)
        val lm = frame.landmarks
        val goods = mutableListOf<String>()
        val issues = mutableListOf<Issue>()

        val shoulder = lm.getOrNull(hl.racketShoulder)
        val elbow    = lm.getOrNull(hl.racketElbow)
        val wrist    = lm.getOrNull(hl.racketWrist)
        val hip      = lm.getOrNull(hl.racketHip)

        // Наклон туловища назад для мощи
        if (shoulder != null && hip != null) {
            val dx = shoulder.x - hip.x
            val dy = hip.y - shoulder.y
            val tilt = Math.toDegrees(Math.atan2(dx.toDouble(), dy.toDouble())).toFloat()
            when {
                tilt in 8f..25f -> goods += context.getString(R.string.accel_tilt_good, tilt.toInt())
                tilt < 5f -> issues += Issue("accel_tilt_low", context.getString(R.string.accel_tilt_low))
                tilt > 30f -> issues += Issue("accel_tilt_high", context.getString(R.string.accel_tilt_high, tilt.toInt()))
            }
        }

        // Рука тянется вверх
        if (wrist != null && shoulder != null) {
            if (wrist.y < shoulder.y - 0.1f) goods += context.getString(R.string.accel_reach_good)
            else issues += Issue("accel_reach_bad", context.getString(R.string.accel_reach_bad))
        }

        return PhaseReport(ServePhase.ACCELERATION, goods, issues)
    }

    // ── Контакт ───────────────────────────────────────────────────────────────

    private fun analyzeContact(context: Context, frames: List<PoseFrame>, hl: HandedLandmarks): PhaseReport {
        // Лучший кадр — максимальная высота запястья (минимальный Y)
        val frame = frames.minByOrNull { f ->
            f.landmarks.getOrNull(hl.racketWrist)?.y ?: 1f
        } ?: frames.first()
        val lm = frame.landmarks
        val goods = mutableListOf<String>()
        val issues = mutableListOf<Issue>()

        val shoulder = lm.getOrNull(hl.racketShoulder)
        val elbow    = lm.getOrNull(hl.racketElbow)
        val wrist    = lm.getOrNull(hl.racketWrist)
        val hip      = lm.getOrNull(hl.racketHip)
        val nose     = lm.getOrNull(LandmarkIndex.NOSE)

        // Угол локтя при контакте — главный показатель
        if (shoulder != null && elbow != null && wrist != null) {
            val angle = angleBetween(shoulder, elbow, wrist)
            when {
                angle in 160f..180f -> goods += context.getString(R.string.contact_ext_great, angle.toInt())
                angle in 145f..160f -> goods += context.getString(R.string.contact_ext_ok, angle.toInt())
                angle in 130f..145f -> issues += Issue("contact_ext_more", context.getString(R.string.contact_ext_more, angle.toInt()))
                else -> issues += Issue("contact_ext_bent", context.getString(R.string.contact_ext_bent, angle.toInt()))
            }
        }

        // Высота контакта как % роста
        if (wrist != null && hip != null && nose != null) {
            val bodyH = hip.y - nose.y
            if (bodyH > 0f) {
                val contactPct = ((hip.y - wrist.y) / bodyH * 100f).toInt()
                when {
                    contactPct > 110 -> goods += context.getString(R.string.contact_h_veryhigh, contactPct)
                    contactPct > 90  -> goods += context.getString(R.string.contact_h_good, contactPct)
                    else -> issues += Issue("contact_h_low", context.getString(R.string.contact_h_low, contactPct))
                }
            }
        }

        // Наклон туловища при контакте
        if (shoulder != null && hip != null) {
            val dx = shoulder.x - hip.x
            val dy = hip.y - shoulder.y
            val tilt = Math.toDegrees(Math.atan2(dx.toDouble(), dy.toDouble())).toFloat()
            if (tilt > 5f) goods += context.getString(R.string.contact_tilt_kept)
        }

        return PhaseReport(ServePhase.CONTACT, goods, issues)
    }

    // ── Завершение ────────────────────────────────────────────────────────────

    private fun analyzeFollowThrough(context: Context, frames: List<PoseFrame>, hl: HandedLandmarks): PhaseReport {
        val frame = frames.lastOrNull() ?: return PhaseReport(ServePhase.FOLLOW_THROUGH, emptyList(), emptyList())
        val lm = frame.landmarks
        val goods = mutableListOf<String>()
        val issues = mutableListOf<Issue>()

        val wrist    = lm.getOrNull(hl.racketWrist)
        val tossHip  = lm.getOrNull(hl.tossHip)
        val racketHip = lm.getOrNull(hl.racketHip)
        val shoulder = lm.getOrNull(hl.racketShoulder)

        // Ракетка пересекла тело (запястье на стороне противоположного бедра)
        if (wrist != null && tossHip != null && racketHip != null) {
            val crossedBody = if (!hl.isLeftHanded)
                wrist.x < tossHip.x + 0.05f   // правша: запястье ушло влево
            else
                wrist.x > tossHip.x - 0.05f   // левша: запястье ушло вправо

            if (crossedBody) goods += context.getString(R.string.ft_crossed)
            else issues += Issue("ft_short", context.getString(R.string.ft_short))
        }

        // Рука опустилась ниже плеча
        if (wrist != null && shoulder != null && wrist.y > shoulder.y + 0.05f) {
            goods += context.getString(R.string.ft_down)
        }

        return PhaseReport(ServePhase.FOLLOW_THROUGH, goods, issues)
    }

    // ── Вспомогательные ───────────────────────────────────────────────────────

    private fun bestFrame(frames: List<PoseFrame>, hl: HandedLandmarks): PoseFrame =
        frames.maxByOrNull { f ->
            val lm = f.landmarks
            val a = lm.getOrNull(hl.racketShoulder)?.visibility ?: 0f
            val b = lm.getOrNull(hl.racketElbow)?.visibility ?: 0f
            val c = lm.getOrNull(hl.racketWrist)?.visibility ?: 0f
            a + b + c
        } ?: frames[frames.size / 2]

    private fun peakVelocityFrame(frames: List<PoseFrame>, hl: HandedLandmarks): PoseFrame? {
        if (frames.size < 3) return null
        var maxVel = 0f
        var result: PoseFrame? = null
        for (i in 1 until frames.size - 1) {
            val prev = frames[i - 1].landmarks.getOrNull(hl.racketWrist) ?: continue
            val next = frames[i + 1].landmarks.getOrNull(hl.racketWrist) ?: continue
            val dt = (frames[i + 1].timestampMs - frames[i - 1].timestampMs).coerceAtLeast(1L) / 1000f
            val dx = next.x - prev.x; val dy = next.y - prev.y
            val vel = sqrt(dx * dx + dy * dy) / dt
            if (vel > maxVel) { maxVel = vel; result = frames[i] }
        }
        return result
    }

    private fun angleBetween(
        a: com.tennis.analyzer.pose.PoseLandmark,
        b: com.tennis.analyzer.pose.PoseLandmark,
        c: com.tennis.analyzer.pose.PoseLandmark
    ): Float {
        val v1x = a.x - b.x; val v1y = a.y - b.y
        val v2x = c.x - b.x; val v2y = c.y - b.y
        val dot = v1x * v2x + v1y * v2y
        val mag = sqrt(v1x * v1x + v1y * v1y) * sqrt(v2x * v2x + v2y * v2y)
        if (mag == 0f) return 0f
        return Math.toDegrees(acos((dot / mag).coerceIn(-1f, 1f).toDouble())).toFloat()
    }
}
