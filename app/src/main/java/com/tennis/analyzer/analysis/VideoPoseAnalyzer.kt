package com.tennis.analyzer.analysis

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.tennis.analyzer.detection.TennisObjectDetector
import com.tennis.analyzer.pose.DetectedObject
import com.tennis.analyzer.pose.LandmarkSmoother
import com.tennis.analyzer.pose.PoseFrame
import com.tennis.analyzer.pose.PoseLandmark
import com.tennis.analyzer.pose.YoloPoseDetector
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class VideoAnalysisResult(
    val frames: List<PoseFrame>,
    val videoDurationMs: Long,
    val videoWidth: Int,
    val videoHeight: Int,
    /** Временны́е метки всех найденных подач. Если > 1 — нужен выбор пользователя. */
    val serveContacts: List<Long> = emptyList(),
    /** Кадры грубого прохода — кэшируются для файн-пасса после выбора подачи. */
    val coarseFrames: List<PoseFrame> = emptyList()
)

class VideoPoseAnalyzer(private val context: Context) {

    private val poseDetector   = YoloPoseDetector(context)
    private val objectDetector = TennisObjectDetector(context)
    // Отдельный поток для параллельного запуска pose + object detection в файн-пасе
    private val parallelExec   = Executors.newSingleThreadExecutor()

    /** Рабочая рука — нужна для seed ROI ракетки по запястью. Ставится перед analyze(). */
    var isLeftHanded = false

    fun setup() {
        if (!poseDetector.setup()) error("YoloPoseDetector init failed")
        objectDetector.setup()
    }

