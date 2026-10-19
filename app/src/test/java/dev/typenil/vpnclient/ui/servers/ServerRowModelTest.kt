package dev.typenil.vpnclient.ui.servers

import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.data.LATENCY_TTL_MS
import dev.typenil.vpnclient.data.LatencyMethod
import dev.typenil.vpnclient.data.NodeLatency
import dev.typenil.vpnclient.data.db.NodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRowModelTest {
    private val now = 10_000_000L

    @Test
    fun `favourites go first inside their own subscription only`() {
        val nodes = listOf("a1" to 1L, "a2" to 1L, "b1" to 2L, "b2" to 2L)
        val ordered = favoritesFirstPerGroup(nodes, { it.second }) { it.first in setOf("a2", "b2") }
        assertEquals(listOf("a2", "a1", "b2", "b1"), ordered.map { it.first })
    }

    @Test
    fun `empty list reason picks its own state`() {
        assertEquals(ServersEmpty.None, serversEmpty(hasGroups = true, noServersAtAll = false, hasSubscriptions = true))
        assertEquals(ServersEmpty.NoMatch, serversEmpty(hasGroups = false, noServersAtAll = false, hasSubscriptions = true))
        assertEquals(ServersEmpty.NoServers, serversEmpty(hasGroups = false, noServersAtAll = true, hasSubscriptions = true))
        assertEquals(ServersEmpty.NoSubscriptions, serversEmpty(hasGroups = false, noServersAtAll = true, hasSubscriptions = false))
    }

    @Test
    fun `filter count adds subscription, protocol and show hidden, and is zero by default`() {
        assertEquals(0, activeFilterCount(null, null, false))
        assertEquals(1, activeFilterCount(7L, null, false))
        assertEquals(1, activeFilterCount(null, "VLESS", false))
        assertEquals(1, activeFilterCount(null, null, true))
        assertEquals(3, activeFilterCount(7L, "VLESS", true))
    }

    @Test
    fun `a live delay wins and names the live method`() {
        val stored = NodeLatency(99, now, LatencyMethod.Tcp)
        val disconnected = nodeLatencyView(42, tested = true, stored = stored, connected = false, nowMs = now)
        assertEquals(42, disconnected.reading?.ms)
        assertEquals(R.string.servers_latency_method_tcp, disconnected.reading?.methodRes)
        assertEquals(R.string.servers_latency_method_proxy, nodeLatencyView(42, false, null, connected = true, nowMs = now).reading?.methodRes)
        assertNull(disconnected.history)
    }

    @Test
    fun `a tested node without a delay is a timeout, an untested one is no data`() {
        assertNull(nodeLatencyView(null, tested = true, stored = null, connected = false, nowMs = now).reading?.ms)
        assertEquals(true, nodeLatencyView(null, tested = true, stored = null, connected = false, nowMs = now).reading != null)
        assertNull(nodeLatencyView(null, tested = false, stored = null, connected = false, nowMs = now).reading)
    }

    @Test
    fun `a fresh stored verdict fills the chip with its own method, an outdated one keeps its value but is flagged stale`() {
        val fresh = nodeLatencyView(null, false, NodeLatency(7, now - 1_000, LatencyMethod.Proxy), connected = false, nowMs = now)
        assertEquals(7, fresh.reading?.ms)
        assertEquals(R.string.servers_latency_method_proxy, fresh.reading?.methodRes)
        assertFalse(fresh.stale)
        val old = nodeLatencyView(null, false, NodeLatency(7, now - LATENCY_TTL_MS - 1, LatencyMethod.Tcp), connected = false, nowMs = now)
        assertEquals(7, old.reading?.ms)
        assertEquals(true, old.reading?.stale)
        assertEquals(R.string.servers_latency_method_tcp, old.reading?.methodRes)
        assertTrue(old.stale)
        assertEquals(7, old.history?.latencyMs)
        assertEquals(false, fresh.reading?.stale)
        val oldTimeout = nodeLatencyView(null, false, NodeLatency(null, now - LATENCY_TTL_MS - 1, LatencyMethod.Tcp), connected = false, nowMs = now)
        assertNull(oldTimeout.reading?.ms)
        assertEquals(true, oldTimeout.reading?.stale)
    }

    private fun serverNode(enabled: Boolean = true, hidden: Boolean = false) =
        ServerNode(
            entity = NodeEntity(
                id = "n1", subscriptionId = 1L, name = "Node", protocol = "VLESS", server = "127.0.0.1",
                port = 1, outboundJson = "{}", rawUri = null, position = 0,
            ),
            favorite = false, enabled = enabled, hidden = hidden,
        )

    @Test
    fun `only an enabled visible node can be selected`() {
        assertTrue(serverNode().canSelect)
        assertFalse(serverNode(enabled = false).canSelect)
        assertFalse(serverNode(hidden = true).canSelect)
    }
}
