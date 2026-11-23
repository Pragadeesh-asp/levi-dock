package com.levi.dock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent

/** 07 LEVI Ecosystem: app tiles + shared keyboard & trackpad. */
class EcosystemView(context: Context, private val actions: Actions) : LeviScreen(context, 5) {

    interface Actions {
        fun openApp(id: String)
        fun openBitChord()
        fun setInputMode(dock: Boolean)
        fun setClipboardShare(on: Boolean)
        fun paired(): Boolean
    }

    private class Tile(val id: String, val mark: String, val name: String, var detail: String, var running: Boolean, var action: Boolean)

    private var apps: List<DevData.App> = emptyList()
    private var host = "the laptop"
    private var linked = false
    private var inputDock = false
    private var inputReady = false
    private var clipboard = true
    private var bitchordInstalled = false
    private var bitchordPlaying = false

    private var pressed: String? = null
    private val opening = HashMap<String, Long>()
    private val tMode = Tween(220f).also { it.snap(0f) }

    fun setLinked(c: Boolean) { linked = c; setConnected(c); invalidate() }
    fun setHost(name: String) { host = name }
    fun update(d: DevData) { apps = d.apps; if (isShown) invalidate() }
    fun setPhoneApps(bitchord: Boolean, playing: Boolean) { bitchordInstalled = bitchord; bitchordPlaying = playing; if (isShown) invalidate() }

    fun setInput(dock: Boolean, ready: Boolean, clip: Boolean) {
        if (dock != inputDock) tMode.to(if (dock) 1f else 0f)
        inputDock = dock; inputReady = ready; clipboard = clip
        invalidate()
    }

    override fun needsFrame(now: Long) = tMode.active(now) || opening.values.any { now - it < 2500 }

    private fun tiles(): List<Tile> {
        fun app(id: String) = apps.firstOrNull { it.id == id }
        fun fromHost(id: String, mark: String, fallback: String): Tile {
            val a = app(id)
            return if (a == null) Tile(id, mark, fallback, if (linked) "…" else "Laptop not linked", false, false)
                   else Tile(id, mark, a.name, a.detail, a.running, a.launchable)
        }
        return listOf(
            fromHost("command-center", "CC", "LEVI Command Center"),
            fromHost("browser", "BR", "LEVI Browser"),
            fromHost("jarvis", "JV", "JARVIS"),
            fromHost("growth", "PG", "LEVI Personal Growth"),
            Tile("bitchord", "BC", "BitChord",
                if (!bitchordInstalled) "Not installed" else if (bitchordPlaying) "Playing on this dock" else "On this dock",
                bitchordPlaying, bitchordInstalled),
            Tile("dock", "DK", "LEVI DOCK", if (linked) "This device · linked" else "This device", linked, false),
        )
    }

