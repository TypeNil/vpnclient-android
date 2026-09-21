package dev.typenil.vpnclient.core.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stray-automatic-start guard in `ClientVpnService.onStartCommand`: a
 * system-issued start (sticky restart, always-on, duplicate restore) must be
 * ignored whenever this instance already owns a session lifecycle — otherwise
 * it would burn a restart-guard slot, clobber the Connected notification, or
 * race `startTunnel` into `cleanup()/stopSelf()` mid-rebuild.
 */
class SessionOwnershipGuardTest {

    @Test
    fun `idle instance accepts an automatic start`() {
        assertFalse(
            ClientVpnService.sessionOwned(
                activeGeneration = -1L,
                startActive = false,
                rebuildActive = false,
                stopRequested = false,
            ),
        )
    }

    @Test
    fun `connected session owns the instance`() {
        assertTrue(
            ClientVpnService.sessionOwned(
                activeGeneration = 3L,
                startActive = false,
                rebuildActive = false,
                stopRequested = false,
            ),
        )
    }

    @Test
    fun `rebuild window still owns the instance with engine null`() {
        // rebuildTunnel() clears the engine slot while keeping the
        // generation — the engine-null gap must not admit a stray start.
        assertTrue(
            ClientVpnService.sessionOwned(
                activeGeneration = 7L,
                startActive = false,
                rebuildActive = true,
                stopRequested = false,
            ),
        )
    }

    @Test
    fun `start in flight owns the instance before a generation exists`() {
        assertTrue(
            ClientVpnService.sessionOwned(
                activeGeneration = -1L,
                startActive = true,
                rebuildActive = false,
                stopRequested = false,
            ),
        )
    }

    @Test
    fun `teardown in progress blocks a stray resurrect`() {
        // stopTunnel() clears generation/engine first but keeps
        // stopRequested — an automatic start must not flip it back.
        assertTrue(
            ClientVpnService.sessionOwned(
                activeGeneration = -1L,
                startActive = false,
                rebuildActive = false,
                stopRequested = true,
            ),
        )
    }
}
