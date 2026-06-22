package com.tennis.analyzer.detection

import android.util.Log
import com.tennis.analyzer.pose.HandedLandmarks
import com.tennis.analyzer.pose.LandmarkIndex
import com.tennis.analyzer.pose.PoseFrame
import kotlin.math.abs
import kotlin.math.sqrt

private const val DETECTION_SERVES = 2  // сколько подач нужно для авто-определения руки

enum class ServePhase {
    IDLE,
    READY_STANCE,
    TOSS,
    TROPHY,
    BACKSCRATCH,    // ракетка за спиной
    ACCELERATION,   // разгон ракетки вверх
    CONTACT,        // момент удара — рука в высшей точке
    FOLLOW_THROUGH  // выход из подачи
}

data class ServeEvent(
    val phase: ServePhase,
    val frames: List<PoseFrame>,
    val startMs: Long,
    val endMs: Long
)

class ServePhaseDetector(
    private val onServeCompleted: (ServeEvent) -> Unit,
    private val onPhaseChanged: ((ServePhase) -> Unit)? = null,
    private val onHandednessDetected: ((Boolean) -> Unit)? = null,
    isLeftHanded: Boolean = false
) {
    private var hl = HandedLandmarks(isLeftHanded)
    private var handednessConfirmed = false  // true = уже определили или задали вручную

    // Накапливаем пиковые скорости для авто-определения
    private var leftPeakVelSum = 0f
    private var rightPeakVelSum = 0f
    private var detectionServeCount = 0
    private var currentLeftPeak = 0f
    private var currentRightPeak = 0f

    private val frameBuffer = ArrayDeque<PoseFrame>(MAX_BUFFER)
    private var currentPhase = ServePhase.IDLE
    private var phaseStartMs = 0L
    private val serveFrames = mutableListOf<PoseFrame>()
    private var logFrameCounter = 0
    // Минимальная "высота" запястья во время разгона: y - 0.3*z
    // z отрицательный когда рука впереди тела (при повороте) → уменьшает значение → точнее пик
    private var accelMinHeight = Float.MAX_VALUE

    /** Принудительно задать руку из UI (отключает авто-определение) */
    fun setHandedness(isLeftHanded: Boolean) {
        hl = HandedLandmarks(isLeftHanded)
        handednessConfirmed = true
        Log.i(TAG, "Handedness set manually: isLeftHanded=$isLeftHanded")
    }

    fun process(frame: PoseFrame) {
        if (frameBuffer.size >= MAX_BUFFER) frameBuffer.removeFirst()
        frameBuffer.addLast(frame)

        val newPhase = detectPhase(frame)

        // Накапливаем пиковые скорости обоих запястий для авто-определения руки
        if (!handednessConfirmed && currentPhase == ServePhase.ACCELERATION) {
            val lv = calcRawWristVelocity(LandmarkIndex.LEFT_WRIST)
            val rv = calcRawWristVelocity(LandmarkIndex.RIGHT_WRIST)
            if (lv > currentLeftPeak) currentLeftPeak = lv
            if (rv > currentRightPeak) currentRightPeak = rv
        }

        logCoordinates(frame)

        if (newPhase != currentPhase) {
            Log.i(TAG, "Phase: $currentPhase → $newPhase")
            onPhaseTransition(currentPhase, newPhase, frame)
            onPhaseChanged?.invoke(newPhase)
        }
    }

    private fun detectPhase(frame: PoseFrame): ServePhase {
        val lm = frame.landmarks
        if (lm.size < 29) return ServePhase.IDLE

        val racketShoulder = lm[hl.racketShoulder]
        val racketWrist    = lm[hl.racketWrist]
        val tossWrist      = lm[hl.tossWrist]
        val tossShoulder   = lm[hl.tossShoulder]
        val racketHip      = lm[hl.racketHip]

        // YOLO-pose отдаёт нестабильную «видимость» ключевых точек — низкий порог,
        // иначе FSM навсегда застревает в IDLE (поза есть, но visibility < 0.4).
        if (racketShoulder.visibility < 0.05f || racketWrist.visibility < 0.05f) {
            return ServePhase.IDLE
        }

        val racketElbow              = lm[hl.racketElbow]
        val tossWristAboveShoulder   = tossWrist.y  < tossShoulder.y  - 0.05f
        val racketElbowAboveShoulder = racketElbow.y < racketShoulder.y - 0.03f
        // Учитываем z: при повороте рука уходит вперёд (z < 0), что компенсирует потерю по y
        val wristHeight              = racketWrist.y - Z_WEIGHT * racketWrist.z
        val shoulderHeight           = racketShoulder.y - Z_WEIGHT * racketShoulder.z
        val racketWristAboveHead     = wristHeight < shoulderHeight - 0.12f
        val racketWristLow           = racketWrist.y > racketHip.y - 0.05f
        val wristVelocity            = calcWristVelocity()
        val stillStanding            = wristVelocity < 0.02f && !tossWristAboveShoulder && racketWristLow

        val racketWristAboveShoulder = racketWrist.y < racketShoulder.y - 0.03f

        // Конечный автомат — каждая фаза переходит только в следующую
        return when (currentPhase) {
            ServePhase.IDLE, ServePhase.READY_STANCE -> when {
                tossWristAboveShoulder -> ServePhase.TOSS
                else -> ServePhase.READY_STANCE
            }
            ServePhase.TOSS -> when {
                // Трофей: обе руки подняты — рука с мячом выше плеча + локоть ракетки выше плеча
                tossWristAboveShoulder && racketElbowAboveShoulder -> ServePhase.TROPHY
                stillStanding -> ServePhase.READY_STANCE
                else -> currentPhase
            }
            ServePhase.TROPHY -> when {
                // Бэкскрэтч: запястье ракетки опустилось ниже плеча (ракетка уходит за спину)
                !racketWristAboveShoulder -> ServePhase.BACKSCRATCH
                else -> currentPhase
            }
            ServePhase.BACKSCRATCH -> when {
                // Разгон: запястье ракетки поднялось выше плеча — начало свинга
                racketWristAboveShoulder -> ServePhase.ACCELERATION
                else -> currentPhase
            }
            ServePhase.ACCELERATION -> {
                // Трекаем 3D-высоту: y - 0.3*z (меньше = выше/вперёд = ближе к контакту)
                if (wristHeight < accelMinHeight) accelMinHeight = wristHeight
                when {
                    // Контакт: рука прошла пик и опустилась на 4% от него
                    accelMinHeight < shoulderHeight - 0.10f &&
                    wristHeight > accelMinHeight + 0.04f -> ServePhase.CONTACT
                    racketWristLow -> ServePhase.FOLLOW_THROUGH
                    else -> currentPhase
                }
            }
            ServePhase.CONTACT -> when {
                // Выход: запястье начало опускаться ниже плеча
                !racketWristAboveShoulder -> ServePhase.FOLLOW_THROUGH
                else -> currentPhase
            }
            ServePhase.FOLLOW_THROUGH -> when {
                stillStanding -> ServePhase.READY_STANCE
                else -> currentPhase
            }
        }
    }

    private fun onPhaseTransition(from: ServePhase, to: ServePhase, frame: PoseFrame) {
        currentPhase = to
        val now = frame.timestampMs

        when (to) {
            ServePhase.TOSS -> {
                phaseStartMs = now
                serveFrames.clear()
                serveFrames.addAll(frameBuffer.takeLast(10))
                Log.i(TAG, "Serve started, recording frames")
            }
            ServePhase.FOLLOW_THROUGH -> {
                serveFrames.add(frame)
                Log.i(TAG, "Serve completed: ${serveFrames.size} frames, ${now - phaseStartMs}ms")

                // Авто-определение руки по накопленным пикам
                if (!handednessConfirmed && (currentLeftPeak > 0f || currentRightPeak > 0f)) {
                    leftPeakVelSum += currentLeftPeak
                    rightPeakVelSum += currentRightPeak
                    detectionServeCount++
                    Log.i(TAG, "Handedness detection: serve #$detectionServeCount left=$leftPeakVelSum right=$rightPeakVelSum")

                    if (detectionServeCount >= DETECTION_SERVES) {
                        val detectedLeftHanded = leftPeakVelSum > rightPeakVelSum
                        Log.i(TAG, "Handedness detected: isLeftHanded=$detectedLeftHanded")
                        hl = HandedLandmarks(detectedLeftHanded)
                        handednessConfirmed = true
                        onHandednessDetected?.invoke(detectedLeftHanded)
                    }
                    currentLeftPeak = 0f
                    currentRightPeak = 0f
                }

                if (serveFrames.size > MIN_SERVE_FRAMES) {
                    onServeCompleted(
                        ServeEvent(
                            phase = ServePhase.FOLLOW_THROUGH,
                            frames = serveFrames.toList(),
                            startMs = phaseStartMs,
                            endMs = now
                        )
                    )
                } else {
                    Log.w(TAG, "Too few frames (${serveFrames.size}), skipping analysis")
                }
                serveFrames.clear()
            }
            ServePhase.IDLE -> serveFrames.clear()
            else -> Unit
        }

        if (to == ServePhase.ACCELERATION) accelMinHeight = Float.MAX_VALUE

        if (to in setOf(ServePhase.TOSS, ServePhase.TROPHY, ServePhase.ACCELERATION)) {
            serveFrames.add(frame)
        }
    }

    private fun calcWristVelocity(): Float = calcRawWristVelocity(hl.racketWrist)

    private fun calcRawWristVelocity(landmarkIdx: Int): Float {
        if (frameBuffer.size < 3) return 0f
        val recent = frameBuffer.takeLast(3)
        val w0 = recent[0].landmarks.getOrNull(landmarkIdx) ?: return 0f
        val w2 = recent[2].landmarks.getOrNull(landmarkIdx) ?: return 0f
        val dt = (recent[2].timestampMs - recent[0].timestampMs).coerceAtLeast(1L) / 1000f
        val dx = w2.x - w0.x
        val dy = w2.y - w0.y
        val dz = w2.z - w0.z
        return sqrt(dx * dx + dy * dy + dz * dz) / dt
    }

    private fun logCoordinates(frame: PoseFrame) {
        val lm = frame.landmarks
        val rShoulder = lm.getOrNull(hl.racketShoulder) ?: return
        val rElbow    = lm.getOrNull(hl.racketElbow)    ?: return
        val rWrist    = lm.getOrNull(hl.racketWrist)    ?: return
        val tShoulder = lm.getOrNull(hl.tossShoulder)   ?: return
        val tWrist    = lm.getOrNull(hl.tossWrist)      ?: return
        val rHip      = lm.getOrNull(hl.racketHip)      ?: return
        val vel = calcWristVelocity()

        val tossAbove   = tWrist.y  < tShoulder.y  - 0.05f
        val elbowAbove  = rElbow.y  < rShoulder.y  - 0.03f
        val wristHigh   = rWrist.y  < rShoulder.y  - 0.15f
        val wristLow    = rWrist.y  > rHip.y       - 0.05f

        Log.i(TAG, "[${frame.timestampMs}ms] phase=$currentPhase vel=${f(vel)} " +
            "tossAboveShoulder=$tossAbove(tW.y=${f(tWrist.y)} tS.y=${f(tShoulder.y)}) " +
            "elbowAboveShoulder=$elbowAbove(rE.y=${f(rElbow.y)} rS.y=${f(rShoulder.y)}) " +
            "wristAboveHead=$wristHigh(rW.y=${f(rWrist.y)}) " +
            "wristLow=$wristLow(rW.y=${f(rWrist.y)} hip.y=${f(rHip.y)}) " +
            "vis(rS=${f(rShoulder.visibility)} rW=${f(rWrist.visibility)})"
        )
    }

    private fun f(v: Float) = "%.2f".format(v)

    companion object {
        private const val TAG = "ServePhaseDetector"
        private const val MAX_BUFFER = 90
        private const val VELOCITY_THRESHOLD = 0.5f
        private const val MIN_SERVE_FRAMES = 15
        // Вес z-координаты при вычислении "высоты": z < 0 когда рука впереди тела
        private const val Z_WEIGHT = 0.3f
    }
}
