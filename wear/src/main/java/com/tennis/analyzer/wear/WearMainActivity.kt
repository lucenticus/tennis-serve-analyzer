package com.tennis.analyzer.wear

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.clickable
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

private const val ANALYZE_TIMEOUT_MS = 40_000L

class WearMainActivity : ComponentActivity() {

    private var mode by mutableStateOf("ANALYSIS")        // ANALYSIS | REALTIME
    private var isRecording by mutableStateOf(false)
    private var analyzing by mutableStateOf(false)
    private var progress by mutableStateOf(0f)             // 0..1
    private var status by mutableStateOf("Готов к записи")
    private var connected by mutableStateOf(true)
    private var lastScore by mutableStateOf<Int?>(null)
    private var lastTip by mutableStateOf<String?>(null)
    private var noServe by mutableStateOf(false)
    private var framing by mutableStateOf("")   // код кадрирования с телефона

    private val handler = Handler(Looper.getMainLooper())
    private val analyzeTimeout = Runnable {
        if (analyzing) {
            analyzing = false
            status = "Анализ не завершился"
        }
    }

    private val msgListener = MessageClient.OnMessageReceivedListener { event ->
        when (event.path) {
            WearComm.PATH_FRAMING -> framing = String(event.data)
            WearComm.PATH_PROGRESS -> {
                analyzing = true
                isRecording = false
                progress = (String(event.data).toIntOrNull() ?: 0) / 100f
                armTimeout()
            }
            WearComm.PATH_RESULT -> {
                handler.removeCallbacks(analyzeTimeout)
                val parts = String(event.data).split("|", limit = 2)
                val sc = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: -1
                analyzing = false
                isRecording = false
                if (sc < 0) {
                    noServe = true; lastScore = null; lastTip = null
                    status = "Подача не распознана"
                } else {
                    noServe = false; lastScore = sc
                    lastTip = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                    status = "Готов к записи"
                }
                vibrate()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold(timeText = { TimeText() }) {
                    when {
                        analyzing -> AnalyzingScreen(progress)
                        lastScore != null -> ResultScreen(
                            score = lastScore!!, tip = lastTip,
                            onBack = { resetResult() }                 // вернуться к экрану записи
                        )
                        noServe -> NoServeScreen(onRetry = { resetResult() })
                        isRecording -> RecordingScreen(onStop = { toggleRecording() })
                        else -> IdleScreen(
                            mode = mode, status = status, connected = connected, framing = framing,
                            onStart = { if (mode == "ANALYSIS") toggleRecording() },
                            onToggleMode = { switchMode() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(msgListener)
        refreshConnection()
    }

    override fun onPause() {
        super.onPause()
        Wearable.getMessageClient(this).removeListener(msgListener)
    }

    private fun resetResult() { lastScore = null; lastTip = null; noServe = false; status = "Готов к записи" }

    private fun armTimeout() {
        handler.removeCallbacks(analyzeTimeout)
        handler.postDelayed(analyzeTimeout, ANALYZE_TIMEOUT_MS)
    }

    private fun refreshConnection() {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { connected = it.isNotEmpty() }
            .addOnFailureListener { connected = false }
    }

    private fun switchMode() {
        val next = if (mode == "ANALYSIS") "REALTIME" else "ANALYSIS"
        sendToPhone(WearComm.PATH_MODE, next) { mode = next; resetResult(); isRecording = false; analyzing = false }
    }

    private fun toggleRecording() {
        if (isRecording) {
            sendToPhone(WearComm.PATH_STOP) {
                isRecording = false; analyzing = true; progress = 0f; status = "Анализирую…"; armTimeout()
            }
        } else {
            sendToPhone(WearComm.PATH_START) {
                isRecording = true; status = "Идёт запись…"; resetResult()
            }
        }
    }

    private fun sendToPhone(path: String, payload: String = "", onSent: () -> Unit = {}) {
        val ctx: Context = this
        Wearable.getNodeClient(ctx).connectedNodes
            .addOnSuccessListener { nodes ->
                connected = nodes.isNotEmpty()
                if (nodes.isEmpty()) { status = "Телефон не найден"; return@addOnSuccessListener }
                val msg = Wearable.getMessageClient(ctx)
                for (n in nodes) msg.sendMessage(n.id, path, payload.toByteArray())
                onSent()
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
private fun IdleScreen(
    mode: String, status: String, connected: Boolean, framing: String,
    onStart: () -> Unit, onToggleMode: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ModeChip(mode, onToggleMode)
        Spacer(Modifier.height(10.dp))
        if (mode == "ANALYSIS") {
            Button(
                onClick = onStart,
                modifier = Modifier.size(72.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = Green)
            ) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White))
            }
            Spacer(Modifier.height(10.dp))
            // Подсказка кадрирования (где встать / влезет ли подброс)
            val (fText, fColor) = framingHint(framing, connected, status)
            Text(fText, color = fColor, fontSize = 12.5.sp, textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium)
        } else {
            Text("Реал-тайм", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (!connected) "Телефон не найден" else "Тренировка идёт на телефоне.\nПодсказки звучат голосом.",
                color = if (!connected) Red else Color(0xFFBDBDBD),
                fontSize = 12.sp, textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ModeChip(mode: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF2A2A2A))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (mode == "ANALYSIS") "🎾 Анализ  ⇄" else "⚡ Реал-тайм  ⇄",
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold
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
        Box(Modifier.size(118.dp).scale(scale).clip(CircleShape).background(Red.copy(alpha = 0.18f)))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
private fun AnalyzingScreen(progress: Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = progress.coerceIn(0f, 1f),
            modifier = Modifier.fillMaxSize().padding(8.dp),
            startAngle = 292.5f, endAngle = 247.5f,
            indicatorColor = Amber, trackColor = Track, strokeWidth = 8.dp
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(progress * 100).toInt()}%", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("анализ", color = Color(0xFF9E9E9E), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ResultScreen(score: Int, tip: String?, onBack: () -> Unit) {
    val color = scoreColor(score)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = (score / 100f).coerceIn(0f, 1f),
            modifier = Modifier.fillMaxSize().padding(6.dp),
            startAngle = 292.5f, endAngle = 247.5f,
            indicatorColor = color, trackColor = Track, strokeWidth = 8.dp
        )
        Column(
            Modifier.padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("$score", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold)
            Text("из 100", color = Color(0xFF9E9E9E), fontSize = 11.sp)
            tip?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = Amber, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2)
            }
            Spacer(Modifier.height(8.dp))
            // Явная кнопка возврата к экрану записи
            CompactChip(text = "‹ К записи", onClick = onBack)
        }
    }
}

@Composable
private fun CompactChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Green)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NoServeScreen(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🤔", fontSize = 30.sp)
        Spacer(Modifier.height(6.dp))
        Text("Подача не распознана", color = Color.White, fontSize = 14.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text("Встань боком в кадр\nи выполни подачу целиком",
            color = Color(0xFFBDBDBD), fontSize = 11.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        CompactChip(text = "‹ К записи", onClick = onRetry)
    }
}

private fun scoreColor(score: Int): Color = when {
    score >= 75 -> Color(0xFF66BB6A)
    score >= 50 -> Amber
    else -> Red
}

/** Текст и цвет подсказки кадрирования по коду с телефона. */
private fun framingHint(code: String, connected: Boolean, fallback: String): Pair<String, Color> {
    if (!connected) return "Телефон не найден" to Red
    return when (code) {
        WearComm.FRAME_OK          -> "✓ Кадр в порядке" to Color(0xFF66BB6A)
        WearComm.FRAME_NO_PERSON   -> "Встань в кадр" to Color(0xFFBDBDBD)
        WearComm.FRAME_MOVE_BACK   -> "↔ Отойди — не\nпомещаешься целиком" to Amber
        WearComm.FRAME_MOVE_CLOSER -> "→ Подойди ближе" to Amber
        WearComm.FRAME_LOW_TOSS    -> "↑ Мало места сверху —\nподброс не влезет" to Amber
        else                       -> fallback to Color(0xFFBDBDBD)
    }
}
