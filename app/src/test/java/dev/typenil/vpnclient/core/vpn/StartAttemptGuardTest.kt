package dev.typenil.vpnclient.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ownership rules for a start attempt. The regressions this locks down:
 * a restore still compiling when the user acts must not stop the service
 * under a queued connect, must not start an engine after a disconnect, and
 * must not resurrect the desire flag for a disconnect — the counter alone
 * can't tell the two intents apart, and they demand opposite handling.
 */
class StartAttemptGuardTest {
    private val guard = StartAttemptGuard()

    private fun owner(
        attempt: Long,
        sessionPending: Boolean = false,
    ) = guard.owner(attempt, sessionPending)

    @Test
    fun `an attempt nobody interrupted still owns the service`() {
        val attempt = guard.begin()

        assertEquals(StartOwner.ThisAttempt, owner(attempt))
    }

    @Test
    fun `a connect during a failing restore supersedes it`() {
        val attempt = guard.begin()

        // The compile failed and the failure path suspended on settings; the
        // user tapped Connect, whose ACTION_CONNECT the single-flight guard
        // rejected, leaving its session queued behind this attempt.
        guard.onUserIntent(StartIntent.Connect)
        guard.onStartQueued()

        assertEquals(StartOwner.Connect, owner(attempt, sessionPending = true))
        // The finishing attempt drains the queued start instead of stopping.
        assertTrue(guard.consumeQueuedStart())
        assertFalse(guard.consumeQueuedStart())
    }

    @Test
    fun `a connect supersedes an attempt even before its session appears`() {
        val attempt = guard.begin()

        // The intent reaches the service before ConnectionManager's pending
        // session is observable — the token alone must already invalidate it.
        guard.onUserIntent(StartIntent.Connect)

        assertEquals(StartOwner.Connect, owner(attempt))
    }

    @Test
    fun `a disconnect supersedes an attempt and is not a connect`() {
        val attempt = guard.begin()

        guard.onUserIntent(StartIntent.Disconnect)

        // Distinct from Connect: the failure path must not compensate by
        // restoring the desire flag, nor start an engine.
        assertEquals(StartOwner.Disconnect, owner(attempt))
    }

    @Test
    fun `a disconnect after a connect is still a disconnect`() {
        val attempt = guard.begin()

        guard.onUserIntent(StartIntent.Connect)
        guard.onUserIntent(StartIntent.Disconnect)

        assertEquals(StartOwner.Disconnect, owner(attempt))
    }

    @Test
    fun `a connect after a disconnect is a connect again`() {
        val attempt = guard.begin()

        guard.onUserIntent(StartIntent.Disconnect)
        guard.onUserIntent(StartIntent.Connect)

        assertEquals(StartOwner.Connect, owner(attempt))
    }

    @Test
    fun `a session waiting for an engine belongs to a connect`() {
        val attempt = guard.begin()

        // No intent bump at all: the pending session alone is the newer owner.
        assertEquals(StartOwner.Connect, owner(attempt, sessionPending = true))
    }

    @Test
    fun `an attempt started after the intent owns the service again`() {
        guard.onUserIntent(StartIntent.Connect)
        val attempt = guard.begin()

        assertEquals(StartOwner.ThisAttempt, owner(attempt))
    }

    @Test
    fun `a disconnect drops a queued start`() {
        guard.onStartQueued()

        guard.clearQueuedStart()

        assertFalse(guard.consumeQueuedStart())
    }
}
