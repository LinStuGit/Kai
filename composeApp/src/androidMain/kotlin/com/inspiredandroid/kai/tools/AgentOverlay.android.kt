package com.inspiredandroid.kai.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.inspiredandroid.kai.AgentOverlayService
import com.inspiredandroid.kai.data.AppSettings
import org.koin.java.KoinJavaComponent.inject

actual fun notifyAgentRunActive(active: Boolean) {
    AgentOverlayController.setRunActive(active)
    if (!active) return // the service observes runState and stops itself
    val context: Context by inject(Context::class.java)
    val appSettings: AppSettings by inject(AppSettings::class.java)
    if (!appSettings.isAgentOverlayEnabled()) return
    if (!Settings.canDrawOverlays(context)) return
    context.startForegroundService(Intent(context, AgentOverlayService::class.java))
}

actual fun isOverlayPermissionGranted(): Boolean {
    val context: Context by inject(Context::class.java)
    return Settings.canDrawOverlays(context)
}

actual fun requestOverlayPermission() {
    val context: Context by inject(Context::class.java)
    context.startActivity(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
