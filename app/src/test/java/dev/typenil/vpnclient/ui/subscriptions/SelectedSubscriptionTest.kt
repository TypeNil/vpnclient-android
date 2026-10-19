package dev.typenil.vpnclient.ui.subscriptions

import dev.typenil.vpnclient.data.db.NodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectedSubscriptionTest {
    private fun node(id: String, sub: Long) =
        NodeEntity(id, sub, "n-$id", "vless", "example.invalid", 443, "{}", null, 0)

    private val nodes = listOf(node("a", 1), node("b", 2))

    @Test fun `selected node maps to its subscription`() {
        assertEquals(2L, selectedSubscriptionId("b", nodes))
    }

    @Test fun `no selection, unknown id or empty list is no subscription`() {
        assertNull(selectedSubscriptionId(null, nodes))
        assertNull(selectedSubscriptionId("zzz", nodes))
        assertNull(selectedSubscriptionId("a", emptyList()))
    }
}
