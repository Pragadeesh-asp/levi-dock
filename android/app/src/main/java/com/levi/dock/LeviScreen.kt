package com.levi.dock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator

/**
 * Base for every LEVI DOCK screen: draws in the design's 960×540 artboard
 * units (scaled to the display), owns the left rail, and maps touches
 * (from scrcpy mouse clicks) back into artboard units.
 */
abstract class LeviScreen(context: Context, private val railIndex: Int) : View(context) {

    var onNavigate: ((Int) -> Unit)? = null

    private val tDot = Tween().also { it.snap(0f) }
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    protected val mono: Typeface = Typeface.MONOSPACE
    protected val sans: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    protected val sansMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    protected val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    protected val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    protected val rect = RectF()
    protected val path = Path()

    fun setConnected(c: Boolean) {
        val target = if (c) 1f else 0f
        if (tDot.target != target) { tDot.to(target); invalidate() }
    }

    final override fun onDraw(c: Canvas) {
        scale = minOf(width / 960f, height / 540f)
        offX = (width - 960f * scale) / 2f
        offY = (height - 540f * scale) / 2f
        val now = SystemClock.uptimeMillis()
        c.drawColor(Levi.GROUND)
        c.save()
        c.translate(offX, offY)
        c.scale(scale, scale)
        drawRail(c, now)
        drawContent(c, now)
        c.restore()
        // Animation frames at ~30 fps: smooth enough on the Adreno 330, half the cost of 60.
        if (tDot.active(now) || needsFrame(now)) postInvalidateDelayed(33L)
    }

    protected abstract fun drawContent(c: Canvas, now: Long)
    protected open fun needsFrame(now: Long) = false
    /** Touch in artboard units, outside the rail. Return true if handled. */
    protected open fun onArtTouch(action: Int, x: Float, y: Float): Boolean = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // The Mi 3W's broken panel floods phantom touches: never act on them.
        if (e.device?.name?.startsWith("atmel") == true) return true

        val x = (e.x - offX) / scale
        val y = (e.y - offY) / scale
        val action = e.actionMasked

