package dev.typenil.vpnclient.ui.servers

import dev.typenil.vpnclient.core.engine.OutboundGroupInfo
import dev.typenil.vpnclient.core.engine.OutboundItemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolveSelectionTargetTest {

    private fun item(tag: String) = OutboundItemInfo(tag, "vless", null)

    private fun group(
        tag: String,
        selectable: Boolean = true,
        items: List<OutboundItemInfo>,
    ) = OutboundGroupInfo(tag, "selector", selectable, null, items)

    @Test
    fun `picks the selectable group containing the tag`() {
        val groups = listOf(
            group("auto", selectable = false, items = listOf(item("n1"))),
            group("proxy", items = listOf(item("n1"), item("n2"))),
        )
        assertEquals("proxy", resolveSelectionTarget(groups, "n1"))
    }

    @Test
    fun `non-selectable groups are skipped`() {
        val groups = listOf(
            group("auto", selectable = false, items = listOf(item("n1"))),
        )
        assertNull(resolveSelectionTarget(groups, "n1"))
    }

    @Test
    fun `unknown tag resolves to null`() {
        val groups = listOf(group("proxy", items = listOf(item("n1"))))
        assertNull(resolveSelectionTarget(groups, "missing"))
    }

    @Test
    fun `empty groups resolve to null`() {
        assertNull(resolveSelectionTarget(emptyList(), "n1"))
    }
}
