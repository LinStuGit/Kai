package com.kami.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Native vibration across API levels (VibratorManager on 31+, legacy
 * Vibrator below). Used by alerts, notifications and the vibrate agent tool.
 */
object Vibe {

    private fun v(ctx: Context): Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    /** One short buzz of [ms] milliseconds (clamped 50..5000). */
    fun once(ctx: Context, ms: Long) {
        try {
            v(ctx).vibrate(VibrationEffect.createOneShot(ms.coerceIn(50, 5000), VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (t: Throwable) {
            // 无震动器（如模拟器）时静默忽略
        }
    }

    /** Waveform of on/off durations; [repeat] = index to loop from (-1 = once). */
    fun pattern(ctx: Context, longs: LongArray, repeat: Int = -1) {
        try {
            v(ctx).vibrate(VibrationEffect.createWaveform(longs, repeat))
        } catch (t: Throwable) {
            // 无震动器时静默忽略
        }
    }

    fun cancel(ctx: Context) {
        try {
            v(ctx).cancel()
        } catch (t: Throwable) {
            // 无震动器时静默忽略
        }
    }
}
