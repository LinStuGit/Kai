package com.kami.app

/**
 * Doze/standby exemptions for this app, applied through Shizuku so timer
 * notifications fire reliably: the deviceidle whitelist plus the
 * RUN_ANY_IN_BACKGROUND appop. Both are persistent system state — applied
 * once they stick until uninstall, and neither keeps any process alive.
 */
object KeepAlive {

    private const val PKG = "com.kami.app"

    /** Apply both exemptions (idempotent); returns a status line. */
    fun apply(): String {
        if (!ShizukuRunner.granted()) return "错误：Shizuku 未授权"
        ShizukuRunner.run("dumpsys deviceidle whitelist +$PKG")
        ShizukuRunner.run("cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow")
        return status()
    }

    /** Human-readable current state (whitelisted? background allowed?). */
    fun status(): String {
        if (!ShizukuRunner.granted()) return "错误：Shizuku 未授权，无法查询"
        val whitelisted = ShizukuRunner.run("dumpsys deviceidle whitelist")
            .contains(PKG)
        val bgAllowed = ShizukuRunner.run("cmd appops get $PKG RUN_ANY_IN_BACKGROUND")
            .contains("allow", ignoreCase = true)
        val wl = if (whitelisted) "已加入" else "未加入"
        val bg = if (bgAllowed) "允许" else "默认"
        return "Doze 白名单：$wl · 后台运行：$bg"
    }
}
