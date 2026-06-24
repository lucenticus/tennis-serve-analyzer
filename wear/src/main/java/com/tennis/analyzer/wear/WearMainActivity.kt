package com.tennis.analyzer.wear

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable

class WearMainActivity : ComponentActivity() {

    private var isRecording by mutableStateOf(false)
    private var status by mutableStateOf("Готов")
    private var lastScore by mutableStateOf<String?>(null)
    private var lastTip by mutableStateOf<String?>(null)

    // Приём результата анализа с телефона
    private val resultListener = MessageClient.OnMessageReceivedListener { event ->
        if (event.path == WearComm.PATH_RESULT) {
            val parts = String(event.data).split("|", limit = 2)
            lastScore = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
            lastTip   = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            isRecording = false
            status = "Результат получен"
            vibrate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold(timeText = { TimeText() }) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Подача",
                            color = Color.White, fontSize = 18.sp,
                            textAlign = TextAlign.Center
                        )

                        Button(
                            onClick = { toggleRecording() },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (isRecording) Color(0xFFF44336) else Color(0xFF4CAF50)
                            )
                        ) {
                            Text(if (isRecording) "Стоп" else "Запись")
                        }

                        Text(
                            status,
                            color = Color(0xFFBBBBBB), fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )

                        lastScore?.let {
                            Text(
                                "Оценка: $it",
                                color = Color.White, fontSize = 16.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        lastTip?.let {
                            Text(
                                it,
                                color = Color(0xFFFFD54F), fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(resultListener)
    }

    override fun onPause() {
        super.onPause()
        Wearable.getMessageClient(this).removeListener(resultListener)
    }

    private fun toggleRecording() {
        val path = if (isRecording) WearComm.PATH_STOP else WearComm.PATH_START
        sendToPhone(path)
    }

    private fun sendToPhone(path: String) {
        val ctx: Context = this
        Wearable.getNodeClient(ctx).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    status = "Телефон не найден"
                    return@addOnSuccessListener
                }
                val msg = Wearable.getMessageClient(ctx)
                for (n in nodes) msg.sendMessage(n.id, path, ByteArray(0))
                if (path == WearComm.PATH_START) {
                    isRecording = true; status = "Идёт запись…"; lastScore = null; lastTip = null
                } else {
                    isRecording = false; status = "Останавливаю…"
                }
            }
            .addOnFailureListener { status = "Ошибка связи" }
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
