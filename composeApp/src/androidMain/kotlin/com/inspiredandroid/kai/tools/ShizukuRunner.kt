package com.inspiredandroid.kai.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

/** Run privileged (shell-uid) commands through Shizuku; no root needed. */
object ShizukuRunner {

    private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4223

    /** App context, installed at tool-mount time for the authorization jump. */
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** True when the Shizuku server is up and we hold its permission. */
    fun granted(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** Run one shell command; returns combined output (error text on failure). */
    fun run(cmd: String): String = try {
        val service = IShizukuService.Stub.asInterface(
            ShizukuBinderWrapper(Shizuku.getBinder()!!),
        )
        val p = service.newProcess(arrayOf("sh", "-c", cmd), null, null)
        val out = ParcelFileDescriptor.AutoCloseInputStream(p.inputStream)
            .bufferedReader().readText()
        val err = ParcelFileDescriptor.AutoCloseInputStream(p.errorStream)
            .bufferedReader().readText()
        p.waitFor()
        (out + err).trim()
    } catch (t: Throwable) {
        "shizuku error: $t"
    }

    /**
     * Sends the user into Shizuku's authorization flow: when the server is up
     * this pops its permission dialog directly, otherwise it opens the manager
     * app (where the user starts the server first).
     */
    fun requestAuthorization() {
        val context = appContext ?: return
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(REQUEST_CODE)
                }
            } else {
                context.packageManager.getLaunchIntentForPackage(SHIZUKU_MANAGER_PACKAGE)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
        } catch (_: Throwable) {
        }
    }
}

actual fun launchShizukuAuthorization() {
    ShizukuRunner.requestAuthorization()
}
