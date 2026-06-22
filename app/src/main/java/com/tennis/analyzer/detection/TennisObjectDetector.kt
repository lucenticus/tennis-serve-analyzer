package com.tennis.analyzer.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.tennis.analyzer.pose.DetectedObject
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min

class TennisObjectDetector(private val context: Context) {

    private var session: OrtSession? = null
    private var outputName: String = "output0"
    private var inferenceCount = 0L
    var available = false
        private set

    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val inputFloatBuf: java.nio.FloatBuffer =
        java.nio.FloatBuffer.allocate(3 * INPUT_SIZE * INPUT_SIZE)
    private val inputShape = longArrayOf(1L, 3L, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())

    // Трекинг траектории — последние позиции мяча и ракетки
    private data class TrackedPos(val cx: Float, val cy: Float, val ms: Long)
    private val ballHistory   = ArrayDeque<TrackedPos>()
    private val racketHistory = ArrayDeque<TrackedPos>()

    companion object {
        private const val TAG           = "TennisObjectDetector"
        private const val MODEL_FILE    = "models/yolov8n.onnx"
        const val INPUT_SIZE            = 640
        const val CONF_THRESHOLD        = 0.15f  // обычный порог
        const val CONF_GUIDED           = 0.04f  // порог в зоне предсказанной траектории (смазанный объект даёт низкий conf)
        const val SEARCH_RADIUS         = 0.24f  // радиус поиска вокруг предсказания (норм. координаты)
        const val NMS_THRESHOLD         = 0.45f
        const val CLASS_BALL            = DetectedObject.CLASS_BALL    // 32
        const val CLASS_RACKET          = DetectedObject.CLASS_RACKET  // 38
        private const val MAX_HISTORY   = 4
        private const val MAX_EXTRAPOLATE_RATIO = 4f  // не экстраполируем дальше чем 4× шаг истории
        // ROI-zoom: доля кадра для кропа вокруг предсказанной позиции
        const val BALL_ROI_SIZE      = 0.22f  // мяч мелкий — узкий ROI = сильный зум
        const val RACKET_ROI_SIZE    = 0.42f  // ракетка крупнее, голова уходит от запястья
        const val ROI_CONF_THRESHOLD = 0.10f  // в сфокусированном кропе можно ниже порог
    }

