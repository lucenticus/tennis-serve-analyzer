package com.tennis.analyzer.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tennis.analyzer.analysis.ServeAdvice
import com.tennis.analyzer.data.ServeRecording

class PlaybackActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: androidx.media3.ui.PlayerView
    private lateinit var poseOverlay: PlaybackOverlay
    private lateinit var speedGroup: RadioGroup
    private lateinit var btnReplay: Button
    private lateinit var scoreText: TextView
    private lateinit var adviceText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val poseSyncRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                poseOverlay.seekToPose(player.currentPosition)
            }
            handler.postDelayed(this, 33)   // ~30fps sync
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()

        val recording = CurrentRecording.value
        if (recording == null) {
            finish()
            return
        }

        val advice = CurrentAdvice.value

        scoreText.text = "Оценка: ${recording.score.toInt()}/100"
        if (advice.isNotEmpty()) {
            adviceText.text = advice.joinToString("\n") { "• ${it.textRu}" }
        }

        poseOverlay.setRecording(recording, vidW = 1280, vidH = 720)
        setupPlayer(recording)
        setupSpeedControls()
        handler.post(poseSyncRunnable)
    }

    private fun setupPlayer(recording: ServeRecording) {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        val mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(recording.videoFile))
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playbackParameters = player.playbackParameters.withSpeed(0.3f)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    player.seekTo(0)
                    player.pause()
                }
            }
        })
        player.play()

        btnReplay.setOnClickListener {
            player.seekTo(0)
            player.play()
        }
    }

    private fun setupSpeedControls() {
        val speeds = listOf(0.15f to "0.15×", 0.3f to "0.3×", 0.5f to "0.5×", 1.0f to "1×")
        speeds.forEachIndexed { idx, (speed, label) ->
            val rb = RadioButton(this).apply {
                text = label
                setTextColor(Color.WHITE)
                id = idx + 1
                if (speed == 0.3f) isChecked = true
            }
            speedGroup.addView(rb)
        }
        speedGroup.setOnCheckedChangeListener { _, checkedId ->
            val speed = speeds[checkedId - 1].first
            player.playbackParameters = player.playbackParameters.withSpeed(speed)
        }
    }

    private fun buildLayout() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // Видео плеер
        playerView = androidx.media3.ui.PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            useController = true
        }

        // Оверлей поз поверх видео
        poseOverlay = PlaybackOverlay(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(playerView)
        root.addView(poseOverlay)

        // Панель снизу
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(24, 16, 24, 32)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        scoreText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
        }

        speedGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }

        btnReplay = Button(this).apply {
            text = "⟳  Повтор"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(180, 0, 120, 220))
        }

        adviceText = TextView(this).apply {
            setTextColor(Color.rgb(255, 220, 60))
            textSize = 15f
            setPadding(0, 12, 0, 0)
        }

        panel.addView(scoreText)
        panel.addView(speedGroup)
        panel.addView(btnReplay)
        panel.addView(adviceText)
        root.addView(panel)

        setContentView(root)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(poseSyncRunnable)
        player.release()
    }

    companion object {
        fun start(context: Context, recording: ServeRecording, advice: List<ServeAdvice>) {
            CurrentRecording.value = recording
            CurrentAdvice.value = advice
            context.startActivity(Intent(context, PlaybackActivity::class.java))
        }
    }
}

// Простой in-memory holder — избегаем сериализации тяжёлых данных через Intent
object CurrentRecording {
    var value: ServeRecording? = null
}

object CurrentAdvice {
    var value: List<ServeAdvice> = emptyList()
}
