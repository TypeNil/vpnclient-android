package dev.typenil.vpnclient.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Nodes of one subscription, under its display name. */
data class ServerGroup(
    val subscriptionId: Long,
    val subscriptionName: String,
    val nodes: List<NodeEntity>,
)

data class ServersUiState(
    val groups: List<ServerGroup> = emptyList(),
    val selectedNodeId: String? = null,
    val connected: Boolean = false,
)

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val settings: SettingsRepository,
    connectionManager: ConnectionManager,
    nodeDao: NodeDao,
    subscriptions: SubscriptionRepository,
) : ViewModel() {

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** One-shot snackbar messages. */
    val messages: SharedFlow<String> = _messages

    val uiState: StateFlow<ServersUiState> = combine(
        nodeDao.observeEnabled(),
        subscriptions.profiles,
        settings.selectedNodeId,
        connectionManager.state,
    ) { nodes, profiles, selectedId, state ->
        val names = profiles.associate { it.id to it.name }
        val groups = nodes
            .groupBy { it.subscriptionId }
            .map { (subId, groupNodes) ->
                ServerGroup(
                    subscriptionId = subId,
                    subscriptionName = names[subId] ?: "Subscription $subId",
                    nodes = groupNodes,
                )
            }
        ServersUiState(
            groups = groups,
            selectedNodeId = selectedId,
            connected = state is VpnConnectionState.Connected,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ServersUiState(),
    )

    fun select(nodeId: String) {
        viewModelScope.launch { settings.setSelectedNodeId(nodeId) }
        if (uiState.value.connected) {
            // MVP: selection persists but the running tunnel keeps its node
            // until the user reconnects.
            _messages.tryEmit("Reconnect to apply the new server")
        }
    }
}
