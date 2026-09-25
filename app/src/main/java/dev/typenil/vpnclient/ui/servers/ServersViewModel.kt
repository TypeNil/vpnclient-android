package dev.typenil.vpnclient.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.common.LatencyProbe
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.db.NodePreferenceDao
import dev.typenil.vpnclient.data.settings.SettingsRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Nodes of one subscription, under its display name. [subscriptionName] is
 *  null when the profile row is gone — the screen renders the localized
 *  "Subscription N" fallback so the string can be translated. */
data class ServerGroup(
    val subscriptionId: Long,
    val subscriptionName: String?,
    val nodes: List<ServerNode>,
)

/** A selectable subscription filter — id + raw profile name (possibly
 *  blank; the screen applies the localized fallback). */
data class SubscriptionFilterOption(
    val id: Long,
    val name: String,
)

enum class ServerSortMode { Default, Latency, Name }

/** Resolved per-node user-facing state — the node row plus its persisted
 *  preferences (missing row = all defaults). [customName]/[isHidden] are
 *  consumed for display + management; [isEnabled] gates VPN membership. */
data class ServerNode(
    val entity: NodeEntity,
    val favorite: Boolean,
    val enabled: Boolean = true,
    val hidden: Boolean = false,
    val customName: String? = null,
)

val ServerNode.id: String get() = entity.id
/** Display name — the local override wins over the provider label. */
val ServerNode.name: String get() = customName?.takeIf { it.isNotBlank() } ?: entity.name
val ServerNode.providerName: String get() = entity.name
val ServerNode.protocol: String get() = entity.protocol
val ServerNode.server: String get() = entity.server
val ServerNode.subscriptionId: Long get() = entity.subscriptionId

