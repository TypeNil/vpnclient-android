package dev.typenil.vpnclient.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.engine.ConnectionInfo
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConnectionsUiState(
    /** Content is only meaningful while the tunnel is up. */
    val connected: Boolean = false,
    /** Live connections, newest first (engine-sorted). */
    val connections: List<ConnectionInfo> = emptyList(),
)

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    val uiState: StateFlow<ConnectionsUiState> = combine(
        connectionManager.state,
        connectionManager.activeConnections,
    ) { state, connections ->
        ConnectionsUiState(
            // Reconnecting still has a live tunnel pushing snapshots —
            // hiding them would flash the wrong empty state mid-handover.
            connected = state is VpnConnectionState.Connected ||
                state is VpnConnectionState.Reconnecting,
            connections = connections,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ConnectionsUiState(),
    )

    /** Fire-and-forget: the row disappears when the core reports the close
     *  through the next connections snapshot. */
    fun closeConnection(id: String) {
        viewModelScope.launch { connectionManager.closeConnection(id) }
    }
}
