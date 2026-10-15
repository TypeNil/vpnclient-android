package dev.typenil.vpnclient.core.vpn

import dev.typenil.vpnclient.core.engine.TrafficStats
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class LastSessionTrackerTest {
    private var mono = 0L
    private var wall = 1_700_000_000_000L
    private val tracker = LastSessionTracker({ mono }, { wall })

    private val node = NodeSummary("n1", "Demo Node", ProtocolType.VLESS, "demo.invalid")
    private val idle = VpnConnectionState.Idle
    private val stopping = VpnConnectionState.Stopping

    private fun stats(down: Long, up: Long) = TrafficStats(0, 0, up, down, 0, 0, 0, 0)

    private fun connected(down: Long? = null, up: Long = 0) = VpnConnectionState.Connected(
        node = node,
        since = Instant.EPOCH,
        stats = down?.let { stats(it, up) },
    )

    private fun reconnecting() = VpnConnectionState.Reconnecting(
        node, VpnConnectionState.Reconnecting.Reason.NetworkUnavailable, 1,
    )

    /** Connects, then ends the session [ms] later from a Connected carrying [down]/[up]. */
    private fun session(ms: Long, down: Long?, up: Long = 0): LastSession? {
        tracker.onTransition(VpnConnectionState.Connecting(node), connected(), 1)
        mono += ms
        return tracker.onTransition(connected(down, up), stopping, 1)
    }

    @Test
    fun `4999 ms is too short, 5000 ms is recorded`() {
        assertNull(session(4_999, down = 10))
        val rec = session(5_000, down = 10)
        assertEquals(5_000L, rec?.durationMs)
    }

    @Test
    fun `no stats snapshot means no record`() {
        assertNull(session(60_000, down = null))
    }

    @Test
    fun `record carries totals, node name and wall clock end`() {
        wall = 1_700_000_123_000L
        val rec = session(10_000, down = 2_048, up = 512)
        assertEquals(LastSession(1_700_000_123_000L, 10_000L, 2_048L, 512L, "Demo Node"), rec)
    }

    @Test
    fun `reconnecting does not end or restart the session`() {
        tracker.onTransition(VpnConnectionState.Connecting(node), connected(), 1)
        mono += 4_000
        assertNull(tracker.onTransition(connected(100, 10), reconnecting(), 1))
        mono += 3_000
        assertNull(tracker.onTransition(reconnecting(), connected(), 1))
        mono += 3_000
        val rec = tracker.onTransition(connected(), stopping, 1)
        assertEquals(10_000L, rec?.durationMs)
        assertEquals(100L, rec?.downBytes)
    }

    @Test
    fun `reconnecting straight to error still ends the session`() {
        tracker.onTransition(VpnConnectionState.Connecting(node), connected(), 1)
        mono += 6_000
        tracker.onTransition(connected(5, 1), reconnecting(), 1)
        val rec = tracker.onTransition(reconnecting(), VpnConnectionState.Error(VpnError.NoNodeSelected, node), 1)
        assertNotNull(rec)
    }

    @Test
    fun `stopping then idle writes once`() {
        tracker.onTransition(VpnConnectionState.Connecting(node), connected(), 1)
        mono += 6_000
        assertNotNull(tracker.onTransition(connected(5, 1), stopping, 1))
        assertNull(tracker.onTransition(stopping, idle, 1))
    }

    @Test
    fun `same engine epoch keeps the latest cumulative totals`() {
        tracker.onTransition(VpnConnectionState.Connecting(node), connected(), 1)
        mono += 1_000
        tracker.onTransition(connected(100, 10), reconnecting(), 1)
        tracker.onTransition(reconnecting(), connected(), 1)
        mono += 5_000
        val rec = tracker.onTransition(connected(300, 30), stopping, 1)
        assertEquals(300L, rec?.downBytes)
        assertEquals(30L, rec?.upBytes)
    }

    @Test
    fun `counters restarted by a new engine are summed across epochs`() {
        tracker.onTransition(VpnConnectionState.Connecting(node), connected(), 1)
        mono += 1_000
        tracker.onTransition(connected(100, 10), reconnecting(), 1)
        tracker.onTransition(reconnecting(), connected(), 2)
        mono += 5_000
        val rec = tracker.onTransition(connected(40, 4), stopping, 2)
        assertEquals(140L, rec?.downBytes)
        assertEquals(14L, rec?.upBytes)
    }

    @Test
    fun `a new session starts from zero and the next record overwrites`() {
        val first = session(10_000, down = 900, up = 90)
        tracker.onTransition(stopping, idle, 1)
        val second = session(7_000, down = 5, up = 1)
        assertEquals(900L, first?.downBytes)
        assertEquals(5L, second?.downBytes)
        assertEquals(7_000L, second?.durationMs)
    }
}
