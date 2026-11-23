package com.levi.dock

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * 04 LEVI Assistant. Typing comes from the laptop keyboard through the shared
 * "LEVI Keyboard" (hardware key events, no on-screen keyboard). The laptop host
 * makes the AI call and streams the reply back.
 */
class AssistantView(context: Context, private val actions: Actions) : LeviScreen(context, 3) {

    interface Actions {
        fun ask(id: Long, text: String)
        fun clear()
    }

    private class Msg(val id: Long, val user: Boolean, var text: String, var error: Boolean = false, var done: Boolean = false) {
        var wrapped: List<String> = emptyList()
        var wrappedFor = -1
    }

    private val msgs = ArrayList<Msg>()
    private val input = StringBuilder()
    private var ready = false
    private var provider = "OpenRouter"
    private var model = ""
    private var project = ""
    private var linked = false
    private var waiting: Long = -1
    private var scrollFromBottom = 0f
    private var pressed: String? = null
    private var dragY = -1f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setLinked(c: Boolean) { linked = c; setConnected(c); invalidate() }
    fun setStatus(r: Boolean, p: String, m: String) { ready = r; provider = p; model = m; invalidate() }
    fun setProject(name: String) { project = name }

    fun onDelta(id: Long, text: String) {
        val m = msgs.lastOrNull { it.id == id && !it.user } ?: Msg(id, false, "").also { msgs.add(it) }
        m.text += text
        if (waiting == id) waiting = -1
        scrollFromBottom = 0f
        invalidate()
    }

    fun onDone(id: Long, error: String?) {
        if (waiting == id) waiting = -1
        val m = msgs.lastOrNull { it.id == id && !it.user }
        if (error != null) {
            if (m == null) msgs.add(Msg(id, false, error, error = true, done = true)) else { m.text += "\n\n$error"; m.error = true; m.done = true }
        } else m?.done = true
        invalidate()
    }

    /** A question asked by voice ("Hey LEVI, …"): the laptop already sent it to the AI. */
    fun addSpoken(id: Long, text: String) {
        msgs.add(Msg(id, true, text))
        waiting = id
        scrollFromBottom = 0f
        invalidate()
    }

    private fun send() {
        val t = input.toString().trim()
        if (t.isEmpty() || waiting >= 0) return
        if (t == "/clear") { newChat(); input.setLength(0); return }
        val id = SystemClock.uptimeMillis()
        msgs.add(Msg(id, true, t))
        input.setLength(0)
        waiting = id
        scrollFromBottom = 0f
        actions.ask(id, t)
        invalidate()
    }

    private fun newChat() { msgs.clear(); waiting = -1; scrollFromBottom = 0f; actions.clear(); invalidate() }

    override fun needsFrame(now: Long) = waiting >= 0

