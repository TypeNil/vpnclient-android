package dev.typenil.vpnclient.ui.home

import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.data.LATENCY_TTL_MS
import dev.typenil.vpnclient.data.LatencyMethod
import dev.typenil.vpnclient.data.NodeLatency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerPickerModelTest {
    private fun option(id: String, group: Long, name: String? = "P$group") =
        ServerOption(id = id, title = id, groupId = group, groupName = name)

    @Test
    fun `groups keep first-seen order and carry the provider name`() {
        val groups = groupPickerOptions(listOf(option("a1", 1), option("a2", 1), option("b1", 2, name = null)))
        assertEquals(listOf<Long?>(1, 2), groups.map { it.id })
        assertEquals(listOf("P1", null), groups.map { it.name })
        assertEquals(listOf("a1", "a2"), groups[0].options.map { it.id })
    }

    @Test
    fun `favourites go first inside their own subscription only`() {
        val nodes = listOf("a1" to 1L, "a2" to 1L, "b1" to 2L, "b2" to 2L)
        val ordered = favoritesFirstPerGroup(nodes, { it.second }) { it.first in setOf("a2", "b2") }
        assertEquals(listOf("a2", "a1", "b2", "b1"), ordered.map { it.first })
    }

    @Test
    fun `a fresh verdict becomes a reading with its method, an outdated one becomes no data`() {
        val now = 10_000_000L
        val fresh = NodeLatency(42, now - 1_000, LatencyMethod.Tcp).pickerReading(now)
        assertEquals(42, fresh?.ms)
        assertEquals(R.string.servers_latency_method_tcp, fresh?.methodRes)
        assertEquals(R.string.servers_latency_method_proxy, NodeLatency(7, now, LatencyMethod.Proxy).pickerReading(now)?.methodRes)
        assertNull(NodeLatency(42, now - LATENCY_TTL_MS - 1, LatencyMethod.Tcp).pickerReading(now))
    }

    @Test
    fun `a failed probe is still a reading with no milliseconds`() {
        val reading = NodeLatency(null, 5_000, LatencyMethod.Proxy).pickerReading(5_000)
        assertEquals(true, reading != null && reading.ms == null)
    }
}
