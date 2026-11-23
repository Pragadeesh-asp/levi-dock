package com.levi.dock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the pairing token from levi-host (`adb shell am broadcast`).
 * Protected by android.permission.DUMP in the manifest, which only the ADB
 * shell and the system hold, so other apps cannot plant a token.
 */
class TokenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val t = intent.getStringExtra("token") ?: return
        if (t.length != 64 || !t.all { it in '0'..'9' || it in 'a'..'f' }) return
        Pairing.save(context, t)
    }
}

object Pairing {
    private const val PREFS = "levi"
    private const val KEY = "token"

    fun save(c: Context, token: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, token).apply()
    }

    fun token(c: Context): String? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
}
