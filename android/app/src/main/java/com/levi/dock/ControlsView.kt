package com.levi.dock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent

/** 05 Quick Controls: allow-listed laptop actions (the host only accepts known ids). */
class ControlsView(context: Context, private val run: (String) -> Unit) : LeviScreen(context, 4) {

    private class Action(val id: String, val label: String, val icon: Int, val risky: Boolean = false)

    private val actions = listOf(
        Action("lock", "Lock Windows", 0),
        Action("screenshot", "Screenshot", 1),
        Action("terminal", "Open Terminal", 2),
        Action("vscode", "Launch VS Code", 3),
        Action("explorer", "File Explorer", 4),
        Action("taskmgr", "Task Manager", 5),
        Action("mute", "Mute", 6),
        Action("volume", "Volume", 7),
        Action("sleep", "Sleep laptop", 8, risky = true),
    )

    private var linked = false
    private var servers: List<DevData.Server> = emptyList()
    private var pressed: String? = null
    private var confirming = false
    private var toast = ""
    private var toastOk = true
    private var toastAt = 0L

    fun setLinked(c: Boolean) { linked = c; setConnected(c); invalidate() }
    fun update(d: DevData) { servers = d.servers; if (isShown) invalidate() }
    fun onResult(ok: Boolean, text: String) { toast = text; toastOk = ok; toastAt = SystemClock.uptimeMillis(); invalidate() }

    override fun needsFrame(now: Long) = now - toastAt < 3200

    override fun drawContent(c: Canvas, now: Long) {
        setText(Levi.TEXT, 26f, sansMedium); c.drawText("Quick Controls", 108f, 58f, text)
        setText(Levi.MUTED, 17f, sans, Paint.Align.RIGHT)
        c.drawText(if (linked) "Approved actions only" else "Waiting for laptop…", 928f, 58f, text)

        val colW = (546f - 24f) / 3f; val rowH = (432f - 24f) / 3f
        actions.forEachIndexed { i, a ->
            val l = 108f + (i % 3) * (colW + 12f); val t = 80f + (i / 3) * (rowH + 12f)
            val down = pressed == a.id
            c.save()
            if (down) c.scale(0.97f, 0.97f, l + colW / 2f, t + rowH / 2f)
            card(c, l, t, l + colW, t + rowH, 20f)
            if (a.risky) {
                rect.set(l + 0.5f, t + 0.5f, l + colW - 0.5f, t + rowH - 0.5f)
                stroke.color = RISKY_BORDER; stroke.strokeWidth = 1f; c.drawRoundRect(rect, 20f, 20f, stroke)
            }
            icon(c, a.icon, l + 18f, t + 18f, if (a.risky) Levi.ATTENTION else Levi.TEXT)
            if (a.id == "volume") {
                // two halves: − and +
                setText(Levi.TEXT, 19f, sansMedium); c.drawText("Volume", l + 18f, t + rowH - 22f, text)
                pill(c, l + colW - 92f, t + 18f, "−", pressed == "vol-down")
                pill(c, l + colW - 48f, t + 18f, "+", pressed == "vol-up")
            } else {
                setText(Levi.TEXT, 19f, sansMedium); c.drawText(a.label, l + 18f, t + rowH - (if (a.risky) 40f else 22f), text)
                if (a.risky) { setText(Levi.ATTENTION, 14f, sans); c.drawText("Needs confirmation", l + 18f, t + rowH - 20f, text) }
            }
            c.restore()
        }

        drawSide(c)

        // Result toast
        if (now - toastAt < 3200 && toast.isNotEmpty()) {
            val a = ((3200 - (now - toastAt)) / 400f).coerceIn(0f, 1f)
            rect.set(682f, 466f, 914f, 500f)
            fill.color = if (toastOk) Levi.ACCENT_DIM else Levi.SUNK; fill.alpha = (255 * a).toInt()
            c.drawRoundRect(rect, 17f, 17f, fill); fill.alpha = 255
            setText(if (toastOk) Levi.TEXT else Levi.ATTENTION, 14f, sans)
            text.alpha = (255 * a).toInt()
            c.drawText(fit(toast, 210f), 694f, 488f, text)
            text.alpha = 255
        }

        if (confirming) drawConfirm(c)
    }

