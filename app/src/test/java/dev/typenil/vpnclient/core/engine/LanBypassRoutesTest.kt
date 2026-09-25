package dev.typenil.vpnclient.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanBypassRoutesTest {
    @Test
    fun `subtract with no exclusions returns the base prefix`() {
        val result = LanBypassRoutes.subtract(CidrAddress("0.0.0.0", 0), emptyList())
        assertEquals(listOf(CidrAddress("0.0.0.0", 0)), result)
    }

    @Test
    fun `subtract a contained prefix splits the space`() {
        // 0/0 minus 10/8 → a small set of prefixes covering everything else.
        val result =
            LanBypassRoutes.subtract(
                CidrAddress("0.0.0.0", 0),
                listOf(CidrAddress("10.0.0.0", 8)),
            )
        // No result overlaps 10/8, and coverage is complete: the union of
        // results plus 10/8 equals the whole space.
        assertTrue(result.isNotEmpty())
        result.forEach { cidr ->
            assertFalse(overlaps(cidr, CidrAddress("10.0.0.0", 8)))
        }
        assertEquals(
            BigInt.MAX_IPV4,
            covered(result) + CidrAddress("10.0.0.0", 8).size(),
        )
    }

    @Test
    fun `all LAN exclusions together cover only the excluded set`() {
        val result =
            LanBypassRoutes.subtract(
                CidrAddress("0.0.0.0", 0),
                LanBypassRoutes.excludedV4,
            )
        LanBypassRoutes.excludedV4.forEach { ex ->
            result.forEach { cidr ->
                assertFalse("overlap: $cidr vs $ex", overlaps(cidr, ex))
            }
        }
        // Completeness: result ∪ excluded = 2^32.
        val excludedTotal = LanBypassRoutes.excludedV4.sumOf { it.size() }
        assertEquals(BigInt.MAX_IPV4, covered(result) + excludedTotal)
        // The TUN keep-alive prefix lives inside an excluded range — the
        // exclusion math itself never yields it; openTun adds it on top.
        val tun = CidrAddress("172.18.0.0", 30)
        result.forEach { cidr ->
            assertFalse("tun space leaked into include: $cidr", overlaps(cidr, tun))
        }
    }

    @Test
    fun `subtract works for ipv6`() {
        val result =
            LanBypassRoutes.subtract(
                CidrAddress("::", 0),
                LanBypassRoutes.excludedV6,
            )
        LanBypassRoutes.excludedV6.forEach { ex ->
            result.forEach { cidr ->
                assertFalse("overlap: $cidr vs $ex", overlaps(cidr, ex))
            }
        }
        val excludedTotal =
            LanBypassRoutes.excludedV6
                // ::1/128 is contained in no other exclusion; ff00/8, fc00/7,
                // fe80/10 are disjoint.
                .sumOf { it.size() }
        assertEquals(BigInt.MAX_IPV6, covered(result) + excludedTotal)
    }

    @Test
    fun `subtracting a superset yields nothing`() {
        val result =
            LanBypassRoutes.subtract(
                CidrAddress("10.0.0.0", 8),
                listOf(CidrAddress("10.0.0.0", 8)),
            )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `disjoint exclusion leaves the base intact`() {
        val result =
            LanBypassRoutes.subtract(
                CidrAddress("8.0.0.0", 8),
                listOf(CidrAddress("10.0.0.0", 8)),
            )
        assertEquals(listOf(CidrAddress("8.0.0.0", 8)), result)
    }

    // ---- helpers ----

    private object BigInt {
        val MAX_IPV4 =
            java.math.BigInteger.ONE
                .shiftLeft(32)
        val MAX_IPV6 =
            java.math.BigInteger.ONE
                .shiftLeft(128)
    }

    private fun rangeOf(cidr: CidrAddress): Pair<java.math.BigInteger, java.math.BigInteger> {
        val addr =
            java.net.InetAddress
                .getByName(cidr.address)
                .address
        val width = addr.size * 8
        val start = java.math.BigInteger(1, addr)
        val size =
            java.math.BigInteger.ONE
                .shiftLeft(width - cidr.prefix)
        return start to start + size - java.math.BigInteger.ONE
    }

    private fun overlaps(
        a: CidrAddress,
        b: CidrAddress,
    ): Boolean {
        val (as_, ae) = rangeOf(a)
        val (bs, be) = rangeOf(b)
        return as_ <= be && bs <= ae
    }

    private fun covered(list: List<CidrAddress>): java.math.BigInteger = list.sumOf { it.size() }

    private fun CidrAddress.size(): java.math.BigInteger {
        val addr =
            java.net.InetAddress
                .getByName(address)
                .address
        return java.math.BigInteger.ONE
            .shiftLeft(addr.size * 8 - prefix)
    }
}
