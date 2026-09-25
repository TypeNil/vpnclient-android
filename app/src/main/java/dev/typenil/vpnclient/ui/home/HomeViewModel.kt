package dev.typenil.vpnclient.ui.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.engine.DnsProfile
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.core.vpn.AppliedSessionConfig
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.PerAppMode
import dev.typenil.vpnclient.core.vpn.UnderlyingTransport
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodePreferenceDao
import dev.typenil.vpnclient.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    /** The real connection state machine — rendered as-is, never invented. */
    val connection: VpnConnectionState = VpnConnectionState.Idle,
    val selectedNodeName: String? = null,
    val selectedNodeProtocol: String? = null,
    val selectedNodeServer: String? = null,
    /** The persisted pick is "Auto / Fastest" — no concrete node exists. */
    val autoSelected: Boolean = false,
    /** Resource for the persisted pick when no concrete node name exists
     *  (the Auto sentinel) — the screen resolves it to the localized label. */
    @param:StringRes val selectedNodeNameRes: Int? = null,
    val subscriptionName: String? = null,
    /** Restart guard disabled auto-start — persistent warning, survives a
     *  denied notification permission (the alert notification may never
     *  have been seen). */
    val restartGuardTripped: Boolean = false,
    /** Real session details for the Connected bottom sheet — every field
     *  comes from a live source (engine groups, DataStore, the service's
     *  underlay tracker); null means "not reported", never a guess. */
    val sessionDetails: SessionDetails? = null,
    /** No node rows exist at all — the NodeCard should offer the add
     *  prompt instead of "No server selected". */
    val noNodesAtAll: Boolean = true,
    /** Pickable entries for the server sheet: Auto first, then every
     *  enabled node. Empty when there are no nodes — the card then shows
     *  the add prompt instead of a picker. */
    val serverOptions: List<ServerOption> = emptyList(),
    /** The persisted pick — used to mark the current option in the sheet. */
    val selectedOptionId: String? = null,
)

/** One row in the Home server picker. [id] is the raw `selected_node_id`
 *  value to persist — [NodeSelection.AUTO_ID] for the Auto row. [searchText]
 *  carries the fields the picker's search matches (name + host) without
 *  widening what the row renders. */
data class ServerOption(
    val id: String,
    /** Literal node name, or null when [titleRes] carries the label (Auto). */
    val title: String? = null,
    /** Label resource — non-null only for entries without a real node name. */
    @param:StringRes val titleRes: Int? = null,
    val subtitle: String? = null,
    /** Subtitle resource — non-null only for the localized Auto subtitle. */
    @param:StringRes val subtitleRes: Int? = null,
    /** Host + name text the search matches; falls back to the Auto tag. */
    val searchText: String? = title,
)

/** Sheet payload — assembled per state emission so it stays in lockstep
 *  with the connection state machine. */
