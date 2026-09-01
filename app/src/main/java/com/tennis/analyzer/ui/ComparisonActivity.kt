package com.tennis.analyzer.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tennis.analyzer.R
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.io.File

/** Сравнение 1–2 подач: видео стопкой, синхронные плей/пауза, перемотка и замедление. */
class ComparisonActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PATHS = "paths"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paths = intent.getStringArrayExtra(EXTRA_PATHS)?.toList().orEmpty()
            .filter { File(it).exists() }
        if (paths.isEmpty()) { finish(); return }

        setContent {
            // Стартовая скорость — та же, что и по умолчанию на экране анализа (0.3×):
            // техника лучше видна в замедлении, сразу с открытия экрана сравнения.
            val players = remember {
                paths.map { path ->
                    ExoPlayer.Builder(this).build().apply {
                        setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
                        repeatMode = Player.REPEAT_MODE_ALL
                        playbackParameters = PlaybackParameters(0.3f)
                        prepare()
                    }
                }
            }
            DisposableEffect(Unit) { onDispose { players.forEach { it.release() } } }

            var isPlaying by remember { mutableStateOf(false) }
            var speed by remember { mutableStateOf(0.3f) }
            var position by remember { mutableStateOf(0f) }
            val duration = remember(players) {
                players.maxOfOrNull { it.duration.coerceAtLeast(1L) } ?: 1L
            }

            // Тикер позиции
            LaunchedEffect(isPlaying) {
                while (isPlaying) {
                    position = players.firstOrNull()?.currentPosition?.toFloat() ?: 0f
                    delay(60)
                }
            }

            Column(Modifier.fillMaxSize().background(Color.Black)) {
                // Видео стопкой
                players.forEachIndexed { i, player ->
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    this.player = player
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        Surface(
                            color = Color(0xAA000000), shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        ) {
                            Text(stringResource(R.string.compare_serve_n, i + 1), color = Color.White, fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                }

                // Панель управления
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Slider(
                        value = position.coerceIn(0f, duration.toFloat()),
                        onValueChange = {
                            position = it
                            players.forEach { p -> p.seekTo(it.toLong()) }
                        },
                        valueRange = 0f..duration.toFloat(),
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF7CB342), activeTrackColor = Color(0xFF7CB342))
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Плей/пауза
                        Button(
                            onClick = {
                                isPlaying = !isPlaying
                                players.forEach { it.playWhenReady = isPlaying }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7CB342)),
                            shape = RoundedCornerShape(24.dp)
                        ) { Text(stringResource(if (isPlaying) R.string.compare_pause else R.string.compare_play), color = Color.White) }

                        // Скорость — тот же набор значений и тот же паттерн (видимые кнопки,
                        // не цикл по одной), что и на экране анализа: одна и та же функция
                        // (замедленный повтор техники) должна выглядеть и работать одинаково
                        // на соседних экранах.
                        Row(
                            Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF2A2A2A)).padding(3.dp)
                        ) {
                            for (sp in listOf(0.15f, 0.3f, 0.5f, 1f)) {
                                val selected = kotlin.math.abs(speed - sp) < 0.001f
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(17.dp))
                                        .background(if (selected) Color(0xFF7CB342) else Color.Transparent)
                                        .clickable {
                                            speed = sp
                                            players.forEach { it.playbackParameters = PlaybackParameters(sp) }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        "${sp}×", fontSize = 13.sp,
                                        color = if (selected) Color.White else Color(0xFFAAAAAA)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
