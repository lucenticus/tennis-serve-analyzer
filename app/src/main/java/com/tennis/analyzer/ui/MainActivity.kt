package com.tennis.analyzer.ui

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.delay
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tennis.analyzer.analysis.ServeAnalyzer
import com.tennis.analyzer.analysis.VideoPoseAnalyzer
import com.tennis.analyzer.camera.CameraManager
import com.tennis.analyzer.camera.ServeRecorder
import com.tennis.analyzer.data.AnalysisInput
import com.tennis.analyzer.data.AnalysisInputData
import com.tennis.analyzer.data.PhaseMarker
import com.tennis.analyzer.detection.ServePhase
import com.tennis.analyzer.detection.ServePhaseDetector
import com.tennis.analyzer.detection.ServeEvent
import com.tennis.analyzer.feedback.VoiceFeedback
import com.tennis.analyzer.pose.DetectedObject
import com.tennis.analyzer.pose.HandedLandmarks
import com.tennis.analyzer.pose.LandmarkIndex
import com.tennis.analyzer.pose.PoseDetector
import com.tennis.analyzer.pose.PoseFrame
import kotlinx.coroutines.*
import kotlin.math.sqrt

/** Режимы приложения, выбираются на стартовом экране. */
enum class AppMode { ANALYSIS, REALTIME }

class MainActivity : ComponentActivity() {

    private lateinit var voice: VoiceFeedback
    private lateinit var poseDetector: PoseDetector
    private lateinit var cameraManager: CameraManager
    private lateinit var serveRecorder: ServeRecorder
    private lateinit var videoAnalyzer: VideoPoseAnalyzer

    private val prefs by lazy { getSharedPreferences("tennis_prefs", MODE_PRIVATE) }

    private var previewView: PreviewView? = null
    private var isLeftHanded = false
    private var analyzerReady = false

    // Auto-record state (accessed from both compose and pose callback)
    private var autoRecordActive = false
    private var currentlyRecording = false
    private var tossWristWasBelow = false
    private var lastPoseDetectMs = 0L

    // Recent serve scores for session chart
    private val recentScores = mutableStateListOf<Float>()

    // Real-time tracking mode (live phase detection + voice coaching)
    private var realtimeActive = false
    private var realtimeDetector: ServePhaseDetector? = null
    private var skeletonOverlay: SkeletonOverlay? = null
    private var lastRealtimePoseMs = 0L

