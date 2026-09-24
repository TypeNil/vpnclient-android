package dev.typenil.vpnclient.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.common.LatencyProbe
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    /** Outbound tag → last measured delay; feeds the latency badges. */
    val delays: Map<String, Int> = emptyMap(),
    /** Tags a latency run has covered — distinguishes "timeout" from
     *  "never tested" on the badge. */
    val testedNodeIds: Set<String> = emptySet(),
    /** Connected → badges come from the engine's urltest (through the
     *  proxy); disconnected → direct TCP-connect probe. Names the
     *  measurement so the UI doesn't imply one means the other. */
    val connected: Boolean = false,
)

/** Engine-reported surface: connection state + per-outbound delays. */
private data class EngineSurface(
    val connected: Boolean,
    val delays: Map<String, Int>,
)

/** Direct-probe surface: per-node results + the tags a run has covered.
 *  [urlTested] tracks engine-covered tags separately so a connected-mode
 *  badge never borrows a disconnected-mode "tested" mark. */
private data class ProbeSurface(
    val delays: Map<String, Int>,
    val tested: Set<String>,
    val urlTested: Set<String> = emptySet(),
)

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val connectionManager: ConnectionManager,
    private val nodeDao: NodeDao,
    private val latencyProbe: LatencyProbe,
    subscriptions: SubscriptionRepository,
) : ViewModel() {

    /** Direct TCP probe results — populated when testing while disconnected. */
    private val probeSurface = MutableStateFlow(ProbeSurface(emptyMap(), emptySet()))

    private val engineSurface: StateFlow<EngineSurface> = combine(
        connectionManager.state,
        connectionManager.groups,
    ) { state, groups ->
        EngineSurface(
            connected = state is VpnConnectionState.Connected,
            delays = groups
                .flatMap { it.items }
                .mapNotNull { item -> item.urlTestDelayMs?.let { item.tag to it } }
                .toMap(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = EngineSurface(connected = false, delays = emptyMap()),
    )

    val uiState: StateFlow<ServersUiState> = combine(
        nodeDao.observeEnabled(),
        subscriptions.profiles,
        settings.selectedNodeId,
        engineSurface,
        probeSurface,
    ) { nodes, profiles, selectedId, engine, probe ->
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
            // Connected: only engine urltest numbers — a stale direct-probe
            // value must not be presented as a "via proxy" measurement.
            // Disconnected: direct TCP probes are the only source.
            delays = if (engine.connected) engine.delays else probe.delays,
            testedNodeIds = if (engine.connected) probe.urlTested else probe.tested,
            connected = engine.connected,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ServersUiState(),
    )

    /**
     * Persist the pick — ConnectionManager reconciles the live engine with
     * it (live selector switch, or a reconnect when the engine can't apply
     * it). Nothing else to do here.
     */
    fun select(nodeId: String) {
        viewModelScope.launch { settings.setSelectedNodeId(nodeId) }
    }

    /**
     * Latency probe. Connected: the engine's urltest measures each node
     * through its own outbound over the real underlay (our sockets never
     * enter the TUN). Disconnected: a direct TCP-connect probe per node —
     * same underlay, without needing a running core.
     */
    fun testLatency() {
        if (_testing.value) return
        viewModelScope.launch {
            _testing.value = true
            try {
                if (connectionManager.state.value is VpnConnectionState.Connected) {
                    val groups = connectionManager.groups.value
                    groups.forEach { connectionManager.urlTest(it.tag) }
                    val covered = groups.flatMap { g -> g.items.map { item -> item.tag } }
                    // Mark covered tags now — a node that stays without a
                    // delay after the run shows "timeout" instead of "—".
                    // Drop their stale direct-probe delays too: the badge
                    probeSurface.update {
                        it.copy(
                            delays = it.delays - covered,
                            tested = it.tested + covered,
                            urlTested = it.urlTested + covered,
                        )
                    }
                } else {
                    val nodes = nodeDao.getEnabled()
                    coroutineScope {
                        nodes.forEach { node ->
                            launch {
                                val delay = latencyProbe.measure(node.server, node.port)
                                // A connect that landed mid-probe must not
                                // publish a direct result into the
                                // connected-mode surface.
                                if (connectionManager.state.value is VpnConnectionState.Connected) {
                                    return@launch
                                }
                                probeSurface.update { surface ->
                                    surface.copy(
                                        delays = if (delay != null) {
                                            surface.delays + (node.id to delay)
                                        } else {
                                            surface.delays - node.id
                                        },
                                        tested = surface.tested + node.id,
                                    )
                                }
                            }
                        }
                    }
                }
            } finally {
                _testing.value = false
            }
        }
    }

    private val _testing = MutableStateFlow(false)
    /** True while a latency probe is in flight — drives the button spinner. */
    val testing: StateFlow<Boolean> = _testing
}
