package dev.typenil.vpnclient.core.engine.singbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderlyingNetworkPolicyTest {

    @Test
    fun `unknown capabilities are never accepted as physical underlay`() {
        assertFalse(isUsableUnderlyingNetwork(capabilitiesKnown = false, hasInternet = false, isVpn = false))
    }

    @Test
    fun `vpn and non-internet networks are rejected`() {
        assertFalse(isUsableUnderlyingNetwork(capabilitiesKnown = true, hasInternet = true, isVpn = true))
        assertFalse(isUsableUnderlyingNetwork(capabilitiesKnown = true, hasInternet = false, isVpn = false))
    }

    @Test
    fun `known internet network without vpn transport is accepted`() {
        assertTrue(isUsableUnderlyingNetwork(capabilitiesKnown = true, hasInternet = true, isVpn = false))
    }
}
