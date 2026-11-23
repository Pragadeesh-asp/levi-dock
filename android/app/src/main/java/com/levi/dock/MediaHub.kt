package com.levi.dock

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent

/**
 * Follows the active media session on the dock (BitChord preferred) through
 * Android's MediaSessionManager. Needs notification-listener access for
 * [MediaListener]; LEVI DOCK grants it over ADB during setup.
 */
class MediaHub(private val ctx: Context, private val onChange: () -> Unit) {

    data class Track(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val art: Bitmap?,
        val app: String,
    )

    var track: Track? = null; private set
    var playing = false; private set
    private var positionMs = 0L
    private var positionAt = 0L
    private var speed = 1f

    private val main = Handler(Looper.getMainLooper())
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(ctx, MediaListener::class.java)
    private var controller: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { readMetadata(); onChange() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { readState(); onChange() }
        override fun onSessionDestroyed() { pick(sessions()); onChange() }
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list); onChange()
    }

    fun hasAccess(): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == listener }
    }

    fun start() {
        if (!hasAccess()) { onChange(); return }
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listener, main)
            pick(sessions())
        } catch (_: SecurityException) {
        }
        onChange()
    }

    fun stop() {
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun sessions(): List<MediaController> =
        try { msm.getActiveSessions(listener) } catch (_: SecurityException) { emptyList() }

    private fun pick(list: List<MediaController>?) {
        val all = list.orEmpty()
        fun isPlaying(c: MediaController) = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val best = all.firstOrNull { it.packageName == BITCHORD && isPlaying(it) }
            ?: all.firstOrNull { isPlaying(it) }
            ?: all.firstOrNull { it.packageName == BITCHORD }
            ?: all.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = best
        best?.registerCallback(callback, main)
        readMetadata(); readState()
    }

    private fun readMetadata() {
        val c = controller
        val md = c?.metadata
        if (c == null || md == null) { track = null; return }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val old = track
        val sameTrack = old != null && old.title == title && old.artist == artist
        track = Track(
            title = title,
            artist = artist,
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            art = if (sameTrack && art == null) old?.art else art?.let { shrink(it) },
            app = c.packageName,
        )
    }

    private fun readState() {
        val s = controller?.playbackState
        playing = s?.state == PlaybackState.STATE_PLAYING
        positionMs = s?.position ?: 0L
        positionAt = s?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        speed = s?.playbackSpeed?.takeIf { it > 0f } ?: 1f
    }

    /** Current playback position, extrapolated from the last state update. */
    fun position(): Long {
        val p = if (playing) positionMs + ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong() else positionMs
        val d = track?.durationMs ?: 0L
        return if (d > 0) p.coerceIn(0L, d) else p.coerceAtLeast(0L)
    }

    fun hasSession() = controller != null

    // Media-button events, not transportControls.play(): BitChord (media3) ignores a bare
    // play() while its player is idle after a restart, but resumes on a media button.
    fun playPause() = mediaKey(if (playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY)
    fun next() = mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    private fun mediaKey(code: Int) {
        val c = controller ?: return
        val t = SystemClock.uptimeMillis()
        c.dispatchMediaButtonEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        c.dispatchMediaButtonEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }
    fun seekTo(ms: Long) { controller?.transportControls?.seekTo(ms) }

    private fun shrink(b: Bitmap): Bitmap {
        val max = 380
        if (b.width <= max && b.height <= max) return b
        val k = max.toFloat() / maxOf(b.width, b.height)
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
    }

    companion object {
        const val BITCHORD = "com.music.bitchord"
    }
}