    /**
     * Грубый проход по всему видео → находит ВСЕ подачи → делает точный файн-пасс
     * вокруг каждой и возвращает единый таймлайн с [serveContacts] всех подач.
     */
    fun analyze(
        videoFile: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): VideoAnalysisResult {
        val coarseFrames = mutableListOf<PoseFrame>()
        var lastLandmarks: List<PoseLandmark>? = null
        // Когда была последняя РЕАЛЬНАЯ (не перенесённая) детекция — см. MAX_CARRY_MS.
        var lastRealMs: Long? = null

        val meta: VideoFrameExtractor.VideoMeta = VideoFrameExtractor.extract(videoFile, stepMs = 100L,
            onProgress = { d, t -> onProgress(d, t * 2) }
        ) { bitmap, timestampMs ->
            try {
                // Грубый проход: поза (NPU) + детекция объектов (CPU) на всей длине видео.
                // Запускаем оба параллельно — разные вычислительные блоки, время почти не складывается.
                val scaled = if (bitmap.width == 640 && bitmap.height == 640) bitmap
                             else Bitmap.createScaledBitmap(bitmap, 640, 640, true)

                val objectsFuture: Future<List<DetectedObject>> = parallelExec.submit(
                    Callable { objectDetector.detectPreScaled(scaled, timestampMs) }
                )
                // Не тащим landmarks дольше MAX_CARRY_MS без свежей детекции — без этого
                // единичный случайный ложный всплеск (например фон, похожий на человека)
                // переносился бы на ВСЕ последующие кадры до конца видео — "замороженный"
                // человек там, где его на самом деле нет. Короткая просадка/смаз реальной
                // подачи всё ещё бесшовно мостится (в пределах MAX_CARRY_MS).
                val withinCarryBudget = lastRealMs != null && timestampMs - lastRealMs!! < MAX_CARRY_MS
                val carryLandmarks = if (withinCarryBudget) lastLandmarks else null
                val poseResult = runYoloPose(scaled, timestampMs, carryLandmarks)
                val objects = objectsFuture.get()

                if (scaled !== bitmap) scaled.recycle()

                poseResult?.let { (lms, isNew) ->
                    coarseFrames.add(PoseFrame(lms, timestampMs, objects))
                    if (isNew) {
                        lastLandmarks = lms
                        lastRealMs = timestampMs
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame error at ${timestampMs}ms: ${e.message}")
            }
        }

        val contacts = findAllServeContacts(coarseFrames)
        Log.i(TAG, "Pass1: ${coarseFrames.size} frames, found ${contacts.size} serve(s): $contacts")

        // Человек в кадре есть (иначе coarseFrames был бы пуст), но findAllServeContacts не
        // нашёл ни одного явного взмаха — пробуем найти хотя бы приблизительный контакт по
        // кинематике (трофей→бэкскрэтч→пик скорости) или по сближению мяча с ракеткой (YOLO).
        // Если и это ничего не даёт — реальных доказательств подачи нет вообще, не подставляем
        // фиктивный "контакт" (раньше здесь всегда возвращался хоть какой-то timestamp вплоть
        // до последнего кадра клипа, даже если человек просто стоял или ходил в кадре).
        val rawContacts = if (contacts.isNotEmpty()) contacts else listOfNotNull(findApproxContact(coarseFrames))

        // Санити-чек: подача физически невозможна без ракетки. Скорость запястья по позе —
        // ненадёжный сигнал сам по себе: человек, который просто разговаривает и жестикулирует
        // (например пассажир в машине), тоже может дать резкий взмах руки/головы, кинематически
        // похожий на трофей/бэкскрэтч — YOLO ни разу не увидит ракетку рядом. Отбрасываем
        // кандидатов, для которых ракетка не найдена НИ НА ОДНОМ грубом кадре в окне ±800мс.
        val effContacts = rawContacts.filter { c -> hasRacketNearby(coarseFrames, c) }.sorted()
        if (rawContacts.isNotEmpty() && effContacts.isEmpty()) {
            Log.i(TAG, "Discarded ${rawContacts.size} contact(s) at $rawContacts — no racket detected nearby")
        }
        return finishWithFinePasses(videoFile, meta, coarseFrames, effContacts, onProgress)
    }

    /** Ракетка (YOLO classId 38) обнаружена хотя бы на одном грубом кадре рядом с [contactMs]. */
    private fun hasRacketNearby(frames: List<PoseFrame>, contactMs: Long, windowMs: Long = 800L): Boolean =
        frames.any { f -> kotlin.math.abs(f.timestampMs - contactMs) <= windowMs && f.objects.any { it.classId == 38 } }

    /** Точный файн-пасс вокруг КАЖДОЙ подачи; кадры всех окон сливаются в один таймлайн. */
    private fun finishWithFinePasses(
        videoFile: File,
        meta: VideoFrameExtractor.VideoMeta,
        coarseFrames: List<PoseFrame>,
        contacts: List<Long>,
        onProgress: (Int, Int) -> Unit
    ): VideoAnalysisResult {
        // Полное окно подачи (для исключения грубых кадров из мерджа). Расширено назад,
        // чтобы захватить подброс и трофей — без этого ранние фазы считались по редким
        // coarse-кадрам с плохой позой и детектились неверно.
        val windows = contacts.map { c ->
            (c - LEAD_MS).coerceAtLeast(0L) to (c + 300L).coerceAtMost(meta.durationMs)
        }
        val allFine = mutableListOf<PoseFrame>()
        // грубая оценка для прогресса: lead-in (40мс) + contact (15мс)
        val totalFine = (contacts.size * ((LEAD_MS - CONTACT_MS) / 40L + (CONTACT_MS + 300L) / 15L)).toInt().coerceAtLeast(1)
        var fineDone = 0
        val tick = { fineDone++; onProgress(coarseFrames.size + fineDone, coarseFrames.size + totalFine) }

        for (c in contacts) {
            // Зона подброса/трофея: средняя плотность, дешёвая детекция (поза + базовый YOLO, без ROI)
            finePassRegion(videoFile,
                (c - LEAD_MS).coerceAtLeast(0L), (c - CONTACT_MS).coerceAtLeast(0L),
                stepMs = 40L, contactMs = c, allFine, tick)
            // Зона удара: высокая плотность + ROI-zoom
            finePassRegion(videoFile,
                (c - CONTACT_MS).coerceAtLeast(0L), (c + 300L).coerceAtMost(meta.durationMs),
                stepMs = 15L, contactMs = c, allFine, tick)
        }

        Log.i(TAG, "Pass2: ${allFine.size} fine frames over ${windows.size} serve window(s)")

        // Грубые кадры вне ВСЕХ окон уточнения + точные кадры
        val merged = (coarseFrames.filter { f -> windows.none { f.timestampMs in it.first..it.second } } + allFine)
            .sortedBy { it.timestampMs }
        val smoothed = LandmarkSmoother.smoothFrames(merged, windowRadius = 2)
        val filled = ObjectGapFiller.fill(smoothed)

        val interpCount = filled.sumOf { f -> f.objects.count { it.interpolated } }
        Log.i(TAG, "Merged: ${merged.size} frames (coarse=${coarseFrames.size} fine=${allFine.size}), gap-filled objs=$interpCount")
        return VideoAnalysisResult(
            frames = filled,
            videoDurationMs = meta.durationMs,
            videoWidth = meta.width,
            videoHeight = meta.height,
            serveContacts = contacts,
            coarseFrames = coarseFrames
        )
    }

    /**
     * Один проход уточнения по диапазону [startMs, endMs] с шагом [stepMs].
     * Возле контакта (±[CONTACT_MS]) — точная детекция с ROI-zoom; дальше (зона подброса/
     * трофея) — дешёвая базовая детекция (ROI там не нужен, важна только хорошая поза).
     */
    private fun finePassRegion(
        videoFile: File,
        startMs: Long,
        endMs: Long,
        stepMs: Long,
        contactMs: Long,
        out: MutableList<PoseFrame>,
        onTick: () -> Unit
    ) {
        if (endMs <= startMs) return
        var lastLandmarks: List<PoseLandmark>? = null
        var lastRealMs: Long? = null
        VideoFrameExtractor.extract(videoFile, stepMs = stepMs, startMs = startMs, endMs = endMs,
            onProgress = { _, _ -> }
        ) { bitmap, timestampMs ->
            try {
                val scaled = if (bitmap.width == 640 && bitmap.height == 640) bitmap
                             else Bitmap.createScaledBitmap(bitmap, 640, 640, true)

                val lms = poseDetector.detectPreScaled(scaled)
                // Тот же лимит переноса, что и в грубом проходе (см. MAX_CARRY_MS) — иначе
                // единственная детекция (реальная или ложная) в начале региона "размазалась"
                // бы на весь плотный fine-pass (может быть больше секунды при шаге 15-40мс).
                val withinCarryBudget = lastRealMs != null && timestampMs - lastRealMs!! < MAX_CARRY_MS
                val carryLandmarks = if (withinCarryBudget) lastLandmarks else null
                val effLms = if (lms.isNotEmpty()) lms else carryLandmarks
                val wristHint = effLms?.getOrNull(if (isLeftHanded) 15 else 16)?.let { it.x to it.y }

                val objects = if (kotlin.math.abs(timestampMs - contactMs) <= CONTACT_MS)
                    objectDetector.detectWithRoi(scaled, bitmap, timestampMs, wristHint)
                else
                    objectDetector.detectPreScaled(scaled, timestampMs)

                if (scaled !== bitmap) scaled.recycle()

                if (lms.isNotEmpty()) {
                    out.add(PoseFrame(lms, timestampMs, objects))
                    lastLandmarks = lms
                    lastRealMs = timestampMs
                } else if (carryLandmarks != null) {
                    out.add(PoseFrame(carryLandmarks, timestampMs, objects))
                }
                onTick()
            } catch (e: Exception) {
                Log.e(TAG, "Fine frame error at ${timestampMs}ms: ${e.message}")
            }
        }
    }

    private fun runYoloPose(
        bitmap: Bitmap,
        timestampMs: Long,
        lastLandmarks: List<PoseLandmark>?
    ): Pair<List<PoseLandmark>, Boolean>? {
        return try {
            val lms = poseDetector.detect(bitmap)
            if (lms.isNotEmpty()) Pair(lms, true)
            else lastLandmarks?.let { Pair(it, false) }
        } catch (e: Exception) {
            Log.w(TAG, "YoloPose err at ${timestampMs}ms: ${e.message}")
            lastLandmarks?.let { Pair(it, false) }
        }
    }

    /**
     * Находит все подачи в видео: кластеризует моменты высокой скорости запястья
     * (запястье выше плеча) с минимальным разрывом 3 секунды между подачами.
     */
    private fun findAllServeContacts(frames: List<PoseFrame>): List<Long> {
        if (frames.size < 3) return emptyList()
        data class ArmIndices(val wrist: Int, val shoulder: Int)
        val arms = listOf(ArmIndices(15, 11), ArmIndices(16, 12))

        // Собираем пары (timestamp, velocity) для кадров где запястье выше плеча
        val candidates = mutableListOf<Pair<Long, Float>>()
        for (i in 1 until frames.size - 1) {
            var best = 0f
            for (arm in arms) {
                val prev     = frames[i - 1].landmarks.getOrNull(arm.wrist)    ?: continue
                val curr     = frames[i    ].landmarks.getOrNull(arm.wrist)    ?: continue
                val next     = frames[i + 1].landmarks.getOrNull(arm.wrist)    ?: continue
                val shoulder = frames[i    ].landmarks.getOrNull(arm.shoulder) ?: continue
                if (curr.y >= shoulder.y) continue  // не выше плеча — пропускаем
                val dtSec = (frames[i + 1].timestampMs - frames[i - 1].timestampMs).coerceAtLeast(1L) / 1000f
                val dx = next.x - prev.x; val dy = next.y - prev.y
                val vel = kotlin.math.sqrt(dx * dx + dy * dy) / dtSec
                if (vel > best) best = vel
            }
            if (best >= MIN_SERVE_VEL) candidates.add(frames[i].timestampMs to best)
        }

        // Кластеризуем с минимальным разрывом 3 секунды → один пик на подачу
        val contacts = mutableListOf<Long>()
        var clusterPeakVel = 0f
        var clusterPeakMs  = -1L
        var lastMs = -1L
        for ((ms, vel) in candidates) {
            if (lastMs >= 0 && ms - lastMs > SERVE_GAP_MS) {
                if (clusterPeakMs >= 0) contacts.add(clusterPeakMs)
                clusterPeakVel = 0f; clusterPeakMs = -1L
            }
            if (vel > clusterPeakVel) { clusterPeakVel = vel; clusterPeakMs = ms }
            lastMs = ms
        }
        if (clusterPeakMs >= 0) contacts.add(clusterPeakMs)

        // Отбрасываем фантомные «подачи» в самом начале записи (игрок не успевает замахнуться
        // за < 0.7с). Если так отфильтровались все — оставляем как есть.
        val filtered = contacts.filter { it >= 700L }.ifEmpty { contacts }

        Log.i(TAG, "findAllServeContacts: ${filtered.size} serves at $filtered (raw $contacts)")
        return filtered
    }

    /**
     * Приблизительный момент контакта, когда findAllServeContacts не нашёл явного взмаха.
     * Возвращает null, если нет ВООБЩЕ никаких признаков подачи — ни кинематики
     * (трофей/бэкскрэтч/пик скорости запястья), ни сближения мяча с ракеткой по YOLO.
     * Человек в кадре мог просто стоять, идти или разминаться — это не подача.
     */
    private fun findApproxContact(frames: List<PoseFrame>): Long? {
        if (frames.isEmpty()) return null

        // Пары (запястье, локоть, плечо): левая=15/13/11, правая=16/14/12
        data class ArmIndices(val wrist: Int, val elbow: Int, val shoulder: Int)
        val arms = listOf(ArmIndices(15, 13, 11), ArmIndices(16, 14, 12))

        var kinematicMs: Long? = null
        if (frames.size >= 3) {
            // Шаг 1: Трофей — оба локтя выше плеч И хотя бы одно запястье выше плеча
            // Ищем первый такой кадр (не максимум — трофей это начало петли, не вершина)
            var trophyIdx = -1
            for (i in frames.indices) {
                val lms = frames[i].landmarks
                val bothElbowsUp = arms.all { arm ->
                    val elbow    = lms.getOrNull(arm.elbow)    ?: return@all false
                    val shoulder = lms.getOrNull(arm.shoulder) ?: return@all false
                    elbow.y < shoulder.y - 0.03f
                }
                val anyWristUp = arms.any { arm ->
                    val wrist    = lms.getOrNull(arm.wrist)    ?: return@any false
                    val shoulder = lms.getOrNull(arm.shoulder) ?: return@any false
                    wrist.y < shoulder.y
                }
                if (bothElbowsUp && anyWristUp) { trophyIdx = i; break }
            }

            // Шаг 2: Бэкскрэтч — после трофея одно запястье резко опускается ниже плеча
            // Это запястье с ракеткой; находим его и минимальную точку
            var backscratchIdx = -1
            var racketArmIdx = -1  // 0=левая, 1=правая
            val searchFrom = if (trophyIdx >= 0) trophyIdx else 0
            for (i in searchFrom until frames.size) {
                val lms = frames[i].landmarks
                for ((ai, arm) in arms.withIndex()) {
                    val wrist    = lms.getOrNull(arm.wrist)    ?: continue
                    val shoulder = lms.getOrNull(arm.shoulder) ?: continue
                    // Запястье опустилось НИЖЕ плеча после трофея — это бэкскрэтч
                    if (wrist.y > shoulder.y + 0.05f) {
                        backscratchIdx = i
                        racketArmIdx = ai
                        break
                    }
                }
                if (backscratchIdx >= 0) break
            }

            // Шаг 3: Контакт — пик скорости запястья ракетки ПОСЛЕ бэкскрэтча, когда оно снова выше плеча
            val contactSearchFrom = if (backscratchIdx >= 0) backscratchIdx else searchFrom
            val racketArm = if (racketArmIdx >= 0) arms[racketArmIdx] else null
            var maxVel = 0f

            for (i in (contactSearchFrom + 1) until frames.size - 1) {
                // Если нашли руку с ракеткой — смотрим только её; иначе обе
                val checkArms = if (racketArm != null) listOf(racketArm) else arms
                for (arm in checkArms) {
                    val prevW    = frames[i - 1].landmarks.getOrNull(arm.wrist)    ?: continue
                    val currW    = frames[i    ].landmarks.getOrNull(arm.wrist)    ?: continue
                    val nextW    = frames[i + 1].landmarks.getOrNull(arm.wrist)    ?: continue
                    val shoulder = frames[i    ].landmarks.getOrNull(arm.shoulder) ?: continue
                    if (currW.y >= shoulder.y) continue  // рука должна быть выше плеча (разгон вверх)

                    val dtSec = (frames[i + 1].timestampMs - frames[i - 1].timestampMs).coerceAtLeast(1L) / 1000f
                    val dx = nextW.x - prevW.x; val dy = nextW.y - prevW.y
                    val vel = kotlin.math.sqrt(dx * dx + dy * dy) / dtSec
                    if (vel > maxVel) { maxVel = vel; kinematicMs = frames[i].timestampMs }
                }
            }

            val tMs = if (trophyIdx >= 0) frames[trophyIdx].timestampMs else -1L
            val bMs = if (backscratchIdx >= 0) frames[backscratchIdx].timestampMs else -1L
            Log.i(TAG, "trophy@${tMs}ms backscratch@${bMs}ms contact@${kinematicMs}ms vel=${"%.3f".format(maxVel)}")
        }

        // Если YOLO нашёл мяч+ракетку в окне ±200мс от кинематики — уточняем до YOLO.
        // База для поиска окна — кинематический момент, а если его тоже нет, крайний кадр
        // (просто чтобы было от чего отсчитывать окно поиска; сама по себе она НЕ считается
        // доказательством подачи и никогда не возвращается напрямую).
        val base = kinematicMs ?: frames.last().timestampMs
        val yoloFrames = frames.filter { f ->
            kotlin.math.abs(f.timestampMs - base) <= 200L &&
            f.objects.any { it.classId == 32 } && f.objects.any { it.classId == 38 }
        }
        if (yoloFrames.isNotEmpty()) {
            val best = yoloFrames.minBy { f ->
                val ball   = f.objects.first { it.classId == 32 }
                val racket = f.objects.first { it.classId == 38 }
                val dx = ball.cx - racket.cx; val dy = ball.cy - racket.cy
                dx * dx + dy * dy
            }
            Log.i(TAG, "approxContact refined by YOLO: ${best.timestampMs}ms (base=${base}ms)")
            return best.timestampMs
        }

        // Ни кинематика (трофей/бэкскрэтч/скорость), ни YOLO-сближение мяча с ракеткой
        // ничего не подтвердили — реальных признаков подачи нет, честно возвращаем null
        // вместо того чтобы подставлять kinematicMs=null→last-frame как будто это контакт.
        return kinematicMs
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDim: Int = 640): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val scale = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    fun close() {
        parallelExec.shutdown()
        poseDetector.close()
        objectDetector.close()
    }

    companion object {
        private const val TAG          = "VideoPoseAnalyzer"
        private const val MIN_SERVE_VEL = 0.4f    // мин. скорость запястья для детекции подачи
        private const val SERVE_GAP_MS  = 3000L   // мин. пауза между подачами
        private const val LEAD_MS       = 1300L   // насколько назад от контакта тянем файн-пасс (подброс/трофей)
        private const val CONTACT_MS    = 350L    // радиус вокруг контакта с ROI-zoom и шагом 15мс
        // Общий лимит переноса landmarks для обоих проходов (грубого и файн-пасса) —
        // в мс, а не в кадрах, т.к. шаг сильно разный (100мс vs 15-40мс). 500мс достаточно,
        // чтобы промостить смаз/кратковременную заслонку при настоящей подаче, но не даёт
        // единичному ложному срабатыванию "заморозиться" на весь клип/весь fine-pass регион.
        private const val MAX_CARRY_MS = 500L
    }
}
