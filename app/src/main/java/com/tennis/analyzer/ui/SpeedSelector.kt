package com.tennis.analyzer.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Компактный сегментированный переключатель скорости воспроизведения.
 * Тап по значению → колбэк [onSelect]. Выделенный сегмент подсвечивается.
 */
class SpeedSelector(context: Context) : LinearLayout(context) {

    private data class Item(val speed: Float, val label: String, val view: TextView)

    private val items = mutableListOf<Item>()
    var onSelect: ((Float) -> Unit)? = null

    init {
        val dp = resources.displayMetrics.density
        orientation = HORIZONTAL
        setPadding((3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 22f * dp
            setColor(Color.argb(0x99, 40, 40, 46))
        }
    }

    /** Сеттит доступные скорости. Формат: список пар (скорость, подпись). */
    fun setSpeeds(speeds: List<Pair<Float, String>>, initial: Float) {
        removeAllViews()
        items.clear()
        val dp = resources.displayMetrics.density
        for ((sp, lbl) in speeds) {
            val tv = TextView(context).apply {
                text = lbl
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 20f * dp
                    setColor(Color.TRANSPARENT)
                }
                setOnClickListener { select(sp) }
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            }
            addView(tv)
            items.add(Item(sp, lbl, tv))
        }
        select(initial, notify = false)
    }

    fun select(speed: Float, notify: Boolean = true) {
        val dp = resources.displayMetrics.density
        val eps = 0.001f
        var found: Item? = null
        for (it in items) {
            val active = kotlin.math.abs(it.speed - speed) < eps
            val bg = it.view.background as GradientDrawable
            bg.setColor(if (active) Color.rgb(76, 175, 80) else Color.TRANSPARENT)
            it.view.setTextColor(if (active) Color.WHITE else Color.argb(0xDD, 255, 255, 255))
            it.view.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            if (active) found = it
        }
        if (notify && found != null) onSelect?.invoke(found.speed)
    }
}
