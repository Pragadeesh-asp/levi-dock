package com.levi.dock

import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Loopback-only line-delimited JSON server. The laptop host connects through
 * `adb forward tcp:47800 tcp:47800`, so nothing listens on Wi-Fi.
 * One client at a time; the host sends a ping at least every 2 s, so a 6 s
 * read timeout detects a dead link.
 */
class LinkServer(private val port: Int, private val listener: Listener) {

    interface Listener {
        fun onMessage(msg: JSONObject)
        fun onConnection(connected: Boolean)
    }

    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "levi-link", isDaemon = true) { acceptLoop() }
    }

    /** Sends one message to the laptop host; returns false if not linked. Any thread. */
    fun send(msg: JSONObject): Boolean {
        val s = client ?: return false
        return try {
            synchronized(this) {
                val out = s.getOutputStream()
                out.write((msg.toString() + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    fun stop() {
        running = false
        closeQuietly(client)
        try { server?.close() } catch (_: IOException) {}
    }

    private fun acceptLoop() {
        while (running) {
            try {
                ServerSocket().use { ss ->
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                    server = ss
                    while (running) handle(ss.accept())
                }
            } catch (e: IOException) {
                if (running) Thread.sleep(1000)
            }
        }
    }

    private fun handle(s: Socket) {
        client = s
        s.tcpNoDelay = true
        s.soTimeout = 6000
        listener.onConnection(true)
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8), 8192)
            val out = s.getOutputStream()
            while (running) {
                val line = try { reader.readLine() } catch (e: SocketTimeoutException) { null } ?: break
                if (line.isEmpty() || line.length > 65536) continue
                val msg = try { JSONObject(line) } catch (e: JSONException) { continue }
                if (msg.optString("type") == "ping") {
                    synchronized(this) {
                        out.write("{\"type\":\"pong\",\"t\":${msg.optLong("t")}}\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    continue
                }
                listener.onMessage(msg)
            }
        } catch (_: IOException) {
        } finally {
            closeQuietly(s)
            client = null
            listener.onConnection(false)
        }
    }

    private fun closeQuietly(s: Socket?) { try { s?.close() } catch (_: IOException) {} }
}
