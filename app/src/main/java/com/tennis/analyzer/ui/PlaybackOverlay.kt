package com.tennis.analyzer.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.tennis.analyzer.data.PoseSnapshot
import com.tennis.analyzer.data.ServeRecording
import com.tennis.analyzer.pose.DetectedObject
import com.tennis.analyzer.pose.LandmarkIndex
import com.tennis.analyzer.pose.PoseFrame

class PlaybackOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var recording: ServeRecording? = null
    private var framesList: List<PoseFrame>? = null
    private var framesDurationMs: Long = 0L
    private var currentSnapshot: PoseSnapshot? = null
    private var currentObjects: List<DetectedObject> = emptyList()

    // Последний известный bbox мяча + время когда он был виден
    private var lastBallObj: DetectedObject? = null
    private var lastBallSeenMs: Long = -1L
    private val BALL_MEMORY_MS = 500L    // показываем "призрак" мяча 500мс после потери

    // Размеры оригинального видео — нужны для масштабирования координат
    private var videoWidth: Int = 1280
    private var videoHeight: Int = 720

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.CYAN
    }

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.WHITE
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.YELLOW
    }

    // Суставы которые хотим выделить при анализе
    private val highlightJoints = setOf(
        LandmarkIndex.RIGHT_SHOULDER,
        LandmarkIndex.RIGHT_ELBOW,
        LandmarkIndex.RIGHT_WRIST,
    )

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
    }

    private val labelBackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val ballBboxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.YELLOW
    }
    private val racketBboxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.rgb(0, 230, 255)
    }
    // Пунктир для достроенных (интерполированных) боксов — отличаем предсказание от детекции
    private val dashEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 8f), 0f)
    private val bboxLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f; isFakeBoldText = true; color = Color.WHITE
    }
    private val bboxLabelBgPaint = Paint().apply {
        style = Paint.Style.FILL; color = Color.argb(180, 0, 0, 0)
    }

    fun setRecording(rec: ServeRecording, vidW: Int, vidH: Int) {
        recording = rec
        videoWidth = vidW
        videoHeight = vidH
        invalidate()
    }

    /** Альтернатива setRecording — для AnalysisActivity где нет ServeRecording */
    fun setFrames(frames: List<PoseFrame>, durationMs: Long, vidW: Int = 1080, vidH: Int = 1920) {
        framesList = frames
        framesDurationMs = durationMs
        videoWidth = vidW
        videoHeight = vidH
        invalidate()
    }

    /** Вызывается из PlaybackActivity при каждом тике плеера */
    fun seekToPose(videoPositionMs: Long) {
        // Режим 1: ServeRecording timeline
        recording?.let { rec ->
            currentSnapshot = rec.poseTimeline.minByOrNull {
                Math.abs(it.videoOffsetMs - videoPositionMs)
            }
            invalidate()
            return
        }
        // Режим 2: прямой список PoseFrame с абсолютными timestamp
        framesList?.let { frames ->
            val frame = frames.minByOrNull { Math.abs(it.timestampMs - videoPositionMs) }
            if (frame != null && currentSnapshot?.timestampMs != frame.timestampMs) {
                Log.v("PlaybackOverlay", "pos=${videoPositionMs}ms → frame@${frame.timestampMs}ms lms=${frame.landmarks.size} objs=${frame.objects.size}")
            }
            currentSnapshot = frame?.let { PoseSnapshot(it.timestampMs, it.timestampMs, it.landmarks) }
            currentObjects  = frame?.objects ?: emptyList()

            // Обновляем "память" мяча
            val ball = currentObjects.firstOrNull { it.classId == DetectedObject.CLASS_BALL }
            if (ball != null) {
                lastBallObj    = ball
                lastBallSeenMs = videoPositionMs
            }
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snap = currentSnapshot ?: return
        val lms = snap.landmarks
        if (lms.isEmpty()) return

        // Масштаб: нормализованные координаты MediaPipe [0..1] → пиксели overlay
        // Видео может быть обрезано letterbox — учитываем соотношение сторон
        val scaleX: Float
        val scaleY: Float
        val offsetX: Float
        val offsetY: Float

        val viewRatio  = width.toFloat() / height.toFloat()
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()

        if (viewRatio > videoRatio) {
            // Pillarbox: чёрные полосы по бокам
            scaleY  = height.toFloat()
            scaleX  = height * videoRatio
            offsetX = (width - scaleX) / 2f
            offsetY = 0f
        } else {
            // Letterbox: чёрные полосы сверху/снизу
            scaleX  = width.toFloat()
            scaleY  = width / videoRatio
            offsetX = 0f
            offsetY = (height - scaleY) / 2f
        }

        fun lmX(idx: Int) = lms[idx].x * scaleX + offsetX
        fun lmY(idx: Int) = lms[idx].y * scaleY + offsetY
        fun vis(idx: Int) = lms.getOrNull(idx)?.visibility ?: 0f

        // Лог координат носа (landmark 0) для диагностики
        if (lms.isNotEmpty()) {
            val nx = lms[0].x * scaleX + offsetX
            val ny = lms[0].y * scaleY + offsetY
            Log.v("PlaybackOverlay", "nose px=(${nx.toInt()},${ny.toInt()}) view=${width}x${height} video=${videoWidth}x${videoHeight} scaleX=$scaleX scaleY=$scaleY offsetY=$offsetY")
        }

        // Кости — порог visibility убран, IMAGE-режим может возвращать низкие значения
        for ((a, b) in LandmarkIndex.CONNECTIONS) {
            if (a >= lms.size || b >= lms.size) continue
            bonePaint.color = if (a in highlightJoints && b in highlightJoints)
                Color.rgb(255, 180, 0) else Color.WHITE
            canvas.drawLine(lmX(a), lmY(a), lmX(b), lmY(b), bonePaint)
        }

        // Суставы
        for ((idx, lm) in lms.withIndex()) {
            val paint = if (idx in highlightJoints) highlightPaint else jointPaint
            val radius = if (idx in highlightJoints) 12f else 7f
            canvas.drawCircle(lmX(idx), lmY(idx), radius, paint)
        }

        // Подписи ключевых суставов руки
        drawJointLabel(canvas, "Плечо",    lmX(LandmarkIndex.RIGHT_SHOULDER), lmY(LandmarkIndex.RIGHT_SHOULDER))
        drawJointLabel(canvas, "Локоть",   lmX(LandmarkIndex.RIGHT_ELBOW),    lmY(LandmarkIndex.RIGHT_ELBOW))
        drawJointLabel(canvas, "Запястье", lmX(LandmarkIndex.RIGHT_WRIST),    lmY(LandmarkIndex.RIGHT_WRIST))

        // YOLO bbox: мяч (жёлтый) и ракетка (голубой)
        val posMs = currentSnapshot?.timestampMs ?: 0L
        for (obj in currentObjects) {
            drawBbox(canvas, obj, scaleX, scaleY, offsetX, offsetY, alpha = 255)
        }

        // Призрак мяча: если мяч не виден сейчас, но был виден недавно — рисуем с затуханием
        val ghost = lastBallObj
        val hasBallNow = currentObjects.any { it.classId == DetectedObject.CLASS_BALL }
        if (!hasBallNow && ghost != null && lastBallSeenMs >= 0) {
            val age = posMs - lastBallSeenMs
            if (age in 1..BALL_MEMORY_MS) {
                val alpha = (255 * (1f - age.toFloat() / BALL_MEMORY_MS)).toInt().coerceIn(0, 255)
                drawBbox(canvas, ghost, scaleX, scaleY, offsetX, offsetY, alpha)
            }
        }
    }

    private fun drawBbox(
        canvas: Canvas, obj: DetectedObject,
        scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float,
        alpha: Int
    ) {
        val cx = obj.cx * scaleX + offsetX
        val cy = obj.cy * scaleY + offsetY
        val hw = obj.w * scaleX / 2f
        val hh = obj.h * scaleY / 2f
        val isBall = obj.classId == DetectedObject.CLASS_BALL
        val bboxP = if (isBall) ballBboxPaint else racketBboxPaint
        val label = if (isBall) "мяч" else "ракетка"
        // Интерполированный бокс — пунктиром и чуть прозрачнее
        bboxP.pathEffect = if (obj.interpolated) dashEffect else null
        val drawAlpha = if (obj.interpolated) (alpha * 0.75f).toInt() else alpha
        bboxP.alpha = drawAlpha
        bboxLabelBgPaint.alpha = (drawAlpha * 0.7f).toInt()
        bboxLabelPaint.alpha = drawAlpha
        canvas.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, bboxP)
        val lw = bboxLabelPaint.measureText(label)
        canvas.drawRect(cx - hw, cy - hh - bboxLabelPaint.textSize - 4f,
            cx - hw + lw + 10f, cy - hh, bboxLabelBgPaint)
        canvas.drawText(label, cx - hw + 5f, cy - hh - 4f, bboxLabelPaint)
        // Сбрасываем состояние чтобы не влиять на другие объекты
        bboxP.alpha = 255; bboxP.pathEffect = null
        bboxLabelBgPaint.alpha = 180; bboxLabelPaint.alpha = 255
    }

    private fun drawJointLabel(canvas: Canvas, text: String, x: Float, y: Float) {
        val tw = labelPaint.measureText(text)
        val pad = 6f
        canvas.drawRoundRect(
            x + 14f, y - labelPaint.textSize, x + 14f + tw + pad * 2, y + pad,
            8f, 8f, labelBackPaint
        )
        canvas.drawText(text, x + 14f + pad, y - 2f, labelPaint)
    }
}
