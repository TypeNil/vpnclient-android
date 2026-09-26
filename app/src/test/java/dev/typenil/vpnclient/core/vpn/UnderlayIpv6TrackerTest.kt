package dev.typenil.vpnclient.core.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transition table for the underlay's IPv6 posture. The regression this
 * locks down: a flip must recompile the routing (the ip_version:6 decision is
 * baked into the compiled config), while the first report under an epoch is
 * only a baseline — the start compile reads the live value anyway.
 */
class UnderlayIpv6TrackerTest {
    private val tracker = UnderlayIpv6Tracker()

    @Test
    fun `the first report is a baseline and never rebuilds`() {
        assertFalse(tracker.report(hasIpv6 = true))
    }

    @Test
    fun `the first report is a baseline even when v6 is absent`() {
        assertFalse(tracker.report(hasIpv6 = false))
    }

    @Test
    fun `losing v6 after a dual-stack baseline rebuilds`() {
        tracker.report(hasIpv6 = true)

        assertTrue(tracker.report(hasIpv6 = false))

        // The flip already fired — a re-emission of the same posture is a
        // caps callback, not a change.
        assertFalse(tracker.report(hasIpv6 = false))
    }

    @Test
    fun `gaining v6 after an ipv4-only baseline rebuilds`() {
        tracker.report(hasIpv6 = false)

        assertTrue(tracker.report(hasIpv6 = true))
    }

    @Test
    fun `a stable posture never rebuilds`() {
        tracker.report(hasIpv6 = true)

        assertFalse(tracker.report(hasIpv6 = true))
        assertFalse(tracker.report(hasIpv6 = true))
        assertFalse(tracker.report(hasIpv6 = true))
    }

    @Test
    fun `a reset starts a new epoch whose first report is a baseline`() {
        tracker.report(hasIpv6 = true)
        tracker.report(hasIpv6 = false)
        tracker.reset()

        assertFalse(tracker.report(hasIpv6 = false))
    }

    @Test
    fun `every flip in an alternating series rebuilds`() {
        tracker.report(hasIpv6 = true)

        assertTrue(tracker.report(hasIpv6 = false))
        assertTrue(tracker.report(hasIpv6 = true))
        assertTrue(tracker.report(hasIpv6 = false))
    }
}
