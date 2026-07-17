package com.tennis.analyzer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.View
import com.tennis.analyzer.data.PhaseMarker
import com.tennis.analyzer.detection.ServePhase
import com.tennis.analyzer.pose.PoseFrame

/**
 * Горизонтальный таймлайн с цветными сегментами фаз и двумя перетаскиваемыми маркерами:
 * ◆ Contact (момент удара) и ◆ Trophy (позиция трофея).
 * Пользователь перетаскивает маркеры чтобы скорректировать время фаз.
 */
class PhaseTimelineView(context: Context) : View(context) {

    private var durationMs: Long = 1L
    private var allFrames: List<PoseFrame> = emptyList()
    private var detectedPhases: List<PhaseMarker> = emptyList()
    private var serveContacts: List<Long> = emptyList()

    private var playheadMs: Long = 0L

    private val bgPaint = Paint().apply { style = Paint.Style.FILL }
    private val phaseLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 18f; textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val tickPaint = Paint().apply {
        color = Color.argb(180, 255, 255, 255); strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val playheadPaint = Paint().apply {
        color = Color.WHITE; strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0); style = Paint.Style.FILL
    }

    private val phaseColors = mapOf(
        ServePhase.IDLE           to Color.argb(180, 80,  80,  80),
        ServePhase.READY_STANCE   to Color.argb(200, 76,  175, 80),
        ServePhase.TOSS           to Color.argb(200, 200, 180, 0),
        ServePhase.TROPHY         to Color.argb(200, 0,   188, 212),
        ServePhase.BACKSCRATCH    to Color.argb(200, 255, 152, 0),
        ServePhase.ACCELERATION   to Color.argb(200, 244, 67,  54),
        ServePhase.CONTACT        to Color.argb(200, 233, 30,  99),
        ServePhase.FOLLOW_THROUGH to Color.argb(200, 156, 39,  176)
    )

    private val phaseShortNames = mapOf(
        ServePhase.IDLE           to context.getString(com.tennis.analyzer.R.string.phase_idle),
        ServePhase.READY_STANCE   to context.getString(com.tennis.analyzer.R.string.phase_stance),
        ServePhase.TOSS           to context.getString(com.tennis.analyzer.R.string.phase_toss),
        ServePhase.TROPHY         to context.getString(com.tennis.analyzer.R.string.phase_trophy),
        ServePhase.BACKSCRATCH    to context.getString(com.tennis.analyzer.R.string.phase_backscratch),
        ServePhase.ACCELERATION   to context.getString(com.tennis.analyzer.R.string.phase_acceleration),
        ServePhase.CONTACT        to context.getString(com.tennis.analyzer.R.string.phase_contact),
        ServePhase.FOLLOW_THROUGH to context.getString(com.tennis.analyzer.R.string.phase_follow)
    )

    fun setData(
        frames: List<PoseFrame>,
        durationMs: Long,
        phases: List<PhaseMarker>,
        serves: List<Long> = emptyList()
    ) {
        this.allFrames = frames
        this.durationMs = durationMs.coerceAtLeast(1L)
        this.detectedPhases = phases
        this.serveContacts = serves
        invalidate()
    }

    private val serveLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 22f; textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun setPlayhead(posMs: Long) {
        playheadMs = posMs
        invalidate()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val barTop = h * 0.45f
        val barBot = h * 0.88f
        val barMid = (barTop + barBot) / 2f

        // Фоновая полоса
        val bgRect = RectF(0f, barTop, w, barBot)
        bgPaint.color = Color.argb(120, 30, 30, 30)
        canvas.drawRoundRect(bgRect, 6f, 6f, bgPaint)

        // Сегменты фаз + подписи внутри
        if (detectedPhases.isNotEmpty()) {
            for (i in detectedPhases.indices) {
                val segStart = msToX(detectedPhases[i].timeMs, w)
                val segEnd = if (i + 1 < detectedPhases.size)
                    msToX(detectedPhases[i + 1].timeMs, w) else w
                val segW = segEnd - segStart

                // Заливка сегмента
                bgPaint.color = phaseColors[detectedPhases[i].phase] ?: Color.GRAY
                canvas.drawRect(segStart, barTop, segEnd, barBot, bgPaint)

                // Вертикальная метка начала фазы (кроме первой)
                if (i > 0) {
                    canvas.drawLine(segStart, barTop, segStart, barBot, tickPaint)
                }

                // Подпись фазы внутри сегмента (если достаточно места)
                val label = phaseShortNames[detectedPhases[i].phase] ?: ""
                val labelW = phaseLabelPaint.measureText(label)
                if (segW > labelW + 8f) {
                    val cx = segStart + segW / 2f
                    // Тень под текст
                    shadowPaint.style = Paint.Style.FILL
                    canvas.drawRoundRect(
                        cx - labelW / 2f - 4f, barMid - phaseLabelPaint.textSize,
                        cx + labelW / 2f + 4f, barMid + 6f,
                        4f, 4f, shadowPaint
                    )
                    canvas.drawText(label, cx, barMid, phaseLabelPaint)
                }
            }
        }

        // Номера подач над полосой (если их несколько)
        if (serveContacts.size > 1) {
            for ((i, c) in serveContacts.withIndex()) {
                val x = msToX(c, w)
                val label = "Подача ${i + 1}"
                val lw = serveLabelPaint.measureText(label)
                shadowPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(
                    x - lw / 2f - 6f, barTop - serveLabelPaint.textSize - 10f,
                    x + lw / 2f + 6f, barTop - 4f, 5f, 5f, shadowPaint
                )
                canvas.drawText(label, x, barTop - 10f, serveLabelPaint)
                // Вертикальный штрих к контакту
                canvas.drawLine(x, barTop, x, barBot, playheadPaint)
            }
        }

        // Playhead
        val ph = msToX(playheadMs, w)
        canvas.drawLine(ph, 0f, ph, h, playheadPaint)
    }


    private fun msToX(ms: Long, w: Float) = (ms.toFloat() / durationMs * w).coerceIn(0f, w)
    private fun xToMs(x: Float, w: Float) = ((x / w) * durationMs).toLong().coerceIn(0L, durationMs)

}
