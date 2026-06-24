package com.tennis.analyzer.wear

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable

private val Green = Color(0xFF7CB342)
private val Red = Color(0xFFE53935)
private val Amber = Color(0xFFFFC107)
private val Track = Color(0xFF2C2C2C)

class WearMainActivity : ComponentActivity() {

    private var isRecording by mutableStateOf(false)
    private var status by mutableStateOf("Готов к записи")
    private var connected by mutableStateOf(true)
    private var lastScore by mutableStateOf<Int?>(null)
    private var lastTip by mutableStateOf<String?>(null)

    private val resultListener = MessageClient.OnMessageReceivedListener { event ->
        if (event.path == WearComm.PATH_RESULT) {
            val parts = String(event.data).split("|", limit = 2)
            lastScore = parts.getOrNull(0)?.trim()?.toIntOrNull()
            lastTip = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            isRecording = false
            status = "Готов к записи"
            vibrate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold(timeText = { TimeText() }) {
                    when {
                        lastScore != null && !isRecording ->
                            ResultScreen(
                                score = lastScore!!,
                                tip = lastTip,
                                onAgain = { lastScore = null; lastTip = null; toggleRecording() },
                                onClose = { lastScore = null; lastTip = null }
                            )
                        isRecording -> RecordingScreen(onStop = { toggleRecording() })
                        else -> IdleScreen(status = status, connected = connected, onStart = { toggleRecording() })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(resultListener)
        refreshConnection()
    }

    override fun onPause() {
        super.onPause()
        Wearable.getMessageClient(this).removeListener(resultListener)
    }

    private fun refreshConnection() {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { connected = it.isNotEmpty() }
            .addOnFailureListener { connected = false }
    }

    private fun toggleRecording() {
        sendToPhone(if (isRecording) WearComm.PATH_STOP else WearComm.PATH_START)
    }

    private fun sendToPhone(path: String) {
        val ctx: Context = this
        Wearable.getNodeClient(ctx).connectedNodes
            .addOnSuccessListener { nodes ->
                connected = nodes.isNotEmpty()
                if (nodes.isEmpty()) {
                    status = "Телефон не найден"
                    return@addOnSuccessListener
                }
                val msg = Wearable.getMessageClient(ctx)
                for (n in nodes) msg.sendMessage(n.id, path, ByteArray(0))
                if (path == WearComm.PATH_START) {
                    isRecording = true; status = "Идёт запись…"; lastScore = null; lastTip = null
                } else {
                    isRecording = false; status = "Анализирую…"
                }
            }
            .addOnFailureListener { status = "Ошибка связи"; connected = false }
    }

    private fun vibrate() {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VibratorManager::class.java)).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        }
        vib?.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

// ── Экраны ──────────────────────────────────────────────────────────────────

@Composable
private fun IdleScreen(status: String, connected: Boolean, onStart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("ПОДАЧА", color = Color(0xFF9E9E9E), fontSize = 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(14.dp))
        // Кнопка записи — крупный круг с белой точкой
        Button(
            onClick = onStart,
            modifier = Modifier.size(78.dp),
            colors = ButtonDefaults.buttonColors(backgroundColor = Green)
        ) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(Color.White))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            if (!connected) "Телефон не найден" else status,
            color = if (!connected) Red else Color(0xFFBDBDBD),
            fontSize = 13.sp, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RecordingScreen(onStop: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "scale"
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Пульсирующее кольцо
        Box(
            Modifier.size(118.dp).scale(scale).clip(CircleShape)
                .background(Red.copy(alpha = 0.18f))
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Кнопка стоп — красный круг с белым квадратом
            Button(
                onClick = onStop,
                modifier = Modifier.size(78.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = Red)
            ) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(Color.White))
            }
            Spacer(Modifier.height(12.dp))
            Text("● Идёт запись", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ResultScreen(score: Int, tip: String?, onAgain: () -> Unit, onClose: () -> Unit) {
    val color = scoreColor(score)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Кольцо-индикатор оценки (разрыв снизу)
        CircularProgressIndicator(
            progress = (score / 100f).coerceIn(0f, 1f),
            modifier = Modifier.fillMaxSize().padding(6.dp),
            startAngle = 292.5f,
            endAngle = 247.5f,
            indicatorColor = color,
            trackColor = Track,
            strokeWidth = 8.dp
        )
        Column(
            Modifier.padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("$score", color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Bold)
            Text("из 100", color = Color(0xFF9E9E9E), fontSize = 11.sp)
            tip?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it, color = Amber, fontSize = 12.sp,
                    textAlign = TextAlign.Center, maxLines = 3
                )
            }
        }
        // Кнопки снизу: записать ещё / закрыть
        Row(
            Modifier.fillMaxSize().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom
        ) {
            Button(
                onClick = onAgain,
                modifier = Modifier.size(40.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = Green)
            ) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}

private fun scoreColor(score: Int): Color = when {
    score >= 75 -> Color(0xFF66BB6A)
    score >= 50 -> Amber
    else -> Red
}
