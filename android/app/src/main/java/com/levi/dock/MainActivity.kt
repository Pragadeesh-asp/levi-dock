package com.levi.dock

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import org.json.JSONObject

class MainActivity : Activity(), LinkServer.Listener {

    private lateinit var dashboard: DashboardView
    private lateinit var dev: DevView
    private lateinit var media: MediaView
    private lateinit var eco: EcosystemView
    private lateinit var assistant: AssistantView
    private lateinit var controls: ControlsView
    private lateinit var banner: VoiceBanner
    private lateinit var hub: MediaHub
    private var current: LeviScreen? = null
    private var currentIndex = 0
    private var linked = false

    private val main = Handler(Looper.getMainLooper())
    private val link = LinkServer(Levi.PORT, this)
    private val curve = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (t != Int.MIN_VALUE) dashboard.setDockTemp(t / 10f)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        dashboard = DashboardView(this)
        dev = DevView(this)
        hub = MediaHub(this) {
            if (::media.isInitialized) media.onHubChanged()
            if (::eco.isInitialized) eco.setPhoneApps(bitchordInstalled(), hub.playing && hub.track?.app == MediaHub.BITCHORD)
        }
        media = MediaView(this, hub)
        eco = EcosystemView(this, object : EcosystemView.Actions {
            override fun openApp(id: String) { command("open-app", JSONObject().put("app", id)) }
            override fun openBitChord() { packageManager.getLaunchIntentForPackage(MediaHub.BITCHORD)?.let { startActivity(it) } }
            override fun setInputMode(dock: Boolean) { command("input-mode", JSONObject().put("mode", if (dock) "dock" else "laptop")) }
            override fun setClipboardShare(on: Boolean) { command("clipboard-share", JSONObject().put("on", on)) }
            override fun paired() = Pairing.token(this@MainActivity) != null
        })
        eco.setPhoneApps(bitchordInstalled(), false)
        assistant = AssistantView(this, object : AssistantView.Actions {
            override fun ask(id: Long, text: String) { command("ai-ask", JSONObject().put("id", id).put("text", text)) }
            override fun clear() { command("ai-clear") }
        })

        controls = ControlsView(this) { id -> command("quick", JSONObject().put("id", id)) }

