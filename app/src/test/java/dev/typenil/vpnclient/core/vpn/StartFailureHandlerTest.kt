package dev.typenil.vpnclient.core.vpn

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The windows a failing start attempt has to survive: the user can state a new
 * intent inside the settings read and inside the settings write, and each one
 * demands different handling. A queued connect must keep the service alive and
 * its desire flag set; a disconnect must not have the flag resurrected, nor get
 * an error published over it.
 */
class StartFailureHandlerTest {
    private val guard = StartAttemptGuard()

    /** Effects observed by the tests. */
    private val desireWrites = mutableListOf<Boolean>()
    private val reported = mutableListOf<VpnError>()
    private var converged = false
    private var stopping = false
    private var desire = true

    /** Runs inside the settings read / write, like a user acting mid-suspend. */
    private var duringRead: () -> Unit = {}
    private var duringWrite: () -> Unit = {}

    private val handler =
        StartFailureHandler(
            guard = guard,
            sessionPending = { false },
            readDesire = {
                duringRead()
                desire
            },
            writeDesire = { wanted ->
                duringWrite()
                desireWrites += wanted
            },
            stopping = { stopping },
            report = { error -> reported += error },
            converge = { converged = true },
        )

    private fun connectLands() {
        guard.onUserIntent(StartIntent.Connect)
        // Its ACTION_CONNECT is rejected by the single-flight guard, so its
        // session waits behind the attempt that is finishing.
        guard.onStartQueued()
    }

    @Test
    fun `an uninterrupted failure reports, clears the desire and stops`() =
        runTest {
            val attempt = guard.begin()

            handler.finish(VpnError.NoNodeSelected, attempt)

            assertEquals(listOf(false), desireWrites)
            assertEquals(listOf(VpnError.NoNodeSelected), reported)
            assertTrue(converged)
        }

    @Test
    fun `a stop under way converges without publishing`() =
        runTest {
            val attempt = guard.begin()
            stopping = true

            handler.finish(VpnError.NoNodeSelected, attempt)

            assertTrue(desireWrites.isEmpty())
            assertTrue(reported.isEmpty())
            assertTrue(converged)
        }

    @Test
    fun `a connect inside the settings read keeps the service alive`() =
        runTest {
            val attempt = guard.begin()
            // The read returns the pre-connect value: acting on it would stop the
            // service the queued connect needs.
            duringRead = { connectLands() }
            desire = false

            handler.finish(VpnError.NoNodeSelected, attempt)

            assertTrue(desireWrites.isEmpty())
            assertTrue(reported.isEmpty())
            assertFalse(converged)
        }

    @Test
    fun `a disconnect inside the settings read converges without publishing`() =
        runTest {
            val attempt = guard.begin()
            duringRead = { guard.onUserIntent(StartIntent.Disconnect) }
            desire = false

            handler.finish(VpnError.NoNodeSelected, attempt)

            assertTrue(desireWrites.isEmpty())
            assertTrue(reported.isEmpty())
            // The disconnect flow owns teardown — this attempt must not report a
            // stale error over it.
            assertFalse(converged)
        }

    @Test
    fun `a connect inside the settings write gets its desire back`() =
        runTest {
            val attempt = guard.begin()
            // The stale clear lands after the connect set the flag: without the
            // compensation a process death would stop restoring the VPN.
            duringWrite = { connectLands() }

            handler.finish(VpnError.NoNodeSelected, attempt)

            assertEquals(listOf(false, true), desireWrites)
            assertTrue(reported.isEmpty())
            assertFalse(converged)
        }

    @Test
    fun `a disconnect inside the settings write is not resurrected`() =
        runTest {
            val attempt = guard.begin()
            duringWrite = { guard.onUserIntent(StartIntent.Disconnect) }

            handler.finish(VpnError.NoNodeSelected, attempt)

            // false is exactly what the disconnect asked for — no compensation.
            assertEquals(listOf(false), desireWrites)
            assertTrue(reported.isEmpty())
            assertFalse(converged)
        }

    @Test
    fun `a disconnect after a queued connect inside the write is not resurrected`() =
        runTest {
            val attempt = guard.begin()
            // A connect was queued behind this attempt, then the user tapped
            // Disconnect while the stale desire-flag write was suspended. The
            // teardown that would clear the queue hasn't run yet — the newest
            // intent alone must outrank it, or the failure path restores a
            // flag the user just turned off.
            duringWrite = {
                connectLands()
                guard.onUserIntent(StartIntent.Disconnect)
            }

            handler.finish(VpnError.NoNodeSelected, attempt)

            assertEquals(listOf(false), desireWrites)
            assertTrue(reported.isEmpty())
            assertFalse(converged)
            // The queued start died with the disconnect — no retry from it.
            assertFalse(guard.consumeQueuedStart())
        }

    @Test
    fun `a connect that arrived before the read leaves everything alone`() =
        runTest {
            val attempt = guard.begin()
            connectLands()

            handler.finish(VpnError.NoNodeSelected, attempt)

            assertTrue(desireWrites.isEmpty())
            assertTrue(reported.isEmpty())
            assertFalse(converged)
        }

    @Test
    fun `a session waiting for an engine belongs to the connect`() =
        runTest {
            val attempt = guard.begin()
            val pendingHandler =
                StartFailureHandler(
                    guard = guard,
                    sessionPending = { true },
                    readDesire = { true },
                    writeDesire = { desireWrites += it },
                    stopping = { false },
                    report = { reported += it },
                    converge = { converged = true },
                )

            pendingHandler.finish(VpnError.NoNodeSelected, attempt)

            assertTrue(desireWrites.isEmpty())
            assertTrue(reported.isEmpty())
            assertFalse(converged)
        }
}
