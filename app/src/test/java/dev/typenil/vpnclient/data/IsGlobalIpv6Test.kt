package dev.typenil.vpnclient.data

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsGlobalIpv6Test {

    private fun v6(hex: String): Inet6Address = InetAddress.getByName(hex) as Inet6Address

    @Test
    fun `global unicast counts`() {
        assertTrue(isGlobalIpv6(v6("2001:4860:4860::8888")))
        assertTrue(isGlobalIpv6(v6("2a00:1fa1:8e14:c805:107b:3eff:fee2:a85d")))
    }

    @Test
    fun `non-global scopes do not count`() {
        assertFalse(isGlobalIpv6(v6("fe80::1")))               // link-local
        assertFalse(isGlobalIpv6(v6("fd56:4007:1055::1")))     // ULA
        assertFalse(isGlobalIpv6(v6("::1")))                   // loopback
        assertFalse(isGlobalIpv6(v6("::")))                    // unspecified
        assertFalse(isGlobalIpv6(v6("ff02::1")))               // multicast
    }
}
