package com.kami.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Headless ADB-Keyboard-style IME: text arrives via the `ADB_INPUT_TEXT`
 * broadcast (same contract as the community ADBKeyBoard, so external tools
 * work unchanged) and is committed into the focused editor. Used by the
 * agent's input_text tool for non-ASCII text that `input text` can't carry.
 */
class AdbKeyboardService : InputMethodService() {

    companion object {
        /** Text parked until the input connection is bound (poller commits it). */
        @Volatile
        private var pending: String? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra("msg") ?: return
            val ic = currentInputConnection
            if (ic != null) {
                ic.commitText(text, 1)
                pending = null
            } else {
                pending = text
            }
        }
    }

    private val poller = object : Runnable {
        override fun run() {
            val text = pending
            val ic = currentInputConnection
            if (text != null && ic != null) {
                ic.commitText(text, 1)
                pending = null
            }
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter("ADB_INPUT_TEXT")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        handler.post(poller)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }
}
