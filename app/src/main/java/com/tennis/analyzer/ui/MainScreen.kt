package com.tennis.analyzer.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tennis.analyzer.detection.ServePhase

@Composable
fun MainScreen(
    state: SessionUiState,
    currentPhase: ServePhase,
    hasReplay: Boolean = false,
    isFrontCamera: Boolean = false,
    isLeftHanded: Boolean = false,
    onStartStop: () -> Unit,
    onReplay: () -> Unit = {},
    onSwitchCamera: () -> Unit = {},
    onToggleHandedness: () -> Unit = {},
    onPreviewViewReady: (PreviewView) -> Unit,
    onOverlayReady: (SkeletonOverlay) -> Unit
) {
    Box(Modifier.fillMaxSize()) {

        // Camera preview + skeleton overlay
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    val preview = PreviewView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                    val overlay = SkeletonOverlay(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                    addView(preview)
                    addView(overlay)
                    onPreviewViewReady(preview)
                    onOverlayReady(overlay)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Верхняя панель: фаза + кнопка смены камеры
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhaseChip(phase = currentPhase)
                if (state.isRecording && state.serveCount > 0) {
                    SessionStats(state)
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End
            ) {
                SwitchCameraButton(isFrontCamera = isFrontCamera, onClick = onSwitchCamera)
                HandednessButton(isLeftHanded = isLeftHanded, onClick = onToggleHandedness)
            }
        }

        // Нижняя панель: повтор + старт/стоп
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (hasReplay) {
                Button(
                    onClick = onReplay,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.height(48.dp).widthIn(min = 200.dp)
                ) {
                    Text("▶  Замедленный повтор", fontSize = 16.sp)
                }
            }
            StartStopButton(isRecording = state.isRecording, onClick = onStartStop)
        }
    }
}

@Composable
private fun HandednessButton(isLeftHanded: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
        modifier = Modifier.size(52.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (isLeftHanded) "🤚" else "✋",
                fontSize = 22.sp
            )
        }
    }
}

@Composable
private fun SwitchCameraButton(isFrontCamera: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
        modifier = Modifier.size(52.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (isFrontCamera) "🔙" else "🤳",
                fontSize = 22.sp
            )
        }
    }
}

@Composable
private fun PhaseChip(phase: ServePhase) {
    val (label, color) = when (phase) {
        ServePhase.IDLE           -> "Ожидание"        to Color(0xFF888888)
        ServePhase.READY_STANCE   -> "Стойка"          to Color(0xFF4CAF50)
        ServePhase.TOSS           -> "Подброс"         to Color(0xFFFFEB3B)
        ServePhase.TROPHY         -> "Трофей"          to Color(0xFF00BCD4)
        ServePhase.BACKSCRATCH    -> "За спиной"       to Color(0xFFFF9800)
        ServePhase.ACCELERATION   -> "Разгон"          to Color(0xFFF44336)
        ServePhase.CONTACT        -> "Контакт"         to Color(0xFFE91E63)
        ServePhase.FOLLOW_THROUGH -> "Завершение"      to Color(0xFF9C27B0)
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.85f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            color = Color.White,
            fontSize = 18.sp
        )
    }
}

@Composable
private fun SessionStats(state: SessionUiState) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            StatItem("Подач", state.serveCount.toString())
            StatItem("Средняя", "${state.avgScore.toInt()}")
            StatItem("Последняя", "${state.lastScore.toInt()}")
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 22.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(label, color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun StartStopButton(isRecording: Boolean, onClick: () -> Unit) {
    val color = if (isRecording) Color(0xFFF44336) else Color(0xFF4CAF50)
    val label = if (isRecording) "Стоп" else "Начать тренировку"
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.height(56.dp).widthIn(min = 200.dp)
    ) {
        Text(label, fontSize = 18.sp)
    }
}
