package dev.typenil.vpnclient.core.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ownership rules for a start attempt. The regression this locks down: a
 * restore that is still compiling when the user taps Connect must not clear
 * the desire flag, publish an error, or stop the service — its
 * `ACTION_CONNECT` is rejected by the single-flight guard, so nothing else
 * would start that session.
 */
class StartAttemptGuardTest {
    private val guard = StartAttemptGuard()

    @Test
    fun `an attempt nobody interrupted still owns the service`() {
        val attempt = guard.begin()

        assertFalse(guard.superseded(attempt, sessionPending = false))
    }

    @Test
    fun `a connect during a failing restore supersedes it`() {
        val attempt = guard.begin()

        // The compile failed and the failure path suspended on settings; the
        // user tapped Connect, whose ACTION_CONNECT the single-flight guard
        // rejected, leaving its session queued behind this attempt.
        guard.onUserIntent()
        guard.onStartQueued()

        assertTrue(guard.superseded(attempt, sessionPending = true))
        // The finishing attempt drains the queued start instead of stopping.
        assertTrue(guard.consumeQueuedStart())
        assertFalse(guard.consumeQueuedStart())
    }

    @Test
    fun `a connect supersedes an attempt even before its session appears`() {
        val attempt = guard.begin()

        // The intent reaches the service before ConnectionManager's pending
        // session is observable — the token alone must already invalidate it.
        guard.onUserIntent()

        assertTrue(guard.superseded(attempt, sessionPending = false))
    }

    @Test
    fun `a disconnect supersedes an attempt`() {
        val attempt = guard.begin()

        guard.onUserIntent()

        assertTrue(guard.superseded(attempt, sessionPending = false))
    }

    @Test
    fun `a session waiting for an engine supersedes an attempt`() {
        val attempt = guard.begin()

        assertTrue(guard.superseded(attempt, sessionPending = true))
    }

    @Test
    fun `an attempt started after the intent owns the service again`() {
        guard.onUserIntent()
        val attempt = guard.begin()

        assertFalse(guard.superseded(attempt, sessionPending = false))
    }

    @Test
    fun `a disconnect drops a queued start`() {
        guard.onStartQueued()

        guard.clearQueuedStart()

        assertFalse(guard.consumeQueuedStart())
    }
}
