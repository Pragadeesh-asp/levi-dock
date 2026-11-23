package com.levi.dock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View

/** "Hey LEVI · …" pill shown over every screen when the laptop hears a voice command. */
class VoiceBanner(context: Context) : View(context) {

    private var text = ""
    private var shownAt = 0L
    private var duration = 2600L
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = 15f; color = Levi.ACCENT; letterSpacing = 0.1f }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); textSize = 18f; color = Levi.TEXT }
    private val rect = RectF()

    init { isClickable = false; isFocusable = false }

    fun show(t: String) {
        text = t.take(80)
        duration = if (t == "Listening…") 8000L else 2600L
        shownAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val age = SystemClock.uptimeMillis() - shownAt
        if (text.isEmpty() || age > duration) return
        // 180 ms fade in, 300 ms fade out (opacity + slide only).
        val a = minOf(age / 180f, (duration - age) / 300f, 1f).coerceIn(0f, 1f)
        val s = minOf(width / 960f, height / 540f)
        c.save()
        c.translate((width - 960f * s) / 2f, (height - 540f * s) / 2f + (1f - a) * -8f * s)
        c.scale(s, s)
        val w = label.measureText("HEY LEVI") + 24f + body.measureText(text) + 40f
        val l = 518f - w / 2f
        rect.set(l, 14f, l + w, 54f)
        fill.color = Levi.SURFACE; fill.alpha = (245 * a).toInt(); c.drawRoundRect(rect, 20f, 20f, fill)
        stroke.color = Levi.ACCENT; stroke.alpha = (200 * a).toInt(); c.drawRoundRect(rect, 20f, 20f, stroke)
        label.alpha = (255 * a).toInt(); body.alpha = (255 * a).toInt()
        c.drawText("HEY LEVI", l + 20f, 40f, label)
        c.drawText(text, l + 20f + label.measureText("HEY LEVI") + 16f, 40f, body)
        c.restore()
        postInvalidateDelayed(33L)
    }
}
