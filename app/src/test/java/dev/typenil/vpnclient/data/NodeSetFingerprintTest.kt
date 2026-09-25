package dev.typenil.vpnclient.data

import dev.typenil.vpnclient.data.db.NodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fingerprint is what tells a live session its compiled outbound set is
 * stale. It must change exactly when the tunnel-relevant content changes —
 * a false negative leaves a disabled subscription in use, a false positive
 * rebuilds the TUN (a visible Reconnecting flash) for nothing.
 */
class NodeSetFingerprintTest {
    private fun node(
        id: String,
        outbound: String = """{"type":"vless","server":"a.example.com"}""",
        name: String = id,
        position: Int = 0,
    ) = NodeEntity(
        id = id,
        subscriptionId = 1,
        name = name,
        protocol = "VLESS",
        server = "a.example.com",
        port = 443,
        outboundJson = outbound,
        rawUri = null,
        position = position,
    )

    @Test
    fun `no enabled nodes has no fingerprint`() {
        assertNull(nodeSetFingerprint(emptyList()))
    }

    @Test
    fun `the same set in a different order is the same fingerprint`() {
        val a = node("a")
        val b = node("b")

        assertEquals(
            nodeSetFingerprint(listOf(a, b)),
            nodeSetFingerprint(listOf(b, a)),
        )
    }

    @Test
    fun `a reorder or rename alone does not change the fingerprint`() {
        val original = listOf(node("a", position = 0), node("b", position = 1))
        val renamed =
            listOf(
                node("a", name = "Renamed A", position = 1),
                node("b", name = "Renamed B", position = 0),
            )

        assertEquals(nodeSetFingerprint(original), nodeSetFingerprint(renamed))
    }

    @Test
    fun `a changed outbound changes the fingerprint`() {
        val before = listOf(node("a", outbound = """{"type":"vless","server":"a"}"""))
        val after = listOf(node("a", outbound = """{"type":"vless","server":"b"}"""))

        assertNotEquals(nodeSetFingerprint(before), nodeSetFingerprint(after))
    }

    @Test
    fun `adding or removing a node changes the fingerprint`() {
        val one = listOf(node("a"))
        val two = listOf(node("a"), node("b"))

        assertNotEquals(nodeSetFingerprint(one), nodeSetFingerprint(two))
    }
}
