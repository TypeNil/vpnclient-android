package dev.typenil.vpnclient.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    /** The real connection state machine — rendered as-is, never invented. */
    val connection: VpnConnectionState = VpnConnectionState.Idle,
    val selectedNodeName: String? = null,
    val selectedNodeProtocol: String? = null,
    val selectedNodeServer: String? = null,
    val subscriptionName: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    nodeDao: NodeDao,
    settings: SettingsRepository,
    subscriptions: SubscriptionRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        connectionManager.state,
        settings.selectedNodeId,
        nodeDao.observeEnabled(),
        subscriptions.profiles,
    ) { connection, selectedId, nodes, profiles ->
        val selected = nodes.firstOrNull { it.id == selectedId }
        HomeUiState(
            connection = connection,
            selectedNodeName = selected?.name,
            selectedNodeProtocol = selected?.protocol,
            selectedNodeServer = selected?.let { "${it.server}:${it.port}" },
            subscriptionName = profiles.firstOrNull { it.enabled }?.name,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun connect() = connectionManager.connect()

    fun disconnect() = connectionManager.disconnect()
}
