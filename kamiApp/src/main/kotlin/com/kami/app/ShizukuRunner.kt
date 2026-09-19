package com.kami.app

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Run privileged (shell-uid) commands through Shizuku; no root needed.
 * Every command first records its own pid to [PID_FILE] so a hard cancel
 * (killCurrent) can take down whatever is in flight.
 */
object ShizukuRunner {

    private const val PID_FILE = "/data/local/tmp/.kami-cur-pid"

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
        val wrapped = "echo \$\$ > $PID_FILE 2>/dev/null; $cmd"
        val p = service.newProcess(arrayOf("sh", "-c", wrapped), null, null)
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
     * Unconditionally kill the in-flight command (the pid recorded by the
     * last run) — the sh itself and its direct children. Never writes the
     * pid file, so cancelling never clobbers the record.
     */
    fun killCurrent() {
        if (!granted()) return
        try {
            val service = IShizukuService.Stub.asInterface(
                ShizukuBinderWrapper(Shizuku.getBinder()!!),
            )
            val kill = "p=\$(cat $PID_FILE 2>/dev/null)" +
                " && { kill -9 \$p 2>/dev/null; pkill -9 -P \$p 2>/dev/null; }; true"
            val p = service.newProcess(arrayOf("sh", "-c", kill), null, null)
            ParcelFileDescriptor.AutoCloseInputStream(p.inputStream).readText()
            p.waitFor()
        } catch (t: Throwable) {
            // best effort — cancelling must never throw
        }
    }
}
