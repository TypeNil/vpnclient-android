package dev.typenil.vpnclient.core.common.log

import android.util.Log

/**
 * Logging facade that redacts secrets before anything reaches logcat.
 * Redaction is on by default for warn/error and opt-in for debug/info.
 */
object SecureLog {

    /** Master switch — w/e stay on in release so failures are diagnosable. */
    var enabled: Boolean = true

    /** Verbose logging (d/i) — set false in release builds. */
    var debugEnabled: Boolean = true

    fun d(tag: String, message: String, redact: Boolean = false) {
        if (!enabled || !debugEnabled) return
        Log.d(tag, if (redact) Redactor.redact(message) else message)
    }

    fun i(tag: String, message: String, redact: Boolean = false) {
        if (!enabled || !debugEnabled) return
        Log.i(tag, if (redact) Redactor.redact(message) else message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null, redact: Boolean = true) {
        if (!enabled) return
        val msg = if (redact) Redactor.redact(message) else message
        // Exception messages can echo untrusted input; the stack trace itself is safe.
        if (throwable != null) Log.w(tag, msg, if (redact) redacted(throwable) else throwable)
        else Log.w(tag, msg)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null, redact: Boolean = true) {
        if (!enabled) return
        val msg = if (redact) Redactor.redact(message) else message
        if (throwable != null) Log.e(tag, msg, if (redact) redacted(throwable) else throwable)
        else Log.e(tag, msg)
    }

    private fun redacted(t: Throwable): Throwable =
        Throwable("${t.javaClass.name}: ${Redactor.redact(t.message)}")
}
