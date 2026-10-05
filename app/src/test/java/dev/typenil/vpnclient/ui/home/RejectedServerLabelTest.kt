package dev.typenil.vpnclient.ui.home

import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.core.vpn.VpnError
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RejectedServerLabelTest {
    private val local = NodeSummary("local", "synthetic local", ProtocolType.SOCKS, "127.0.0.1")
    private fun ui(node: NodeSummary) = HomeUiState(
        connection = VpnConnectionState.Connected(node, Instant.EPOCH, null),
        selectedNodeName = "synthetic blocked", selectedNodeProtocol = "HTTP",
        selectionError = VpnError.UnencryptedTransport,
    )
    @Test fun `rejected live choice shows actual server not desired plaintext`() {
        val label = ui(local).withAppliedServerOnRejection()
        assertEquals(local.name, label.selectedNodeName)
        assertEquals(local.protocol.label, label.selectedNodeProtocol)
        assertFalse(label.autoSelected)
    }
    @Test fun `actual Auto mode survives rejected manual choice`() {
        assertTrue(ui(local.copy(id = "auto")).withAppliedServerOnRejection().autoSelected)
    }
    @Test fun `without a live rejection desired label is retained`() {
        val desired = ui(local).copy(selectionError = null)
        assertEquals(desired, desired.withAppliedServerOnRejection())
        val idle = ui(local).copy(connection = VpnConnectionState.Idle)
        assertEquals(idle, idle.withAppliedServerOnRejection())
    }
}