    private fun drawSide(c: Canvas) {
        card(c, 668f, 80f, 928f, 512f, 20f)
        setText(Levi.MUTED, 16f, sans); c.drawText("Laptop media", 686f, 108f, text)
        circleBtn(c, 724f, 150f, 24f, "media-prev", 0)
        circleBtn(c, 798f, 150f, 30f, "media-play", 1)
        circleBtn(c, 872f, 150f, 24f, "media-next", 2)

        stroke.color = Levi.LINE; stroke.strokeWidth = 1f; c.drawLine(686f, 204f, 910f, 204f, stroke)
        setText(Levi.MUTED, 16f, sans); c.drawText("Services", 686f, 234f, text)
        if (servers.isEmpty()) { setText(Levi.MUTED, 15f, sans); c.drawText(if (linked) "Nothing listening" else "—", 686f, 266f, text) }
        var y = 266f
        for (s in servers.take(7)) {
            fill.color = if (s.network) Levi.ATTENTION else Levi.ACCENT
            if (s.network) { rect.set(686f, y - 9f, 694f, y - 1f); c.drawRoundRect(rect, 1.5f, 1.5f, fill) } else c.drawCircle(690f, y - 5f, 4f, fill)
            setText(Levi.TEXT, 15f, sans); c.drawText(fit(s.name, 130f), 704f, y, text)
            setText(Levi.MUTED, 14f, mono, Paint.Align.RIGHT); c.drawText(":${s.port}", 910f, y, text)
            y += 28f
        }
    }

    private fun drawConfirm(c: Canvas) {
        fill.color = Color.argb(200, 5, 6, 7); c.drawRect(76f, 0f, 960f, 540f, fill)
        rect.set(298f, 170f, 738f, 370f)
        fill.color = Levi.SURFACE; c.drawRoundRect(rect, 24f, 24f, fill)
        stroke.color = RISKY_BORDER; stroke.strokeWidth = 1f; c.drawRoundRect(rect, 24f, 24f, stroke)
        setText(Levi.TEXT, 24f, sansMedium); c.drawText("Put the laptop to sleep?", 324f, 214f, text)
        setText(Levi.TEXT_2, 17f, sans)
        c.drawText("Running builds and the dock link will pause", 324f, 248f, text)
        c.drawText("until you wake the laptop.", 324f, 272f, text)
        // buttons
        rect.set(484f, 300f, 594f, 348f); fill.color = Levi.SUNK; c.drawRoundRect(rect, 16f, 16f, fill)
        stroke.color = Levi.LINE_STRONG; c.drawRoundRect(rect, 16f, 16f, stroke)
        setText(Levi.TEXT, 18f, sans, Paint.Align.CENTER); c.drawText("Cancel", 539f, 330f, text)
        rect.set(606f, 300f, 714f, 348f); fill.color = Levi.ATTENTION; c.drawRoundRect(rect, 16f, 16f, fill)
        setText(Levi.GROUND, 18f, sansMedium, Paint.Align.CENTER); c.drawText("Sleep", 660f, 330f, text)
    }

    // ---- touch ----
    override fun onArtTouch(action: Int, x: Float, y: Float): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> { pressed = hit(x, y); invalidate() }
            MotionEvent.ACTION_UP -> {
                val h = hit(x, y)
                if (h != null && h == pressed) act(h)
                pressed = null; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }

    private fun hit(x: Float, y: Float): String? {
        if (confirming) {
            return when {
                x in 484f..594f && y in 300f..348f -> "cancel"
                x in 606f..714f && y in 300f..348f -> "confirm-sleep"
                else -> null
            }
        }
        fun inC(cx: Float, cy: Float, r: Float) = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
        if (inC(724f, 150f, 28f)) return "media-prev"
        if (inC(798f, 150f, 34f)) return "media-play"
        if (inC(872f, 150f, 28f)) return "media-next"
        val colW = (546f - 24f) / 3f; val rowH = (432f - 24f) / 3f
        actions.forEachIndexed { i, a ->
            val l = 108f + (i % 3) * (colW + 12f); val t = 80f + (i / 3) * (rowH + 12f)
            if (x in l..(l + colW) && y in t..(t + rowH)) {
                if (a.id == "volume") return if (x < l + colW - 50f) "vol-down" else "vol-up"
                return a.id
            }
        }
        return null
    }

    private fun act(id: String) {
        when (id) {
            "sleep" -> confirming = true
            "cancel" -> confirming = false
            "confirm-sleep" -> { confirming = false; run("sleep") }
            else -> run(id)
        }
    }

    // ---- drawing helpers ----
    private fun pill(c: Canvas, l: Float, t: Float, label: String, down: Boolean) {
        rect.set(l, t, l + 38f, t + 38f); if (down) rect.inset(1f, 1f)
        fill.color = Levi.SUNK; c.drawRoundRect(rect, 12f, 12f, fill)
        stroke.color = Levi.LINE_STRONG; stroke.strokeWidth = 1f; c.drawRoundRect(rect, 12f, 12f, stroke)
        setText(Levi.TEXT, 22f, sansMedium, Paint.Align.CENTER); c.drawText(label, l + 19f, t + 27f, text)
    }