    /** Caret blink: one redraw every 530 ms while focused, nothing otherwise. */
    private val blink = object : Runnable {
        override fun run() {
            if (isShown && hasFocus()) invalidate()
            postDelayed(this, 530L)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(blink) }
    override fun onDetachedFromWindow() { removeCallbacks(blink); super.onDetachedFromWindow() }

    // ---- keyboard (hardware) ----
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when {
            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.isShiftPressed) input.append('\n') else send()
            }
            keyCode == KeyEvent.KEYCODE_DEL -> if (input.isNotEmpty()) input.setLength(input.length - 1)
            keyCode == KeyEvent.KEYCODE_V && event.isCtrlPressed -> {
                val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.let { input.append(it.take(4000 - input.length)) }
            }
            keyCode == KeyEvent.KEYCODE_L && event.isCtrlPressed -> newChat()
            keyCode == KeyEvent.KEYCODE_PAGE_UP -> scrollBy(200f)
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN -> scrollBy(-200f)
            else -> {
                val ch = event.getUnicodeChar(event.metaState)
                if (ch == 0 || event.isCtrlPressed) return super.onKeyDown(keyCode, event)
                if (input.length < 4000) input.appendCodePoint(ch)
            }
        }
        invalidate()
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_SCROLL) {
            scrollBy(e.getAxisValue(MotionEvent.AXIS_VSCROLL) * 60f)
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    private fun scrollBy(d: Float) { scrollFromBottom = (scrollFromBottom + d).coerceAtLeast(0f); invalidate() }

    // ---- drawing ----
    override fun drawContent(c: Canvas, now: Long) {
        setText(Levi.TEXT, 26f, sansMedium); c.drawText("LEVI", 108f, 58f, text)

        // chips: project, model, new chat
        var x = 928f
        x = chip(c, x, "New chat", pressed == "new", true) - 8f
        if (model.isNotEmpty()) x = chip(c, x, "$provider · ${shortModel(model)}", false, false, mono) - 8f
        if (project.isNotEmpty()) chip(c, x, "Project · $project", false, false)

        drawConversation(c, now)
        drawInput(c, now)
    }

    private fun chip(c: Canvas, right: Float, label: String, down: Boolean, button: Boolean, face: android.graphics.Typeface = sans): Float {
        setText(if (button) Levi.TEXT else Levi.TEXT_2, 15f, face)
        val w = text.measureText(label) + 24f
        rect.set(right - w, 36f, right, 66f)
        if (down) rect.inset(w * 0.015f, 0.5f)
        fill.color = if (button) Levi.SUNK else Levi.SURFACE; c.drawRoundRect(rect, 15f, 15f, fill)
        stroke.color = Levi.LINE_STRONG; stroke.strokeWidth = 1f; c.drawRoundRect(rect, 15f, 15f, stroke)
        c.drawText(label, right - w + 12f, 56f, text)
        return right - w
    }

    private fun drawConversation(c: Canvas, now: Long) {
        val top = 84f; val bottom = 436f
        if (msgs.isEmpty()) {
            setText(Levi.MUTED, 18f, sans, Paint.Align.CENTER)
            val cx = 518f
            when {
                !linked -> c.drawText("Waiting for the laptop…", cx, 250f, text)
                !ready -> {
                    c.drawText("Add an AI key on the laptop to start:", cx, 236f, text)
                    setText(Levi.TEXT, 18f, mono, Paint.Align.CENTER); c.drawText("levi-host --set-ai-key", cx, 266f, text)
                }
                else -> {
                    c.drawText("Ask LEVI about your projects, code or anything else.", cx, 236f, text)
                    setText(Levi.MUTED, 15f, sans, Paint.Align.CENTER)
                    c.drawText("Type with your laptop keyboard · Enter to send · Ctrl+L new chat", cx, 264f, text)
                }
            }
            return
        }

        // Lay out bottom-up.
        setText(Levi.TEXT, 18f, sans)
        val maxW = 560f
        val heights = msgs.map { m ->
            if (m.wrappedFor != m.text.length) { m.wrapped = wrapAll(m.text, maxW); m.wrappedFor = m.text.length }
            m.wrapped.size * 26f + 24f
        }
        val total = heights.sum() + 12f * msgs.size + (if (waiting >= 0) 40f else 0f)
        scrollFromBottom = scrollFromBottom.coerceAtMost((total - (bottom - top)).coerceAtLeast(0f))
        var y = bottom + scrollFromBottom - (if (waiting >= 0) 40f else 0f)

        c.save()
        c.clipRect(96f, top, 940f, bottom)
        if (waiting >= 0) {
            val dots = ".".repeat(((now / 350) % 4).toInt())
            setText(Levi.MUTED, 16f, sans); c.drawText("LEVI is thinking$dots", 108f, bottom + scrollFromBottom - 14f, text)
        }
        for (i in msgs.indices.reversed()) {
            val m = msgs[i]; val h = heights[i]
            y -= h
            if (y > bottom) { y -= 12f; continue }
            if (y + h < top) break
            setText(Levi.TEXT, 18f, sans)
            val w = (m.wrapped.maxOfOrNull { text.measureText(it) } ?: 0f) + 32f
            val l = if (m.user) 928f - w else 108f
            rect.set(l, y, l + w, y + h)
            fill.color = when { m.user -> Levi.ACCENT_DIM; else -> Levi.SURFACE }
            c.drawRoundRect(rect, 18f, 18f, fill)
            if (!m.user) {
                rect.inset(0.5f, 0.5f)
                stroke.color = if (m.error) Levi.ATTENTION else Levi.LINE; stroke.strokeWidth = 1f
                c.drawRoundRect(rect, 18f, 18f, stroke)
            }
            text.color = if (m.error) Levi.ATTENTION else Levi.TEXT
            m.wrapped.forEachIndexed { r, line -> c.drawText(line, l + 16f, y + 30f + r * 26f, text) }
            y -= 12f
        }
        c.restore()
    }

    private fun drawInput(c: Canvas, now: Long) {
        val focused = hasFocus()
        rect.set(108f, 452f, 856f, 508f)
        fill.color = Levi.SURFACE; c.drawRoundRect(rect, 18f, 18f, fill)
        rect.inset(0.5f, 0.5f)
        stroke.color = if (focused) Levi.ACCENT else Levi.LINE_STRONG; stroke.strokeWidth = if (focused) 1.5f else 1f
        c.drawRoundRect(rect, 18f, 18f, stroke)

        val shown = input.toString().replace('\n', '↵')
        c.save(); c.clipRect(120f, 452f, 846f, 508f)
        if (shown.isEmpty()) {
            setText(Levi.MUTED, 18f, sans)
            c.drawText(if (ready) "Ask LEVI…" else "Waiting for an AI key on the laptop", 126f, 486f, text)
            if (focused && (now / 530) % 2 == 0L) { fill.color = Levi.ACCENT; c.drawRect(124f, 468f, 126f, 492f, fill) }
        } else {
            setText(Levi.TEXT, 18f, sans)
            val w = text.measureText(shown)
            val x0 = if (w > 700f) 126f - (w - 700f) else 126f
            c.drawText(shown, x0, 486f, text)
            if (focused && (now / 530) % 2 == 0L) { fill.color = Levi.ACCENT; c.drawRect(x0 + w + 2f, 468f, x0 + w + 4f, 492f, fill) }
        }
        c.restore()

        // Send
        val down = pressed == "send"
        val r = if (down) 27f else 28f
        val can = input.isNotBlank() && ready && waiting < 0
        fill.color = if (can) Levi.ACCENT else Levi.SURFACE
        rect.set(900f - r, 480f - r, 900f + r, 480f + r); c.drawRoundRect(rect, 18f, 18f, fill)
        stroke.color = if (can) Levi.GROUND else Levi.MUTED; stroke.strokeWidth = 2f
        c.drawLine(890f, 480f, 910f, 480f, stroke); c.drawLine(903f, 473f, 910f, 480f, stroke); c.drawLine(903f, 487f, 910f, 480f, stroke)
    }

    // ---- touch / mouse ----
    override fun onArtTouch(action: Int, x: Float, y: Float): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                pressed = when {
                    y in 30f..72f && x > 780f -> "new"
                    x in 872f..928f && y in 452f..508f -> "send"
                    else -> null
                }
                dragY = y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (pressed == null && dragY >= 0) { scrollBy(y - dragY); dragY = y }
            MotionEvent.ACTION_UP -> {
                when (pressed) { "new" -> newChat(); "send" -> send() }
                pressed = null; dragY = -1f; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; dragY = -1f; invalidate() }
        }
        return true
    }

    // ---- text helpers ----
    private fun wrapAll(s: String, maxW: Float): List<String> {
        val out = ArrayList<String>()
        for (para in s.replace("**", "").split('\n')) {
            if (para.isBlank()) { if (out.isNotEmpty() && out.last().isNotEmpty()) out.add(""); continue }
            var rest = para
            while (rest.isNotEmpty()) {
                var n = text.breakText(rest, true, maxW, null)
                if (n < rest.length) { val sp = rest.lastIndexOf(' ', n); if (sp > 0) n = sp }
                out.add(rest.substring(0, n).trimEnd())
                rest = rest.substring(n).trimStart()
            }
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return if (out.isEmpty()) listOf("") else out
    }

    private fun shortModel(m: String) = m.substringAfterLast('/').take(28)
}
