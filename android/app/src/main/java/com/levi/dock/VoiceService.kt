package com.levi.dock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * "Hey LEVI" on the dock: streams the dock microphone to the laptop (which runs
 * the speech recogniser) and plays LEVI's spoken replies on the dock speaker.
 *
 * Both sockets listen on loopback only and require the pairing token as the
 * first line, so other apps on the phone can't listen to the mic or play audio.
 *   127.0.0.1:47801  mic   → 16 kHz mono PCM16 to the laptop
 *   127.0.0.1:47802  voice ← 16 kHz mono PCM16 from the laptop
 */
class VoiceService : Service() {

    @Volatile private var running = true
    private val sockets = ArrayList<ServerSocket>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "LEVI voice", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LEVI DOCK")
            .setContentText("Listening for \"Hey LEVI\" with the laptop")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
        thread(name = "levi-mic", isDaemon = true) { serve(MIC_PORT) { s -> streamMic(s) } }
        thread(name = "levi-voice", isDaemon = true) { serve(SPEAK_PORT) { s -> playVoice(s) } }
    }

    override fun onDestroy() {
        running = false
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        super.onDestroy()
    }

    private fun serve(port: Int, handle: (Socket) -> Unit) {
        while (running) {
            try {
                ServerSocket().use { ss ->
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                    synchronized(sockets) { sockets.add(ss) }
                    while (running) {
                        val s = ss.accept()
                        try {
                            s.soTimeout = 4000
                            if (authorized(s.getInputStream())) { s.soTimeout = 0; handle(s) }
                        } catch (_: Exception) {
                        } finally {
                            runCatching { s.close() }
                        }
                    }
                }
            } catch (_: Exception) {
                if (running) Thread.sleep(1000)
            }
        }
    }

    /** First line must be the pairing token levi-host gave this app. */
    private fun authorized(input: InputStream): Boolean {
        val expected = Pairing.token(this) ?: return false
        val sb = StringBuilder()
        while (sb.length < 80) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            sb.append(b.toChar())
        }
        return MessageDigest.isEqual(sb.toString().trim().toByteArray(), expected.toByteArray())
    }

    private fun streamMic(s: Socket) {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400) * 2)
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        // The Mi 3W mic is quiet at desk distance: use the platform AGC if present, plus 4× gain.
        val agc = if (android.media.audiofx.AutomaticGainControl.isAvailable())
            android.media.audiofx.AutomaticGainControl.create(rec.audioSessionId)?.also { it.enabled = true } else null
        val buf = ByteArray(3200) // 100 ms
        var gain = 2f
        val out = s.getOutputStream()
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                // Adaptive gain: lift quiet speech up to GAIN×, never past ~75 % of full scale (no clipping).
                var peak = 1
                var i = 0
                while (i + 1 < n) {
                    val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                    if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
                    i += 2
                }
                val target = (24000f / peak).coerceIn(1f, GAIN.toFloat())
                gain += (target - gain) * (if (target < gain) 0.6f else 0.15f) // fast down, slow up
                i = 0
                while (i + 1 < n) {
                    val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort() * gain
                    val c = v.toInt().coerceIn(-32768, 32767)
                    buf[i] = (c and 0xFF).toByte(); buf[i + 1] = ((c shr 8) and 0xFF).toByte()
                    i += 2
                }
                out.write(buf, 0, n)
            }
        } finally {
            agc?.release()
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private fun playVoice(s: Socket) {
        val am = getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        am.requestAudioFocus(focus) // music ducks while LEVI talks
        try {
            track.play()
            val input = s.getInputStream()
            val buf = ByteArray(4096)
            var carry = -1
            while (running) {
                var n = input.read(buf, if (carry >= 0) 1 else 0, buf.size - 1)
                if (n < 0) break
                if (carry >= 0) { buf[0] = carry.toByte(); n += 1; carry = -1 }
                if (n % 2 == 1) { carry = buf[n - 1].toInt() and 0xFF; n -= 1 } // keep samples whole
                if (n > 0) track.write(buf, 0, n)
            }
            Thread.sleep(250)
        } finally {
            runCatching { track.stop() }
            track.release()
            am.abandonAudioFocusRequest(focus)
        }
    }

    companion object {
        const val MIC_PORT = 47801
        const val SPEAK_PORT = 47802
        private const val RATE = 16000
        private const val GAIN = 4
        private const val CHANNEL = "levi-voice"
    }
}
