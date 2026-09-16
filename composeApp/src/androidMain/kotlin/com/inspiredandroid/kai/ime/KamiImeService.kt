package com.inspiredandroid.kai.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * Headless IME used by the agent's `input_text` tool. Because the IME runs in
 * the app's own process, the tool passes text through the static [pendingCommit]
 * bridge and this service commits it into whatever field currently has input
 * focus — the only way to enter non-ASCII text (e.g. Chinese) into other apps
 * without root: `input text` drops everything outside ASCII.
 *
 * The service renders no keyboard UI; it's enabled and selected programmatically
 * via Shizuku (`ime enable` / `ime set`) and switched back to the user's own IME
 * after the commit. The poller is cheap (a 100 ms handler tick while bound).
 */
class KamiImeService : InputMethodService() {

    companion object {
        /** Text waiting to be committed into the focused field; null when idle. */
        @Volatile
        var pendingCommit: String? = null

        /** True while the system has this IME bound to an editor. */
        @Volatile
        var bound: Boolean = false
    }

    private val handler = Handler(Looper.getMainLooper())

    private val poller = object : Runnable {
        override fun run() {
            pendingCommit?.let { text ->
                pendingCommit = null
                currentInputConnection?.commitText(text, 1)
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler.post(poller)
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        bound = false
        pendingCommit = null
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        bound = true
    }

    override fun onFinishInput() {
        bound = false
        super.onFinishInput()
    }

    override fun onCreateInputView(): View = View(this)

    override fun onCreateCandidatesView(): View? = null
}

private const val POLL_INTERVAL_MS = 100L
