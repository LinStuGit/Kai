package com.kami.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * "强制终止" from the notification action or the home chip: cancel every
 * running agent job and bring down the overlay service.
 */
class StopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AgentOverlayState.cancelAll()
        Thread { ScreenControl.restoreImeIfNeeded(force = true) }.start()
        context.stopService(Intent(context, AgentOverlayService::class.java))
    }

    companion object {
        const val ACTION = "com.kami.app.STOP_AGENT"
    }
}
