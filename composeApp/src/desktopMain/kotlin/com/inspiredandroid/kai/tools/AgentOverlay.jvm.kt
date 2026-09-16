package com.inspiredandroid.kai.tools

actual fun notifyAgentRunActive(active: Boolean) {}

actual fun isRunVisibleToUser(): Boolean = true

actual fun isOverlayPermissionGranted(): Boolean = true

actual fun requestOverlayPermission() {}

actual fun launchShizukuAuthorization() {
    // Shizuku is Android-only; device tools are unavailable here.
}