data class ServersUiState(
    val groups: List<ServerGroup> = emptyList(),
    /** Show only starred nodes — chip-driven filter, orthogonal to query. */
    val favoritesOnly: Boolean = false,
    val selectedNodeId: String? = null,
    /** The persisted pick is the "Auto / Fastest" urltest group, not a node. */
    val autoSelected: Boolean = false,
    /** Outbound tag → last measured delay; feeds the latency badges. */
    val delays: Map<String, Int> = emptyMap(),
    /** Tags a latency run has covered — distinguishes "timeout" from
     *  "never tested" on the badge. */
    val testedNodeIds: Set<String> = emptySet(),
    /** Connected → badges come from the engine's urltest (through the
     *  proxy); disconnected → direct TCP-connect probe. Names the
     *  measurement so the UI doesn't imply one means the other. */
    val connected: Boolean = false,
    // ---- search / sort / filters ----
    val query: String = "",
    val sortMode: ServerSortMode = ServerSortMode.Default,
    val subscriptionFilter: Long? = null,
    val protocolFilter: String? = null,
    /** Every enabled subscription with nodes — drives the sub filter row. */
    val subscriptionOptions: List<SubscriptionFilterOption> = emptyList(),
    /** Protocols present in the (search-filtered) node set — drives the
     *  protocol filter row. ProtocolType.name values, not labels. */
    val protocolOptions: List<String> = emptyList(),
    /** Reveal hidden nodes for management — default off. */
    val showHidden: Boolean = false,
    /** False when any filter/search is active — the screen keeps the flat
     *  "filtered" look instead of per-subscription headers. */
    val showHeaders: Boolean = true,
    /** True when the raw node set is empty (no subscriptions) — the screen
     *  shows the "add a subscription" prompt; false when filters merely
     *  excluded everything. */
    val noSubscriptions: Boolean = true,
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

/** The raw inputs filtering/sorting operate on — bundled so the filter
 *  combine stays under the 5-flow arity limit. */
private data class NodeSurface(
    val nodes: List<ServerNode>,
    val subscriptionNames: Map<Long, String>,
)

/** User-controlled list transforms — each is a Flow input to combine. */
private data class ListControls(
    val query: String,
    val sortMode: ServerSortMode,
    val subscriptionFilter: Long?,
    val protocolFilter: String?,
    val favoritesOnly: Boolean,
    /** Reveal hidden nodes for management — they stay dimmed/managed, not
     *  selectable. Default false: hidden means "leave the picker". */
    val showHidden: Boolean,
)

@HiltViewModel
class ServersViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val connectionManager: ConnectionManager,
        private val nodeDao: NodeDao,
        private val nodePreferenceDao: NodePreferenceDao,
        private val latencyProbe: LatencyProbe,
        private val subscriptions: SubscriptionRepository,
    ) : ViewModel() {
        /** Direct TCP probe results — populated when testing while disconnected. */
        private val probeSurface = MutableStateFlow(ProbeSurface(emptyMap(), emptySet()))

        // Search text — a substring match over name/server, case-insensitive.
        private val query = MutableStateFlow("")
        private val sortMode = MutableStateFlow(ServerSortMode.Default)

        /** Null = all enabled subscriptions. */
        private val subscriptionFilter = MutableStateFlow<Long?>(null)

        /** ProtocolType.name value; null = all protocols. */
        private val protocolFilter = MutableStateFlow<String?>(null)

        /** True = restrict the list to starred nodes. */
        private val favoritesOnly = MutableStateFlow(false)

        /** Reveal hidden nodes so the user can Unhide them — off by default
         *  since "hidden" is presentation, not disablement. */
        private val showHidden = MutableStateFlow(false)

        private val controls: StateFlow<ListControls> =
            combine(
                query,
                sortMode,
                subscriptionFilter,
                protocolFilter,
                favoritesOnly,
                showHidden,
            ) { values ->
                ListControls(
                    query = values[0] as String,
                    sortMode = values[1] as ServerSortMode,
                    subscriptionFilter = values[2] as Long?,
                    protocolFilter = values[3] as String?,
                    favoritesOnly = values[4] as Boolean,
                    showHidden = values[5] as Boolean,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ListControls(
                    "", ServerSortMode.Default, null, null, false, false,
                ),
            )

        private val engineSurface: StateFlow<EngineSurface> =
            combine(
                connectionManager.state,
                connectionManager.groups,
            ) { state, groups ->
                EngineSurface(
                    connected = state is VpnConnectionState.Connected,
                    delays =
                        groups
                            .flatMap { it.items }
                            .mapNotNull { item -> item.urlTestDelayMs?.let { item.tag to it } }
                            .toMap(),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = EngineSurface(connected = false, delays = emptyMap()),
            )

        private val nodeSurface: StateFlow<NodeSurface> =
            combine(
                nodeDao.observeEnabled(),
                nodePreferenceDao.observeAll(),
                subscriptions.profiles,
            ) { nodes, prefs, profiles ->
                val prefById = prefs.associateBy { it.nodeId }
                NodeSurface(
                    nodes =
                        nodes.map { entity ->
                            val pref = prefById[entity.id]
                            ServerNode(
                                entity = entity,
                                favorite = pref?.isFavorite == true,
                                enabled = pref?.isEnabled ?: true,
                                hidden = pref?.isHidden == true,
                                customName = pref?.customName,
                            )
                        },
                    subscriptionNames = profiles.associate { it.id to it.name },
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = NodeSurface(emptyList(), emptyMap()),
            )

        val uiState: StateFlow<ServersUiState> =
            combine(
                nodeSurface,
                settings.selectedNodeId,
                engineSurface,
                probeSurface,
                controls,
            ) { surface, selectedId, engine, probe, ctl ->
                val names = surface.subscriptionNames
                // Connected: only engine urltest numbers — a stale direct-probe
                // value must not be presented as a "via proxy" measurement.
                // Disconnected: direct TCP probes are the only source.
                val delays = if (engine.connected) engine.delays else probe.delays
                val testedIds = if (engine.connected) probe.urlTested else probe.tested

                val trimmedQuery = ctl.query.trim()
                val searching = trimmedQuery.isNotEmpty()
                // Hidden nodes leave every picker unless the user asked to
                // manage them — the flag is presentation-only, the engine
                // still sees the node as usable.
                val filtered =
                    surface.nodes
                        .asSequence()
                        .filter { node -> ctl.showHidden || !node.hidden }
                        .filter { node ->
                            ctl.subscriptionFilter == null || node.subscriptionId == ctl.subscriptionFilter
                        }.filter { node ->
                            !ctl.favoritesOnly || node.favorite
                        }.filter { node ->
                            !searching ||
                                node.name.contains(trimmedQuery, ignoreCase = true) ||
                                node.server.contains(trimmedQuery, ignoreCase = true)
                        }.toList()

                // Protocol options come from the (sub-filtered) set so a stale
                // selection can't silently hide everything — the chip for the
                // active protocol always exists while it still has nodes.
                val protocolOptions =
                    filtered
                        .map { it.protocol }
                        .distinct()
                        .sortedBy { protocolLabel(it) }
                val effectiveProtocol =
                    if (ctl.protocolFilter != null && ctl.protocolFilter in protocolOptions) {
                        ctl.protocolFilter
                    } else {
                        null
                    }
                val afterProtocol =
                    filtered.filter {
                        effectiveProtocol == null || it.protocol == effectiveProtocol
                    }

                val sorted =
                    when (ctl.sortMode) {
                        // DB order: subscriptionId, position — already the "default".
                        ServerSortMode.Default -> {
                            afterProtocol
                        }

                        // Known delay ascending; untested/unmeasurable nodes last.
                        ServerSortMode.Latency -> {
                            afterProtocol.sortedBy { node ->
                                delays[node.id] ?: Int.MAX_VALUE
                            }
                        }

                        ServerSortMode.Name -> {
                            afterProtocol.sortedBy { it.name.lowercase() }
                        }
                    }

                // Group headers only make sense in the default unfiltered view —
                // searching, a single-subscription filter, or a non-default sort
                // present one flat result list instead.
                val flat =
                    searching || ctl.subscriptionFilter != null ||
                        ctl.favoritesOnly ||
                        ctl.sortMode != ServerSortMode.Default
                val groups =
                    if (flat) {
                        listOfNotNull(
                            if (sorted.isNotEmpty()) {
                                ServerGroup(
                                    subscriptionId = -1L,
                                    subscriptionName = "",
                                    nodes = sorted,
                                )
                            } else {
                                null
                            },
                        )
                    } else {
                        sorted
                            .groupBy { it.subscriptionId }
                            .map { (subId, groupNodes) ->
                                ServerGroup(
                                    subscriptionId = subId,
                                    // Raw profile name — null when the profile
                                    // row is missing; the screen renders the
                                    // localized fallback so it can translate.
                                    subscriptionName = names[subId],
                                    nodes = groupNodes,
                                )
                            }
                    }

                ServersUiState(
                    groups = groups,
                    selectedNodeId = selectedId,
                    autoSelected = selectedId == NodeSelection.AUTO_ID,
                    delays = delays,
                    testedNodeIds = testedIds,
                    connected = engine.connected,
                    query = ctl.query,
                    sortMode = ctl.sortMode,
                    subscriptionFilter = ctl.subscriptionFilter,
                    protocolFilter = effectiveProtocol,
                    favoritesOnly = ctl.favoritesOnly,
                    showHidden = ctl.showHidden,
                    subscriptionOptions =
                        surface.nodes
                            .map { it.subscriptionId }
                            .distinct()
                            .map { subId ->
                                SubscriptionFilterOption(
                                    id = subId,
                                    name = names[subId].orEmpty(),
                                )
                            },
                    protocolOptions = protocolOptions,
                    showHeaders = !flat,
                    noSubscriptions = surface.nodes.isEmpty(),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ServersUiState(),
            )

        fun setQuery(value: String) {
            query.value = value
        }

        fun setSortMode(mode: ServerSortMode) {
            sortMode.value = mode
        }

        /** Toggle semantics: tapping the active chip clears the filter. */
        fun setSubscriptionFilter(id: Long?) {
            subscriptionFilter.value = id
        }

        fun setProtocolFilter(protocol: String?) {
            protocolFilter.value = protocol
        }

        /** Toggle semantics: tapping the active chip clears the filter. */
        fun setFavoritesOnly(enabled: Boolean) {
            favoritesOnly.value = enabled
        }

        fun setShowHidden(enabled: Boolean) {
            showHidden.value = enabled
        }

        /**
         * Star/unstar a node — writes only the prefs row; the node row itself
         * is subscription-owned and rewritten on every refresh. Routed
         * through the repository so the write is serialized against a
         * refresh commit.
         */
        fun toggleFavorite(nodeId: String) {
            viewModelScope.launch {
                val current = nodePreferenceDao.get(nodeId)?.isFavorite == true
                subscriptions.setNodeFavorite(nodeId, !current)
            }
        }

        /** Enable/disable VPN membership — disabling the selected node falls
         *  the selection back inside the repository (conditional, so a
         *  concurrent user pick isn't wiped). */
        fun setNodeEnabled(
            nodeId: String,
            enabled: Boolean,
        ) {
            viewModelScope.launch { subscriptions.setNodeEnabled(nodeId, enabled) }
        }

        /** Presentation-only hide — the node stays usable for routing but
         *  leaves user lists (favorites/search). */
        fun setNodeHidden(
            nodeId: String,
            hidden: Boolean,
        ) {
            viewModelScope.launch { subscriptions.setNodeHidden(nodeId, hidden) }
        }

        /** Local display name — blank clears back to the provider name. */
        fun setNodeCustomName(
            nodeId: String,
            customName: String?,
        ) {
            viewModelScope.launch {
                subscriptions.setNodeCustomName(nodeId, customName?.takeIf { it.isNotBlank() })
            }
        }

        /**
         * Persist a node pick — ConnectionManager reconciles the live engine
         * with it (live selector switch, or a reconnect when the engine can't
         * apply it). Nothing else to do here.
         */
        fun selectNode(nodeId: String) {
            viewModelScope.launch {
                // A disabled node can't be picked — the compiled set won't
                // carry it, so the reconcile would chase a ghost selection.
                val usable = nodeDao.getUsable().map { it.id }.toSet()
                if (nodeId in usable) {
                    settings.setSelectedNodeId(nodeId)
                }
            }
        }

        /** Persist the "Auto / Fastest" pick — stored as the [NodeSelection.AUTO_ID]
         *  sentinel; the compiler/live reconciler route it to the urltest group. */
        fun selectAuto() {
            viewModelScope.launch { settings.setSelectedNodeId(NodeSelection.AUTO_ID) }
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
                                            delays =
                                                if (delay != null) {
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

/** Display label for a stored protocol tag — raw value as fallback. */
internal fun protocolLabel(protocol: String): String = runCatching { ProtocolType.valueOf(protocol) }.getOrNull()?.label ?: protocol
