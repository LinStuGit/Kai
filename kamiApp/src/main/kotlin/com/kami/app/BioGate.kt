package com.kami.app

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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
        try {
            return deferred.await()
        } finally {
            synchronized(this) { deferreds.remove(id) }
        }
    }

    /** Chat UI takes the oldest pending request right before prompting. */
    fun claimFirst(): Request? {
        val first = pending.value.firstOrNull() ?: return null
        pending.value = pending.value.filterNot { it.id == first.id }
        return first
    }

    fun decide(id: Long, allowed: Boolean) {
        synchronized(this) { deferreds[id] }?.complete(allowed)
    }
}
