package dev.typenil.vpnclient.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.engine.OutboundGroupInfo
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
    /** Outbound tag → last measured delay; feeds the latency badges. */
    val delays: Map<String, Int> = emptyMap(),
)

/**
 * The group a node tag should be selected in: the first selectable group
 * that actually contains it. Falls back to null when the engine hasn't
 * reported groups yet — selection stays persisted-only until reconnect.
 */
internal fun resolveSelectionTarget(
    groups: List<OutboundGroupInfo>,
    tag: String,
): String? = groups.firstOrNull { g -> g.selectable && g.items.any { it.tag == tag } }?.tag

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val connectionManager: ConnectionManager,
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
        connectionManager.groups,
    ) { nodes, profiles, selectedId, state, groups ->
        val names = profiles.associate { it.id to it.name }
        val serverGroups = nodes
            .groupBy { it.subscriptionId }
            .map { (subId, groupNodes) ->
                ServerGroup(
                    subscriptionId = subId,
                    subscriptionName = names[subId] ?: "Subscription $subId",
                    nodes = groupNodes,
                )
            }
        ServersUiState(
            groups = serverGroups,
            selectedNodeId = selectedId,
            connected = state is VpnConnectionState.Connected ||
                state is VpnConnectionState.Reconnecting,
            delays = groups
                .flatMap { it.items }
                .mapNotNull { item -> item.urlTestDelayMs?.let { item.tag to it } }
                .toMap(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ServersUiState(),
    )

    fun select(nodeId: String) {
        viewModelScope.launch {
            settings.setSelectedNodeId(nodeId)
            if (uiState.value.connected) {
                // Live-switch the running tunnel; if the engine hasn't
                // reported groups yet the persisted choice still wins on
                // the next connect.
                resolveSelectionTarget(connectionManager.groups.value, nodeId)
                    ?.let { connectionManager.selectOutbound(it, nodeId) }
                    ?: _messages.tryEmit("Reconnect to apply the new server")
            }
        }
    }

    /** Trigger a latency probe across all reported groups. */
    fun testLatency() {
        viewModelScope.launch {
            connectionManager.groups.value.forEach { connectionManager.urlTest(it.tag) }
        }
    }
}
