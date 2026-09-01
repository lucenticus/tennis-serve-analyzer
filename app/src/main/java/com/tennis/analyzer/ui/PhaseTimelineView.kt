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
    // Более тонкие и тусклые разделители фаз внутри одной подачи: с несколькими подачами
    // на баре их набирается по 6-7 на каждую, и на полной яркости (180) они спорили за
    // внимание с самими цветными сегментами. Границы фаз остаются видны, но не кричат.
    private val tickPaint = Paint().apply {
        color = Color.argb(90, 255, 255, 255); strokeWidth = 1.5f; style = Paint.Style.STROKE
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

    // Названия фаз внутри сегментов раньше рисовались текстом прямо на баре — при
    // нескольких подачах на баре получалось до 6×7 узких сегментов, и подписи либо не
    // влезали (мигали то есть, то нет), либо перекрывались. Название текущей фазы и так
    // видно рядом со счётом ("● Стойка"), а полный разбор — в диалоге по тапу на совет,
    // так что здесь достаточно только цвета сегмента, без текста.

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

    // Номер подачи ("1", "2"…) вместо полного "Подача 1" — тот же смысл читается из
    // контекста (счётчик "Подач: N" уже виден в шапке), а короткая цифра почти никогда
    // не перекрывается с соседями, даже когда подач много и они стоят близко друг к другу.
    private val serveLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 18f; textAlign = Paint.Align.CENTER
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

        // Фоновая полоса
        val bgRect = RectF(0f, barTop, w, barBot)
        bgPaint.color = Color.argb(120, 30, 30, 30)
        canvas.drawRoundRect(bgRect, 6f, 6f, bgPaint)

        // Сегменты фаз — только цвет, без текста внутри (см. комментарий у поля выше)
        if (detectedPhases.isNotEmpty()) {
            for (i in detectedPhases.indices) {
                val segStart = msToX(detectedPhases[i].timeMs, w)
                val segEnd = if (i + 1 < detectedPhases.size)
                    msToX(detectedPhases[i + 1].timeMs, w) else w

                bgPaint.color = phaseColors[detectedPhases[i].phase] ?: Color.GRAY
                canvas.drawRect(segStart, barTop, segEnd, barBot, bgPaint)

                // Вертикальная метка начала фазы (кроме первой)
                if (i > 0) {
                    canvas.drawLine(segStart, barTop, segStart, barBot, tickPaint)
                }
            }
        }

        // Номера подач над полосой (если их несколько) — просто "1", "2"…, и только там,
        // где для метки реально есть место, иначе на коротких подачах подряд цифры
        // налезали бы друг на друга.
        if (serveContacts.size > 1) {
            val minGap = serveLabelPaint.textSize * 1.6f
            var lastLabelX = Float.NEGATIVE_INFINITY
            for ((i, c) in serveContacts.withIndex()) {
                val x = msToX(c, w)
                // Вертикальный штрих к контакту рисуем всегда — это просто отметка на баре
                canvas.drawLine(x, barTop, x, barBot, playheadPaint)
                if (x - lastLabelX < minGap) continue
                lastLabelX = x
                val label = (i + 1).toString()
                val lw = serveLabelPaint.measureText(label)
                shadowPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(
                    x - lw / 2f - 6f, barTop - serveLabelPaint.textSize - 8f,
                    x + lw / 2f + 6f, barTop - 4f, 5f, 5f, shadowPaint
                )
                canvas.drawText(label, x, barTop - 10f, serveLabelPaint)
            }
        }

        // Playhead
        val ph = msToX(playheadMs, w)
        canvas.drawLine(ph, 0f, ph, h, playheadPaint)
    }


    private fun msToX(ms: Long, w: Float) = (ms.toFloat() / durationMs * w).coerceIn(0f, w)
    private fun xToMs(x: Float, w: Float) = ((x / w) * durationMs).toLong().coerceIn(0L, durationMs)

}
