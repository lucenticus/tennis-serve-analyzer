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
    // Компактная строка совета: показывает ТОЛЬКО замечание текущей фазы (или скрыта).
    // adviceText — сам текст, adviceBadge — отдельная "таблетка" со счётчиком остальных
    // замечаний этой фазы (раньше "+N" было приклеено к тексту и переносилось на новую
    // строку рваным куском — теперь это отдельный визуальный элемент).
    private lateinit var adviceRow: LinearLayout
    private lateinit var adviceText: TextView
    private lateinit var adviceBadge: TextView
    // Карточка "Главное" — 1-2 приоритетных совета по всему видео (агрегированы по всем
    // подачам), видна постоянно, не зависит от того, где сейчас стоит плейхед. До этой
    // правки такой сводки не было вообще: ServeAnalyzer.generateAdvice() её считал, но
    // результат просто выбрасывался (val (metrics, _) = ...), а пользователю показывались
    // только разрозненные замечания текущей фазы при скраббинге.
    private lateinit var focusCard: LinearLayout
    // Основная нижняя панель (можно скрыть тапом по видео)
    private lateinit var chromePanel: LinearLayout
    private var chromeVisible = true
    // Разбор по фазам (лукап при смене фазы). Значения уже агрегированы по стабильному
    // ключу замечания (см. Issue.key в PhaseAnalyzer) — одно и то же замечание с разных
    // подач одного видео схлопнуто в одну строку со счётчиком, а не повторяется N раз
    // с чуть разными углами.
    private var phaseIssues: Map<ServePhase, List<AggIssue>> = emptyMap()
    private var topPriorities: List<TopPriority> = emptyList()
    private var totalServes: Int = 1
    private var lastCoachPhase: ServePhase? = null
    private lateinit var phaseBar: PhaseTimelineView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseBtn: Button
    private lateinit var saveToGalleryBtn: ImageButton
    private lateinit var speedSelector: SpeedSelector

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
        // Советы уровня "вся подача" (ServeAnalyzer.generateAdvice) — раньше считались и
        // тут же выбрасывались (val (metrics, _) = ...). Теперь собираем их с каждой
        // подачи в видео, чтобы построить сводку "над чем работать в первую очередь".
        val adviceAcc = mutableListOf<ServeAdvice>()

        if (windows.size <= 1) {
            val (metrics, advice) = ServeAnalyzer.analyze(applicationContext, frames, isLeftHanded)
            val report = PhaseAnalyzer.analyze(applicationContext, frames, phases, isLeftHanded)
            scoreText.text = getString(R.string.analysis_score, metrics.overallScore.toInt())
            for (pr in report.phases) bucket.getOrPut(pr.phase) { mutableListOf() }.add(pr)
            adviceAcc += advice
            totalServes = 1
        } else {
            val scores = mutableListOf<Int>()
            for ((_, window) in windows.withIndex()) {
                val sub = frames.filter { it.timestampMs in window.first..window.second }
                if (sub.size < 3) continue
                val subPhases = phases.filter { it.timeMs in window.first..window.second }
                val (m, advice) = ServeAnalyzer.analyze(applicationContext, sub, isLeftHanded)
                scores.add(m.overallScore.toInt())
                val rep = PhaseAnalyzer.analyze(applicationContext, sub, subPhases, isLeftHanded)
                for (pr in rep.phases) bucket.getOrPut(pr.phase) { mutableListOf() }.add(pr)
                adviceAcc += advice
            }
            val avg = if (scores.isEmpty()) 0 else scores.average().toInt()
            scoreText.text = getString(R.string.analysis_multi_summary, scores.size, avg)
            totalServes = scores.size.coerceAtLeast(1)
        }

        // Топ-приоритеты на всё видео: группируем по тексту совета (сами тексты уже
        // не содержат чисел — там нет "поправь угол на N°", это советы верхнего уровня
        // вроде "выпрями руку"), считаем на скольких подачах каждый всплыл, сортируем
        // по важности (priority: 1 — самое важное) и берём 2 самых частых/важных.
        topPriorities = adviceAcc.groupBy { it.textRu }
            .map { (text, group) -> TopPriority(text, group.minOf { it.priority }, group.size) }
            .sortedWith(compareBy({ it.priority }, { -it.count }))
            .take(2)

        // Мержим по фазе: группируем по стабильному ключу замечания (Issue.key), а не по
        // готовому тексту — иначе "прямой локоть 166°" и "прямой локоть 159°" с разных
        // подач того же видео считаются разными строками и не схлопываются в одну.
        phaseIssues = bucket.mapValues { (_, reps) ->
            reps.flatMap { it.issues }
                .groupBy { it.key }
                .map { (_, group) -> AggIssue(text = group.first().text, count = group.size) }
                .sortedByDescending { it.count }
        }
        lastCoachPhase = null
        updateFocusCard()
        updateCoachCard(phaseAt(0L))
    }

    /**
     * Карточка "Главное" — 1-2 совета, актуальных для всего видео (не для текущей фазы),
     * с пометкой на скольких подачах они встретились, если подач несколько. Показывается
     * один раз после анализа и не меняется при скраббинге — это отличает её от adviceRow,
     * который зависит от текущей фазы.
     */
    private fun updateFocusCard() {
        focusCard.removeAllViews()
        if (topPriorities.isEmpty()) {
            focusCard.visibility = android.view.View.GONE
            return
        }
        focusCard.visibility = android.view.View.VISIBLE
        val dp = resources.displayMetrics.density
        val title = TextView(this).apply {
            text = getString(R.string.focus_title)
            setTextColor(Color.rgb(124, 179, 66))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
        }
        focusCard.addView(title)
        for ((i, p) in topPriorities.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (3 * dp).toInt(), 0, 0)
            }
            val text = TextView(this).apply {
                this.text = "${i + 1}. ${p.text}"
                setTextColor(Color.WHITE)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(text)
            // Частота показываем только когда это реально информативно: подач больше одной
            // и совет всплыл не на каждой (иначе "6 из 6" — просто шум).
            if (totalServes > 1 && p.count < totalServes) {
                row.addView(TextView(this).apply {
                    this.text = getString(R.string.focus_frequency, p.count, totalServes)
                    setTextColor(Color.rgb(150, 150, 150))
                    textSize = 12f
                    setPadding((6 * dp).toInt(), 0, 0, 0)
                })
            }
            focusCard.addView(row)
        }
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

    /**
     * Обновляет строку совета: только замечания текущей фазы (без «хорошо»), уже
     * агрегированные по всем подачам (см. phaseIssues). Если замечаний нет — строка
     * скрыта, видео чистое. Тап по строке показывает диалог со всеми замечаниями.
     */
    private fun updateCoachCard(phase: ServePhase) {
        if (phase == lastCoachPhase) return
        lastCoachPhase = phase
        val issues = phaseIssues[phase].orEmpty()
        val top = issues.firstOrNull()
        if (top == null) {
            adviceRow.visibility = android.view.View.GONE
            return
        }
        adviceText.text = "⚠ ${top.text}"
        val extra = issues.size - 1
        if (extra > 0) {
            adviceBadge.text = "+$extra"
            adviceBadge.visibility = android.view.View.VISIBLE
        } else {
            adviceBadge.visibility = android.view.View.GONE
        }
        adviceRow.visibility = android.view.View.VISIBLE
    }

    /**
     * Диалог со всеми замечаниями по всем фазам (по тапу на adviceRow). Раньше это был
     * plain AlertDialog.setMessage() с плоским списком строк — для одного и того же
     * замечания на разных подачах ("прямой локоть 166°", "...159°", "...163°", "...161°")
     * это давало 4 почти одинаковые строки подряд. Теперь: кастомный вид с "Главным"
     * сверху и по фазам — каждое замечание один раз, со счётчиком "×N" если оно
     * встретилось на нескольких подачах.
     */
    private fun showAllIssuesDialog() {
        val order = listOf(
            ServePhase.READY_STANCE, ServePhase.TOSS, ServePhase.TROPHY,
            ServePhase.BACKSCRATCH, ServePhase.ACCELERATION, ServePhase.CONTACT,
            ServePhase.FOLLOW_THROUGH
        )
        if (topPriorities.isEmpty() && phaseIssues.values.all { it.isEmpty() }) return

        val dp = resources.displayMetrics.density
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
        }

        fun sectionHeader(text: String, color: Int) {
            content.addView(TextView(this).apply {
                this.text = text
                setTextColor(color)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.03f
                setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
            })
        }

        fun issueRow(text: String, count: Int) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            }
            row.addView(TextView(this).apply {
                this.text = "⚠ $text"
                setTextColor(Color.rgb(230, 230, 230))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (totalServes > 1 && count > 1) {
                row.addView(TextView(this).apply {
                    this.text = "×$count"
                    setTextColor(Color.rgb(150, 150, 150))
                    textSize = 12f
                    setPadding((6 * dp).toInt(), 0, 0, 0)
                })
            }
            content.addView(row)
        }

        // "Главное" — те же 1-2 совета, что и в focusCard, но в диалоге это скорее
        // напоминание, чем открытие: пользователь обычно уже увидел их в панели.
        if (topPriorities.isNotEmpty()) {
            sectionHeader(getString(R.string.focus_title), Color.rgb(124, 179, 66))
            for (p in topPriorities) issueRow(p.text, p.count)
        }

        for (ph in order) {
            val issues = phaseIssues[ph].orEmpty()
            if (issues.isEmpty()) continue
            sectionHeader(phaseName(ph), phaseColor(ph))
            for (i in issues) issueRow(i.text, i.count)
        }

        val scroll = ScrollView(this).apply { addView(content) }

        // Тёмная системная тема — без неё диалог открывался светлым поверх тёмного интерфейса
        // приложения, что давало резкий разрыв темы в момент чтения советов по технике.
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(scroll)
            .setPositiveButton("OK", null)
            .show()
    }

    /** Тап по видео — прячет/показывает нижнюю панель управления. */
    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        val target = if (chromeVisible) 1f else 0f
        chromePanel.animate().alpha(target).setDuration(150).withStartAction {
            if (chromeVisible) chromePanel.visibility = android.view.View.VISIBLE
        }.withEndAction {
            if (!chromeVisible) chromePanel.visibility = android.view.View.GONE
        }.start()
        // Строка совета тоже скрывается вместе с панелью
        adviceRow.animate().alpha(target).setDuration(150).start()
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
        speedSelector.setSpeeds(speeds, initial = 0.3f)
        speedSelector.onSelect = { sp ->
            player.playbackParameters = player.playbackParameters.withSpeed(sp)
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

        // Нижняя панель — максимально компактная, полупрозрачная. Тап по видео скрывает.
        chromePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(0xB8, 0, 0, 0))
            }
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        // Верхняя строка: оценка + фаза + сохранение
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scoreText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        phaseLabel = TextView(this).apply {
            setTextColor(Color.CYAN); textSize = 12f; gravity = Gravity.END
            setPadding(0, 0, (8 * dp).toInt(), 0)
        }
        saveToGalleryBtn = ImageButton(this).apply {
            // Векторная иконка вместо текстового символа-стрелки — метафора "сохранить
            // на диск" (была дискета 💾) считывается не всеми, особенно младшей
            // аудиторией, которая физически не застала дискеты, а стрелка вниз читается
            // как "сохранить/экспортировать" интуитивнее. Именно векторная иконка, а не
            // Unicode-глиф: на живом устройстве (Galaxy S25 Ultra) системный шрифт этой
            // кнопки не рисовал ни эмодзи-стрелку (⬇, U+2B07), ни обычную стрелку
            // (↓, U+2193) — обе показывались как "тофу"-полоска.
            setImageResource(R.drawable.ic_download)
            contentDescription = getString(R.string.export_save_button)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (32 * dp).toInt())
        }
        topRow.addView(scoreText); topRow.addView(phaseLabel); topRow.addView(saveToGalleryBtn)

        // Заглушка frameInfoText — оставляем для совместимости, но скрыта (детализация ушла в диалог)
        frameInfoText = TextView(this).apply {
            visibility = android.view.View.GONE
        }

        // Карточка "Главное" — 1-2 приоритетных совета на всё видео, постоянно видна
        // (не зависит от текущей фазы/позиции скраббера). Лёгкий акцентный фон отделяет
        // её от phase-специфичной строки совета ниже, чтобы не путать "что важнее всего
        // в этом видео" с "что не так конкретно в этом кадре".
        focusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(0x26, 124, 179, 66))
                cornerRadius = 10 * dp
            }
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (6 * dp).toInt()
            layoutParams = lp
            visibility = android.view.View.GONE
        }

        // Компактная строка совета текущей фазы: текст + отдельная "таблетка"-счётчик
        // остальных замечаний этой фазы (раньше "+N" склеивался с текстом и переносился
        // рваным куском на новую строку).
        adviceText = TextView(this).apply {
            setTextColor(Color.rgb(255, 200, 60))
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        adviceBadge = TextView(this).apply {
            setTextColor(Color.rgb(255, 200, 60))
            textSize = 11f
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(0x33, 255, 200, 60))
                cornerRadius = 20 * dp
            }
            setPadding((7 * dp).toInt(), (2 * dp).toInt(), (7 * dp).toInt(), (2 * dp).toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = (6 * dp).toInt()
            lp.gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp
            visibility = android.view.View.GONE
        }
        adviceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            visibility = android.view.View.GONE
            setOnClickListener { showAllIssuesDialog() }
            addView(adviceText)
            addView(adviceBadge)
        }

        // Таймлайн фаз (уменьшен с 80dp до 56dp)
        phaseBar = PhaseTimelineView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (56 * dp).toInt()
            )
        }

        // Один ряд: play/pause + seekBar + скорость
        val scrubRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playPauseBtn = Button(this).apply {
            text = "⏸"; setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (36 * dp).toInt())
        }
        seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        speedSelector = SpeedSelector(this)
        scrubRow.addView(playPauseBtn); scrubRow.addView(seekBar)

        // Speed selector — центрируется под скруббером
        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (6 * dp).toInt(), 0, 0)
        }
        speedRow.addView(speedSelector)

        chromePanel.addView(topRow)
        chromePanel.addView(focusCard)
        chromePanel.addView(adviceRow)
        chromePanel.addView(phaseBar)
        chromePanel.addView(scrubRow)
        chromePanel.addView(speedRow)
        root.addView(chromePanel)

        // Тап по видео (не по панели) — скрыть/показать панель
        poseOverlay.setOnClickListener { toggleChrome() }

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

/** Замечание одной фазы, схлопнутое по [Issue.key]: [text] — представитель, [count] — на скольких подачах встретилось. */
private data class AggIssue(val text: String, val count: Int)

/** Один из 1-2 приоритетных советов по всему видео: [count] — на скольких подачах из [ServeAdvice] он всплыл. */
private data class TopPriority(val text: String, val priority: Int, val count: Int)
