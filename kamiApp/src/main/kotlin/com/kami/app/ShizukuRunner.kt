package com.kami.app

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.util.concurrent.ConcurrentHashMap

/**
 * Run privileged (shell-uid) commands through Shizuku; no root needed.
 * Each command's wrapper sh records its own pid to a per-run file, and the
 * in-flight remote processes are tracked here, so killAll can take down
 * every running command tree (all parallel sessions) and make any
 * app-side blocking read return at once.
 */
object ShizukuRunner {

    private const val PID_DIR = "/data/local/tmp"
    private const val LEGACY_PID_FILE = "$PID_DIR/.kami-cur-pid"

    /** In-flight remote process -> the pid file its wrapper writes. */
    private val inflight = ConcurrentHashMap<IRemoteProcess, String>()

    /** True when the Shizuku server is up and we hold its permission. */
    fun granted(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** Run one shell command; returns combined output (error text on failure). */
    fun run(cmd: String): String = try {
        val service = binder()
        val pf = "$PID_DIR/.kami-pid-${System.nanoTime()}"
        val wrapped = "echo \$\$ > $pf 2>/dev/null; $cmd; rm -f $pf 2>/dev/null"
        val p = service.newProcess(arrayOf("sh", "-c", wrapped), null, null)
        inflight[p] = pf
        try {
            val out = ParcelFileDescriptor.AutoCloseInputStream(p.inputStream)
                .bufferedReader().readText()
            val err = ParcelFileDescriptor.AutoCloseInputStream(p.errorStream)
                .bufferedReader().readText()
            p.waitFor()
            (out + err).trim()
        } finally {
            inflight.remove(p)
        }
    } catch (t: Throwable) {
        "shizuku error: $t"
    }

    private fun binder(): IShizukuService = IShizukuService.Stub.asInterface(
        ShizukuBinderWrapper(Shizuku.getBinder()!!),
    )

    /**
     * Unconditional hard stop: for every in-flight command (all sessions,
     * not just the newest) SIGKILL its whole process tree — wrapper sh plus
     * descendants to depth 5, swept while parents are alive and children
     * still discoverable — then destroy() each remote process so blocking
     * reads return immediately. Also sweeps the pre-v11 shared pid file.
     */
    fun killAll() {
        val targets = inflight.toList()
        if (!granted()) {
            targets.forEach { (p, _) -> runCatching { p.destroy() } }
            return
        }
        val files = targets.joinToString(" ") { (_, pf) -> pf } + " $LEGACY_PID_FILE"
        execRaw(
            "roots=''; for f in $files; do r=\$(cat \$f 2>/dev/null); " +
                "roots=\"\$roots \$r\"; done; list=\$roots; " +
                "for i in 1 2 3 4 5; do nxt=''; for q in \$list; do " +
                "n=\$(pgrep -P \$q 2>/dev/null); nxt=\"\$nxt \$n\"; done; " +
                "list=\"\$list \$nxt\"; done; kill -9 \$list 2>/dev/null; " +
                "rm -f $files 2>/dev/null; true",
        )
        targets.forEach { (p, _) -> runCatching { p.destroy() } }
    }

    /** Best-effort one-shot exec used by [killAll]; never throws. */
    private fun execRaw(cmd: String) {
        try {
            val p = binder().newProcess(arrayOf("sh", "-c", cmd), null, null)
            ParcelFileDescriptor.AutoCloseInputStream(p.inputStream)
                .bufferedReader().readText()
            p.waitFor()
        } catch (_: Throwable) {
        }
    }
}
