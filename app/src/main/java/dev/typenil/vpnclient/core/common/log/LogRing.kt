package dev.typenil.vpnclient.core.common.log

/**
 * Bounded in-memory ring of the most recent log lines emitted through
 * [SecureLog] — the backing store for the Diagnostics screen's log export.
 *
 * Contract: callers append only *already-redacted* text. [SecureLog] applies
 * [Redactor] before anything reaches logcat and hands the same sanitized
 * string here, so the buffer never holds raw URLs, node ids, UUIDs or proxy
 * configs; the redact-on-append fallback below is a second line of defense
 * for direct callers.
 *
 * Thread-safe; process lifetime only (never persisted).
 */
class LogRing(
    val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lines = ArrayDeque<String>(capacity)

    /** Append one line. A defensive [Redactor] pass keeps the invariant
     *  "only redacted strings are stored" even for callers that bypass
     *  [SecureLog]. */
    @Synchronized
    fun add(line: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(Redactor.redact(line))
    }

    /** Oldest→newest copy of the buffered lines, for export. */
    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
    }

    val size: Int
        @Synchronized get() = lines.size

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
