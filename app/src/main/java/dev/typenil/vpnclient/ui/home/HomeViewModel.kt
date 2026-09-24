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
import kotlinx.coroutines.launch

data class HomeUiState(
    /** The real connection state machine — rendered as-is, never invented. */
    val connection: VpnConnectionState = VpnConnectionState.Idle,
    val selectedNodeName: String? = null,
    val selectedNodeProtocol: String? = null,
    val selectedNodeServer: String? = null,
    val subscriptionName: String? = null,
    /** Restart guard disabled auto-start — persistent warning, survives a
     *  denied notification permission (the alert notification may never
     *  have been seen). */
    val restartGuardTripped: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    nodeDao: NodeDao,
    private val settings: SettingsRepository,
    subscriptions: SubscriptionRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        connectionManager.state,
        settings.selectedNodeId,
        nodeDao.observeEnabled(),
        subscriptions.profiles,
        settings.restartGuardTripped,
    ) { connection, selectedId, nodes, profiles, guardTripped ->
        val selected = nodes.firstOrNull { it.id == selectedId }
        HomeUiState(
            connection = connection,
            selectedNodeName = selected?.name,
            selectedNodeProtocol = selected?.protocol,
            selectedNodeServer = selected?.let { "${it.server}:${it.port}" },
            subscriptionName = profiles.firstOrNull { it.enabled }?.name,
            restartGuardTripped = guardTripped,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        // Seed with the live state — Idle would flash "Disconnected" for a
        // frame on a connected session before combine's first emission.
        initialValue = HomeUiState(connection = connectionManager.state.value),
    )

    fun connect() = connectionManager.connect()

    fun disconnect() = connectionManager.disconnect()

    /** Explicit dismiss — connecting again also clears it via the reset. */
    fun dismissRestartGuardWarning() {
        viewModelScope.launch {
            runCatching { settings.setRestartGuardTripped(false) }
        }
    }
}
