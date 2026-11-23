package com.levi.dock

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Synced lyrics from LRCLIB (https://lrclib.net, free public API).
 * BitChord does not share its own lyrics with other apps, so LEVI DOCK looks
 * them up by title / artist / duration.
 */
object Lyrics {

    data class Line(val timeMs: Long, val text: String)

    sealed class Result {
        data class Synced(val lines: List<Line>) : Result()
        data class Plain(val lines: List<String>) : Result()
        object Instrumental : Result()
        object NotFound : Result()
        object Failed : Result()
    }

    private const val UA = "LEVI DOCK 0.2 (personal desk dock app)"
    private val cache = object : LinkedHashMap<String, Result>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Result>?) = size > 30
    }

    /** Blocking; call off the main thread. */
    fun lookup(title: String, artist: String, album: String, durationMs: Long): Result {
        val t = cleanTitle(title)
        val a = cleanArtist(artist)
        if (t.isBlank()) return Result.NotFound
        val key = "$t|$a|${durationMs / 1000}"
        synchronized(cache) { cache[key]?.let { return it } }

        val result = try {
            exact(t, a, album, durationMs) ?: search(t, a, durationMs) ?: Result.NotFound
        } catch (_: Exception) {
            Result.Failed
        }
        if (result !is Result.Failed) synchronized(cache) { cache[key] = result }
        return result
    }

    private fun exact(t: String, a: String, album: String, durationMs: Long): Result? {
        val q = StringBuilder("https://lrclib.net/api/get?track_name=").append(enc(t))
        if (a.isNotBlank()) q.append("&artist_name=").append(enc(a))
        if (album.isNotBlank()) q.append("&album_name=").append(enc(album))
        if (durationMs > 0) q.append("&duration=").append(durationMs / 1000)
        val body = get(q.toString()) ?: return null
        return fromRecord(JSONObject(body))
    }

    private fun search(t: String, a: String, durationMs: Long): Result? {
        val q = StringBuilder("https://lrclib.net/api/search?track_name=").append(enc(t))
        if (a.isNotBlank()) q.append("&artist_name=").append(enc(a))
        val body = get(q.toString()) ?: return null
        val arr = JSONArray(body)
        if (arr.length() == 0) return null
        val want = durationMs / 1000.0
        var best: JSONObject? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val d = o.optDouble("duration", 0.0)
            if (want > 0 && d > 0 && abs(d - want) > 6) continue
            val score = (if (o.isNull("syncedLyrics")) 1000.0 else 0.0) + (if (want > 0) abs(d - want) else 0.0)
            if (score < bestScore) { bestScore = score; best = o }
        }
        return best?.let { fromRecord(it) }
    }

    private fun fromRecord(o: JSONObject): Result? {
        if (o.optBoolean("instrumental", false)) return Result.Instrumental
        val synced = o.optString("syncedLyrics", "").takeUnless { o.isNull("syncedLyrics") }
        if (!synced.isNullOrBlank()) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return Result.Synced(lines)
        }
        val plain = o.optString("plainLyrics", "").takeUnless { o.isNull("plainLyrics") }
        if (!plain.isNullOrBlank()) return Result.Plain(plain.lines().map { it.trim() })
        return null
    }

    private val TIME = Regex("""\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)]""")
    private val WORD_TAG = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    /** Parses LRC: multiple time tags per line, ignores metadata tags and word-level timing. */
    fun parseLrc(lrc: String): List<Line> {
        val out = ArrayList<Line>()
        for (raw in lrc.lineSequence()) {
            val tags = TIME.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val textPart = raw.substring(tags.last().range.last + 1).replace(WORD_TAG, "").trim()
            for (m in tags) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].replace(':', '.').toDouble()
                out.add(Line(min * 60_000 + (sec * 1000).toLong(), textPart))
            }
        }
        out.sortBy { it.timeMs }
        return out
    }

    private val NOISE = Regex("""\s*[(\[][^)\]]*(official|video|audio|lyrics?|visuali[sz]er|mv|hd|4k|remaster(ed)?)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

    fun cleanTitle(t: String) = t.replace(NOISE, "").trim()
    fun cleanArtist(a: String) = a.removeSuffix(" - Topic").substringBefore(" • ").trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", UA)
        try {
            return when (c.responseCode) {
                200 -> c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                404 -> null
                else -> throw java.io.IOException("HTTP ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }
}
