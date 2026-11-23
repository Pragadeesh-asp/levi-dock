package com.levi.dock

import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.media.AudioManager
import android.os.SystemClock
import android.view.MotionEvent
import java.util.Locale
import java.util.concurrent.Executors

/** 03 Media: now playing on the dock (BitChord) with synced LRCLIB lyrics. */
class MediaView(context: Context, private val hub: MediaHub) : LeviScreen(context, 2) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val worker = Executors.newSingleThreadExecutor()

    // ---- lyrics state ----
    private var lyricsKey = ""
    private var lyrics: Lyrics.Result? = null
    private class Entry(val rows: List<String>, val top: Float, val height: Float)
    private var entries: List<Entry> = emptyList()
    private var syncedTimes: LongArray = LongArray(0)
    private var curIdx = -1
    private var prevIdx = -1
    private val tScroll = Tween(520f).also { it.snap(0f) }
    private val tEmph = Tween(380f).also { it.snap(1f) }

    // ---- interaction ----
    private var pressed = NONE
    private var volume = 0
    private var volumeMax = 15
    private var lastTick = 0L

    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artMatrix = Matrix()
    private var artFor: Any? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (isShown) tick()
            postDelayed(this, 250L)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); readVolume(); post(ticker) }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }

    /** Called by the activity whenever the media session changes. */
    fun onHubChanged() {
        val t = hub.track
        val key = if (t == null) "" else "${t.title}|${t.artist}|${t.durationMs / 1000}"
        if (key != lyricsKey) {
            lyricsKey = key
            lyrics = null; entries = emptyList(); syncedTimes = LongArray(0); curIdx = -1; prevIdx = -1
            tScroll.snap(0f)
            if (t != null && t.title.isNotBlank()) {
                worker.execute {
                    val r = Lyrics.lookup(t.title, t.artist, t.album, t.durationMs)
                    post { if (lyricsKey == key) { lyrics = r; layoutLyrics(); tick(); invalidate() } }
                }
            }
        }
        invalidate()
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        var dirty = false
        if (syncedTimes.isNotEmpty()) {
            val idx = indexAt(hub.position() + 250L)
            if (idx != curIdx) { moveTo(idx); dirty = true }
        }
        if (now - lastTick >= 1000L) {
            lastTick = now
            readVolume()
            dirty = true
        }
        if (dirty) invalidate()
    }

    private fun moveTo(idx: Int) {
        prevIdx = curIdx
        curIdx = idx
        tEmph.snap(0f); tEmph.to(1f)
        val e = entries.getOrNull(idx.coerceAtLeast(0)) ?: return
        tScroll.to(LY_CENTER - (e.top + e.height / 2f))
    }

    private fun indexAt(pos: Long): Int {
        var lo = 0; var hi = syncedTimes.size - 1; var ans = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (syncedTimes[mid] <= pos) { ans = mid; lo = mid + 1 } else hi = mid - 1 }
        return ans
    }

    private fun layoutLyrics() {
        val texts: List<String>
        when (val r = lyrics) {
            is Lyrics.Result.Synced -> { texts = r.lines.map { it.text.ifBlank { "♪" } }; syncedTimes = LongArray(r.lines.size) { r.lines[it].timeMs } }
            is Lyrics.Result.Plain -> { texts = r.lines; syncedTimes = LongArray(0) }
            else -> { entries = emptyList(); return }
        }
        setText(Levi.TEXT, LY_SIZE, sansMedium)
        val maxW = (LY_RIGHT - LY_LEFT) / LY_SCALE
        var y = 0f
        entries = texts.map { line ->
            val rows = wrap(line, maxW, 3)
            val h = rows.size * LY_ROW + LY_GAP
            Entry(rows, y, h).also { y += h }
        }
        if (syncedTimes.isEmpty()) tScroll.snap(0f)
    }

    private fun wrap(s: String, maxW: Float, maxRows: Int): List<String> {
        if (s.isEmpty()) return listOf("")
        val rows = ArrayList<String>()
        var rest = s
        while (rest.isNotEmpty() && rows.size < maxRows) {
            var n = text.breakText(rest, true, maxW, null)
            if (n < rest.length) { val sp = rest.lastIndexOf(' ', n); if (sp > 0) n = sp }
            rows.add(rest.substring(0, n).trim())
            rest = rest.substring(n).trimStart()
        }
        if (rest.isNotEmpty()) rows[rows.size - 1] = fit(rows.last() + " " + rest, maxW)
        return rows
    }

    private fun fit(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        val n = text.breakText(s, true, maxW - text.measureText("…"), null)
        return s.substring(0, n).trimEnd() + "…"
    }

    private fun readVolume() {
        volumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    override fun needsFrame(now: Long) = tScroll.active(now) || tEmph.active(now)

    // ---- drawing ----
    override fun drawContent(c: Canvas, now: Long) {
        drawNowPlaying(c)
        drawLyricsPanel(c, now)
    }

    private fun drawNowPlaying(c: Canvas) {
        val t = hub.track
        // Source chip
        val label = when {
            !hub.hasAccess() -> "MEDIA ACCESS OFF"
            t == null -> "BITCHORD"
            t.app == MediaHub.BITCHORD -> "BITCHORD"
            else -> appLabel(t.app).uppercase(Locale.getDefault())
        }
        setText(Levi.ACCENT, 14f, mono, Paint.Align.LEFT, 0.04f)
        val cw = text.measureText(label) + 22f
        fill.color = Levi.ACCENT_DIM; rect.set(108f, 28f, 108f + cw, 54f); c.drawRoundRect(rect, 13f, 13f, fill)
        c.drawText(label, 119f, 46f, text)
        setText(Levi.MUTED, 15f, sans); c.drawText("on this dock", 118f + cw, 46f, text)

        // Album art
        card(c, 108f, 72f, 298f, 262f, 24f)
        val art = t?.art
        if (art != null) {
            if (artFor !== art) {
                artFor = art
                val sh = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val k = maxOf(190f / art.width, 190f / art.height)
                artMatrix.reset(); artMatrix.setScale(k, k)
                artMatrix.postTranslate(108f + (190f - art.width * k) / 2f, 72f + (190f - art.height * k) / 2f)
                sh.setLocalMatrix(artMatrix); artPaint.shader = sh
            }
            rect.set(108f, 72f, 298f, 262f); c.drawRoundRect(rect, 24f, 24f, artPaint)
        } else {
            noteIcon(c, 203f, 167f, 2.2f, Color.parseColor("#2C323C"))
        }

        // Title / artist
        if (t == null) {
            setText(Levi.TEXT, 26f, sansMedium); c.drawText(if (hub.hasAccess()) "Nothing playing" else "Media access is off", 108f, 304f, text)
            setText(Levi.TEXT_2, 17f, sans)
            c.drawText(if (hub.hasAccess()) "Start a song in BitChord" else "Grant it from the laptop: setup-media", 108f, 330f, text)
            if (hub.hasAccess()) {
                val p = pressed == OPEN
                fill.color = Levi.ACCENT
                rect.set(108f, 396f, 300f, 448f); if (p) shrink(rect, 0.97f)
                c.drawRoundRect(rect, 16f, 16f, fill)
                setText(Levi.GROUND, 18f, sansMedium, Paint.Align.CENTER); c.drawText("Open BitChord", 204f, 428f, text)
            }
            drawVolume(c)
            return
        }
        setText(Levi.TEXT, 26f, sansMedium); c.drawText(fit(t.title.ifBlank { "Unknown title" }, 330f), 108f, 304f, text)
        setText(Levi.TEXT_2, 17f, sans)
        c.drawText(fit(listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" — "), 330f), 108f, 330f, text)

        // Progress
        val pos = hub.position(); val dur = t.durationMs
        rect.set(108f, 352f, 438f, 358f); fill.color = Levi.TRACK; c.drawRoundRect(rect, 3f, 3f, fill)
        if (dur > 0) {
            rect.right = 108f + 330f * (pos.toFloat() / dur).coerceIn(0f, 1f)
            fill.color = Levi.ACCENT; c.drawRoundRect(rect, 3f, 3f, fill)
        }
        setText(Levi.MUTED, 14f, mono); c.drawText(clock(pos), 108f, 378f, text)
        text.textAlign = Paint.Align.RIGHT; c.drawText(if (dur > 0) clock(dur) else "--:--", 438f, 378f, text)

        // Transport
        roundButton(c, 136f, 434f, 28f, pressed == PREV, false) { prevIcon(c, 136f, 434f) }
        roundButton(c, 218f, 434f, 38f, pressed == PLAY, true) { if (hub.playing) pauseIcon(c, 218f, 434f) else playIcon(c, 218f, 434f) }
        roundButton(c, 300f, 434f, 28f, pressed == NEXT, false) { nextIcon(c, 300f, 434f) }

        drawVolume(c)
    }

    private fun drawVolume(c: Canvas) {
        setText(Levi.MUTED, 16f, sans); c.drawText("Volume", 108f, 499f, text)
        val x0 = 182f; val x1 = 392f; val k = volume.toFloat() / volumeMax
        rect.set(x0, 492f, x1, 496f); fill.color = Levi.TRACK; c.drawRoundRect(rect, 2f, 2f, fill)
        rect.right = x0 + (x1 - x0) * k; fill.color = Levi.ACCENT; c.drawRoundRect(rect, 2f, 2f, fill)
        fill.color = Levi.TEXT; c.drawCircle(x0 + (x1 - x0) * k, 494f, if (pressed == VOLUME) 11f else 9f, fill)
        setText(Levi.TEXT, 16f, mono, Paint.Align.RIGHT); c.drawText((100 * k).toInt().toString(), 438f, 499f, text)
    }

    private fun drawLyricsPanel(c: Canvas, now: Long) {
        card(c, LY_PANEL_L, 28f, 928f, 512f, 24f, Levi.SUNK)
        setText(Levi.MUTED, 16f, sans); c.drawText(if (syncedTimes.isNotEmpty()) "Lyrics · synced" else "Lyrics", 488f, 58f, text)
        setText(Levi.MUTED, 14f, mono, Paint.Align.RIGHT); c.drawText("LRCLIB", 906f, 58f, text)

        val msg = when {
            hub.track == null -> ""
            lyrics == null -> "Finding lyrics…"
            lyrics is Lyrics.Result.NotFound -> "No lyrics found for this song"
            lyrics is Lyrics.Result.Instrumental -> "Instrumental"
            lyrics is Lyrics.Result.Failed -> "Lyrics unavailable — no internet?"
            else -> null
        }
        if (msg != null) {
            setText(Levi.MUTED, 18f, sans, Paint.Align.CENTER); c.drawText(msg, (LY_PANEL_L + 928f) / 2f, 280f, text)
            return
        }

        val synced = syncedTimes.isNotEmpty()
        val scroll = tScroll.value(now)
        val emph = tEmph.value(now)
        c.save()
        c.clipRect(LY_PANEL_L + 1f, LY_TOP, 927f, LY_BOTTOM)
        setText(Levi.TEXT, LY_SIZE, sansMedium)
        for (i in entries.indices) {
            val e = entries[i]
            val y = LY_TOP + 16f + scroll + e.top
            if (y + e.height < LY_TOP || y > LY_BOTTOM) continue
            var scale = 1f
            val color: Int
            if (!synced) color = Levi.TEXT_2
            else when (i) {
                curIdx -> { color = blend(Levi.MUTED, Levi.TEXT, emph); scale = 1f + (LY_SCALE - 1f) * emph }
                prevIdx -> { color = blend(Levi.TEXT, Levi.MUTED, emph); scale = LY_SCALE - (LY_SCALE - 1f) * emph }
                else -> color = if (kotlin.math.abs(i - curIdx) == 1) Levi.MUTED else FAR
            }
            text.color = color
            c.save()
            if (scale != 1f) c.scale(scale, scale, LY_LEFT, y + e.height / 2f)
            for (r in e.rows.indices) c.drawText(e.rows[r], LY_LEFT, y + LY_SIZE + r * LY_ROW, text)
            c.restore()
        }
        c.restore()
    }

    // ---- touch ----
    override fun onArtTouch(action: Int, x: Float, y: Float): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit(x, y)
                if (pressed == VOLUME) setVolumeAt(x)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (pressed == VOLUME) { setVolumeAt(x); invalidate() }
            MotionEvent.ACTION_UP -> {
                val h = hit(x, y)
                if (h == pressed) when (h) {
                    PREV -> hub.previous()
                    PLAY -> hub.playPause()
                    NEXT -> hub.next()
                    OPEN -> context.packageManager.getLaunchIntentForPackage(MediaHub.BITCHORD)?.let { context.startActivity(it) }
                    SEEK -> hub.track?.durationMs?.takeIf { it > 0 }?.let { d -> hub.seekTo((d * ((x - 108f) / 330f).coerceIn(0f, 1f)).toLong()) }
                }
                pressed = NONE; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = NONE; invalidate() }
        }
        return true
    }

    private fun hit(x: Float, y: Float): Int {
        fun inCircle(cx: Float, cy: Float, r: Float) = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
        if (x in 172f..402f && y in 476f..512f) return VOLUME
        if (hub.track == null) return if (hub.hasAccess() && x in 108f..300f && y in 396f..448f) OPEN else NONE
        return when {
            inCircle(136f, 434f, 32f) -> PREV
            inCircle(218f, 434f, 42f) -> PLAY
            inCircle(300f, 434f, 32f) -> NEXT
            x in 108f..438f && y in 340f..370f -> SEEK
            else -> NONE
        }
    }

    private fun setVolumeAt(x: Float) {
        val k = ((x - 182f) / (392f - 182f)).coerceIn(0f, 1f)
        val v = Math.round(k * volumeMax)
        if (v != volume) { volume = v; audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
    }

    // ---- small drawing helpers ----
    private inline fun roundButton(c: Canvas, cx: Float, cy: Float, r: Float, down: Boolean, primary: Boolean, icon: () -> Unit) {
        val rr = if (down) r * 0.97f else r
        if (primary) { fill.color = Levi.ACCENT; c.drawCircle(cx, cy, rr, fill) }
        else {
            fill.color = Levi.SURFACE; c.drawCircle(cx, cy, rr, fill)
            stroke.color = Levi.LINE_STRONG; stroke.strokeWidth = 1f; c.drawCircle(cx, cy, rr - 0.5f, stroke)
        }
        icon()
    }

    private fun playIcon(c: Canvas, cx: Float, cy: Float) {
        fill.color = Levi.GROUND
        path.reset(); path.moveTo(cx - 6f, cy - 11f); path.lineTo(cx + 11f, cy); path.lineTo(cx - 6f, cy + 11f); path.close()
        c.drawPath(path, fill)
    }

    private fun pauseIcon(c: Canvas, cx: Float, cy: Float) {
        fill.color = Levi.GROUND
        rect.set(cx - 9f, cy - 11f, cx - 3f, cy + 11f); c.drawRoundRect(rect, 1.5f, 1.5f, fill)
        rect.set(cx + 3f, cy - 11f, cx + 9f, cy + 11f); c.drawRoundRect(rect, 1.5f, 1.5f, fill)
    }

    private fun prevIcon(c: Canvas, cx: Float, cy: Float) {
        stroke.color = Levi.TEXT; stroke.strokeWidth = 1.8f
        path.reset(); path.moveTo(cx + 7f, cy + 8f); path.lineTo(cx - 3f, cy); path.lineTo(cx + 7f, cy - 8f); path.close()
        c.drawPath(path, stroke); c.drawLine(cx - 7f, cy - 7f, cx - 7f, cy + 7f, stroke)
    }

    private fun nextIcon(c: Canvas, cx: Float, cy: Float) {
        stroke.color = Levi.TEXT; stroke.strokeWidth = 1.8f
        path.reset(); path.moveTo(cx - 7f, cy - 8f); path.lineTo(cx + 3f, cy); path.lineTo(cx - 7f, cy + 8f); path.close()
        c.drawPath(path, stroke); c.drawLine(cx + 7f, cy - 7f, cx + 7f, cy + 7f, stroke)
    }

    private fun noteIcon(c: Canvas, cx: Float, cy: Float, k: Float, color: Int) {
        c.save(); c.translate(cx - 12f * k, cy - 12f * k); c.scale(k, k)
        stroke.color = color; stroke.strokeWidth = 1.4f
        path.reset(); path.moveTo(9f, 18f); path.lineTo(9f, 5f); path.lineTo(21f, 3f); path.lineTo(21f, 16f)
        c.drawPath(path, stroke); c.drawCircle(6f, 18f, 3f, stroke); c.drawCircle(18f, 16f, 3f, stroke)
        c.restore()
    }

    private fun shrink(r: android.graphics.RectF, k: Float) {
        val dx = r.width() * (1 - k) / 2f; val dy = r.height() * (1 - k) / 2f
        r.inset(dx, dy)
    }

    private val labels = HashMap<String, String>()
    private fun appLabel(pkg: String) = labels.getOrPut(pkg) {
        try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }
        catch (_: Exception) { pkg }
    }

    private fun clock(ms: Long): String { val s = ms / 1000; return String.format(Locale.US, "%d:%02d", s / 60, s % 60) }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
        )
    }

    companion object {
        private const val NONE = -1; private const val PREV = 0; private const val PLAY = 1; private const val NEXT = 2
        private const val OPEN = 3; private const val VOLUME = 4; private const val SEEK = 5

        private const val LY_PANEL_L = 466f
        private const val LY_LEFT = 488f
        private const val LY_RIGHT = 906f
        private const val LY_TOP = 76f
        private const val LY_BOTTOM = 500f
        private const val LY_CENTER = 170f   // where the current line sits, relative to LY_TOP + 16
        private const val LY_SIZE = 22f
        private const val LY_ROW = 30f
        private const val LY_GAP = 16f
        private const val LY_SCALE = 1.14f
        private val FAR = Color.parseColor("#3A414C")
    }
}
