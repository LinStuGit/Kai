package com.kami.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Agent-facing channel of the extension interface. The bridge agent (or any
 * adb / Shizuku session) manages features without touching the UI:
 *
 *     am broadcast -a com.kami.app.ext.ADD    --es json '{"id":…,"name":…,"cmd":…}'
 *     am broadcast -a com.kami.app.ext.REMOVE --es id ext-123
 *     am broadcast -a com.kami.app.ext.LIST
 *     am broadcast -a com.kami.app.ext.CLEAR
 *
 * Results are logged under tag "KamiExt" and read back with
 * `logcat -d -s KamiExt` — the round trip an agent needs, since broadcasts
 * carry no return value. When the interface is switched off in Settings
 * every action is ignored.
 */
class ExtensionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ExtensionStore.init(context)
        if (!ExtensionStore.master.value) {
            Log.i(TAG, "extension interface disabled — ignored: ${intent.action}")
            return
        }
        when (intent.action) {
            ACTION_ADD -> {
                val n = try {
                    ExtensionStore.addFromJson(intent.getStringExtra("json") ?: "")
                } catch (t: Throwable) {
                    Log.w(TAG, "ADD failed: $t")
                    -1
                }
                if (n >= 0) {
                    Log.i(TAG, "ADD ok: $n added, ${ExtensionStore.items.value.size} total")
                }
            }

            ACTION_REMOVE -> {
                val id = intent.getStringExtra("id") ?: ""
                val before = ExtensionStore.items.value.size
                ExtensionStore.remove(id)
                Log.i(TAG, "REMOVE $id: ${before - ExtensionStore.items.value.size} removed")
            }

            ACTION_LIST -> Log.i(TAG, "LIST: ${ExtensionStore.toJson()}")

            ACTION_CLEAR -> {
                ExtensionStore.clear()
                Log.i(TAG, "CLEAR ok")
            }

            else -> Log.w(TAG, "unknown action: ${intent.action}")
        }
    }

    companion object {
        const val ACTION_ADD = "com.kami.app.ext.ADD"
        const val ACTION_REMOVE = "com.kami.app.ext.REMOVE"
        const val ACTION_LIST = "com.kami.app.ext.LIST"
        const val ACTION_CLEAR = "com.kami.app.ext.CLEAR"
        private const val TAG = "KamiExt"
    }
}