    override fun drawContent(c: Canvas, now: Long) {
        setText(Levi.TEXT, 26f, sansMedium); c.drawText("LEVI Ecosystem", 108f, 58f, text)
        setText(Levi.MUTED, 17f, sans, Paint.Align.RIGHT); c.drawText(if (linked) "All apps on $host" else "Waiting for laptop…", 928f, 58f, text)

        // App tiles: 3 × 2
        val list = tiles()
        val colW = (520f - 24f) / 3f; val rowH = (432f - 12f) / 2f
        list.forEachIndexed { i, t ->
            val l = 108f + (i % 3) * (colW + 12f); val top = 80f + (i / 3) * (rowH + 12f)
            val down = pressed == t.id
            val busy = opening[t.id]?.let { now - it < 2500 } ?: false
            c.save()
            if (down) c.scale(0.97f, 0.97f, l + colW / 2f, top + rowH / 2f)
            card(c, l, top, l + colW, top + rowH, 20f, if (busy) Levi.ACCENT_DIM else Levi.SURFACE)

            // mark
            rect.set(l + 16f, top + 16f, l + 56f, top + 56f)
            fill.color = Levi.SUNK; c.drawRoundRect(rect, 12f, 12f, fill)
            stroke.color = Levi.LINE_STRONG; stroke.strokeWidth = 1f; c.drawRoundRect(rect, 12f, 12f, stroke)
            setText(Levi.ACCENT, 15f, mono, Paint.Align.CENTER); c.drawText(t.mark, l + 36f, top + 41f, text)
            // status dot (filled = running, ring = not running)
            if (t.running) { fill.color = Levi.ACCENT; c.drawCircle(l + colW - 22f, top + 26f, 5f, fill) }
            else { stroke.color = Levi.MUTED; stroke.strokeWidth = 1.5f; c.drawCircle(l + colW - 22f, top + 26f, 4.25f, stroke) }

            setText(Levi.TEXT, 19f, sansMedium)
            val nameLines = wrap2(t.name, colW - 32f)
            val nameTop = top + rowH - (if (nameLines.second.isEmpty()) 58f else 82f)
            c.drawText(nameLines.first, l + 16f, nameTop, text)
            if (nameLines.second.isNotEmpty()) c.drawText(nameLines.second, l + 16f, nameTop + 24f, text)
            setText(if (busy) Levi.ACCENT else Levi.MUTED, 14f, sans)
            c.drawText(fit(if (busy) "Opening on laptop…" else t.detail, colW - 32f), l + 16f, top + rowH - 22f, text)
            c.restore()
        }

        drawInputPanel(c, now)
    }

    private fun drawInputPanel(c: Canvas, now: Long) {
        card(c, 642f, 80f, 928f, 512f, 20f)
        setText(Levi.MUTED, 16f, sans); c.drawText("Shared keyboard & trackpad", 660f, 108f, text)

        // Diagram: laptop → dock, the one in control lit.
        val k = tMode.value(now)
        box(c, 660f, 128f, 810f, 220f, 1f - k, "Laptop")
        stroke.color = Levi.MUTED; stroke.strokeWidth = 1.6f
        c.drawLine(820f, 174f, 842f, 174f, stroke); c.drawLine(836f, 168f, 842f, 174f, stroke); c.drawLine(836f, 180f, 842f, 174f, stroke)
        box(c, 850f, 154f, 910f, 194f, k, "Dock")

        // Segmented control
        rect.set(660f, 240f, 910f, 292f); fill.color = Levi.SUNK; c.drawRoundRect(rect, 14f, 14f, fill)
        val segW = (250f - 8f - 6f) / 2f
        val selL = 664f + k * (segW + 6f)
        rect.set(selL, 244f, selL + segW, 288f); fill.color = Levi.ACCENT; c.drawRoundRect(rect, 10f, 10f, fill)
        setText(if (k < 0.5f) Levi.GROUND else Levi.TEXT_2, 16f, if (k < 0.5f) sansMedium else sans, Paint.Align.CENTER)
        c.drawText("Laptop", 664f + segW / 2f, 272f, text)
        setText(if (k >= 0.5f) Levi.GROUND else Levi.TEXT_2, 16f, if (k >= 0.5f) sansMedium else sans, Paint.Align.CENTER)
        c.drawText("Dock", 664f + segW + 6f + segW / 2f, 272f, text)

        // How-to
        setText(Levi.TEXT_2, 15f, sans)
        c.drawText("Push the cursor past the laptop's", 660f, 322f, text)
        c.drawText("right edge, or press", 660f, 342f, text)
        val w = text.measureText("right edge, or press ")
        setText(Levi.TEXT, 15f, mono); c.drawText("Ctrl+Alt+D", 660f + w, 342f, text)
        setText(Levi.TEXT_2, 15f, sans); c.drawText("Push off the dock's left edge to return.", 660f, 362f, text)

        // Clipboard checkbox
        rect.set(660f, 388f, 682f, 410f)
        if (clipboard) {
            fill.color = Levi.ACCENT; c.drawRoundRect(rect, 5f, 5f, fill)
            stroke.color = Levi.GROUND; stroke.strokeWidth = 2.2f
            c.drawLine(665f, 399f, 669.5f, 404f, stroke); c.drawLine(669.5f, 404f, 677f, 393f, stroke)
        } else {
            stroke.color = Levi.MUTED; stroke.strokeWidth = 1.5f; c.drawRoundRect(rect, 5f, 5f, stroke)
        }
        setText(Levi.TEXT, 16f, sans); c.drawText("Share clipboard", 694f, 405f, text)

        // Status
        val status = when {
            !linked -> "Laptop not linked"
            !actions.paired() -> "Not paired yet — restart levi-host"
            !inputReady -> "Starting LEVI Keyboard + Mouse…"
            inputDock -> "Your keyboard and trackpad are here"
            else -> "Ready · LEVI Keyboard + Mouse"
        }
        fill.color = if (linked && inputReady) Levi.ACCENT else Levi.MUTED
        c.drawCircle(665f, 484f, 4f, fill)
        setText(Levi.MUTED, 14f, sans); c.drawText(fit(status, 230f), 676f, 489f, text)
    }

