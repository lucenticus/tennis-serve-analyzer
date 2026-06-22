package com.tennis.analyzer.data

import com.tennis.analyzer.detection.ServePhase
import com.tennis.analyzer.pose.PoseFrame
import java.io.File

data class PhaseMarker(val phase: ServePhase, val timeMs: Long)

data class AnalysisInputData(
    val videoFile: File,
    val frames: List<PoseFrame>,
    val videoDurationMs: Long,
    val videoWidth: Int,
    val videoHeight: Int,
    val phases: List<PhaseMarker>,
    val serveContacts: List<Long> = emptyList(),
    val isLeftHanded: Boolean
)

object AnalysisInput {
    var value: AnalysisInputData? = null
}
