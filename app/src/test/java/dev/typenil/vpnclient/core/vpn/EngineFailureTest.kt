package dev.typenil.vpnclient.core.vpn

import dev.typenil.vpnclient.core.engine.EngineError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A compile/start failure must surface as its own cause. Reporting it as
 * "no server selected" would tell the user to pick a server when the real
 * problem is the config the engine refused.
 */
class EngineFailureTest {
    @Test
    fun `an engine error keeps its typed kind`() {
        assertEquals(
            VpnError.ConfigInvalid("bad outbound"),
            engineFailure(EngineError.InvalidConfig("bad outbound"), "fallback"),
        )
        assertEquals(
            VpnError.TunnelFailed("tun failed"),
            engineFailure(EngineError.TunnelFailed("tun failed"), "fallback"),
        )
    }

    @Test
    fun `a non-engine failure reports its own message`() {
        // e.g. RuleSetStore's "missing rule set files" — not an EngineError.
        assertEquals(
            VpnError.Unexpected("missing rule set files"),
            engineFailure(IllegalArgumentException("missing rule set files"), "fallback"),
        )
    }

    @Test
    fun `a messageless failure falls back to the caller's wording`() {
        assertEquals(
            VpnError.Unexpected("node set change could not be applied"),
            engineFailure(RuntimeException(), "node set change could not be applied"),
        )
    }

    @Test
    fun `a compile failure never reads as no-server-selected`() {
        val error = engineFailure(IllegalStateException("boom"), "fallback")

        assertEquals(false, error is VpnError.NoNodeSelected)
    }
}
