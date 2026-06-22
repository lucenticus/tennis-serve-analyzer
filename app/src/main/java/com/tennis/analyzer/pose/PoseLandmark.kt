package com.tennis.analyzer.pose

data class PoseLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float
)

data class DetectedObject(
    val classId: Int,
    val confidence: Float,
    val cx: Float,   // normalized [0,1]
    val cy: Float,
    val w: Float,
    val h: Float,
    /** true — бокс достроен интерполяцией траектории (объект был размыт/не найден YOLO в этом кадре) */
    val interpolated: Boolean = false
) {
    companion object {
        const val CLASS_BALL   = 32
        const val CLASS_RACKET = 38
    }
}

data class PoseFrame(
    val landmarks: List<PoseLandmark>,
    val timestampMs: Long,
    val objects: List<DetectedObject> = emptyList()
)

object LandmarkIndex {
    const val NOSE = 0
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28

    val CONNECTIONS = listOf(
        11 to 12,
        11 to 13, 13 to 15,
        12 to 14, 14 to 16,
        11 to 23, 12 to 24,
        23 to 24,
        23 to 25, 25 to 27,
        24 to 26, 26 to 28
    )
}

/** Индексы суставов в зависимости от рабочей руки */
data class HandedLandmarks(val isLeftHanded: Boolean) {
    val racketShoulder = if (isLeftHanded) LandmarkIndex.LEFT_SHOULDER  else LandmarkIndex.RIGHT_SHOULDER
    val racketElbow    = if (isLeftHanded) LandmarkIndex.LEFT_ELBOW     else LandmarkIndex.RIGHT_ELBOW
    val racketWrist    = if (isLeftHanded) LandmarkIndex.LEFT_WRIST     else LandmarkIndex.RIGHT_WRIST
    val racketHip      = if (isLeftHanded) LandmarkIndex.LEFT_HIP       else LandmarkIndex.RIGHT_HIP
    val racketKnee     = if (isLeftHanded) LandmarkIndex.LEFT_KNEE      else LandmarkIndex.RIGHT_KNEE
    val tossWrist      = if (isLeftHanded) LandmarkIndex.RIGHT_WRIST    else LandmarkIndex.LEFT_WRIST
    val tossShoulder   = if (isLeftHanded) LandmarkIndex.RIGHT_SHOULDER else LandmarkIndex.LEFT_SHOULDER
    val tossElbow      = if (isLeftHanded) LandmarkIndex.RIGHT_ELBOW    else LandmarkIndex.LEFT_ELBOW
    val tossHip        = if (isLeftHanded) LandmarkIndex.RIGHT_HIP      else LandmarkIndex.LEFT_HIP
    val tossKnee       = if (isLeftHanded) LandmarkIndex.RIGHT_KNEE     else LandmarkIndex.LEFT_KNEE
}
