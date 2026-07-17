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
import com.tennis.analyzer.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tennis.analyzer.data.TrainingDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private const val SOURCE_URL = "https://github.com/lucenticus/tennis-serve-analyzer"

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("tennis_prefs", MODE_PRIVATE)

        setContent {
            var leftHanded by remember { mutableStateOf(prefs.getBoolean("isLeftHanded", false)) }
            var voiceOn by remember { mutableStateOf(prefs.getBoolean("voice_enabled", true)) }
            var cleared by remember { mutableStateOf(false) }

            Column(
                Modifier.fillMaxSize().background(Color(0xFF101010)).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(36.dp))
                Text(stringResource(R.string.settings), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                // Рабочая рука
                SettingRow(
                    title = stringResource(R.string.settings_handedness),
                    subtitle = stringResource(if (leftHanded) R.string.hand_left else R.string.hand_right)
                ) {
                    Switch(
                        checked = leftHanded,
                        onCheckedChange = {
                            leftHanded = it
                            prefs.edit().putBoolean("isLeftHanded", it).apply()
                        }
                    )
                }
                // Голос
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
                ) { clearHistory { cleared = true } }

                Spacer(Modifier.height(8.dp))

                // Исходный код (AGPL)
                ActionRow(
                    title = stringResource(R.string.settings_source),
                    subtitle = stringResource(R.string.settings_source_sub)
                ) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.settings_about),
                    color = Color(0xFF777777), fontSize = 12.sp
                )
                Spacer(Modifier.height(24.dp))
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
private fun SettingRow(title: String, subtitle: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C1C))
            .padding(14.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(subtitle, color = Color(0xFF9E9E9E), fontSize = 12.sp)
        }
        control()
    }
    Spacer(Modifier.height(8.dp))
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
