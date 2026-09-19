package com.kami.app

/**
 * Doze/standby exemptions for this app, applied through Shizuku so timer
 * notifications fire reliably: the deviceidle whitelist plus the
 * RUN_ANY_IN_BACKGROUND appop (set to `ignore`, meaning ignore the
 * background-restriction so work may run). Both are persistent system state
 * — applied once they stick until uninstall, and neither keeps any process
 * alive. For an always-warm process the user can additionally turn on the
 * optional [KeepAliveService] watchdog (foreground service) below.
 */
object KeepAlive {

    private const val PKG = "com.kami.app"

    /** Apply both exemptions (idempotent); returns a status line. */
    fun apply(): String {
        if (!ShizukuRunner.granted()) return "错误：Shizuku 未授权"
        ShizukuRunner.run("cmd deviceidle whitelist +$PKG")
        ShizukuRunner.run("cmd appops set $PKG RUN_ANY_IN_BACKGROUND ignore")
        return status()
    }

    /** Human-readable current state (whitelisted? background allowed?). */
    fun status(): String {
        if (!ShizukuRunner.granted()) return "错误：Shizuku 未授权，无法查询"
        val whitelisted = ShizukuRunner.run("dumpsys deviceidle whitelist")
            .contains(PKG)
        val appop = ShizukuRunner.run("cmd appops get $PKG RUN_ANY_IN_BACKGROUND")
        // appops reports "ignore" and "default" both as "default" in the
        // human column depending on OEM, so match the mode token explicitly.
        val bgOk =
            Regex("RUN_ANY_IN_BACKGROUND:\\s*(allow|ignore)").containsMatchIn(appop)
        val wl = if (whitelisted) "已加入" else "未加入"
        val bg = if (bgOk) "允许" else "默认"
        return "Doze 白名单：$wl · 后台运行：$bg"
    }
}