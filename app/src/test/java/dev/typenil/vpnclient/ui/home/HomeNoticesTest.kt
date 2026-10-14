package dev.typenil.vpnclient.ui.home

import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.core.vpn.VpnError
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeNoticesTest {
    private val node = NodeSummary("n1", "synthetic", ProtocolType.SOCKS, "127.0.0.1")
    private val connected = VpnConnectionState.Connected(node, Instant.EPOCH, null)

    @Test fun `nothing to say shows nothing`() {
        assertEquals(emptyList<HomeNotice>(), homeNotices(HomeUiState(), batteryHintVisible = false))
        assertEquals(emptyList<HomeNotice>(), homeNotices(HomeUiState(connection = connected), batteryHintVisible = false))
    }

    @Test fun `permission explanation only while permission is required`() {
        val ui = HomeUiState(connection = VpnConnectionState.PermissionRequired)
        assertEquals(listOf(HomeNotice.PermissionExplanation), homeNotices(ui, batteryHintVisible = false))
        assertEquals(emptyList<HomeNotice>(), homeNotices(HomeUiState(connection = VpnConnectionState.Idle), false))
    }

    @Test fun `battery hint needs both the flag and a connected tunnel`() {
        assertEquals(listOf(HomeNotice.BatteryHint), homeNotices(HomeUiState(connection = connected), true))
        assertEquals(emptyList<HomeNotice>(), homeNotices(HomeUiState(connection = VpnConnectionState.Idle), true))
    }

    @Test fun `restart guard shows regardless of connection`() {
        assertEquals(listOf(HomeNotice.RestartGuard), homeNotices(HomeUiState(restartGuardTripped = true), false))
    }

    @Test fun `failure outranks pick rejection and stays a single slot`() {
        val ui = HomeUiState(
            connection = VpnConnectionState.Error(VpnError.UnencryptedTransport, node),
            selectionError = VpnError.NoNodeSelected,
        )
        assertEquals(listOf(HomeNotice.Failure(VpnError.UnencryptedTransport)), homeNotices(ui, false))
    }

    @Test fun `simultaneous notices keep priority order`() {
        val ui = HomeUiState(connection = connected, selectionError = VpnError.UnencryptedTransport, restartGuardTripped = true)
        assertEquals(
            listOf(HomeNotice.SelectionRejected(VpnError.UnencryptedTransport), HomeNotice.RestartGuard, HomeNotice.BatteryHint),
            homeNotices(ui, true),
        )
        val failed = HomeUiState(connection = VpnConnectionState.Error(VpnError.NoNodeSelected, null), restartGuardTripped = true)
        assertEquals(listOf(HomeNotice.Failure(VpnError.NoNodeSelected), HomeNotice.RestartGuard), homeNotices(failed, true))
    }
}