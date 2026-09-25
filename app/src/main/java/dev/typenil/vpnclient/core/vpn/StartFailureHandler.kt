package dev.typenil.vpnclient.core.vpn

import dev.typenil.vpnclient.core.common.log.SecureLog

/**
 * Finishes a failed service start attempt.
 *
 * Each step suspends — a settings read, then a settings write — and the user
 * can state a new intent inside either one. Ownership is therefore re-checked
 * between the steps, and which intent took over decides the outcome: a connect
 * that raced the failure keeps the desire flag it just set, while a disconnect
 * must not have it resurrected.
 *
 * That sequencing is the part of the service that kept getting raced, so it
 * lives here — pure over its effects — instead of inside a `VpnService` no
 * unit test can drive.
 */
internal class StartFailureHandler(
    private val guard: StartAttemptGuard,
    /** A session waiting for an engine — a connect by construction. */
    private val sessionPending: () -> Boolean,
    /** Durable "the user wants the VPN running". */
    private val readDesire: suspend () -> Boolean,
    private val writeDesire: suspend (Boolean) -> Unit,
    /** A stop is already under way (disconnect teardown or destruction). */
    private val stopping: () -> Boolean,
    private val report: (VpnError) -> Unit,
    /** Stop the service without publishing anything. */
    private val converge: () -> Unit,
) {
    suspend fun finish(
        error: VpnError,
        attempt: Long,
    ) {
        // Checked before every terminal action and after every suspension
        // point: each of them is a window where the user can act.
        if (!stillOwns(attempt)) {
            SecureLog.i(TAG, "stale start failure — the service has a newer owner")
            return
        }
        // A failed read keeps the previous behaviour (report the failure) —
        // silently swallowing it would be worse than a stale error.
        val wanted = readDesire()
        // A value read before a newer intent arrived is stale, so ownership is
        // re-checked before acting on it: a queued connect must not be stopped
        // out from under.
        if (!stillOwns(attempt)) {
            SecureLog.i(TAG, "stale start failure — the service has a newer owner")
            return
        }
        if (stopping() || !wanted) {
            SecureLog.i(TAG, "start failed after a stop request — converging quietly")
            converge()
            return
        }
        SecureLog.w(TAG, "service start failed: ${error.javaClass.simpleName}")
        // Not a transient failure: don't let a boot / always-on restore loop.
        writeDesire(false)
        // The write suspends too. Only a *connect* needs the stale clear
        // undone — false is exactly what a disconnect asked for.
        val owner = ownerNow(attempt)
        if (owner == StartOwner.Connect) {
            writeDesire(true)
            SecureLog.i(TAG, "start failure raced a newer connect — desire restored")
            return
        }
        if (owner == StartOwner.Disconnect) {
            SecureLog.i(TAG, "start failure raced a disconnect — leaving the desire off")
            return
        }
        report(error)
        converge()
    }

    private fun ownerNow(attempt: Long): StartOwner = guard.owner(attempt, sessionPending())

    private fun stillOwns(attempt: Long): Boolean = ownerNow(attempt) == StartOwner.ThisAttempt

    private companion object {
        const val TAG = "StartFailureHandler"
    }
}
