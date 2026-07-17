package com.tennis.analyzer.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.tennis.analyzer.detection.ServePhase
import com.tennis.analyzer.pose.LandmarkIndex
import com.tennis.analyzer.pose.PoseFrame
import com.tennis.analyzer.R

class SkeletonOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var poseFrame: PoseFrame? = null
    private var currentPhase: ServePhase = ServePhase.IDLE
    private var lastScore: Float? = null
    private var adviceLines: List<String> = emptyList()
    private var adviceAlpha: Float = 0f          // плавное появление/исчезновение
    private var adviceShowUntilMs: Long = 0L

    // --- Paints ---

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.FILL
    }

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val phaseBackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val phasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        isFakeBoldText = true
    }

    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 80f
        isFakeBoldText = true
    }

    private val adviceBackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val adviceTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        isFakeBoldText = true
    }

    private val adviceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
    }

    private val adviceIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 44f
    }

    private val rectF = RectF()

    // Подсказки для каждой фазы: заголовок + описание что делать
    private fun s(id: Int) = context.getString(id)
    private val phaseHints by lazy {
        mapOf(
            ServePhase.IDLE           to Pair(s(R.string.hint_idle_t),    s(R.string.hint_idle_b)),
            ServePhase.READY_STANCE   to Pair(s(R.string.hint_stance_t),  s(R.string.hint_stance_b)),
            ServePhase.TOSS           to Pair(s(R.string.hint_toss_t),    s(R.string.hint_toss_b)),
            ServePhase.TROPHY         to Pair(s(R.string.hint_trophy_t),  s(R.string.hint_trophy_b)),
            ServePhase.ACCELERATION   to Pair(s(R.string.hint_accel_t),   s(R.string.hint_accel_b)),
            ServePhase.CONTACT        to Pair(s(R.string.hint_contact_t), s(R.string.hint_contact_b)),
            ServePhase.FOLLOW_THROUGH to Pair(s(R.string.hint_follow_t),  s(R.string.hint_follow_b))
        )
    }

    private val hintTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        isFakeBoldText = true
    }

    private val hintBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 220, 220, 220)
        textSize = 30f
    }

    private val hintBackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun update(frame: PoseFrame, phase: ServePhase) {
        poseFrame = frame
        currentPhase = phase

        // Гасим совет если время вышло
        if (System.currentTimeMillis() > adviceShowUntilMs) {
            adviceAlpha = (adviceAlpha - 0.05f).coerceAtLeast(0f)
        }
        invalidate()
    }

    fun showScore(score: Float) {
        lastScore = score
        invalidate()
    }

    fun showAdvice(lines: List<String>, durationMs: Long = 5000L) {
        adviceLines = lines
        adviceAlpha = 1f
        adviceShowUntilMs = System.currentTimeMillis() + durationMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = poseFrame ?: return

        drawSkeleton(canvas, frame)
        drawPhaseChip(canvas)
        drawScore(canvas)

        // Подсказки показываем только когда нет советов после подачи
        if (adviceAlpha <= 0f) {
            drawPhaseHint(canvas)
        } else {
            drawAdviceCard(canvas)
        }
    }

    private fun drawSkeleton(canvas: Canvas, frame: PoseFrame) {
        bonePaint.color = phaseColor(currentPhase)
        for ((a, b) in LandmarkIndex.CONNECTIONS) {
            val lmA = frame.landmarks.getOrNull(a) ?: continue
            val lmB = frame.landmarks.getOrNull(b) ?: continue
            if (lmA.visibility < 0.4f || lmB.visibility < 0.4f) continue
            canvas.drawLine(lmA.x * width, lmA.y * height,
                            lmB.x * width, lmB.y * height, bonePaint)
        }
        for (lm in frame.landmarks) {
            if (lm.visibility < 0.4f) continue
            canvas.drawCircle(lm.x * width, lm.y * height, 7f, jointPaint)
        }
    }

    private fun drawPhaseChip(canvas: Canvas) {
        val label = phaseLabel(currentPhase)
        val color = phaseColor(currentPhase)
        val textW = phasePaint.measureText(label)
        val padH = 20f; val padV = 12f; val r = 24f
        val left = 24f; val top = 48f

        phaseBackPaint.color = withAlpha(color, 0xCC)
        rectF.set(left, top, left + textW + padH * 2, top + phasePaint.textSize + padV * 2)
        canvas.drawRoundRect(rectF, r, r, phaseBackPaint)
        canvas.drawText(label, left + padH, top + padV + phasePaint.textSize - 8f, phasePaint)
    }

    private fun drawScore(canvas: Canvas) {
        val score = lastScore ?: return
        val text = "${score.toInt()}"
        scorePaint.color = scoreColor(score)
        val x = width - scorePaint.measureText(text) - 28f
        canvas.drawText(text, x, 100f, scorePaint)
        // Подпись "балл" убрана — цифра говорит сама за себя
    }

    private fun drawPhaseHint(canvas: Canvas) {
        val hint = phaseHints[currentPhase] ?: return
        val (title, body) = hint

        // Разбиваем длинный текст на строки (макс ~30 символов на строку)
        val bodyLines = wrapText(body, hintBodyPaint, width * 0.85f)

        val padH = 20f
        val padV = 14f
        val titleH = hintTitlePaint.textSize + 6f
        val bodyH = bodyLines.size * (hintBodyPaint.textSize + 8f)
        val cardH = titleH + bodyH + padV * 2
        val cardW = width * 0.88f
        val left = (width - cardW) / 2f
        val top = height * 0.72f  // нижняя треть экрана

        // Цветная полоса слева = цвет текущей фазы
        val accentColor = phaseColor(currentPhase)

        hintBackPaint.color = Color.argb(185, 0, 0, 0)
        rectF.set(left, top, left + cardW, top + cardH)
        canvas.drawRoundRect(rectF, 20f, 20f, hintBackPaint)

        // Акцентная полоса
        hintBackPaint.color = accentColor
        rectF.set(left, top, left + 6f, top + cardH)
        canvas.drawRoundRect(rectF, 3f, 3f, hintBackPaint)

        // Заголовок
        hintTitlePaint.color = accentColor
        canvas.drawText(title, left + padH, top + padV + hintTitlePaint.textSize, hintTitlePaint)

        // Тело
        hintBodyPaint.color = Color.argb(220, 220, 220, 220)
        bodyLines.forEachIndexed { i, line ->
            canvas.drawText(
                line,
                left + padH,
                top + padV + titleH + i * (hintBodyPaint.textSize + 8f) + hintBodyPaint.textSize,
                hintBodyPaint
            )
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val test = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(test) <= maxWidth) {
                current = test
            } else {
                if (current.isNotEmpty()) lines.add(current)
                current = word
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }

    private fun drawAdviceCard(canvas: Canvas) {
        if (adviceLines.isEmpty()) return

        val cardW = width * 0.88f
        val left = (width - cardW) / 2f
        val lineH = adviceTextPaint.textSize + 14f
        val titleH = adviceTitlePaint.textSize + 10f
        val cardH = titleH + adviceLines.size * lineH + 40f
        val top = height - cardH - 120f   // над кнопкой

        // Фон карточки
        adviceBackPaint.color = Color.argb((adviceAlpha * 210).toInt(), 0, 0, 0)
        rectF.set(left, top, left + cardW, top + cardH)
        canvas.drawRoundRect(rectF, 28f, 28f, adviceBackPaint)

        // Заголовок
        adviceTitlePaint.color = Color.argb((adviceAlpha * 255).toInt(), 255, 220, 60)
        canvas.drawText(s(R.string.advice_card_title), left + 24f, top + titleH, adviceTitlePaint)

        // Разделитель
        val divPaint = Paint().apply {
            color = Color.argb((adviceAlpha * 80).toInt(), 255, 255, 255)
            strokeWidth = 1f
        }
        canvas.drawLine(left + 16f, top + titleH + 8f,
                        left + cardW - 16f, top + titleH + 8f, divPaint)

        // Строки советов
        adviceTextPaint.color = Color.argb((adviceAlpha * 255).toInt(), 255, 255, 255)
        adviceLines.forEachIndexed { i, line ->
            val y = top + titleH + 20f + (i + 1) * lineH
            canvas.drawText("• $line", left + 20f, y, adviceTextPaint)
        }
    }

    // --- helpers ---

    private fun phaseColor(phase: ServePhase) = when (phase) {
        ServePhase.IDLE           -> Color.rgb(150, 150, 150)
        ServePhase.READY_STANCE   -> Color.rgb(76,  175, 80)
        ServePhase.TOSS           -> Color.rgb(255, 235, 59)
        ServePhase.TROPHY         -> Color.rgb(0,   188, 212)
        ServePhase.BACKSCRATCH    -> Color.rgb(255, 152, 0)
        ServePhase.ACCELERATION   -> Color.rgb(244, 67,  54)
        ServePhase.CONTACT        -> Color.rgb(233, 30,  99)
        ServePhase.FOLLOW_THROUGH -> Color.rgb(156, 39,  176)
    }

    private fun phaseLabel(phase: ServePhase) = context.getString(when (phase) {
        ServePhase.IDLE           -> com.tennis.analyzer.R.string.phase_idle
        ServePhase.READY_STANCE   -> com.tennis.analyzer.R.string.phase_stance
        ServePhase.TOSS           -> com.tennis.analyzer.R.string.phase_toss
        ServePhase.TROPHY         -> com.tennis.analyzer.R.string.phase_trophy
        ServePhase.BACKSCRATCH    -> com.tennis.analyzer.R.string.phase_backscratch
        ServePhase.ACCELERATION   -> com.tennis.analyzer.R.string.phase_acceleration
        ServePhase.CONTACT        -> com.tennis.analyzer.R.string.phase_contact
        ServePhase.FOLLOW_THROUGH -> com.tennis.analyzer.R.string.phase_follow
    })

    private fun scoreColor(score: Float) = when {
        score >= 80f -> Color.rgb(76, 175, 80)
        score >= 60f -> Color.rgb(255, 193, 7)
        else         -> Color.rgb(244, 67, 54)
    }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