    // Помощь кадрирования для часов (где встать, влезет ли подброс)
    private var framingActive = false
    private var lastFramingPoseMs = 0L
    private var lastFramingCode = ""
    private val realtimePhase = mutableStateOf(ServePhase.IDLE)
    private val realtimeServeCount = mutableStateOf(0)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.CAMERA] == true) startCamera()
    }

    // Колбэк для пикера видео из галереи
    private var onVideoPickedCallback: ((Uri) -> Unit)? = null
    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { onVideoPickedCallback?.invoke(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isLeftHanded = prefs.getBoolean("isLeftHanded", false)

        serveRecorder = ServeRecorder(this)
        voice = VoiceFeedback(this)
        voice.init {}
        videoAnalyzer = VideoPoseAnalyzer(this)
        scope.launch(Dispatchers.IO) {
            videoAnalyzer.setup()
            analyzerReady = true
            Log.i("MainActivity", "VideoPoseAnalyzer ready")
        }

        setContent {
            var isRecording by remember { mutableStateOf(false) }
            var isAnalyzing by remember { mutableStateOf(false) }
            var analyzeProgress by remember { mutableStateOf(0f) }
            var isFrontCamera by remember { mutableStateOf(false) }
            var leftHanded by remember { mutableStateOf(isLeftHanded) }
            var autoRecord by remember { mutableStateOf(false) }
            var mode by remember { mutableStateOf(AppMode.ANALYSIS) }
            var showOnboarding by remember { mutableStateOf(!prefs.getBoolean("onboarding_done", false)) }

            // Включаем/выключаем реал-тайм трекинг при смене режима
            LaunchedEffect(mode) {
                if (mode == AppMode.REALTIME) startRealtime() else stopRealtime()
            }

            Box(Modifier.fillMaxSize()) {
                // Camera preview — скрываем во время анализа
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also { pv ->
                            previewView = pv
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize().alpha(if (isAnalyzing) 0f else 1f)
                )

                // Чёрный фон во время анализа
                if (isAnalyzing) {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                }

                // Скелет позы + подсказки по фазам поверх камеры (реал-тайм)
                if (mode == AppMode.REALTIME && !isAnalyzing) {
                    AndroidView(
                        factory = { ctx -> SkeletonOverlay(ctx).also { skeletonOverlay = it } },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Переключатель режимов (сверху по центру)
                if (!isRecording && !isAnalyzing) {
                    ModeSelector(
                        mode = mode,
                        onSelect = { mode = it },
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                    )
                }

                // Верхняя панель: кнопки камеры и руки
                Column(
                    Modifier.align(Alignment.TopEnd).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    CameraToggleBtn(isFrontCamera) {
                        isFrontCamera = !isFrontCamera
                        cameraManager.switchCamera()
                    }
                    HandednessBtn(leftHanded) {
                        leftHanded = !leftHanded
                        isLeftHanded = leftHanded
                        prefs.edit().putBoolean("isLeftHanded", leftHanded).apply()
                    }
                }

                // Индикатор записи (только когда идёт запись)
                if (isRecording) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xAAF44336),
                        modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                    ) {
                        Text(
                            "● Запись...",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            color = Color.White, fontSize = 18.sp
                        )
                    }
                }

                // Центр: прогресс анализа
                if (isAnalyzing) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xDD000000)
                        ) {
                            Column(
                                Modifier.padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text("Анализирую подачу...", color = Color.White, fontSize = 18.sp)
                                LinearProgressIndicator(
                                    progress = { analyzeProgress },
                                    modifier = Modifier.width(220.dp)
                                )
                                Text(
                                    "${(analyzeProgress * 100).toInt()}%",
                                    color = Color.White, fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                // Нижняя панель — кнопки записи и галереи (режим анализа)
                if (!isAnalyzing && mode == AppMode.ANALYSIS) {
                    Column(
                        Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Статистика текущей сессии
                        if (recentScores.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Black.copy(alpha = 0.55f)
                            ) {
                                Text(
                                    text = "Подач: ${recentScores.size}   Средняя: ${recentScores.average().toInt()}   Последняя: ${recentScores.last().toInt()}",
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                    color = Color.White, fontSize = 14.sp
                                )
                            }
                        }

                        if (isRecording) {
                            Button(
                                onClick = {
                                    isRecording = false
                                    currentlyRecording = false
                                    serveRecorder.stopAndWait { file ->
                                        launchAnalysis(
                                            uri = android.net.Uri.fromFile(file),
                                            setAnalyzing = { isAnalyzing = it },
                                            setProgress = { analyzeProgress = it }
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                                shape = RoundedCornerShape(28.dp),
                                modifier = Modifier.height(64.dp).widthIn(min = 220.dp)
                            ) {
                                Text("⏹  Стоп и анализ", fontSize = 20.sp)
                            }
                        } else {
                            // Авто-запись toggle
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Switch(
                                    checked = autoRecord,
                                    onCheckedChange = {
                                        autoRecord = it
                                        autoRecordActive = it
                                        tossWristWasBelow = false
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF4CAF50),
                                        checkedTrackColor = Color(0xFF4CAF50).copy(alpha = 0.5f)
                                    )
                                )
                                Text(
                                    text = if (autoRecord) "Авто-запись: вкл" else "Авто-запись: выкл",
                                    color = Color.White, fontSize = 14.sp
                                )
                            }

                            Button(
                                onClick = {
                                    isRecording = true
                                    currentlyRecording = true
                                    serveRecorder.startRecording { _, _ -> }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                shape = RoundedCornerShape(28.dp),
                                modifier = Modifier.height(64.dp).widthIn(min = 220.dp)
                            ) {
                                Text("● Записать подачу", fontSize = 20.sp)
                            }

                            // Загрузить из галереи
                            OutlinedButton(
                                onClick = {
                                    onVideoPickedCallback = { uri ->
                                        launchAnalysis(
                                            uri = uri,
                                            setAnalyzing = { isAnalyzing = it },
                                            setProgress = { analyzeProgress = it }
                                        )
                                    }
                                    videoPicker.launch("video/*")
                                },
                                shape = RoundedCornerShape(28.dp),
                                modifier = Modifier.height(48.dp).widthIn(min = 220.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) {
                                Text("📂  Загрузить из галереи", fontSize = 16.sp)
                            }
                        }

                        // Сигнал авто-записи — если auto-record включён и идёт запись (поднята рука)
                        if (autoRecord && isRecording) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xAAF44336)
                            ) {
                                Text(
                                    "● Авто-запись...",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    color = Color.White, fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                // Реал-тайм режим — живой трекинг фаз + подсказки
                if (!isAnalyzing && mode == AppMode.REALTIME) {
                    RealtimePanel(
                        phase = realtimePhase.value,
                        serveCount = realtimeServeCount.value,
                        lastScore = recentScores.lastOrNull(),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                    )
                }

                // Sync compose state with non-compose auto-record logic
                LaunchedEffect(isRecording) { currentlyRecording = isRecording }
                LaunchedEffect(Unit) {
                    onAutoRecordStart = {
                        if (!isRecording && autoRecordActive) {
                            isRecording = true
                            currentlyRecording = true
                            serveRecorder.startRecording { _, _ -> }
                        }
                    }
                }
                // Команды с часов (Wear OS пульт): START/STOP записи, MODE переключение режима
                LaunchedEffect(Unit) {
                    com.tennis.analyzer.wear.WearLink.onCommand = { path, payload ->
                        scope.launch(Dispatchers.Main) {
                            when (path) {
                                com.tennis.analyzer.wear.WearLink.PATH_MODE ->
                                    if (!isRecording && !isAnalyzing) {
                                        mode = if (payload == "REALTIME") AppMode.REALTIME else AppMode.ANALYSIS
                                    }
                                com.tennis.analyzer.wear.WearLink.PATH_START ->
                                    if (!isRecording && !isAnalyzing && mode == AppMode.ANALYSIS) {
                                        isRecording = true
                                        currentlyRecording = true
                                        serveRecorder.startRecording { _, _ -> }
                                    }
                                com.tennis.analyzer.wear.WearLink.PATH_STOP ->
                                    if (isRecording) {
                                        isRecording = false
                                        currentlyRecording = false
                                        serveRecorder.stopAndWait { file ->
                                            launchAnalysis(
                                                uri = android.net.Uri.fromFile(file),
                                                setAnalyzing = { isAnalyzing = it },
                                                setProgress = { analyzeProgress = it }
                                            )
                                        }
                                    }
                            }
                        }
                    }
                }
                // Помощь кадрирования активна, когда стоишь в «Анализ» и ещё не пишешь
                LaunchedEffect(mode, isRecording, isAnalyzing) {
                    framingActive = (mode == AppMode.ANALYSIS && !isRecording && !isAnalyzing)
                    if (!framingActive) lastFramingCode = ""
                }

                // Обучалка при первом запуске (поверх всего)
                if (showOnboarding) {
                    OnboardingOverlay(onDone = {
                        showOnboarding = false
                        prefs.edit().putBoolean("onboarding_done", true).apply()
                    })
                }
            }
        }
    }

    private fun launchAnalysis(
        uri: Uri,
        setAnalyzing: (Boolean) -> Unit,
        setProgress: (Float) -> Unit
    ) {
        scope.launch {
            setAnalyzing(true)
            setProgress(0f)
            while (!analyzerReady) delay(200)

            // Копируем URI во временный файл (MediaMetadataRetriever требует путь или FileDescriptor)
            val tmpFile = java.io.File(cacheDir, "gallery_serve_${System.currentTimeMillis()}.mp4")
            withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { input ->
                    tmpFile.outputStream().use { output -> input.copyTo(output) }
                }
            }

            videoAnalyzer.isLeftHanded = isLeftHanded
            com.tennis.analyzer.wear.WearLink.sendProgress(this@MainActivity, 0)
            val result = withContext(Dispatchers.IO) {
                videoAnalyzer.analyze(tmpFile) { done, total ->
                    val frac = done.toFloat() / total.coerceAtLeast(1)
                    scope.launch(Dispatchers.Main) {
                        setProgress(frac)
                        com.tennis.analyzer.wear.WearLink.sendProgress(this@MainActivity, (frac * 100).toInt())
                    }
                }
            }
            setAnalyzing(false)
            launchAnalysisActivity(tmpFile, result)
        }
    }

    private fun launchAnalysisActivity(videoFile: java.io.File, result: com.tennis.analyzer.analysis.VideoAnalysisResult) {
        // Фазы каждой подачи отдельно + IDLE-разрывы между ними
        val phases = detectAllServePhases(result.frames, result.serveContacts)

        // Счёт сессии — добавляем оценку каждой подачи; запоминаем последнюю для часов
        var lastScore: Int? = null
        var lastTip: String? = null
        for (window in serveWindows(result.frames, result.serveContacts)) {
            val sub = result.frames.filter { it.timestampMs in window.first..window.second }
            if (sub.size < 3) continue
            val (metrics, advice) = ServeAnalyzer.analyze(sub, isLeftHanded)
            recentScores.add(metrics.overallScore)
            lastScore = metrics.overallScore.toInt()
            lastTip = advice.firstOrNull()?.textRu
        }
        while (recentScores.size > 15) recentScores.removeAt(0)

        // Всегда шлём итог на часы — иначе экран «Анализ» на часах висит вечно.
        // Если подача не распозналась (lastScore null) → score=-1.
        com.tennis.analyzer.wear.WearLink.sendResult(this, lastScore ?: -1, lastTip)

        AnalysisActivity.start(
            this@MainActivity,
            AnalysisInputData(
                videoFile = videoFile,
                frames = result.frames,
                videoDurationMs = result.videoDurationMs,
                videoWidth = result.videoWidth,
                videoHeight = result.videoHeight,
                phases = phases,
                serveContacts = result.serveContacts,
                isLeftHanded = isLeftHanded
            )
        )
    }

    /**
     * Делит таймлайн на окна подач: каждое окно — [серединаДоПредыдущей .. серединаДоСледующей].
     * Так фазы одной подачи не залезают на соседнюю.
     */
    private fun serveWindows(frames: List<PoseFrame>, contacts: List<Long>): List<Pair<Long, Long>> {
        if (frames.isEmpty()) return emptyList()
        val firstMs = frames.first().timestampMs
        val lastMs  = frames.last().timestampMs
        val cs = contacts.ifEmpty { return emptyList() }
        return cs.mapIndexed { i, c ->
            val start = if (i == 0) firstMs else (cs[i - 1] + c) / 2
            val end   = if (i == cs.lastIndex) lastMs else (c + cs[i + 1]) / 2
            start to end
        }
    }

    /** Фазы для всех подач: для каждого окна — kinematic-детектор, между подачами IDLE. */
    private fun detectAllServePhases(frames: List<PoseFrame>, contacts: List<Long>): List<PhaseMarker> {
        if (frames.size < 3) return listOf(PhaseMarker(ServePhase.READY_STANCE, 0L))
        val windows = serveWindows(frames, contacts)
        if (windows.size <= 1) return detectPhasesFromFrames(frames)

        val markers = mutableListOf<PhaseMarker>()
        for ((i, window) in windows.withIndex()) {
            val sub = frames.filter { it.timestampMs in window.first..window.second }
            if (sub.size < 3) continue
            markers.addAll(detectPhasesFromFrames(sub))
            // После каждой подачи (кроме последней) — нейтральный IDLE-разрыв до следующей
            if (i < windows.lastIndex) {
                val contact = contacts.getOrNull(i) ?: window.second
                val idleAt = (contact + 800L).coerceIn(window.first + 1, window.second)
                markers.add(PhaseMarker(ServePhase.IDLE, idleAt))
            }
        }
        return markers
    }

    private fun startCamera() {
        poseDetector = PoseDetector(
            context = this,
            onResult = { frame -> onLivePoseResult(frame) },
            onError = { Log.w("MainActivity", "PoseDetector: $it") }
        )
        scope.launch(Dispatchers.IO) { poseDetector.setup() }

        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this,
            previewView = previewView!!,
            serveRecorder = serveRecorder,
            onFrame = { bitmap, ts ->
                val now = System.currentTimeMillis()
                when {
                    // Реал-тайм режим — высокая частота для детекции фаз
                    realtimeActive && now - lastRealtimePoseMs > 50L -> {
                        lastRealtimePoseMs = now
                        poseDetector.detectAsync(bitmap, ts)
                    }
                    // Авто-запись — реже, достаточно поймать подброс
                    autoRecordActive && now - lastPoseDetectMs > 300L -> {
                        lastPoseDetectMs = now
                        poseDetector.detectAsync(bitmap, ts)
                    }
                    // Помощь кадрирования (для часов) — пока стоишь и целишься
                    framingActive && now - lastFramingPoseMs > 250L -> {
                        lastFramingPoseMs = now
                        poseDetector.detectAsync(bitmap, ts)
                    }
                }
            }
        ).also { it.start() }
    }

    // Called from compose to trigger auto-record start
    var onAutoRecordStart: (() -> Unit)? = null

    /**
     * Оценивает кадрирование для часов: видно ли игрока целиком и хватит ли места
     * сверху для подброса (мяч уходит высоко над головой — нужно «небо» в кадре).
     */
    private fun computeFraming(frame: com.tennis.analyzer.pose.PoseFrame): String {
        val lm = frame.landmarks
        if (lm.size < 29) return com.tennis.analyzer.wear.WearLink.FRAME_NO_PERSON
        val nose = lm.getOrNull(0) ?: return com.tennis.analyzer.wear.WearLink.FRAME_NO_PERSON
        val lSh = lm.getOrNull(11); val rSh = lm.getOrNull(12)
        val lHip = lm.getOrNull(23); val rHip = lm.getOrNull(24)
        val lAnk = lm.getOrNull(27); val rAnk = lm.getOrNull(28)
        if (lSh == null || rSh == null || lAnk == null || rAnk == null)
            return com.tennis.analyzer.wear.WearLink.FRAME_NO_PERSON

        val shY  = (lSh.y + rSh.y) / 2f
        val hipY = ((lHip?.y ?: shY) + (rHip?.y ?: shY)) / 2f
        val headY  = nose.y
        val ankleY = maxOf(lAnk.y, rAnk.y)        // самая нижняя стопа
        val bodyHeight = ankleY - headY

        // Похоже ли на стоящего человека (голова выше плеч выше бёдер)
        if (bodyHeight < 0.15f || headY > shY || shY > hipY)
            return com.tennis.analyzer.wear.WearLink.FRAME_NO_PERSON

        return when {
            ankleY > 0.98f || bodyHeight > 0.92f -> com.tennis.analyzer.wear.WearLink.FRAME_MOVE_BACK
            bodyHeight < 0.35f                   -> com.tennis.analyzer.wear.WearLink.FRAME_MOVE_CLOSER
            // Места над головой (headY от верха кадра) мало для подброса
            headY < 0.30f                        -> com.tennis.analyzer.wear.WearLink.FRAME_LOW_TOSS
            else                                 -> com.tennis.analyzer.wear.WearLink.FRAME_OK
        }
    }

    // ── Реал-тайм режим: живой детектор фаз + голосовые подсказки ──────────────
    private fun startRealtime() {
        realtimeServeCount.value = 0
        realtimePhase.value = ServePhase.IDLE
        realtimeDetector = ServePhaseDetector(
            onServeCompleted = { event -> onRealtimeServeCompleted(event) },
            onPhaseChanged = { phase ->
                scope.launch(Dispatchers.Main) { realtimePhase.value = phase }
            },
            isLeftHanded = isLeftHanded
        )
        realtimeActive = true
        // Не гасим/не блокируем экран во время тренировки
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        voice.speakImmediate("Режим тренировки. Выполняй подачу.")
    }

    private fun stopRealtime() {
        realtimeActive = false
        realtimeDetector = null
        skeletonOverlay = null
        realtimePhase.value = ServePhase.IDLE
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun onRealtimeServeCompleted(event: ServeEvent) {
        scope.launch {
            realtimeServeCount.value += 1
            val (metrics, advice) = withContext(Dispatchers.Default) {
                ServeAnalyzer.analyze(event.frames, isLeftHanded)
            }
            recentScores.add(metrics.overallScore)
            while (recentScores.size > 15) recentScores.removeAt(0)
            // Карточка совета + оценка на экране
            skeletonOverlay?.showScore(metrics.overallScore)
            if (advice.isNotEmpty()) skeletonOverlay?.showAdvice(advice.take(2).map { it.textRu })
            // Голосовая подсказка после подачи: совет, иначе — оценка
            if (advice.isNotEmpty()) voice.speak(advice) else voice.speakScore(metrics.overallScore)
            // Дублируем результат на часы
            com.tennis.analyzer.wear.WearLink.sendResult(
                this@MainActivity, metrics.overallScore.toInt(), advice.firstOrNull()?.textRu
            )
        }
    }

    private fun onLivePoseResult(frame: com.tennis.analyzer.pose.PoseFrame) {
        // Реал-тайм режим — кормим детектор фаз + рисуем скелет
        if (realtimeActive) {
            realtimeDetector?.process(frame)
            scope.launch(Dispatchers.Main) { skeletonOverlay?.update(frame, realtimePhase.value) }
            return
        }
        // Помощь кадрирования — считаем статус и шлём на часы (только при изменении)
        if (framingActive) {
            val code = computeFraming(frame)
            if (code != lastFramingCode) {
                lastFramingCode = code
                com.tennis.analyzer.wear.WearLink.sendFraming(this, code)
            }
            return
        }
        if (!autoRecordActive || currentlyRecording) return
        val hl = HandedLandmarks(isLeftHanded)
        val tossWrist    = frame.landmarks.getOrNull(hl.tossWrist)    ?: return
        val tossShoulder = frame.landmarks.getOrNull(hl.tossShoulder) ?: return

        val wristAboveShoulder = tossWrist.y < tossShoulder.y - 0.05f
        if (!wristAboveShoulder) {
            tossWristWasBelow = true
        } else if (tossWristWasBelow) {
            // Toss wrist crossed above shoulder — auto-start recording
            tossWristWasBelow = false
            Log.i("MainActivity", "Auto-record: toss detected, starting recording")
            scope.launch(Dispatchers.Main) {
                onAutoRecordStart?.invoke()
            }
        }
    }

    /**
     * Простой детектор фаз по готовым кадрам — используется только для разметки таймлайна.
     */
    /**
     * Кинематический детектор фаз — надёжнее порогового автомата.
     *
     * 1. ACCELERATION (удар) = кадр с пиковой скоростью запястья ракетки
     * 2. TROPHY          = кадр до удара где запястье тосса на максимальной высоте
     * 3. TOSS            = момент когда рука с мячом начинает подниматься выше плеча
     * 4. READY_STANCE    = начало видео до подброса
     * 5. FOLLOW_THROUGH  = сразу после удара
     */
    private fun detectPhasesFromFrames(frames: List<PoseFrame>): List<PhaseMarker> {
        if (frames.size < 3) return listOf(PhaseMarker(ServePhase.READY_STANCE, 0L))

        val hl = HandedLandmarks(isLeftHanded)

        // Центрированная скорость запястья ракетки для кадра idx
        fun racketVelocity(idx: Int): Float {
            if (idx < 1 || idx >= frames.size - 1) return 0f
            val prev = frames[idx - 1].landmarks.getOrNull(hl.racketWrist) ?: return 0f
            val next = frames[idx + 1].landmarks.getOrNull(hl.racketWrist) ?: return 0f
            val dtSec = (frames[idx + 1].timestampMs - frames[idx - 1].timestampMs)
                .coerceAtLeast(1L) / 1000f
            val dx = next.x - prev.x
            val dy = next.y - prev.y
            val dz = next.z - prev.z
            return sqrt(dx * dx + dy * dy + dz * dz) / dtSec
        }

        // Высота: ПРЕДПОЧИТАЕМ bbox ракетки (cy). Поза часто теряет запястье на быстром
        // свинге и ставит ложный «пик» на трофее (контакт детектился на ~0.2с раньше реального
        // удара). Ракетка же детектится надёжно. Запасной сигнал — запястье из позы (ry).
        fun ry(idx: Int): Float? = frames[idx].landmarks.getOrNull(hl.racketWrist)?.y
        fun rcy(idx: Int): Float? = frames[idx].objects
            .filter { it.classId == DetectedObject.CLASS_RACKET }
            .maxByOrNull { it.confidence }?.cy
        val racketCount = frames.indices.count { rcy(it) != null }
        val useRacket = racketCount >= 10
        fun h(idx: Int): Float? = if (useRacket) rcy(idx) else ry(idx)

        val velocities = frames.indices.map { racketVelocity(it) }
        val velPeakIdx = velocities.indices.maxByOrNull { velocities[it] }
            ?: return listOf(PhaseMarker(ServePhase.READY_STANCE, 0L))

        // 1. Удар = ВЫСШАЯ точка (мин высоты) ракетки/запястья во всём окне подачи.
        val withH = frames.indices.filter { h(it) != null }
        val minHIdx = withH.minByOrNull { h(it)!! } ?: velPeakIdx
        val hMin = h(minHIdx) ?: 0f
        val hMax = withH.mapNotNull { h(it) }.maxOrNull() ?: 1f
        // Почти нет движения по высоте — нормальной подачи нет, берём пик скорости
        val contactIdx = if (hMax - hMin < 0.08f) velPeakIdx else minHIdx
        val contactMs = frames[contactIdx].timestampMs

        // Диагностика: обе траектории высоты (ракетка cy и запястье y) вокруг удара
        run {
            val zone = frames.indices.filter { frames[it].timestampMs in (contactMs - 1500)..(contactMs + 300) }
            Log.i("KinematicDetector", "useRacket=$useRacket racketCov=$racketCount/${frames.size} contact=${contactMs}ms")
            Log.i("KinematicDetector", "racketCy traj: " +
                zone.joinToString(" ") { "${frames[it].timestampMs}:${rcy(it)?.let { v -> "%.3f".format(v) } ?: "-"}" })
            Log.i("KinematicDetector", "wristY traj: " +
                zone.joinToString(" ") { "${frames[it].timestampMs}:${"%.3f".format(ry(it) ?: -1f)}" })
        }

        fun idxInRange(loMs: Long, hiMs: Long) =
            frames.indices.filter { frames[it].timestampMs in loMs..hiMs && h(it) != null }

        // 2. Бэкскрэтч = САМАЯ НИЗКАЯ точка (макс высоты) в ~0.7с перед ударом — дно петли.
        val backscratchIdx = idxInRange(contactMs - 700, contactMs - 80).maxByOrNull { h(it)!! }
        val bsMs = backscratchIdx?.let { frames[it].timestampMs } ?: (contactMs - 250)
        val bsH  = backscratchIdx?.let { h(it)!! } ?: 1f

        // 3. Трофей = ВЫСШАЯ точка ДО бэкскрэтча — «загрузка» перед петлёй.
        val trophyIdx = (idxInRange(contactMs - 1400, bsMs - 50).minByOrNull { h(it)!! }
            ?: (0 until contactIdx).minByOrNull { frames[it].landmarks.getOrNull(hl.tossWrist)?.y ?: 1f }
            ?: (contactIdx / 2))

        // 4. Разгон = первый кадр после бэкскрэтча, где высота заметно пошла вверх (свинг).
        val accelSearchFrom = backscratchIdx ?: trophyIdx
        val accelerationIdx = (accelSearchFrom + 1 until contactIdx).firstOrNull { idx ->
            (h(idx) ?: 1f) <= bsH - 0.05f
        }

        // contactIdx остаётся как CONTACT (удар)

        // 5. Подброс — последний кадр ДО трофея где запястье тосса было ниже плеча
        val tossIdx = (0 until trophyIdx).lastOrNull { idx ->
            val tw = frames[idx].landmarks.getOrNull(hl.tossWrist)    ?: return@lastOrNull false
            val ts = frames[idx].landmarks.getOrNull(hl.tossShoulder) ?: return@lastOrNull false
            tw.y >= ts.y
        }?.plus(1) ?: 0

        Log.i("KinematicDetector",
            "toss@${frames[tossIdx].timestampMs} trophy@${frames[trophyIdx].timestampMs} " +
            "bs@${backscratchIdx?.let { frames[it].timestampMs }} " +
            "accel@${accelerationIdx?.let { frames[it].timestampMs }} " +
            "contact@${frames[contactIdx].timestampMs}")

        // Собираем маркеры по порядку
        val markers = mutableListOf<PhaseMarker>()
        markers.add(PhaseMarker(ServePhase.READY_STANCE, frames[0].timestampMs))
        if (tossIdx > 0)
            markers.add(PhaseMarker(ServePhase.TOSS, frames[tossIdx].timestampMs))
        if (trophyIdx > tossIdx)
            markers.add(PhaseMarker(ServePhase.TROPHY, frames[trophyIdx].timestampMs))
        if (backscratchIdx != null && backscratchIdx > trophyIdx)
            markers.add(PhaseMarker(ServePhase.BACKSCRATCH, frames[backscratchIdx].timestampMs))
        if (accelerationIdx != null && accelerationIdx > (backscratchIdx ?: trophyIdx))
            markers.add(PhaseMarker(ServePhase.ACCELERATION, frames[accelerationIdx].timestampMs))
        markers.add(PhaseMarker(ServePhase.CONTACT, frames[contactIdx].timestampMs))
        val followIdx = (contactIdx + 1).coerceAtMost(frames.size - 1)
        if (followIdx > contactIdx)
            markers.add(PhaseMarker(ServePhase.FOLLOW_THROUGH, frames[followIdx].timestampMs))

        return markers
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        if (::poseDetector.isInitialized) poseDetector.close()
        videoAnalyzer.close()
        voice.shutdown()
    }
}

@Composable
private fun OnboardingOverlay(onDone: () -> Unit) {
    data class Slide(val emoji: String, val title: String, val body: String)
    val slides = listOf(
        Slide("🎾", "Анализатор подачи",
            "Снимай свою подачу — получай разбор по фазам, момент удара и советы, как улучшить технику."),
        Slide("🔀", "Два режима",
            "«Анализ» — запись и детальный разбор. «Реал-тайм» — живые подсказки голосом во время тренировки. Переключай вверху по центру."),
        Slide("📹", "Как снимать",
            "Поставь телефон боком к корту, лучше на штатив. Оставь место сверху — чтобы подброс мяча влез в кадр. Жми запись на экране или с часов."),
        Slide("⌚", "Рука и часы",
            "Укажи рабочую руку кнопкой ✋ справа вверху. С Galaxy Watch можно запускать запись и видеть оценку с подсказкой прямо на руке.")
    )
    var step by remember { mutableStateOf(0) }
    val slide = slides[step]
    Box(
        Modifier.fillMaxSize().background(Color(0xF20A0A0A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(slide.emoji, fontSize = 56.sp)
            Text(slide.title, color = Color.White, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(slide.body, color = Color(0xFFCCCCCC), fontSize = 15.sp,
                textAlign = TextAlign.Center, lineHeight = 21.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                slides.indices.forEach { i ->
                    Box(Modifier.size(8.dp).clip(CircleShape)
                        .background(if (i == step) Color(0xFF7CB342) else Color(0xFF444444)))
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { if (step < slides.lastIndex) step++ else onDone() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7CB342)),
                modifier = Modifier.fillMaxWidth(0.7f).height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(if (step < slides.lastIndex) "Далее" else "Начать",
                    fontSize = 16.sp, color = Color.White)
            }
            if (step < slides.lastIndex) {
                Text("Пропустить", color = Color(0xFF888888), fontSize = 13.sp,
                    modifier = Modifier.clickable { onDone() })
            } else {
                Spacer(Modifier.height(19.dp))
            }
        }
    }
}

@Composable
private fun CameraToggleBtn(isFrontCamera: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
        modifier = Modifier.size(52.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(if (isFrontCamera) "🔙" else "🤳", fontSize = 22.sp)
        }
    }
}

@Composable
private fun HandednessBtn(isLeftHanded: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
        modifier = Modifier.size(52.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(if (isLeftHanded) "🤚" else "✋", fontSize = 22.sp)
        }
    }
}

// ── Реал-тайм режим: UI ────────────────────────────────────────────────────

@Composable
private fun ModeSelector(mode: AppMode, onSelect: (AppMode) -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(24.dp), color = Color.Black.copy(alpha = 0.6f), modifier = modifier) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ModeTab("Анализ", mode == AppMode.ANALYSIS) { onSelect(AppMode.ANALYSIS) }
            ModeTab("Реал-тайм", mode == AppMode.REALTIME) { onSelect(AppMode.REALTIME) }
        }
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) Color(0xFF1565C0) else Color.Transparent
    ) {
        Text(
            label, color = Color.White, fontSize = 15.sp,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun RealtimePanel(phase: ServePhase, serveCount: Int, lastScore: Float?, modifier: Modifier = Modifier) {
    // Фаза и подсказки рисует SkeletonOverlay поверх камеры; тут — только счётчик подач.
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.55f),
        modifier = modifier
    ) {
        Text(
            buildString {
                append("Подач: $serveCount")
                if (lastScore != null) append("   Последняя: ${lastScore.toInt()}")
            },
            color = Color.White, fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}
