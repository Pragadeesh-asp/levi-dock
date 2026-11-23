package com.levi.dock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 01 Dashboard: clock, weather, live laptop metrics. Redraws once per second plus 300 ms tweens. */
class DashboardView(context: Context) : LeviScreen(context, 0) {

    private var metrics: Metrics? = null
    private var linked = false
    private var dockTempC = Float.NaN
    private val cpuHistory = FloatArray(24) { -1f }

    private val tCpu = Tween(); private val tMem = Tween(); private val tDisk = Tween()
    private val tBatt = Tween(); private val tTemp = Tween()

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L))
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(ticker) }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }

    fun setLinked(c: Boolean) { linked = c; setConnected(c); invalidate() }
    fun setDockTemp(c: Float) { dockTempC = c }

    fun update(m: Metrics) {
        metrics = m
        System.arraycopy(cpuHistory, 1, cpuHistory, 0, cpuHistory.size - 1)
        cpuHistory[cpuHistory.size - 1] = m.cpu
        tCpu.to(m.cpu, 1f)
        tMem.to(if (m.memTotal > 0) 100f * m.memUsed / m.memTotal else 0f, 1f)
        tDisk.to(if (m.diskTotal > 0) 100f * (m.diskTotal - m.diskFree) / m.diskTotal else 0f, 1f)
        tBatt.to(m.battery.coerceAtLeast(0).toFloat(), 1f)
        if (!m.cpuTemp.isNaN()) tTemp.to(m.cpuTemp, 1f)
        if (isShown) invalidate()
    }

    override fun needsFrame(now: Long) = Tween.anyActive(now, tCpu, tMem, tDisk, tBatt, tTemp)

    override fun drawContent(c: Canvas, now: Long) {
        val d = Date()
        setText(Levi.TEXT, 104f, mono, Paint.Align.LEFT, -0.04f)
        c.drawText(timeFmt.format(d), 104f, 111f, text)
        setText(Levi.MUTED, 20f, sans)
        c.drawText(dateFmt.format(d), 108f, 152f, text)

        card(c, 108f, 346f, 408f, 476f, 22f)
        setText(Levi.MUTED, 17f, sans); c.drawText("Weather", 128f, 384f, text)
        setText(Levi.TEXT, 48f, mono); c.drawText("—", 128f, 450f, text)
        setText(Levi.MUTED, 18f, sans); c.drawText("Not set up yet", 182f, 446f, text)

        drawTiles(c, now)

        val m = metrics
        setText(Levi.MUTED, 16f, mono)
        val left = if (linked && m != null) "${m.host} · ADB" + (if (m.rtt >= 0) " · ${m.rtt} ms" else "")
                   else if (linked) "Laptop linked · waiting for data…" else "Waiting for laptop…"
        c.drawText(left, 108f, 512f, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText(if (linked) "Updated every second" else "Run levi-host on the laptop", 928f, 512f, text)
    }

    private fun drawTiles(c: Canvas, now: Long) {
        val m = metrics
        val x0 = 436f; val y0 = 28f; val colW = 240f; val rowH = (448f - 24f) / 3f
        fun tile(col: Int, row: Int): RectF {
            val l = x0 + col * (colW + 12f); val t = y0 + row * (rowH + 12f)
            card(c, l, t, l + colW, t + rowH, 20f)
            return RectF(l + 18f, t + 16f, l + colW - 18f, t + rowH - 16f)
        }

        tile(0, 0).let { r ->
            label(c, r, "CPU", m?.let { if (it.cpuMhz > 0) String.format(Locale.US, "%.1f GHz", it.cpuMhz / 1000f) else "" } ?: "")
            bigNumber(c, r.left, r.bottom - 2f, m?.let { pct(tCpu.value(now)) }, "%")
            drawSpark(c, r.right - 110f, r.bottom - 36f, 110f, 36f)
        }
        tile(1, 0).let { r ->
            label(c, r, "Memory", m?.let { String.format(Locale.US, "%.1f / %.0f GB", gb(it.memUsed), gb(it.memTotal)) } ?: "")
            bigNumber(c, r.left, r.bottom - 18f, m?.let { pct(tMem.value(now)) }, "%")
            bar(c, r, tMem.value(now), Levi.ACCENT, m != null)
        }
        tile(0, 1).let { r ->
            label(c, r, "Storage C:", m?.let { String.format(Locale.US, "%.1f GB free", gb(it.diskFree)) } ?: "")
            val used = tDisk.value(now)
            bigNumber(c, r.left, r.bottom - 18f, m?.let { pct(used) }, "%")
            bar(c, r, used, if (used >= 85f) Levi.ATTENTION else Levi.ACCENT, m != null)
        }
        tile(1, 1).let { r ->
            label(c, r, "Network", "")
            if (m == null) bigNumber(c, r.left, r.bottom - 2f, null, "") else {
                setText(Levi.MUTED, 15f, mono)
                c.drawText("↓ DOWN", r.left, r.bottom - 34f, text)
                c.drawText("↑ UP", r.left + 112f, r.bottom - 34f, text)
                rate(c, r.left, r.bottom - 2f, m.rx); rate(c, r.left + 112f, r.bottom - 2f, m.tx)
            }
        }
        tile(0, 2).let { r ->
            val detail = m?.let { if (it.battery < 0) "No battery" else if (it.charging) "Charging" else if (it.onAc) "Plugged in" else "On battery" } ?: ""
            label(c, r, "Battery", detail)
            val has = m != null && m.battery >= 0
            val b = tBatt.value(now)
            bigNumber(c, r.left, r.bottom - 18f, if (has) pct(b) else null, "%")
            bar(c, r, b, if (has && b <= 20f) Levi.ATTENTION else Levi.ACCENT, has)
        }
        tile(1, 2).let { r ->
            label(c, r, "CPU temp", if (dockTempC.isNaN()) "" else String.format(Locale.US, "Dock %.0f°", dockTempC))
            bigNumber(c, r.left, r.bottom - 2f, if (m != null && !m.cpuTemp.isNaN()) pct(tTemp.value(now)) else null, "°C")
        }
    }

    private fun drawSpark(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        path.reset()
        var started = false
        val n = cpuHistory.size
        for (i in 0 until n) {
            val v = cpuHistory[i]; if (v < 0) continue
            val px = x + w * i / (n - 1); val py = y + h - 2f - (v / 100f) * (h - 4f)
            if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
        }
        if (!started) return
        stroke.color = Levi.ACCENT; stroke.strokeWidth = 2f
        c.drawPath(path, stroke)
    }

    private fun label(c: Canvas, r: RectF, left: String, right: String) {
        setText(Levi.MUTED, 17f, sans)
        c.drawText(left, r.left, r.top + 16f, text)
        if (right.isNotEmpty()) {
            setText(Levi.MUTED, 15f, mono, Paint.Align.RIGHT)
            c.drawText(right, r.right, r.top + 16f, text)
        }
    }

    private fun bigNumber(c: Canvas, x: Float, baseline: Float, v: String?, unit: String) {
        setText(Levi.TEXT, 38f, mono)
        val s = v ?: "—"
        c.drawText(s, x, baseline, text)
        if (v != null && unit.isNotEmpty()) {
            val w = text.measureText(s)
            setText(Levi.MUTED, 20f, mono)
            c.drawText(unit, x + w + 2f, baseline, text)
        }
    }

    private fun bar(c: Canvas, r: RectF, pct: Float, color: Int, show: Boolean) {
        rect.set(r.left, r.bottom - 6f, r.right, r.bottom)
        fill.color = Levi.TRACK; c.drawRoundRect(rect, 3f, 3f, fill)
        if (!show) return
        rect.right = r.left + (r.width() * (pct / 100f).coerceIn(0f, 1f)).coerceAtLeast(6f)
        fill.color = color; c.drawRoundRect(rect, 3f, 3f, fill)
    }

    private fun rate(c: Canvas, x: Float, baseline: Float, bytesPerSec: Double) {
        val mb = bytesPerSec / 1_048_576.0
        val (num, unit) = if (mb >= 0.1) String.format(Locale.US, "%.1f", mb) to " MB/s"
                          else String.format(Locale.US, "%.0f", bytesPerSec / 1024.0) to " KB/s"
        setText(Levi.TEXT, 26f, mono)
        c.drawText(num, x, baseline, text)
        val w = text.measureText(num)
        setText(Levi.MUTED, 16f, mono)
        c.drawText(unit, x + w, baseline, text)
    }

    private fun pct(v: Float) = String.format(Locale.US, "%.0f", v)
    private fun gb(bytes: Long) = bytes / 1_073_741_824.0
}
