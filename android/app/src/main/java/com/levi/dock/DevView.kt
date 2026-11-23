package com.levi.dock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** 02 Dev Command Center: LEVI projects + git, agents, local servers, running tools. */
class DevView(context: Context) : LeviScreen(context, 1) {

    private var data: DevData? = null
    private var linked = false

    fun setLinked(c: Boolean) { linked = c; setConnected(c); invalidate() }
    fun update(d: DevData) { data = d; if (isShown) invalidate() }

    override fun drawContent(c: Canvas, now: Long) {
        setText(Levi.TEXT, 26f, sansMedium)
        c.drawText("Dev Command Center", 108f, 58f, text)

        val d = data
        if (d == null) {
            setText(Levi.MUTED, 18f, sans, Paint.Align.CENTER)
            c.drawText(if (linked) "Gathering projects…" else "Waiting for laptop… run levi-host", 518f, 290f, text)
            return
        }

        // Header right: agents
        val agents = d.agents.joinToString(" · ") { it.name }
        setText(Levi.MUTED, 17f, sans, Paint.Align.RIGHT)
        if (agents.isEmpty()) c.drawText("No coding agent running", 928f, 58f, text)
        else {
            setText(Levi.TEXT, 17f, sans, Paint.Align.RIGHT)
            c.drawText(agents, 928f, 58f, text)
            val w = text.measureText(agents)
            setText(Levi.MUTED, 17f, sans, Paint.Align.RIGHT)
            c.drawText("Agents  ", 928f - w, 58f, text)
        }

        drawProjects(c, d)
        drawTiles(c, d)
        drawServers(c, d)
        drawTools(c, d)
    }

    private fun drawProjects(c: Canvas, d: DevData) {
        var y = 80f
        for (p in d.projects.take(5)) {
            val bg = if (p.active) Levi.ACCENT_DIM else Levi.SURFACE
            rect.set(108f, y, 398f, y + 76f)
            fill.color = bg; c.drawRoundRect(rect, 18f, 18f, fill)
            rect.inset(0.5f, 0.5f)
            stroke.color = if (p.active) ACTIVE_BORDER else Levi.LINE; stroke.strokeWidth = 1f
            c.drawRoundRect(rect, 18f, 18f, stroke)

            setText(Levi.TEXT, 19f, sansMedium)
            c.drawText(fit(p.name, 220f), 124f, y + 32f, text)
            if (p.active) {
                setText(Levi.ACCENT, 13f, mono, Paint.Align.RIGHT)
                c.drawText("ACTIVE", 382f, y + 31f, text)
            }
            val line2 = if (p.git) buildString {
                append(p.branch.ifBlank { "detached" })
                append(" · ")
                append(if (p.changed == 0) "clean" else "${p.changed} changed")
                if (p.ahead > 0) append(" · ↑${p.ahead}")
                if (p.behind > 0) append(" · ↓${p.behind}")
            } else "Not a git repo · ${p.modifiedAgo}"
            setText(if (p.active) Levi.TEXT_2 else Levi.MUTED, 15f, sans)
            c.drawText(fit(line2, 258f), 124f, y + 58f, text)
            y += 86f
        }
    }

