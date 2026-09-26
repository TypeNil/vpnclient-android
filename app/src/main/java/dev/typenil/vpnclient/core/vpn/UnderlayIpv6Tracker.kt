package dev.typenil.vpnclient.core.vpn

/**
 * Tracks the underlay's IPv6 posture and flags a flip that must recompile the
 * routing: the compiled config bakes the ip_version:6 decision (direct v6 vs
 * v6-via-proxy) in, so a dual-stack → IPv4-only (or back) transition cannot
 * just re-read the value — the running config has to be rebuilt.
 *
 * Pure so the transition table is JVM-testable — the service only wires it to
 * the tunnel. The first report establishes the baseline and never triggers a
 * rebuild: there is nothing to compare against, and the start compile reads
 * the live value anyway.
 */
internal class UnderlayIpv6Tracker {
    /** Last reported posture; null = no baseline yet. */
    private var lastReported: Boolean? = null

    /**
     * @param hasIpv6 current underlay IPv6 posture.
     * @return true when this is not the first report and the value changed —
     *   the caller must request a config recompile.
     */
    fun report(hasIpv6: Boolean): Boolean {
        val previous = lastReported
        lastReported = hasIpv6
        return previous != null && previous != hasIpv6
    }

    /** Starts a new epoch (callback re-registered): the next report is a
     *  first report again and must not trigger a rebuild. */
    fun reset() {
        lastReported = null
    }
}
