package com.kami.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Max wait for a sensitive-gate confirmation before it settles as denied. */
private const val GATE_TIMEOUT_MS = 60_000L
private const val GATE_CHANNEL = "kami_gate"
private const val GATE_TAG = "kami_gate"

/**
 * Biometric convenience gate backed by device credential. Devices without
 * enrolled biometrics auto-pass (fail-open) so the console stays usable —
 * the toggle in Settings switches the whole mechanism off.
 */
object BioGate {

    fun available(context: Context): Boolean = runCatching {
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    fun enabled(context: Context): Boolean = context.getSharedPreferences("kami_bio", Context.MODE_PRIVATE)
        .getBoolean("enabled", true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("kami_bio", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", value).apply()
    }

    /** Show the system biometric prompt; true only on successful auth. */
    suspend fun authenticate(activity: FragmentActivity, title: String, description: String): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onAuthenticationError(code: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(description.take(80))
                .setNegativeButtonText("取消")
                .build()
            prompt.authenticate(info)
        }
    }
}

/**
 * Holds agent tool calls that touch destructive host operations until the
 * chat UI runs a biometric check for them (ChatScreen collects [pending]).
 * The await side runs inside the blocking tool loop (Dispatchers.IO), the
 * decide side is a plain function called by the UI.
 */
object SensitiveGate {

    data class Request(val id: Long, val title: String, val detail: String)

    val pending = mutableStateOf<List<Request>>(emptyList())

    private val deferreds = HashMap<Long, CompletableDeferred<Boolean>>()
    private var seq = 0L

    /** Host-mutating operations that deserve a biometric check. */
    private val DANGEROUS = listOf(
        "reboot", "shutdown", "poweroff",
        "rm -rf", "rm -r ", " rm -r", " rm ", "unlink ", "shred ",
        "pm uninstall", "pm clear", "pm disable", "pm suspend", "pm install",
        "am force-stop", "am kill", "am crash",
        "settings put", "settings delete",
        "cmd appops", "cmd package", "cmd deviceidle",
        "svc ", "dd if=", "mkfs", "flash_image", "fastboot",
        "content delete", "content update", "content insert",
        "wm ", "setprop",
        // Messaging: sending texts / opening share or send intents
        "sendto", "sms:", " sms ", "sms send", "telephony.sms", "action.send",
        // Payment & major messaging apps: launching them always needs approval
        "alipay", "com.eg.android.alipaygphone", "com.tencent.mm", "wechat",
        "weixin", "micromsg", "com.unionpay", "com.tencent.mobileqq",
        "com.icbc", "com.chinamworld", "com.android.bankabc", "cmb.pb",
        "com.chinamobile", "bankofchina", "com.cebbank", "com.chinapay",
    )

    fun isDangerous(cmd: String): Boolean {
        val c = cmd.trim().lowercase()
        return DANGEROUS.any { c.contains(it) }
    }

    suspend fun await(title: String, detail: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val id = synchronized(this) {
            seq += 1
            deferreds[seq] = deferred
            seq
        }
        pending.value = pending.value + Request(id, title, detail)
        notifyPending(id, title, detail)
        try {
            // Never hang the tool loop: if nothing can prompt (app in the
            // background, no UI), settle as denied after a grace period.
            return runBlocking {
                withTimeoutOrNull(GATE_TIMEOUT_MS) { deferred.await() } ?: false
            }
        } finally {
            synchronized(this) { deferreds.remove(id) }
        }
    }

    private fun notifyPending(id: Long, title: String, detail: String) {
        val ctx = try {
            AppContextHolder.get()
        } catch (t: Throwable) {
            return
        }
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    GATE_CHANNEL,
                    "操作确认",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
            val open = PendingIntent.getActivity(
                ctx,
                id.toInt(),
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            nm.notify(
                GATE_TAG,
                id.toInt(),
                NotificationCompat.Builder(ctx, GATE_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("Kami 请求确认：$title")
                    .setContentText(detail.take(120))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun cancelNotification(id: Long) {
        val ctx = try {
            AppContextHolder.get()
        } catch (t: Throwable) {
            return
        }
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(GATE_TAG, id.toInt())
        }
    }

    /** Chat UI takes the oldest pending request right before prompting. */
    fun claimFirst(): Request? {
        val first = pending.value.firstOrNull() ?: return null
        pending.value = pending.value.filterNot { it.id == first.id }
        return first
    }

    fun decide(id: Long, allowed: Boolean) {
        cancelNotification(id)
        synchronized(this) { deferreds[id] }?.complete(allowed)
    }

    /**
     * Force-stop hook: settle every pending request as denied, so a
     * cancelled turn stops waiting on the biometric prompt and cannot run
     * its sensitive command afterwards if the user taps allow later.
     */
    fun cancelPending() {
        val all = synchronized(this) { deferreds.values.toList() }
        all.forEach { runCatching { it.complete(false) } }
        pending.value = emptyList()
    }
}