    private fun drawTiles(c: Canvas, d: DevData) {
        val active = d.projects.firstOrNull { it.active }
        val repo = active?.takeIf { it.git } ?: d.projects.firstOrNull { it.git }
        val w = (506f - 20f) / 3f
        fun tile(i: Int): Float { val l = 422f + i * (w + 10f); card(c, l, 80f, l + w, 176f, 18f); return l + 16f }

        // Git (active project, or the most recent repo)
        tile(0).let { x ->
            setText(Levi.MUTED, 15f, sans); c.drawText(fit("Git · ${repo?.name ?: "—"}", w - 32f), x, 104f, text)
            if (repo == null) { setText(Levi.TEXT, 20f, sansMedium); c.drawText("No repos", x, 140f, text) }
            else {
                val dirty = repo.changed > 0
                fill.color = if (dirty) Levi.ATTENTION else Levi.ACCENT
                if (dirty) { rect.set(x, 129f, x + 10f, 139f); c.drawRoundRect(rect, 2f, 2f, fill) } else c.drawCircle(x + 5f, 134f, 5f, fill)
                setText(Levi.TEXT, 20f, sansMedium); c.drawText(if (dirty) "${repo.changed} changed" else "Clean", x + 18f, 141f, text)
                setText(Levi.MUTED, 14f, mono); c.drawText("↑${repo.ahead} ↓${repo.behind}", x, 164f, text)
            }
        }
        // Last commit
        tile(1).let { x ->
            setText(Levi.MUTED, 15f, sans); c.drawText("Last commit", x, 104f, text)
            if (repo == null || repo.commit.isBlank()) { setText(Levi.TEXT, 20f, sansMedium); c.drawText("—", x, 140f, text) }
            else {
                setText(Levi.TEXT, 15f, sans)
                val lines = wrap2(repo.commit, w - 32f)
                c.drawText(lines.first, x, 130f, text)
                if (lines.second.isNotEmpty()) c.drawText(lines.second, x, 149f, text)
                setText(Levi.MUTED, 13f, mono); c.drawText(fit(repo.commitAgo, w - 32f), x, 168f, text)
            }
        }
        // Agents
        tile(2).let { x ->
            setText(Levi.MUTED, 15f, sans); c.drawText("Coding agents", x, 104f, text)
            setText(Levi.TEXT, 30f, mono); c.drawText(d.agents.size.toString(), x, 146f, text)
            setText(Levi.MUTED, 14f, sans)
            c.drawText(fit(if (d.agents.isEmpty()) "none running" else "running now", w - 32f), x, 168f, text)
        }
    }

    private fun drawServers(c: Canvas, d: DevData) {
        card(c, 422f, 188f, 928f, 412f, 18f, Levi.SUNK)
        setText(Levi.MUTED, 15f, sans); c.drawText("Local servers", 440f, 214f, text)
        setText(Levi.MUTED, 13f, mono, Paint.Align.RIGHT); c.drawText("${d.servers.size} listening", 910f, 214f, text)
        if (d.servers.isEmpty()) {
            setText(Levi.MUTED, 16f, sans); c.drawText("Nothing listening", 440f, 250f, text); return
        }
        var y = 246f
        for (s in d.servers.take(7)) {
            setText(Levi.TEXT, 17f, mono); c.drawText(":${s.port}", 440f, y, text)
            setText(Levi.TEXT_2, 16f, sans); c.drawText(fit(s.name, 260f), 530f, y, text)
            if (s.network) {
                // Reachable from other machines: attention colour + explicit label.
                setText(Levi.ATTENTION, 13f, mono, Paint.Align.RIGHT); c.drawText("NETWORK", 910f, y, text)
            } else {
                setText(Levi.MUTED, 13f, mono, Paint.Align.RIGHT); c.drawText("local only", 910f, y, text)
            }
            y += 24f
        }
    }

    private fun drawTools(c: Canvas, d: DevData) {
        setText(Levi.MUTED, 15f, sans); c.drawText("Running tools", 422f, 440f, text)
        if (d.tools.isEmpty()) { setText(Levi.MUTED, 15f, sans); c.drawText("No build tools running", 422f, 480f, text); return }
        var x = 422f
        for (t in d.tools) {
            val label = if (t.processes > 1) "${t.name} ×${t.processes}" else t.name
            setText(Levi.TEXT, 15f, sans)
            val w = text.measureText(label) + 28f
            if (x + w > 928f) break
            rect.set(x, 456f, x + w, 494f)
            fill.color = Levi.SURFACE; c.drawRoundRect(rect, 19f, 19f, fill)
            stroke.color = Levi.LINE_STRONG; stroke.strokeWidth = 1f; rect.inset(0.5f, 0.5f); c.drawRoundRect(rect, 19f, 19f, stroke)
            c.drawText(label, x + 14f, 481f, text)
            x += w + 8f
        }
    }

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

    companion object {
        private val ACTIVE_BORDER = Color.parseColor("#2A4A7A")
    }
}