data class SessionDetails(
    /** Selector group's current pick, resolved to the display label —
     *  the group's `selected` is a raw outbound tag (node id hash or
     *  "auto"), which the UI must not render. */
    val activeOutbound: String?,
    /** Resource for [activeOutbound] when the tag is the Auto sentinel —
     *  resolves to the localized "Auto · Fastest" label at render. */
    @param:StringRes val activeOutboundRes: Int? = null,
    /** The routing/per-app plan the live engine actually runs — null until
     *  the service reports one; never guessed from the current settings. */
    val applied: AppliedSessionConfig?,
    /** Settings that changed but aren't applied to the live session yet —
     *  string resources, resolved at render so the locale switch applies. */
    val pendingReconnect: List<Int>,
    /** Physical underlay label reported by the service's network tracker. */
    val underlay: UnderlyingTransport,
    /** Message of the most recent Error state this process observed —
     *  shown as history context, never presented as live state. */
    val lastError: String?,
)

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val connectionManager: ConnectionManager,
        nodeDao: NodeDao,
        nodePreferenceDao: NodePreferenceDao,
        private val settings: SettingsRepository,
        subscriptions: SubscriptionRepository,
    ) : ViewModel() {
        /** The last terminal error the state machine published this process —
         *  the cheapest honest source for the sheet's "last error" row. */
        private val lastErrorMessage = MutableStateFlow<String?>(null)

        init {
            viewModelScope.launch {
                connectionManager.state.collect { state ->
                    if (state is VpnConnectionState.Error) {
                        lastErrorMessage.value = state.error.message
                    }
                }
            }
        }

        val uiState: StateFlow<HomeUiState> =
            combine(
                connectionManager.state,
                settings.selectedNodeId,
                nodeDao.observeEnabled(),
                subscriptions.profiles,
                settings.restartGuardTripped,
                connectionManager.groups,
                settings.perAppPolicy,
                settings.routeMode,
                connectionManager.underlyingTransport,
                lastErrorMessage,
                connectionManager.appliedSessionConfig,
                nodePreferenceDao.observeAll(),
                settings.dnsProfile,
            ) { values ->
                val connection = values[0] as VpnConnectionState
                val selectedId = values[1] as String?

                @Suppress("UNCHECKED_CAST")
                val nodes = values[2] as List<dev.typenil.vpnclient.data.db.NodeEntity>

                @Suppress("UNCHECKED_CAST")
                val profiles = values[3] as List<dev.typenil.vpnclient.core.subscription.model.SubscriptionProfile>
                val guardTripped = values[4] as Boolean

                @Suppress("UNCHECKED_CAST")
                val groups = values[5] as List<dev.typenil.vpnclient.core.engine.OutboundGroupInfo>

                @Suppress("UNCHECKED_CAST")
                val perAppPolicy = values[6] as Pair<PerAppMode, Set<String>>
                val perAppMode = perAppPolicy.first
                val perAppPackages = perAppPolicy.second
                val routeMode = values[7] as RouteMode
                val underlay = values[8] as UnderlyingTransport
                val lastError = values[9] as String?
                val appliedConfig = values[10] as AppliedSessionConfig?

                @Suppress("UNCHECKED_CAST")
                val prefs =
                    values[11] as List<dev.typenil.vpnclient.data.db.NodePreferenceEntity>
                val prefById = prefs.associateBy { it.nodeId }
                val favoriteIds = prefs.asSequence().filter { it.isFavorite }.map { it.nodeId }.toSet()
                val dnsProfile = values[12] as DnsProfile

                // Picker source: usable + not hidden — the engine can't
                // select a disabled node, and a hidden one has already left
                // the user's lists. The prefs join (not just the node row)
                // decides both. Auto stays first regardless.
                val usableNodes =
                    nodes.filter { node ->
                        val pref = prefById[node.id]
                        (pref?.isEnabled ?: true) && !(pref?.isHidden ?: false)
                    }
                val selected = usableNodes.firstOrNull { it.id == selectedId }
                val auto = selectedId == NodeSelection.AUTO_ID
                val subscriptionNames = profiles.associate { it.id to it.name }
                // What the header describes: the picked node, or — for Auto —
                // the urltest group's measured winner. "First enabled
                // subscription" would name a provider the session isn't using.
                val shownNode =
                    selected
                        ?: groups
                            .firstOrNull { it.tag == NodeSelection.AUTO_ID }
                            ?.selected
                            ?.let { winnerId -> nodes.firstOrNull { it.id == winnerId } }
                HomeUiState(
                    connection = connection,
                    // The Auto pick resolves to a node only at the engine — while
                    // disconnected (or during a session) the label stands alone.
                    // Custom name applies to the header too — same presentation
                    // rule as the picker.
                    selectedNodeName =
                        selected?.let {
                            prefById[it.id]?.customName?.takeIf { n -> n.isNotBlank() }
                                ?: it.name
                        },
                    selectedNodeNameRes =
                        if (!auto || selected != null) null else R.string.common_auto_fastest,
                    selectedNodeProtocol = selected?.protocol,
                    selectedNodeServer = selected?.let { "${it.server}:${it.port}" },
                    autoSelected = auto,
                    noNodesAtAll = nodes.isEmpty(),
                    serverOptions =
                        if (nodes.isEmpty()) {
                            emptyList()
                        } else {
                            buildList {
                                add(
                                    ServerOption(
                                        id = NodeSelection.AUTO_ID,
                                        titleRes = R.string.common_auto_fastest,
                                        subtitleRes = R.string.home_auto_subtitle,
                                        searchText = NodeSelection.AUTO_ID,
                                    ),
                                )
                                // Favorites first, right after Auto — the
                                // quick pick is where they pay off. Original
                                // order (subscriptionId, position) is kept
                                // inside each partition.
                                val ordered =
                                    usableNodes.sortedByDescending { it.id in favoriteIds }
                                ordered.forEach { node ->
                                    add(
                                        ServerOption(
                                            id = node.id,
                                            // Local override wins over the
                                            // provider name — same rule as
                                            // ServersCard.
                                            title =
                                                prefById[node.id]?.customName
                                                    ?.takeIf { it.isNotBlank() }
                                                    ?: node.name,
                                            subtitle =
                                                listOfNotNull(
                                                    node.protocol,
                                                    subscriptionNames[node.subscriptionId],
                                                ).joinToString(" · "),
                                            // Match the host too — a user hunting
                                            // for a specific server usually
                                            // knows its address, not its name.
                                            searchText = "${node.name} ${node.server}",
                                        ),
                                    )
                                }
                            }
                        },
                    selectedOptionId = selectedId,
                    subscriptionName = shownNode?.let { subscriptionNames[it.subscriptionId] },
                    restartGuardTripped = guardTripped,
                    // The sheet is real-state only: it exists only while Connected —
                    // outside that there is no session to describe.
                    sessionDetails =
                        if (connection is VpnConnectionState.Connected) {
                            SessionDetails(
                                // The engine's selector group reports what actually
                                // egresses — the session node label can lag a live
                                // switch, so the group's selected tag is authoritative.
                                // The tag itself is a raw outbound id (hash) — resolve
                                // it to the node's name; "auto" means the urltest group
                                // is the egress (Auto · Fastest), a member tag resolves
                                // through the node table.
                                activeOutbound =
                                    groups
                                        .firstOrNull { it.selectable }
                                        ?.selected
                                        ?.let { tag ->
                                            when {
                                                tag == NodeSelection.AUTO_ID -> null
                                                else -> nodes.firstOrNull { it.id == tag }?.name ?: tag
                                            }
                                        },
                                activeOutboundRes =
                                    if (
                                        groups
                                            .firstOrNull { it.selectable }
                                            ?.selected == NodeSelection.AUTO_ID
                                    ) {
                                        R.string.common_auto_fastest
                                    } else {
                                        null
                                    },
                                // Applied plan — a route-mode change is only
                                // real after a reconnect, and per-app after a
                                // TUN rebuild; the settings flow is intent.
                                applied = appliedConfig,
                                pendingReconnect =
                                    buildList {
                                        if (appliedConfig != null) {
                                            if (appliedConfig.routeMode != routeMode) {
                                                add(R.string.common_routing_mode)
                                            }
                                            // The set, not the size: replacing
                                            // one app with another is still
                                            // an unapplied change.
                                            if (appliedConfig.perAppMode != perAppMode ||
                                                appliedConfig.perAppPackages != perAppPackages
                                            ) {
                                                add(R.string.common_per_app_vpn)
                                            }
                                            if (appliedConfig.dnsProfileSummary != dnsProfile.summary) {
                                                add(R.string.routing_dns_upstream)
                                            }
                                        }
                                    },
                                underlay = underlay,
                                lastError = lastError,
                            )
                        } else {
                            null
                        },
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                // Seed with the live state — Idle would flash "Disconnected" for a
                // frame on a connected session before combine's first emission.
                initialValue = HomeUiState(connection = connectionManager.state.value),
            )

        /** Persist a pick from the sheet — the same `selected_node_id` slot
         *  Servers writes; ConnectionManager reconciles a live engine with it. */
        fun selectServer(id: String) {
            viewModelScope.launch { settings.setSelectedNodeId(id) }
        }

        fun connect() = connectionManager.connect()

        fun disconnect() = connectionManager.disconnect()

        /** Explicit dismiss — connecting again also clears it via the reset. */
        fun dismissRestartGuardWarning() {
            viewModelScope.launch {
                runCatching { settings.setRestartGuardTripped(false) }
            }
        }
    }
