package dev.typenil.vpnclient.core.vpn

/** A user intent the service has to honour. */
internal enum class StartIntent { Connect, Disconnect }

/** Who a start attempt's work belongs to now. */
internal enum class StartOwner {
    /** The attempt still owns the service. */
    ThisAttempt,

    /** A connect arrived meanwhile — its start may be queued behind us. */
    Connect,

    /** A disconnect arrived meanwhile — teardown belongs to it. */
    Disconnect,
}

/**
 * Ownership bookkeeping for a service start attempt.
 *
 * A start attempt is long — it compiles a config, reads and writes settings —
 * so the user can state a new intent while it runs. Every side effect of
 * *finishing* an attempt (clearing the "the user wants the VPN" flag,
 * publishing an error, stopping the service, starting an engine) must be
 * conditional on the attempt still owning the service, and must know *which*
 * intent took over: a stale restore may compensate for a connect it raced,
 * but never resurrect the desire flag for a disconnect.
 *
 * Pure: the caller supplies the pending-session lookup, so the whole
 * transition table is unit-testable without a service.
 */
internal class StartAttemptGuard {
    /** Bumped by every new user intent. */
    private var intent = 0L

    /** The newest user intent — the counter alone can't tell connect from
     *  disconnect, and the two demand opposite handling. */
    private var lastIntent: StartIntent? = null

    /** A start whose `ACTION_CONNECT` the single-flight guard rejected. */
    private var queued = false

    /** A new user intent invalidates every in-flight attempt. */
    fun onUserIntent(intent: StartIntent) {
        this.intent++
        lastIntent = intent
    }

    /** Token for a new attempt — compared later by [owner]. */
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
     * Who the service works for now. A session waiting for an engine and a
     * start queued behind this attempt are both connects by construction — a
     * disconnect leaves neither behind.
     */
    fun owner(
        attempt: Long,
        sessionPending: Boolean,
    ): StartOwner =
        when {
            sessionPending || queued -> StartOwner.Connect
            intent == attempt -> StartOwner.ThisAttempt
            lastIntent == StartIntent.Connect -> StartOwner.Connect
            else -> StartOwner.Disconnect
        }
}