    private fun box(c: Canvas, l: Float, t: Float, r: Float, b: Float, lit: Float, label: String) {
        rect.set(l, t, r, b)
        fill.color = blend(Levi.SUNK, Levi.ACCENT_DIM, lit); c.drawRoundRect(rect, 10f, 10f, fill)
        stroke.color = blend(Levi.LINE_STRONG, Levi.ACCENT, lit); stroke.strokeWidth = 2f
        rect.inset(1f, 1f); c.drawRoundRect(rect, 10f, 10f, stroke)
        setText(Levi.MUTED, 12f, sans, Paint.Align.CENTER); c.drawText(label, (l + r) / 2f, b - 8f, text)
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
        if (x in 660f..785f && y in 240f..292f) return "mode-laptop"
        if (x in 785f..910f && y in 240f..292f) return "mode-dock"
        if (x in 656f..860f && y in 382f..416f) return "clipboard"
        val colW = (520f - 24f) / 3f; val rowH = (432f - 12f) / 2f
        val list = tiles()
        for (i in list.indices) {
            val l = 108f + (i % 3) * (colW + 12f); val top = 80f + (i / 3) * (rowH + 12f)
            if (x in l..(l + colW) && y in top..(top + rowH)) return list[i].id.takeIf { list[i].action }
        }
        return null
    }

    private fun act(id: String) {
        when (id) {
            "mode-laptop" -> actions.setInputMode(false)
            "mode-dock" -> actions.setInputMode(true)
            "clipboard" -> actions.setClipboardShare(!clipboard)
            "bitchord" -> actions.openBitChord()
            else -> { opening[id] = SystemClock.uptimeMillis(); actions.openApp(id) }
        }
    }

    // ---- helpers ----
    private fun fit(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        val n = text.breakText(s, true, maxW - text.measureText("…"), null)
        return s.substring(0, n).trimEnd() + "…"
    }

    private fun wrap2(s: String, maxW: Float): Pair<String, String> {
        var n = text.breakText(s, true, maxW, null)
        if (n >= s.length) return s to ""
        val sp = s.lastIndexOf(' ', n); if (sp > 0) n = sp
        return s.substring(0, n).trim() to fit(s.substring(n).trim(), maxW)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return android.graphics.Color.rgb(
            (android.graphics.Color.red(a) + (android.graphics.Color.red(b) - android.graphics.Color.red(a)) * k).toInt(),
            (android.graphics.Color.green(a) + (android.graphics.Color.green(b) - android.graphics.Color.green(a)) * k).toInt(),
            (android.graphics.Color.blue(a) + (android.graphics.Color.blue(b) - android.graphics.Color.blue(a)) * k).toInt(),
        )
    }
}
