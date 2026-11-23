package com.levi.dock

import android.graphics.Color
import org.json.JSONObject

/** LEVI design tokens (docs/mission-006-design.md). */
object Levi {
    val GROUND = Color.parseColor("#090B0E")
    val SURFACE = Color.parseColor("#12151A")
    val SUNK = Color.parseColor("#0D1014")
    val LINE = Color.parseColor("#1C2027")
    val LINE_STRONG = Color.parseColor("#242932")
    val TRACK = Color.parseColor("#1F242C")
    val TEXT = Color.parseColor("#E6E9EE")
    val TEXT_2 = Color.parseColor("#A9B1BE")
    val MUTED = Color.parseColor("#8C95A3")
    val ACCENT = Color.parseColor("#4C8DFF")
    val ACCENT_DIM = Color.parseColor("#16243A")
    val ATTENTION = Color.parseColor("#F2A93B")

    /** Port the dock listens on (loopback only; the laptop reaches it through `adb forward`). */
    const val PORT = 47800
}

/** One metrics sample from the laptop host (protocol v1, see docs/protocol-v1.md). */
data class Metrics(
    val host: String,
    val cpu: Float,
    val cpuMhz: Int,
    val memUsed: Long,
    val memTotal: Long,
    val diskFree: Long,
    val diskTotal: Long,
    val rx: Double,
    val tx: Double,
    val battery: Int,
    val charging: Boolean,
    val onAc: Boolean,
    val cpuTemp: Float,
    val rtt: Int,
) {
    companion object {
        fun parse(o: JSONObject): Metrics {
            val mem = o.optJSONObject("mem") ?: JSONObject()
            val disk = o.optJSONObject("disk") ?: JSONObject()
            val net = o.optJSONObject("net") ?: JSONObject()
            val bat = o.optJSONObject("battery") ?: JSONObject()
            return Metrics(
                host = o.optString("host", "Laptop").take(40),
                cpu = o.optDouble("cpu", 0.0).toFloat().coerceIn(0f, 100f),
                cpuMhz = o.optInt("cpuMhz", 0),
                memUsed = mem.optLong("used", 0),
                memTotal = mem.optLong("total", 0),
                diskFree = disk.optLong("free", 0),
                diskTotal = disk.optLong("total", 0),
                rx = net.optDouble("rx", 0.0),
                tx = net.optDouble("tx", 0.0),
                battery = bat.optInt("pct", -1),
                charging = bat.optBoolean("charging", false),
                onAc = bat.optBoolean("ac", false),
                cpuTemp = if (o.isNull("cpuTemp") || !o.has("cpuTemp")) Float.NaN else o.optDouble("cpuTemp").toFloat(),
                rtt = o.optInt("rtt", -1),
            )
        }
    }
}
