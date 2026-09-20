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
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** Live outbound switch in flight — keeps the row honest while the
     *  engine decides, and debounces double-taps. */
    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching

    fun select(nodeId: String) {
        viewModelScope.launch {
            settings.setSelectedNodeId(nodeId)
            // uiState.connected can lag a fresh connect — the manager's live
            // state is the source of truth for "is a tunnel actually up".
            if (connectionManager.state.value is VpnConnectionState.Connected) {
                if (_switching.value) return@launch
                _switching.value = true
                try {
                    val target = resolveSelectionTarget(
                        connectionManager.groups.value, nodeId,
                    )
                    val switched = target != null &&
                        connectionManager.selectOutbound(target, nodeId)
                    if (!switched) {
                        // Engine couldn't apply it live — the persisted pick
                        // still takes effect on the next connect.
                        _messages.tryEmit("Reconnect to apply the new server")
                    }
                } finally {
                    _switching.value = false
                }
            }
        }
    }

    /** Trigger a latency probe across all reported groups. Connected-only:
     *  during Reconnecting the tunnel is starved, so badges would just churn
     *  to timeouts and mask the last real measurement. */
    fun testLatency() {
        if (connectionManager.state.value !is VpnConnectionState.Connected) return
        if (_testing.value) return
        viewModelScope.launch {
            _testing.value = true
            try {
                connectionManager.groups.value.forEach { connectionManager.urlTest(it.tag) }
            } finally {
                _testing.value = false
            }
        }
    }

    private val _testing = MutableStateFlow(false)
    /** True while a latency probe is in flight — drives the button spinner. */
    val testing: StateFlow<Boolean> = _testing
}
