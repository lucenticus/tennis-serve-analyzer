package com.tennis.analyzer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tennis.analyzer.analysis.ServeMetrics
import com.tennis.analyzer.data.ServeResult
import com.tennis.analyzer.data.TrainingDatabase
import com.tennis.analyzer.data.TrainingSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class SessionUiState(
    val isRecording: Boolean = false,
    val serveCount: Int = 0,
    val avgScore: Float = 0f,
    val lastScore: Float = 0f,
    val recentScores: List<Float> = emptyList(),
    val sessionId: String = UUID.randomUUID().toString(),
    val sessionStartMs: Long = 0L
)

class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val db = TrainingDatabase.get(app)
    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    fun startSession() {
        _state.value = SessionUiState(
            isRecording = true,
            sessionId = UUID.randomUUID().toString(),
            sessionStartMs = System.currentTimeMillis()
        )
    }

    fun stopSession() {
        val s = _state.value
        if (!s.isRecording) return

        viewModelScope.launch {
            db.sessionDao().save(
                TrainingSession(
                    id = s.sessionId,
                    startMs = s.sessionStartMs,
                    endMs = System.currentTimeMillis(),
                    totalServes = s.serveCount,
                    avgScore = s.avgScore
                )
            )
        }
        _state.value = s.copy(isRecording = false)
    }

    fun recordServe(metrics: ServeMetrics, adviceTexts: List<String>) {
        val s = _state.value
        if (!s.isRecording) return

        val newCount = s.serveCount + 1
        val newAvg = (s.avgScore * s.serveCount + metrics.overallScore) / newCount

        val newScores = (s.recentScores + metrics.overallScore).takeLast(15)
        _state.value = s.copy(
            serveCount = newCount,
            avgScore = newAvg,
            lastScore = metrics.overallScore,
            recentScores = newScores
        )

        viewModelScope.launch {
            db.serveDao().insertServe(
                ServeResult(
                    sessionId = s.sessionId,
                    timestampMs = System.currentTimeMillis(),
                    overallScore = metrics.overallScore,
                    elbowAngle = metrics.elbowAngleAtContact,
                    trunkTilt = metrics.trunkTiltAngle,
                    shoulderRotation = metrics.shoulderRotation,
                    legDriveScore = metrics.legDriveScore,
                    adviceGiven = adviceTexts.joinToString("|")
                )
            )
        }
    }
}
