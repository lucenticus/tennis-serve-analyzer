package com.tennis.analyzer.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.tennis.analyzer.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tennis.analyzer.data.TrainingDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private const val SOURCE_URL = "https://github.com/lucenticus/tennis-serve-analyzer"
private const val PRIVACY_URL = "https://lucenticus.github.io/tennis-serve-analyzer/"

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("tennis_prefs", MODE_PRIVATE)
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "—"

        setContent {
            var leftHanded by remember { mutableStateOf(prefs.getBoolean("isLeftHanded", false)) }
            var voiceOn by remember { mutableStateOf(prefs.getBoolean("voice_enabled", true)) }
            var useFrontCamera by remember { mutableStateOf(prefs.getBoolean("use_front_camera", false)) }
            var cleared by remember { mutableStateOf(false) }
            // Очистка истории — необратимое действие, ждёт подтверждения в диалоге, а не
            // выполняется мгновенно по тапу (см. UX-аудит).
            var confirmClear by remember { mutableStateOf(false) }

            Column(
                Modifier.fillMaxSize().background(Color(0xFF101010)).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(36.dp))
                Text(stringResource(R.string.settings), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                // Рабочая рука — сегментированный переключатель: оба варианта видны сразу,
                // не нужно читать подпись под названием, чтобы понять текущее значение
                // (обычный Switch тут подразумевал "вкл/выкл", а не выбор одного из двух).
                SettingRow(title = stringResource(R.string.settings_handedness)) {
                    SegmentedTwoOption(
                        leftLabel = stringResource(R.string.hand_left),
                        rightLabel = stringResource(R.string.hand_right),
                        rightSelected = !leftHanded,
                        onSelectLeft = {
                            leftHanded = true
                            prefs.edit().putBoolean("isLeftHanded", true).apply()
                        },
                        onSelectRight = {
                            leftHanded = false
                            prefs.edit().putBoolean("isLeftHanded", false).apply()
                        }
                    )
                }
                // Камера
                SettingRow(title = stringResource(R.string.settings_camera)) {
                    SegmentedTwoOption(
                        leftLabel = stringResource(R.string.camera_back),
                        rightLabel = stringResource(R.string.camera_front),
                        rightSelected = useFrontCamera,
                        onSelectLeft = {
                            useFrontCamera = false
                            prefs.edit().putBoolean("use_front_camera", false).apply()
                        },
                        onSelectRight = {
                            useFrontCamera = true
                            prefs.edit().putBoolean("use_front_camera", true).apply()
                        }
                    )
                }
                // Голос — это действительно вкл/выкл, обычный Switch тут уместен
                SettingRow(
                    title = stringResource(R.string.settings_voice),
                    subtitle = stringResource(if (voiceOn) R.string.voice_on else R.string.voice_off)
                ) {
                    Switch(
                        checked = voiceOn,
                        onCheckedChange = {
                            voiceOn = it
                            prefs.edit().putBoolean("voice_enabled", it).apply()
                        }
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Очистить историю
                ActionRow(
                    title = stringResource(if (cleared) R.string.settings_history_cleared else R.string.settings_clear_history),
                    danger = true
                ) { confirmClear = true }

                Spacer(Modifier.height(8.dp))

                // Политика конфиденциальности
                ActionRow(title = stringResource(R.string.settings_privacy)) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
                }

                Spacer(Modifier.height(24.dp))

                // Исходный код (AGPL) и версия — мелкая сноска, не карточка того же веса,
                // что и содержательные настройки выше: для обычного пользователя это не
                // действие, которое ожидаешь видеть на уровне "Рабочая рука".
                Text(
                    stringResource(R.string.settings_about),
                    color = Color(0xFF777777), fontSize = 12.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_version, versionName),
                    color = Color(0xFF777777), fontSize = 12.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_source_sub),
                    color = Color(0xFF888888), fontSize = 12.sp,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
                    }
                )
                Spacer(Modifier.height(24.dp))
            }

            if (confirmClear) {
                AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text(stringResource(R.string.settings_clear_history_title)) },
                    text = { Text(stringResource(R.string.settings_clear_history_body)) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmClear = false
                            clearHistory { cleared = true }
                        }) {
                            Text(stringResource(R.string.action_delete), color = Color(0xFFE53935))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmClear = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                    containerColor = Color(0xFF1C1C1C),
                    titleContentColor = Color.White,
                    textContentColor = Color(0xFFBBBBBB)
                )
            }
        }
    }

    private fun clearHistory(onDone: () -> Unit) {
        val ctx = applicationContext
        val dir = File(filesDir, "serves")
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                dir.listFiles()?.forEach { it.delete() }
                TrainingDatabase.get(ctx).historyDao().deleteAll()
            }
            runOnUiThread { onDone() }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String? = null, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C1C))
            .padding(14.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            subtitle?.let { Text(it, color = Color(0xFF9E9E9E), fontSize = 12.sp) }
        }
        control()
    }
    Spacer(Modifier.height(8.dp))
}

/** Сегментированный переключатель между двумя именованными вариантами (не вкл/выкл). */
@Composable
private fun SegmentedTwoOption(
    leftLabel: String,
    rightLabel: String,
    rightSelected: Boolean,
    onSelectLeft: () -> Unit,
    onSelectRight: () -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF2A2A2A))
            .padding(3.dp)
    ) {
        SegmentOption(leftLabel, selected = !rightSelected, onClick = onSelectLeft)
        SegmentOption(rightLabel, selected = rightSelected, onClick = onSelectRight)
    }
}

@Composable
private fun SegmentOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) Color(0xFF4CAF50) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color.White else Color(0xFFAAAAAA),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C1C))
            .clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (danger) Color(0xFFEF5350) else Color.White, fontSize = 16.sp)
            subtitle?.let { Text(it, color = Color(0xFF9E9E9E), fontSize = 12.sp) }
        }
    }
}
