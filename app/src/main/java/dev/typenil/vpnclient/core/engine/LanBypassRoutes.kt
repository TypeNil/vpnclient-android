package dev.typenil.vpnclient.core.engine

import java.math.BigInteger
import java.net.InetAddress

/**
 * The single source of truth for LAN bypass routes — shared by the engine
 * config compiler (`tun.route_exclude_address`) and the Android-side
 * `openTun` fallback that computes inclusive prefixes on API 26–32 where
 * `VpnService.Builder.excludeRoute()` doesn't exist.
 *
 * Excluded set (per family):
 * - IPv4: RFC1918 private (10/8, 172.16/12, 192.168/16), link-local
 *   169.254/16, loopback 127/8, multicast 224/4 — multicast must bypass
 *   the tunnel or mDNS/SSDP discovery (Chromecast, printers) dies.
 * - IPv6: ULA fc00::/7, link-local fe80::/10, loopback ::1/128,
 *   multicast ff00::/8.
 *
 * The TUN's own addresses sit INSIDE these ranges — 172.18.0.1/30 inside
 * 172.16/12, fdfe:dcba:9877::1/126 inside fc00::/7. Both paths therefore
 * add explicit more-specific routes for the TUN space so virtual-DNS
 * hijacking keeps working while the surrounding private range bypasses.
 */
object LanBypassRoutes {
    /** Prefixes that must bypass the tunnel — IPv4. */
    val excludedV4: List<CidrAddress> =
        listOf(
            CidrAddress("10.0.0.0", 8),
            CidrAddress("172.16.0.0", 12),
            CidrAddress("192.168.0.0", 16),
            CidrAddress("169.254.0.0", 16),
            CidrAddress("127.0.0.0", 8),
            CidrAddress("224.0.0.0", 4),
        )

    /** Prefixes that must bypass the tunnel — IPv6. */
    val excludedV6: List<CidrAddress> =
        listOf(
            CidrAddress("fc00::", 7),
            CidrAddress("fe80::", 10),
            CidrAddress("::1", 128),
            CidrAddress("ff00::", 8),
        )

    /**
     * Compute the inclusive prefix list equivalent to "everything in
     * [base] except [excluded]" — for `addRoute()` on API <33 which can't
     * express exclusions. Uses the standard prefix-difference algorithm:
     * an excluded range that partially overlaps [base] splits it, and the
     * fragments recurse until nothing overlaps.
     */
    fun subtract(
        base: CidrAddress,
        excluded: List<CidrAddress>,
    ): List<CidrAddress> {
        val width = addressWidth(base.address)
        require(width > 0) { "unsupported address family: ${base.address}" }
        var result = listOf(normalize(base, width))
        for (ex in excluded) {
            val exRange = runCatching { normalize(ex, width) }.getOrNull() ?: continue
            result =
                result.flatMap { range ->
                    subtractOne(range, exRange, width)
                }
            if (result.isEmpty()) break
        }
        return result.sortedBy { it.first }.map { (start, len) -> cidrFromStart(start, len, width) }
    }

    // (start-address, prefix-length) — covers start .. start+2^(width-len)-1.
    // Pair<BigInteger, Int> directly: nested typealiases need language 2.3+.

    // ---- prefix math ----

    /** (start-address, prefix-length) — covers start .. start+2^(width-len)-1. */

    private fun addressWidth(address: String): Int =
        when {
            address.contains('.') -> {
                32
            }

            address.contains(':') -> {
                128
            }

            else -> {
                -1
            }
        }

    private fun toBigInteger(address: String): BigInteger = BigInteger(1, InetAddress.getByName(address).address)

    /** Normalize host bits (10.0.0.1/8 ≡ 10.0.0.0/8) into a canonical range. */
    private fun normalize(
        cidr: CidrAddress,
        width: Int,
    ): Pair<BigInteger, Int> {
        require(cidr.prefix in 0..width) { "bad prefix ${cidr.prefix} for ${cidr.address}" }
        val hostBits = width - cidr.prefix
        val value = toBigInteger(cidr.address)
        return (if (hostBits == 0) value else value.shiftRight(hostBits).shiftLeft(hostBits)) to
            cidr.prefix
    }

    private fun end(
        start: BigInteger,
        len: Int,
        width: Int,
    ): BigInteger = start + BigInteger.ONE.shiftLeft(width - len) - BigInteger.ONE

    /**
     * Remove [ex] from [range]. Disjoint → keep; covered → drop; overlap →
     * split [range] in half at its midpoint and recurse into each half
     * (an excluded prefix narrower than the range can only overlap one
     * half of a bisection at a time, so recursion always terminates).
     */
    private fun subtractOne(
        range: Pair<BigInteger, Int>,
        ex: Pair<BigInteger, Int>,
        width: Int,
    ): List<Pair<BigInteger, Int>> {
        val (rStart, rLen) = range
        val (eStart, eLen) = ex
        val rEnd = end(rStart, rLen, width)
        val eEnd = end(eStart, eLen, width)
        if (eEnd < rStart || eStart > rEnd) return listOf(range)
        if (eStart <= rStart && eEnd >= rEnd) return emptyList()
        // Partial overlap — split at the midpoint; both halves are aligned.
        val halfLen = rLen + 1
        val low = rStart to halfLen
        val high = rStart + BigInteger.ONE.shiftLeft(width - halfLen) to halfLen
        return subtractOne(low, ex, width) + subtractOne(high, ex, width)
    }

    private fun cidrFromStart(
        start: BigInteger,
        prefixLen: Int,
        width: Int,
    ): CidrAddress {
        val bytes =
            start.toByteArray().let { raw ->
                val want = width / 8
                when {
                    raw.size == want -> raw
                    raw.size > want -> raw.copyOfRange(raw.size - want, raw.size)
                    else -> ByteArray(want - raw.size) + raw
                }
            }
        val host = InetAddress.getByAddress(bytes).hostAddress
        return CidrAddress(host.substringBefore('%'), prefixLen)
    }
}
