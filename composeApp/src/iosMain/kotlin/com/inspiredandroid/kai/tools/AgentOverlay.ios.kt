package com.inspiredandroid.kai.tools

actual fun notifyAgentRunActive(active: Boolean) {}

actual fun isRunVisibleToUser(): Boolean = true

actual fun isOverlayPermissionGranted(): Boolean = true

actual fun requestOverlayPermission() {}