    fun setup(): Boolean {
        val bytes = try {
            context.assets.open(MODEL_FILE).readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "Model not found: ${e.message}")
            return false
        }
        return try {
            session = OrtManager.createSession(bytes, TAG)
            outputName = session!!.outputNames.first()
            available = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "setup failed: ${e.message}")
            false
        }
    }

    /** Принимает уже масштабированный 640×640 bitmap — без внутреннего createScaledBitmap. */
    fun detectPreScaled(bitmap640: Bitmap, timestampMs: Long = 0L): List<DetectedObject> =
        detect(bitmap640, timestampMs, preScaled = true)

    /** timestampMs нужен для трекинга траектории. */
    fun detect(bitmap: Bitmap, timestampMs: Long = 0L): List<DetectedObject> =
        detect(bitmap, timestampMs, preScaled = false)

    private fun detect(bitmap: Bitmap, timestampMs: Long, preScaled: Boolean): List<DetectedObject> {
        if (!available) return emptyList()
        val scaled = if (preScaled || (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE)) bitmap
                     else Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        val predBall   = predictPos(ballHistory,   timestampMs)
        val predRacket = predictPos(racketHistory, timestampMs)
        val result = infer(scaled, predBall, predRacket, flatThreshold = null)
        if (scaled !== bitmap) scaled.recycle()

        updateHistory(result, timestampMs)
        if (result.isNotEmpty())
            Log.d(TAG, result.joinToString { clsName(it.classId) + " %.2f".format(it.confidence) })
        return result
    }

    /**
     * Точная детекция с ROI-zoom: сначала обычный проход по всему кадру (640),
     * затем для мяча и ракетки вырезаем область вокруг предсказанной позиции из
     * ПОЛНОГО разрешения и прогоняем YOLO по увеличенному кропу. Мелкий смазанный
     * мяч на 1080p после ужатия в 640 занимает ~6px — в кропе он крупный и детектится.
     *
     * @param scaled640    кадр, ужатый до 640×640 (для полного прохода)
     * @param fullBitmap   кадр в полном разрешении (для вырезания ROI)
     * @param racketWristHint нормализованная позиция запястья ракетки из позы — seed ROI,
     *                        когда YOLO потерял ракетку и истории ещё нет
     */
    fun detectWithRoi(
        scaled640: Bitmap,
        fullBitmap: Bitmap,
        timestampMs: Long,
        racketWristHint: Pair<Float, Float>? = null
    ): List<DetectedObject> {
        if (!available) return emptyList()
        val predBall   = predictPos(ballHistory,   timestampMs)
        val predRacket = predictPos(racketHistory, timestampMs)

        val base = infer(scaled640, predBall, predRacket, flatThreshold = null)
        var ball   = base.filter { it.classId == CLASS_BALL   }.maxByOrNull { it.confidence }
        var racket = base.filter { it.classId == CLASS_RACKET  }.maxByOrNull { it.confidence }

        // ROI вокруг: найденного бокса → предсказания траектории → (для ракетки) запястья
        val ballCenter   = (ball?.let   { it.cx to it.cy }) ?: predBall
        val racketCenter = (racket?.let { it.cx to it.cy }) ?: predRacket ?: racketWristHint

        if (ballCenter != null) {
            val roi = roiDetect(fullBitmap, ballCenter, BALL_ROI_SIZE, CLASS_BALL)
            if (roi != null && (ball == null || roi.confidence > ball.confidence)) ball = roi
        }
        if (racketCenter != null) {
            val roi = roiDetect(fullBitmap, racketCenter, RACKET_ROI_SIZE, CLASS_RACKET)
            if (roi != null && (racket == null || roi.confidence > racket.confidence)) racket = roi
        }

        val result = listOfNotNull(ball, racket)
        updateHistory(result, timestampMs)
        return result
    }

    /** Вырезает ROI из полного кадра, зумит в 640, детектит один класс, возвращает бокс в координатах полного кадра. */
    private fun roiDetect(
        full: Bitmap, center: Pair<Float, Float>, sizeNorm: Float, cls: Int
    ): DetectedObject? {
        val fw = full.width; val fh = full.height
        val leftN = (center.first  - sizeNorm / 2f).coerceIn(0f, 1f - sizeNorm)
        val topN  = (center.second - sizeNorm / 2f).coerceIn(0f, 1f - sizeNorm)
        val left = (leftN * fw).toInt().coerceIn(0, fw - 1)
        val top  = (topN  * fh).toInt().coerceIn(0, fh - 1)
        val w = (sizeNorm * fw).toInt().coerceIn(1, fw - left)
        val h = (sizeNorm * fh).toInt().coerceIn(1, fh - top)

        val crop = try { Bitmap.createBitmap(full, left, top, w, h) } catch (e: Exception) { return null }
        val scaled = if (crop.width == INPUT_SIZE && crop.height == INPUT_SIZE) crop
                     else Bitmap.createScaledBitmap(crop, INPUT_SIZE, INPUT_SIZE, true)
        val objs = infer(scaled, null, null, flatThreshold = ROI_CONF_THRESHOLD)
        if (scaled !== crop) scaled.recycle()
        crop.recycle()

        val best = objs.filter { it.classId == cls }.maxByOrNull { it.confidence } ?: return null
        // crop-нормализованные [0,1] → нормализованные координаты полного кадра
        val realLeftN = left.toFloat() / fw; val realTopN = top.toFloat() / fh
        val realWN = w.toFloat() / fw;        val realHN = h.toFloat() / fh
        return best.copy(
            cx = realLeftN + best.cx * realWN,
            cy = realTopN  + best.cy * realHN,
            w  = best.w * realWN,
            h  = best.h * realHN
        )
    }

    /** Чистый инференс по 640×640 bitmap. flatThreshold != null → плоский порог без трекинга (для ROI). */
    private fun infer(
        bitmap640: Bitmap,
        predBall: Pair<Float, Float>?,
        predRacket: Pair<Float, Float>?,
        flatThreshold: Float?
    ): List<DetectedObject> {
        val ortSess = session ?: return emptyList()
        bitmap640.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        inputFloatBuf.rewind()
        for (p in pixels) inputFloatBuf.put(((p shr 16) and 0xFF) / 255f)
        for (p in pixels) inputFloatBuf.put(((p shr 8)  and 0xFF) / 255f)
        for (p in pixels) inputFloatBuf.put((p           and 0xFF) / 255f)
        inputFloatBuf.rewind()

        val inputTensor = OnnxTensor.createTensor(OrtManager.env, inputFloatBuf, inputShape)
        return try {
            val t0 = System.currentTimeMillis()
            val result = ortSess.run(mapOf("images" to inputTensor)).use { results ->
                val tensor   = results.get(0) as? OnnxTensor ?: return emptyList()
                val shape    = tensor.info.shape
                applyNms(parseFlat(tensor.floatBuffer, shape[1].toInt(), shape[2].toInt(),
                    predBall, predRacket, flatThreshold))
            }
            if (inferenceCount++ % 20 == 0L)
                Log.i(TAG, "inference ${System.currentTimeMillis() - t0}ms")
            result
        } catch (e: Exception) {
            Log.w(TAG, "Inference error: ${e.message} — disabling YOLO")
            available = false
            emptyList()
        } finally {
            inputTensor.close()
        }
    }

    private fun updateHistory(result: List<DetectedObject>, timestampMs: Long) {
        result.firstOrNull { it.classId == CLASS_BALL }?.let {
            ballHistory.addLast(TrackedPos(it.cx, it.cy, timestampMs))
            if (ballHistory.size > MAX_HISTORY) ballHistory.removeFirst()
        }
        result.firstOrNull { it.classId == CLASS_RACKET }?.let {
            racketHistory.addLast(TrackedPos(it.cx, it.cy, timestampMs))
            if (racketHistory.size > MAX_HISTORY) racketHistory.removeFirst()
        }
    }

    /** Линейная экстраполяция по последним двум точкам. */
    private fun predictPos(history: ArrayDeque<TrackedPos>, atMs: Long): Pair<Float, Float>? {
        if (history.size < 2) return null
        val p1 = history[history.size - 2]
        val p2 = history.last()
        val dt = (p2.ms - p1.ms).toFloat().takeIf { it > 0 } ?: return null
        val elapsed = (atMs - p2.ms).toFloat()
        if (elapsed < 0 || elapsed > dt * MAX_EXTRAPOLATE_RATIO) return null
        return Pair(
            (p2.cx + (p2.cx - p1.cx) / dt * elapsed).coerceIn(0f, 1f),
            (p2.cy + (p2.cy - p1.cy) / dt * elapsed).coerceIn(0f, 1f)
        )
    }

    private fun parseFlat(
        buf: java.nio.FloatBuffer,
        numRows: Int,
        numBoxes: Int,
        predictedBall: Pair<Float, Float>?,
        predictedRacket: Pair<Float, Float>?,
        flatThreshold: Float?
    ): List<DetectedObject> {
        val results = mutableListOf<DetectedObject>()
        for (i in 0 until numBoxes) {
            val cx = buf.get(0 * numBoxes + i) / INPUT_SIZE
            val cy = buf.get(1 * numBoxes + i) / INPUT_SIZE
            val w  = buf.get(2 * numBoxes + i) / INPUT_SIZE
            val h  = buf.get(3 * numBoxes + i) / INPUT_SIZE
            for (cls in intArrayOf(CLASS_BALL, CLASS_RACKET)) {
                if (4 + cls >= numRows) continue
                val score = buf.get((4 + cls) * numBoxes + i)
                val threshold = when {
                    flatThreshold != null -> flatThreshold   // ROI: плоский порог, без трекинга
                    else -> {
                        val pred = if (cls == CLASS_BALL) predictedBall else predictedRacket
                        if (pred != null) {
                            val dx = cx - pred.first; val dy = cy - pred.second
                            if (sqrt(dx * dx + dy * dy) < SEARCH_RADIUS) CONF_GUIDED else CONF_THRESHOLD
                        } else CONF_THRESHOLD
                    }
                }
                if (score >= threshold)
                    results.add(DetectedObject(cls, score, cx, cy, w, h))
            }
        }
        return results
    }

    private fun applyNms(detections: List<DetectedObject>): List<DetectedObject> =
        detections.groupBy { it.classId }.flatMap { (_, boxes) ->
            val sorted = boxes.sortedByDescending { it.confidence }
            val suppressed = BooleanArray(sorted.size)
            buildList {
                for (i in sorted.indices) {
                    if (suppressed[i]) continue
                    add(sorted[i])
                    for (j in i + 1 until sorted.size)
                        if (!suppressed[j] && iou(sorted[i], sorted[j]) > NMS_THRESHOLD)
                            suppressed[j] = true
                }
            }
        }

    private fun iou(a: DetectedObject, b: DetectedObject): Float {
        val ix = max(0f, min(a.cx + a.w/2, b.cx + b.w/2) - max(a.cx - a.w/2, b.cx - b.w/2))
        val iy = max(0f, min(a.cy + a.h/2, b.cy + b.h/2) - max(a.cy - a.h/2, b.cy - b.h/2))
        val inter = ix * iy
        val union = a.w * a.h + b.w * b.h - inter
        return if (union > 0f) inter / union else 0f
    }

    private fun clsName(id: Int) = if (id == CLASS_BALL) "ball" else "racket"

    fun close() {
        session?.close(); session = null
        ballHistory.clear(); racketHistory.clear()
        available = false
    }
}
