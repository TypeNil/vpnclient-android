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

    /** Last-N redacted lines kept for the Diagnostics export. Receives the
     *  same sanitized text that reaches logcat — never raw input. */
    val ring = LogRing()

    fun d(
        tag: String,
        message: String,
        redact: Boolean = false,
    ) {
        if (!enabled || !debugEnabled) return
        record('D', tag, if (redact) Redactor.redact(message) else message)
    }

    fun i(
        tag: String,
        message: String,
        redact: Boolean = false,
    ) {
        if (!enabled || !debugEnabled) return
        record('I', tag, if (redact) Redactor.redact(message) else message)
    }

    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        redact: Boolean = true,
    ) {
        if (!enabled) return
        val msg = if (redact) Redactor.redact(message) else message
        val t = if (redact) throwable?.let(::redacted) else throwable
        record('W', tag, throwable?.let { "$msg — ${t?.message}" } ?: msg)
        // Exception messages can echo untrusted input; the stack trace itself is safe.
        if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
    }

    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        redact: Boolean = true,
    ) {
        if (!enabled) return
        val msg = if (redact) Redactor.redact(message) else message
        val t = if (redact) throwable?.let(::redacted) else throwable
        record('E', tag, throwable?.let { "$msg — ${t?.message}" } ?: msg)
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
    }

    /** Log the line to logcat and mirror it into [ring] with a timestamp.
     *  Only the sanitized form is stored. */
    private fun record(
        level: Char,
        tag: String,
        message: String,
    ) {
        Log.println(
            when (level) {
                'D' -> Log.DEBUG
                'I' -> Log.INFO
                'W' -> Log.WARN
                else -> Log.ERROR
            },
            tag,
            message,
        )
        val stamp = TIME_FORMAT.format(java.util.Date())
        ring.add("$stamp $level/$tag: $message")
    }

    private val TIME_FORMAT =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)

    private fun redacted(t: Throwable): Throwable = Throwable("${t.javaClass.name}: ${Redactor.redact(t.message)}")
}