        val root = FrameLayout(this)
        root.setBackgroundColor(Levi.GROUND)
        for (v in listOf<LeviScreen>(dashboard, dev, media, assistant, controls, eco)) {
            v.onNavigate = { show(it) }
            v.visibility = View.GONE
            root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        banner = VoiceBanner(this)
        root.addView(banner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        dashboard.visibility = View.VISIBLE
        current = dashboard

        link.start()
        hub.start()
        // "Hey LEVI" uses the dock mic + speaker; needs RECORD_AUDIO (granted by levi-host over ADB).
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, VoiceService::class.java))
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** `adb shell am start -n com.levi.dock/.MainActivity --ei screen 2` opens a screen directly. */
    private fun handleIntent(i: Intent?) {
        val s = i?.getIntExtra("screen", -1) ?: -1
        if (s >= 0) show(s, animate = false)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // Access may have been granted while we were away.
        if (hub.hasAccess() && !hub.hasSession()) { hub.stop(); hub.start() }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, VoiceService::class.java))
        }
    }

    override fun onPause() {
        unregisterReceiver(batteryReceiver)
        super.onPause()
    }

    override fun onDestroy() {
        link.stop()
        hub.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun show(index: Int, animate: Boolean = true) {
        val incoming: LeviScreen = when (index) {
            0 -> dashboard
            1 -> dev
            2 -> media
            3 -> assistant
            4 -> controls
            5 -> eco
            else -> return // other sections are not built yet
        }
        val outgoing = current
        if (incoming === outgoing) return
        val dir = if (index > currentIndex) 1f else -1f
        val shift = 24f * resources.displayMetrics.density
        current = incoming
        currentIndex = index
        incoming.setConnected(linked)

        if (incoming === assistant) incoming.post { assistant.requestFocus() }
        if (!animate || outgoing == null) {
            outgoing?.visibility = View.GONE
            incoming.alpha = 1f; incoming.translationX = 0f; incoming.visibility = View.VISIBLE
            return
        }
        outgoing.animate().cancel()
        outgoing.animate().alpha(0f).translationX(-dir * shift).setDuration(220).setInterpolator(curve)
            .withEndAction { outgoing.visibility = View.GONE; outgoing.translationX = 0f; outgoing.alpha = 1f }
            .start()
        incoming.alpha = 0f; incoming.translationX = dir * shift; incoming.visibility = View.VISIBLE
        incoming.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(curve).start()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    // ---- LinkServer.Listener (link thread) ----
    override fun onMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "metrics" -> { val m = Metrics.parse(msg); main.post { dashboard.update(m); eco.setHost(m.host) } }
            "dev" -> {
                val d = DevData.parse(msg)
                main.post { dev.update(d); eco.update(d); controls.update(d); assistant.setProject(d.projects.firstOrNull { it.active }?.name ?: "") }
            }
            // ---- "Hey LEVI" (recognised on the laptop) ----
            "voice" -> { val t = msg.optString("text").take(80); main.post { banner.show(t) } }
            "ui" -> { val s = msg.optInt("screen", -1); main.post { show(s) } }
            "media" -> main.post {
                when (msg.optString("action")) {
                    "play" -> if (!hub.playing) { if (hub.hasSession()) hub.playPause() else packageManager.getLaunchIntentForPackage(MediaHub.BITCHORD)?.let { startActivity(it) } }
                    "pause" -> if (hub.playing) hub.playPause()
                    "next" -> hub.next()
                    "previous" -> hub.previous()
                }
            }
            "dock-volume" -> main.post {
                val am = getSystemService(android.media.AudioManager::class.java)
                val dir = when (msg.optString("dir")) {
                    "up" -> android.media.AudioManager.ADJUST_RAISE
                    "down" -> android.media.AudioManager.ADJUST_LOWER
                    else -> android.media.AudioManager.ADJUST_TOGGLE_MUTE
                }
                repeat(if (dir == android.media.AudioManager.ADJUST_TOGGLE_MUTE) 1 else 2) {
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, 0)
                }
            }
            "ai-user" -> { val id = msg.optLong("id"); val t = msg.optString("text").take(4000); main.post { assistant.addSpoken(id, t) } }
            "quick-result" -> {
                val ok = msg.optBoolean("ok"); val t = msg.optString("text").take(120)
                main.post { controls.onResult(ok, t.replaceFirstChar { it.uppercase() }) }
            }
            "ai-status" -> {
                val r = msg.optBoolean("ready"); val p = msg.optString("provider", "AI").take(24); val m = msg.optString("model").take(60)
                main.post { assistant.setStatus(r, p, m) }
            }
            "ai-delta" -> { val id = msg.optLong("id"); val t = msg.optString("text"); main.post { assistant.onDelta(id, t) } }
            "ai-done" -> {
                val id = msg.optLong("id"); val e = if (msg.has("error")) msg.optString("error").take(300) else null
                main.post { assistant.onDone(id, e) }
            }
            "input" -> {
                val dock = msg.optString("mode") == "dock"
                val ready = msg.optBoolean("ready"); val clip = msg.optBoolean("clipboard", true)
                main.post { eco.setInput(dock, ready, clip) }
            }
            // Laptop → dock clipboard (writing the clipboard is allowed in the background).
            "clipboard" -> {
                val t = msg.optString("text").take(65_536)
                if (t.isNotEmpty()) main.post {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LEVI", t))
                }
            }
            // Dock → laptop: Android 10+ only lets the focused app read the clipboard.
            "clipboard-request" -> main.post {
                if (hasWindowFocus()) {
                    val clip = getSystemService(ClipboardManager::class.java).primaryClip
                    val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
                    if (!t.isNullOrEmpty()) command("clipboard", JSONObject().put("text", t.take(65_536)))
                }
            }
        }
    }

    /** Token-authenticated command to the laptop host (off the main thread). */
    private fun command(action: String, extra: JSONObject = JSONObject()) {
        val token = Pairing.token(this) ?: return
        extra.put("type", "cmd").put("action", action).put("token", token)
        Thread { link.send(extra) }.start()
    }

    private fun bitchordInstalled() =
        try { packageManager.getPackageInfo(MediaHub.BITCHORD, 0); true } catch (_: Exception) { false }

    override fun onConnection(connected: Boolean) {
        main.post {
            linked = connected
            dashboard.setLinked(connected)
            dev.setLinked(connected)
            media.setConnected(connected)
            eco.setLinked(connected)
            assistant.setLinked(connected)
            controls.setLinked(connected)
        }
    }
}
