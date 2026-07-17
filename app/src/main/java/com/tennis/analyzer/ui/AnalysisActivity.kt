package com.tennis.analyzer.ui

import android.app.ProgressDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import com.tennis.analyzer.R
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tennis.analyzer.analysis.FrameAdvisor
import com.tennis.analyzer.analysis.PhaseAnalyzer
import com.tennis.analyzer.analysis.ServeAdvice
import com.tennis.analyzer.analysis.ServeAnalyzer
import com.tennis.analyzer.analysis.VideoExporter
import com.tennis.analyzer.data.AnalysisInput
import com.tennis.analyzer.data.AnalysisInputData
import com.tennis.analyzer.data.PhaseMarker
import com.tennis.analyzer.detection.ServePhase
import com.tennis.analyzer.detection.ServePhaseDetector
import com.tennis.analyzer.pose.DetectedObject
import com.tennis.analyzer.pose.PoseFrame

class AnalysisActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: androidx.media3.ui.PlayerView
    private lateinit var poseOverlay: PlaybackOverlay

    private lateinit var scoreText: TextView
    private lateinit var phaseLabel: TextView
    private lateinit var frameInfoText: TextView
    // Плавающая карточка совета текущей фазы (заменяет длинный отчёт)
    private lateinit var coachCard: LinearLayout
    private lateinit var coachHeader: TextView
    private lateinit var coachContent: LinearLayout
    private lateinit var coachToggle: TextView
    private var coachExpanded: Boolean = true
    // Разбор по фазам (для лукапа при смене фазы) — обновляется в runOverallAnalysis
    private var phaseReports: Map<ServePhase, com.tennis.analyzer.analysis.PhaseReport> = emptyMap()
    private var lastCoachPhase: ServePhase? = null
    private lateinit var phaseBar: PhaseTimelineView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseBtn: Button
    private lateinit var saveToGalleryBtn: Button
    private lateinit var speedGroup: RadioGroup

    private val handler = Handler(Looper.getMainLooper())
    private var userScrubbing = false

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized) {
                val pos = player.currentPosition
                if (!userScrubbing) {
                    seekBar.progress = pos.toInt()
                    poseOverlay.seekToPose(pos)
                    phaseBar.setPlayhead(pos)
                    updateFrameFeedback(pos)
                }
                playPauseBtn.text = if (player.isPlaying) "⏸" else "▶"
            }
            handler.postDelayed(this, 33)
        }
    }

    private var allFrames: List<PoseFrame> = emptyList()
    private var isLeftHanded = false
    // Фазы по времени для поиска текущей фазы
    private var phaseTimeline: List<Pair<Long, ServePhase>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()

        val input: AnalysisInputData = AnalysisInput.value ?: run { finish(); return }

        allFrames = input.frames
        isLeftHanded = input.isLeftHanded
        phaseTimeline = input.phases.map { it.timeMs to it.phase }

        poseOverlay.setFrames(input.frames, input.videoDurationMs, input.videoWidth, input.videoHeight)
        phaseBar.setData(input.frames, input.videoDurationMs, input.phases, input.serveContacts)
        seekBar.max = input.videoDurationMs.toInt()

        runOverallAnalysis(input.frames, input.phases)
        setupPlayer(input)
        setupSaveToGalleryBtn(input)
        setupSpeedControls()
        setupSeekBar()
        handler.post(syncRunnable)
    }

    // ── Анализ всей подачи (оценка вверху) ──────────────────────────────────

    private fun runOverallAnalysis(frames: List<PoseFrame>, phases: List<PhaseMarker>) {
        val contacts = AnalysisInput.value?.serveContacts ?: emptyList()
        val windows = serveWindows(frames, contacts)

        // Собираем разбор по фазам, чтоб потом отдавать пользователю по одной фазе за раз
        val bucket = mutableMapOf<ServePhase, MutableList<com.tennis.analyzer.analysis.PhaseReport>>()

        if (windows.size <= 1) {
            val (metrics, _) = ServeAnalyzer.analyze(applicationContext, frames, isLeftHanded)
            val report = PhaseAnalyzer.analyze(applicationContext, frames, phases, isLeftHanded)
            scoreText.text = getString(R.string.analysis_score, metrics.overallScore.toInt())
            for (pr in report.phases) bucket.getOrPut(pr.phase) { mutableListOf() }.add(pr)
        } else {
            val scores = mutableListOf<Int>()
            for ((_, window) in windows.withIndex()) {
                val sub = frames.filter { it.timestampMs in window.first..window.second }
                if (sub.size < 3) continue
                val subPhases = phases.filter { it.timeMs in window.first..window.second }
                val (m, _) = ServeAnalyzer.analyze(applicationContext, sub, isLeftHanded)
                scores.add(m.overallScore.toInt())
                val rep = PhaseAnalyzer.analyze(applicationContext, sub, subPhases, isLeftHanded)
                for (pr in rep.phases) bucket.getOrPut(pr.phase) { mutableListOf() }.add(pr)
            }
            val avg = if (scores.isEmpty()) 0 else scores.average().toInt()
            scoreText.text = getString(R.string.analysis_multi_summary, scores.size, avg)
        }

        // Мержим по фазе (уникальные советы)
        phaseReports = bucket.mapValues { (phase, reps) ->
            com.tennis.analyzer.analysis.PhaseReport(
                phase = phase,
                goods  = reps.flatMap { it.goods  }.distinct(),
                issues = reps.flatMap { it.issues }.distinct()
            )
        }
        lastCoachPhase = null
        updateCoachCard(phaseAt(0L))
    }

    /** Окна подач: [серединаДоПредыдущей .. серединаДоСледующей]. */
    private fun serveWindows(frames: List<PoseFrame>, contacts: List<Long>): List<Pair<Long, Long>> {
        if (frames.isEmpty() || contacts.isEmpty()) return emptyList()
        val firstMs = frames.first().timestampMs
        val lastMs  = frames.last().timestampMs
        return contacts.mapIndexed { i, c ->
            val start = if (i == 0) firstMs else (contacts[i - 1] + c) / 2
            val end   = if (i == contacts.lastIndex) lastMs else (c + contacts[i + 1]) / 2
            start to end
        }
    }

    /** Обновляет плавающую карточку советов под текущую фазу видео. */
    private fun updateCoachCard(phase: ServePhase) {
        if (phase == lastCoachPhase) return
        lastCoachPhase = phase
        val report = phaseReports[phase]
        val hasContent = report != null && (report.goods.isNotEmpty() || report.issues.isNotEmpty())

        coachCard.visibility = if (hasContent) android.view.View.VISIBLE else android.view.View.GONE
        if (!hasContent) return

        val phaseColor = phaseColor(phase)
        coachHeader.text = phaseName(phase)
        coachHeader.setTextColor(phaseColor)

        coachContent.removeAllViews()
        val dp = resources.displayMetrics.density
        // При сжатой карточке показываем только 1 главный совет (issue > good)
        val goods  = report!!.goods
        val issues = report.issues
        val itemsToShow = if (coachExpanded) issues + goods
                          else listOfNotNull(issues.firstOrNull() ?: goods.firstOrNull())

        val isIssue = { text: String -> issues.contains(text) }
        for (text in itemsToShow) {
            coachContent.addView(TextView(this).apply {
                this.text = (if (isIssue(text)) "⚠  " else "✓  ") + text
                setTextColor(if (isIssue(text)) Color.rgb(255, 200, 60) else Color.rgb(120, 220, 120))
                textSize = 13f
                setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
            })
        }

        // Индикатор развёрнутости
        val totalCount = goods.size + issues.size
        coachToggle.text = if (coachExpanded) "▾" else if (totalCount > 1) "▴ +${totalCount - 1}" else "▴"
    }

    private fun phaseName(phase: ServePhase) = when (phase) {
        ServePhase.READY_STANCE   -> getString(R.string.phase_stance)
        ServePhase.TOSS           -> getString(R.string.phase_toss)
        ServePhase.TROPHY         -> getString(R.string.phase_trophy)
        ServePhase.BACKSCRATCH    -> getString(R.string.phase_backscratch)
        ServePhase.ACCELERATION   -> getString(R.string.phase_acceleration)
        ServePhase.CONTACT        -> getString(R.string.phase_contact)
        ServePhase.FOLLOW_THROUGH -> getString(R.string.phase_follow)
        else                      -> phase.name
    }

    private fun phaseColor(phase: ServePhase) = when (phase) {
        ServePhase.READY_STANCE   -> Color.rgb(76, 175, 80)
        ServePhase.TOSS           -> Color.rgb(255, 235, 59)
        ServePhase.TROPHY         -> Color.rgb(0, 188, 212)
        ServePhase.BACKSCRATCH    -> Color.rgb(255, 152, 0)
        ServePhase.ACCELERATION   -> Color.rgb(244, 67, 54)
        ServePhase.CONTACT        -> Color.rgb(233, 30, 99)
        ServePhase.FOLLOW_THROUGH -> Color.rgb(156, 39, 176)
        else                      -> Color.WHITE
    }

    // ── Советы по текущему кадру ─────────────────────────────────────────────

    private fun updateFrameFeedback(posMs: Long) {
        val frame = allFrames.minByOrNull { Math.abs(it.timestampMs - posMs) } ?: return
        val phase = phaseAt(posMs)
        val fb = FrameAdvisor.analyze(applicationContext, frame, phase, isLeftHanded)

        phaseLabel.text = phaseLabel(phase)
        updateCoachCard(phase)

        val hl = com.tennis.analyzer.pose.HandedLandmarks(isLeftHanded)
        val parts = mutableListOf<String>()

        // Высота запястья относительно плеча
        val wrist    = frame.landmarks.getOrNull(hl.racketWrist)
        val shoulder = frame.landmarks.getOrNull(hl.racketShoulder)
        if (wrist != null && shoulder != null) {
            val diffPct = ((shoulder.y - wrist.y) * 100).toInt()
            parts += if (diffPct >= 0) getString(R.string.arm_above, diffPct)
                     else getString(R.string.arm_below, diffPct)
        }


        frameInfoText.text = parts.joinToString("   ")
    }

    private fun phaseAt(posMs: Long): ServePhase {
        if (phaseTimeline.isEmpty()) return ServePhase.IDLE
        return phaseTimeline
            .filter { it.first <= posMs }
            .maxByOrNull { it.first }?.second
            ?: phaseTimeline.first().second
    }

    private fun phaseLabel(phase: ServePhase) = when (phase) {
        ServePhase.IDLE           -> "⬤  " + getString(R.string.phase_idle)
        ServePhase.READY_STANCE   -> "⬤  " + getString(R.string.phase_stance)
        ServePhase.TOSS           -> "⬤  " + getString(R.string.phase_toss)
        ServePhase.TROPHY         -> "⬤  " + getString(R.string.phase_trophy)
        ServePhase.BACKSCRATCH    -> "⬤  " + getString(R.string.phase_backscratch)
        ServePhase.ACCELERATION   -> "⬤  " + getString(R.string.phase_acceleration)
        ServePhase.CONTACT        -> "⬤  " + getString(R.string.phase_contact)
        ServePhase.FOLLOW_THROUGH -> "⬤  " + getString(R.string.phase_follow)
    }

    // ── Сохранение в галерею ─────────────────────────────────────────────────

    private fun setupSaveToGalleryBtn(input: AnalysisInputData) {
        saveToGalleryBtn.setOnClickListener {
            @Suppress("DEPRECATION")
            val progress = ProgressDialog(this).apply {
                setMessage(getString(R.string.export_rendering))
                setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                max = 100; setCancelable(false)
                show()
            }

            Thread {
                val tmp = java.io.File(cacheDir, "export_${System.currentTimeMillis()}.mp4")
                val ok = VideoExporter.export(
                    videoFile = input.videoFile,
                    frames    = allFrames,
                    videoWidth  = input.videoWidth,
                    videoHeight = input.videoHeight,
                    outputFile  = tmp
                ) { p -> handler.post { progress.progress = (p * 100).toInt() } }

                handler.post {
                    progress.dismiss()
                    if (ok) {
                        saveFileToGallery(tmp)
                    } else {
                        Toast.makeText(this, getString(R.string.export_error), Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    private fun saveFileToGallery(file: java.io.File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "serve_overlay_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TennisAnalyzer")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                Toast.makeText(this, getString(R.string.export_saved), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.save_error), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_generic, e.message ?: ""), Toast.LENGTH_SHORT).show()
        } finally {
            file.delete()
        }
    }

    // ── Плеер ────────────────────────────────────────────────────────────────

    private fun setupPlayer(input: AnalysisInputData) {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(input.videoFile)))
        player.prepare()
        player.playbackParameters = player.playbackParameters.withSpeed(0.3f)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { player.seekTo(0); player.pause() }
            }
        })
        player.play()
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    poseOverlay.seekToPose(progress.toLong())
                    phaseBar.setPlayhead(progress.toLong())
                    updateFrameFeedback(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                userScrubbing = true
                player.pause()
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                player.seekTo(sb.progress.toLong())
                userScrubbing = false
            }
        })

        playPauseBtn.setOnClickListener {
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    private fun setupSpeedControls() {
        val speeds = listOf(0.15f to "0.15×", 0.3f to "0.3×", 0.5f to "0.5×", 1.0f to "1×")
        speeds.forEachIndexed { idx, (_, label) ->
            speedGroup.addView(RadioButton(this).apply {
                text = label; setTextColor(Color.WHITE); id = idx + 1
                if (label == "0.3×") isChecked = true
            })
        }
        speedGroup.setOnCheckedChangeListener { _, id ->
            player.playbackParameters = player.playbackParameters.withSpeed(speeds[id - 1].first)
        }
    }


    // ── Layout ───────────────────────────────────────────────────────────────

    private fun buildLayout() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = androidx.media3.ui.PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            useController = false
        }

        poseOverlay = PlaybackOverlay(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(playerView)
        root.addView(poseOverlay)

        val dp = resources.displayMetrics.density

        // Нижняя панель — компактная
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(16, 4, 16, 20)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        // Строка: оценка + фаза + кнопка сохранения
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scoreText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        phaseLabel = TextView(this).apply {
            setTextColor(Color.CYAN); textSize = 13f; gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        saveToGalleryBtn = Button(this).apply {
            text = "💾"; textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                (44 * dp).toInt(), (36 * dp).toInt()
            )
        }
        topRow.addView(scoreText); topRow.addView(phaseLabel); topRow.addView(saveToGalleryBtn)

        // Высота руки + детекция объектов (обновляется каждый кадр)
        frameInfoText = TextView(this).apply {
            setTextColor(Color.rgb(160, 220, 255))
            textSize = 11f
            setPadding(0, 0, 0, 2)
        }

        // Таймлайн фаз
        phaseBar = PhaseTimelineView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (80 * dp).toInt()
            ).also { it.topMargin = 2; it.bottomMargin = 0 }
        }

        // Скруббер
        seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Play/pause + скорости
        val ctrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playPauseBtn = Button(this).apply {
            text = "⏸"; setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(140, 50, 50, 50))
            layoutParams = LinearLayout.LayoutParams((80 * dp).toInt(), (36 * dp).toInt())
        }
        speedGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        ctrlRow.addView(playPauseBtn); ctrlRow.addView(speedGroup)

        panel.addView(topRow)
        panel.addView(frameInfoText)
        panel.addView(phaseBar)
        panel.addView(seekBar)
        panel.addView(ctrlRow)
        root.addView(panel)

        // ── Плавающая карточка советов текущей фазы ─────────────────────────
        // Полупрозрачная, не перекрывает видео полностью; тап — сжать/развернуть.
        coachHeader = TextView(this).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, (24 * dp).toInt(), 0)
        }
        coachToggle = TextView(this).apply {
            text = "▾"; setTextColor(Color.WHITE); textSize = 13f
            gravity = Gravity.END
        }
        val coachHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(coachHeader, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(coachToggle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        coachContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        coachCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20f * dp
                setColor(Color.argb(0xE6, 20, 20, 24))
                setStroke((1 * dp).toInt(), Color.argb(0x55, 255, 255, 255))
            }
            addView(coachHeaderRow)
            addView(coachContent)
            visibility = android.view.View.GONE
            setOnClickListener {
                coachExpanded = !coachExpanded
                val cur = lastCoachPhase; lastCoachPhase = null
                cur?.let { updateCoachCard(it) }
            }
        }
        // Позиционируем карточку слева над панелью управления
        val coachLp = FrameLayout.LayoutParams(
            (280 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.BOTTOM
        ).also {
            it.leftMargin = (12 * dp).toInt()
            it.bottomMargin = (232 * dp).toInt()   // выше панели управления
        }
        coachCard.layoutParams = coachLp
        root.addView(coachCard)

        setContentView(root)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(syncRunnable)
        if (::player.isInitialized) player.release()
    }

    companion object {
        fun start(context: Context, input: AnalysisInputData) {
            AnalysisInput.value = input
            context.startActivity(Intent(context, AnalysisActivity::class.java))
        }
    }
}