    private fun circleBtn(c: Canvas, cx: Float, cy: Float, r: Float, id: String, kind: Int) {
        val rr = if (pressed == id) r * 0.97f else r
        if (kind == 1) { fill.color = Levi.ACCENT; c.drawCircle(cx, cy, rr, fill) }
        else { fill.color = Levi.SUNK; c.drawCircle(cx, cy, rr, fill); stroke.color = Levi.LINE_STRONG; stroke.strokeWidth = 1f; c.drawCircle(cx, cy, rr - 0.5f, stroke) }
        val col = if (kind == 1) Levi.GROUND else Levi.TEXT
        fill.color = col; stroke.color = col; stroke.strokeWidth = 1.8f
        path.reset()
        when (kind) {
            0 -> { path.moveTo(cx + 6f, cy - 7f); path.lineTo(cx - 3f, cy); path.lineTo(cx + 6f, cy + 7f); path.close(); c.drawPath(path, stroke); c.drawLine(cx - 6f, cy - 6f, cx - 6f, cy + 6f, stroke) }
            1 -> { path.moveTo(cx - 5f, cy - 9f); path.lineTo(cx + 9f, cy); path.lineTo(cx - 5f, cy + 9f); path.close(); c.drawPath(path, fill) }
            2 -> { path.moveTo(cx - 6f, cy - 7f); path.lineTo(cx + 3f, cy); path.lineTo(cx - 6f, cy + 7f); path.close(); c.drawPath(path, stroke); c.drawLine(cx + 6f, cy - 6f, cx + 6f, cy + 6f, stroke) }
        }
    }

    /** 28 px stroke icons. */
    private fun icon(c: Canvas, which: Int, x: Float, y: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = 1.9f
        c.save(); c.translate(x, y); c.scale(28f / 24f, 28f / 24f)
        path.reset()
        when (which) {
            0 -> { rect.set(4f, 11f, 20f, 21f); c.drawRoundRect(rect, 2f, 2f, stroke); path.moveTo(8f, 11f); path.lineTo(8f, 7f); path.quadTo(8f, 3f, 12f, 3f); path.quadTo(16f, 3f, 16f, 7f); path.lineTo(16f, 11f); c.drawPath(path, stroke) }
            1 -> { path.moveTo(3f, 7f); path.lineTo(3f, 4f); path.lineTo(6f, 4f); path.moveTo(18f, 4f); path.lineTo(21f, 4f); path.lineTo(21f, 7f); path.moveTo(21f, 17f); path.lineTo(21f, 20f); path.lineTo(18f, 20f); path.moveTo(6f, 20f); path.lineTo(3f, 20f); path.lineTo(3f, 17f); c.drawPath(path, stroke); c.drawCircle(12f, 12f, 3.5f, stroke) }
            2 -> { path.moveTo(4f, 17f); path.lineTo(10f, 11f); path.lineTo(4f, 5f); c.drawPath(path, stroke); c.drawLine(12f, 19f, 20f, 19f, stroke) }
            3 -> { path.moveTo(16f, 18f); path.lineTo(22f, 12f); path.lineTo(16f, 6f); path.moveTo(8f, 6f); path.lineTo(2f, 12f); path.lineTo(8f, 18f); c.drawPath(path, stroke) }
            4 -> { path.moveTo(3f, 7f); path.lineTo(3f, 19f); path.lineTo(21f, 19f); path.lineTo(21f, 9f); path.lineTo(11f, 9f); path.lineTo(9f, 6f); path.lineTo(3f, 6f); path.close(); c.drawPath(path, stroke) }
            5 -> { path.moveTo(2f, 12f); path.lineTo(6f, 12f); path.lineTo(9f, 5f); path.lineTo(15f, 19f); path.lineTo(18f, 12f); path.lineTo(22f, 12f); c.drawPath(path, stroke) }
            6 -> { path.moveTo(11f, 5f); path.lineTo(6f, 9f); path.lineTo(2f, 9f); path.lineTo(2f, 15f); path.lineTo(6f, 15f); path.lineTo(11f, 19f); path.close(); c.drawPath(path, stroke); c.drawLine(16f, 9f, 22f, 15f, stroke); c.drawLine(22f, 9f, 16f, 15f, stroke) }
            7 -> { path.moveTo(11f, 5f); path.lineTo(6f, 9f); path.lineTo(2f, 9f); path.lineTo(2f, 15f); path.lineTo(6f, 15f); path.lineTo(11f, 19f); path.close(); c.drawPath(path, stroke); path.reset(); path.moveTo(15.5f, 8.5f); path.quadTo(18f, 12f, 15.5f, 15.5f); c.drawPath(path, stroke) }
            8 -> { path.moveTo(21f, 12.8f); path.quadTo(18f, 21f, 11f, 20.5f); path.quadTo(3f, 19f, 3.5f, 11f); path.quadTo(4.5f, 4f, 11.2f, 3f); path.quadTo(8f, 9f, 12.5f, 13.5f); path.quadTo(16.5f, 16.5f, 21f, 12.8f); path.close(); c.drawPath(path, stroke) }
        }
        c.restore()
    }

    private fun fit(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        val n = text.breakText(s, true, maxW - text.measureText("…"), null)
        return s.substring(0, n).trimEnd() + "…"
    }

    companion object {
        private val RISKY_BORDER = Color.parseColor("#3A2A12")
    }
}
