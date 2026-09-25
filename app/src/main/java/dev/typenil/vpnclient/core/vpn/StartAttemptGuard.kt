package dev.typenil.vpnclient.core.vpn

/**
 * Ownership bookkeeping for a service start attempt.
 *
 * A start attempt is long — it compiles a config, reads and writes settings —
 * so the user can state a new intent while it runs. Every side effect of
 * *finishing* a failed attempt (clearing the "the user wants the VPN" flag,
 * publishing an error, stopping the service) must be conditional on the
 * attempt still owning the service. Otherwise a stale restore drops a
 * `connect()` the user just made: its `ACTION_CONNECT` is rejected by the
 * service's single-flight guard, so nothing else would ever start it.
 *
 * Pure: the caller supplies the intent counter and the pending-session lookup,
 * so the whole transition table is unit-testable without a service.
 */
internal class StartAttemptGuard {
    /** Bumped by every new user intent (connect/disconnect). */
    private var intent = 0L

    /** A start whose `ACTION_CONNECT` the single-flight guard rejected. */
    private var queued = false

    /** A new user intent invalidates every in-flight attempt. */
    fun onUserIntent() {
        intent++
    }

    /** Token for a new attempt — compared later by [superseded]. */
    fun begin(): Long = intent

    /** A start was requested while an attempt was still running. */
    fun onStartQueued() {
        queued = true
    }

    /** Takes the queued request, clearing it. */
    fun consumeQueuedStart(): Boolean = queued.also { queued = false }

    /** Drops a queued request that must not be retried (e.g. a disconnect). */
    fun clearQueuedStart() {
        queued = false
    }

    /**
     * True when the service no longer works on [attempt]'s behalf: a newer
     * user intent arrived, a start is queued behind this attempt, or a session
     * is already waiting for an engine.
     */
    fun superseded(
        attempt: Long,
        sessionPending: Boolean,
    ): Boolean = intent != attempt || queued || sessionPending
}
