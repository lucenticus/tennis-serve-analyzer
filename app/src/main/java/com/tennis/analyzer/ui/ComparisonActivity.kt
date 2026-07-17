package com.tennis.analyzer.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            val players = remember {
                paths.map { path ->
                    ExoPlayer.Builder(this).build().apply {
                        setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
                        repeatMode = Player.REPEAT_MODE_ALL
                        prepare()
                    }
                }
            }
            DisposableEffect(Unit) { onDispose { players.forEach { it.release() } } }

            var isPlaying by remember { mutableStateOf(false) }
            var speed by remember { mutableStateOf(1f) }
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

                        // Скорость
                        val speeds = listOf(0.25f, 0.5f, 1f)
                        OutlinedButton(
                            onClick = {
                                speed = speeds[(speeds.indexOf(speed) + 1) % speeds.size]
                                players.forEach { it.playbackParameters = PlaybackParameters(speed) }
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { Text("${speed}x") }
                    }
                }
            }
        }
    }
}