        // Mouse (LEVI Mouse): act on press. While a phantom touch is active, Android
        // drops the release of other devices, so a click handled on release gets lost.
        val mouse = e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
        if (mouse) {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    if (x < 76f) navigateAt(y)
                    else { onArtTouch(MotionEvent.ACTION_DOWN, x, y); onArtTouch(MotionEvent.ACTION_UP, x, y) }
                }
                MotionEvent.ACTION_MOVE -> if (x >= 76f) onArtTouch(MotionEvent.ACTION_MOVE, x, y)
            }
            return true
        }

        if (x < 76f) {
            if (action == MotionEvent.ACTION_UP) navigateAt(y)
            return true
        }
        onArtTouch(action, x, y)
        return true
    }

    private fun navigateAt(y: Float) {
        val i = ((y - 62f) / 62f).toInt()
        if (y >= 62f && i in 0..5 && (y - 62f) % 62f <= 52f && i != railIndex) onNavigate?.invoke(i)
    }

    // ---- rail ----
    private fun drawRail(c: Canvas, now: Long) {
        stroke.color = Levi.LINE; stroke.strokeWidth = 1f
        c.drawLine(76.5f, 0f, 76.5f, 540f, stroke)
        setText(Levi.ACCENT, 15f, mono, Paint.Align.CENTER, 0.13f)
        c.drawText("LEVI", 38f, 38f, text)
        for (i in 0 until 6) {
            val top = 62f + i * 62f
            val active = i == railIndex
            if (active) {
                fill.color = Levi.ACCENT_DIM
                rect.set(12f, top, 64f, top + 52f)
                c.drawRoundRect(rect, 16f, 16f, fill)
            }
            drawIcon(c, i, 26f, top + 14f, if (active) Levi.ACCENT else Levi.MUTED, if (active) Levi.ACCENT_DIM else Levi.GROUND)
        }
        val k = tDot.value(now)
        fill.color = Levi.ACCENT; fill.alpha = (255 * k).toInt()
        c.drawCircle(38f, 510f, 5f, fill)
        fill.alpha = 255
        if (k < 1f) {
            stroke.color = Levi.MUTED; stroke.strokeWidth = 1.5f; stroke.alpha = (255 * (1 - k)).toInt()
            c.drawCircle(38f, 510f, 4.25f, stroke)
            stroke.alpha = 255
        }
    }

    private fun drawIcon(c: Canvas, which: Int, x: Float, y: Float, color: Int, bg: Int) {
        stroke.color = color; stroke.strokeWidth = 1.8f
        c.save(); c.translate(x, y)
        path.reset()
        when (which) {
            0 -> for ((rx, ry) in listOf(3f to 3f, 14f to 3f, 3f to 14f, 14f to 14f)) {
                rect.set(rx, ry, rx + 7f, ry + 7f); c.drawRoundRect(rect, 1.5f, 1.5f, stroke)
            }
            1 -> {
                path.moveTo(8f, 6f); path.lineTo(2f, 12f); path.lineTo(8f, 18f)
                path.moveTo(16f, 6f); path.lineTo(22f, 12f); path.lineTo(16f, 18f)
                c.drawPath(path, stroke)
            }
            2 -> {
                path.moveTo(9f, 18f); path.lineTo(9f, 5f); path.lineTo(21f, 3f); path.lineTo(21f, 16f)
                c.drawPath(path, stroke)
                c.drawCircle(6f, 18f, 3f, stroke); c.drawCircle(18f, 16f, 3f, stroke)
            }
            3 -> {
                path.moveTo(21f, 15f); path.quadTo(21f, 17f, 19f, 17f); path.lineTo(7f, 17f)
                path.lineTo(3f, 21f); path.lineTo(3f, 5f); path.quadTo(3f, 3f, 5f, 3f)
                path.lineTo(19f, 3f); path.quadTo(21f, 3f, 21f, 5f); path.close()
                c.drawPath(path, stroke)
            }
            4 -> {
                c.drawLine(4f, 7f, 20f, 7f, stroke); c.drawLine(4f, 17f, 20f, 17f, stroke)
                fill.color = bg
                c.drawCircle(9f, 7f, 2.4f, fill); c.drawCircle(9f, 7f, 2.4f, stroke)
                c.drawCircle(15f, 17f, 2.4f, fill); c.drawCircle(15f, 17f, 2.4f, stroke)
            }
            5 -> {
                c.drawCircle(12f, 12f, 2.6f, stroke)
                for ((cx, cy) in listOf(5f to 5f, 19f to 5f, 5f to 19f, 19f to 19f)) c.drawCircle(cx, cy, 2f, stroke)
                c.drawLine(6.5f, 6.5f, 10f, 10f, stroke); c.drawLine(17.5f, 6.5f, 14f, 10f, stroke)
                c.drawLine(6.5f, 17.5f, 10f, 14f, stroke); c.drawLine(17.5f, 17.5f, 14f, 14f, stroke)
            }
        }
        c.restore()
    }

    // ---- shared primitives ----
    protected fun setText(color: Int, size: Float, face: Typeface, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f) {
        text.color = color; text.textSize = size; text.typeface = face
        text.textAlign = align; text.letterSpacing = spacing
    }

    protected fun card(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, bg: Int = Levi.SURFACE) {
        rect.set(l, t, r, b)
        fill.color = bg; c.drawRoundRect(rect, radius, radius, fill)
        rect.inset(0.5f, 0.5f)
        stroke.color = Levi.LINE; stroke.strokeWidth = 1f; c.drawRoundRect(rect, radius, radius, stroke)
    }
}

/** Value tween with the LEVI curve cubic-bezier(.2,.8,.2,1). */
class Tween(private val duration: Float = 300f) {
    private var from = 0f
    var target = 0f; private set
    private var start = 0L
    private var first = true

    fun snap(v: Float) { from = v; target = v; start = 0L; first = false }

    fun to(v: Float, minDelta: Float = 0f) {
        val now = SystemClock.uptimeMillis()
        if (first) { snap(v); return }
        val cur = value(now)
        if (kotlin.math.abs(v - cur) < minDelta) { snap(v); return }
        from = cur; target = v; start = now
    }

    fun value(now: Long): Float {
        val k = ((now - start) / duration).coerceIn(0f, 1f)
        return from + (target - from) * CURVE.getInterpolation(k)
    }

    fun active(now: Long) = now - start < duration

    companion object {
        private val CURVE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
        fun anyActive(now: Long, vararg t: Tween) = t.any { it.active(now) }
    }
}
