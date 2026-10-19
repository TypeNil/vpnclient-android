package dev.typenil.vpnclient.ui.servers

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import dev.typenil.vpnclient.ui.common.headingSemantics
import dev.typenil.vpnclient.ui.theme.AfterglowDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import dev.typenil.vpnclient.ui.theme.AfterglowCheckbox
import dev.typenil.vpnclient.ui.theme.AfterglowChip as FilterChip
import dev.typenil.vpnclient.ui.theme.AfterglowRadioButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import dev.typenil.vpnclient.ui.theme.AfterglowTextField as OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.engine.singbox.NodeTlsSummary
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.ui.theme.AfterglowTheme
import dev.typenil.vpnclient.ui.common.relativeTimeText
import dev.typenil.vpnclient.ui.theme.AfterglowButtonStyle
import dev.typenil.vpnclient.ui.theme.AfterglowEmptyState
import dev.typenil.vpnclient.ui.theme.EmptyArt
import dev.typenil.vpnclient.ui.theme.AfterglowStar
import dev.typenil.vpnclient.ui.theme.AfterglowTokens
import dev.typenil.vpnclient.ui.theme.LocalSheetMaxHeight
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import dev.typenil.vpnclient.ui.theme.ProviderCardSlice
import dev.typenil.vpnclient.ui.theme.AfterglowCard
import dev.typenil.vpnclient.ui.theme.cardOutline
import androidx.compose.foundation.lazy.itemsIndexed
import dev.typenil.vpnclient.ui.theme.ServerRow
import dev.typenil.vpnclient.ui.theme.scaledDp
import dev.typenil.vpnclient.ui.theme.showProviderHeader
import dev.typenil.vpnclient.ui.theme.AfterglowType

/** What the Servers list asks of its owner — the view model in the app, a recorder in tests. */
internal data class ServersActions(
    val setQuery: (String) -> Unit = {},
    val setSortMode: (ServerSortMode) -> Unit = {},
    val setFavoritesOnly: (Boolean) -> Unit = {},
    val setSubscriptionFilter: (Long?) -> Unit = {},
    val setProtocolFilter: (String?) -> Unit = {},
    val setShowHidden: (Boolean) -> Unit = {},
    val testLatency: () -> Unit = {},
    val cancelLatencyTest: () -> Unit = {},
    val selectAuto: () -> Unit = {},
    val selectNode: (String) -> Unit = {},
    val toggleFavorite: (String) -> Unit = {},
    val setNodeCustomName: (String, String?) -> Unit = { _, _ -> },
    val setNodeEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val setNodeHidden: (String, Boolean) -> Unit = { _, _ -> },
    val clearFilters: () -> Unit = {},
    val refreshSubscriptions: () -> Unit = {},
)

private fun ServersViewModel.asActions() =
    ServersActions(
        ::setQuery, ::setSortMode, ::setFavoritesOnly, ::setSubscriptionFilter, ::setProtocolFilter, ::setShowHidden,
        ::testLatency, ::cancelLatencyTest, ::selectAuto, ::selectNode, ::toggleFavorite,
        ::setNodeCustomName, ::setNodeEnabled, ::setNodeHidden, ::clearFilters, ::refreshSubscriptions,
    )

@Composable
fun ServersScreen(
    modifier: Modifier = Modifier,
    onAddServer: () -> Unit = {},
    /** Called after a row or Auto is picked — the sheet closes; star, menu, filters, sort and the latency test never call it. */
    onSelected: () -> Unit = {},
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    // A direct-probe run has nobody to show its progress to once the screen is
    // gone — but a rotation recreates the composition and must not kill it.
    val activity = LocalActivity.current
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.onScreenLeft() }
    }
    val refreshing by viewModel.refreshingAll.collectAsStateWithLifecycle()
    val actions = remember(viewModel) { viewModel.asActions() }
    ServersContent(ui, testing, progress, actions, modifier, onAddServer, onSelected, refreshing)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ServersContent(
    ui: ServersUiState,
    testing: Boolean,
    progress: LatencyProgress?,
    actions: ServersActions,
    modifier: Modifier = Modifier,
    onAddServer: () -> Unit = {},
    onSelected: () -> Unit = {},
    refreshing: Boolean = false,
) {
    val c = AfterglowTheme.colors
    val sheetMax = LocalSheetMaxHeight.current
        ?: LocalConfiguration.current.screenHeightDp.dp
    val showHeaders = ui.showHeaders && showProviderHeader(ui.groups.size)
    val now = System.currentTimeMillis()
    // Rows are lazy items (a provider card is drawn slice by slice): one non-lazy card per provider took ~2.4 s to first frame on 300 nodes.
    // Plain remember, not the saveable default: the sheet's SaveableStateProvider would restore the old scroll offset on reopen.
    LazyColumn(
        state = remember { LazyListState() },
        modifier = modifier.fillMaxWidth().heightIn(max = sheetMax * .78f),
        contentPadding = PaddingValues(start = AfterglowTokens.screenPadding, top = AfterglowTokens.cardGap, end = AfterglowTokens.screenPadding, bottom = AfterglowTokens.screenPadding),
        // No spacedBy: rows of one provider card touch; the last slice and the other items carry the gap.
    ) {
        item(key = "controls") {
            Column(Modifier.fillMaxWidth().padding(bottom = AfterglowTokens.cardGap)) {
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = actions.setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.servers_search_hint)) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(scaledDp(AfterglowTokens.iconSize)))
                    },
                    trailingIcon = {
                        if (ui.query.isNotEmpty()) {
                            IconButton(onClick = { actions.setQuery("") }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.common_clear_search),
                                )
                            }
                        }
                    },
                    singleLine = true,
                )
                Spacer(Modifier.height(AfterglowTokens.headerInset))
                // One control row; it wraps instead of clipping at large font scales.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AfterglowTokens.headerGap),
                    verticalArrangement = Arrangement.spacedBy(AfterglowTokens.headerInset),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    SortSelector(sortMode = ui.sortMode, onSelect = actions.setSortMode)
                    // The glyph reads the same in both locales; the word "Избранное" pushed Filters off the viewport.
                    val favLabel = stringResource(R.string.servers_favorites)
                    FilterChip(
                        selected = ui.favoritesOnly,
                        onClick = { actions.setFavoritesOnly(!ui.favoritesOnly) },
                        label = {
                            Icon(
                                AfterglowStar.icon(ui.favoritesOnly),
                                contentDescription = favLabel,
                                modifier = Modifier.size(scaledDp(AfterglowTokens.iconSize)),
                            )
                        },
                    )
                    FiltersEntry(ui, actions)
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.size(AfterglowTokens.progressSize), strokeWidth = AfterglowTokens.progressStroke)
                        progress?.let {
                            Text(
                                text = stringResource(R.string.servers_latency_progress, it.done, it.total),
                                style = AfterglowType.rowCaption,
                                color = c.muted,
                            )
                        }
                        TextButton(onClick = actions.cancelLatencyTest) { Text(stringResource(R.string.servers_latency_stop)) }
                    } else {
                        TextButton(onClick = actions.testLatency) { Text(stringResource(R.string.servers_test_latency)) }
                    }
                }
                // Says what the button above will do — never what the shown chips are
                // (each chip's method is in the row description): connected → urltest
                // through the proxy chain; disconnected → direct TCP.
                Text(
                    text = stringResource(if (ui.connected) R.string.servers_latency_next_proxy else R.string.servers_latency_next_tcp),
                    style = AfterglowType.rowCaption,
                    color = c.muted,
                    modifier = Modifier.padding(top = AfterglowTokens.headerGap),
                )
            }
        }

        if (ui.groups.isEmpty()) {
            item(key = "empty") {
                when (serversEmpty(hasGroups = false, noServersAtAll = ui.noSubscriptions, hasSubscriptions = ui.hasSubscriptions)) {
                    // Nothing imported at all: the way forward is the Subscriptions tab's add dialog (URL or share link).
                    ServersEmpty.NoSubscriptions -> AfterglowEmptyState(
                        EmptyArt.Subscriptions, stringResource(R.string.common_no_servers_yet), stringResource(R.string.common_add_servers_hint),
                        actionLabel = stringResource(R.string.subs_add_cta), onAction = onAddServer,
                    )
                    ServersEmpty.NoServers -> AfterglowEmptyState(
                        EmptyArt.NoServers, stringResource(R.string.servers_no_servers_title), stringResource(R.string.servers_no_servers_hint),
                        actionLabel = stringResource(if (refreshing) R.string.subs_refreshing else R.string.subs_refresh_now),
                        onAction = { if (!refreshing) actions.refreshSubscriptions() },
                    )
                    else -> AfterglowEmptyState(
                        EmptyArt.Search, stringResource(R.string.servers_no_match), stringResource(R.string.servers_no_match_hint),
                        actionLabel = stringResource(R.string.servers_clear_filters), actionStyle = AfterglowButtonStyle.Secondary,
                        onAction = actions.clearFilters,
                    )
                }
            }
        }

        // "Auto / Fastest" — persisted as the AUTO_ID sentinel; the engine compiles/selects
        // the urltest group for it, so it never maps to a concrete node row. Hidden with no
        // nodes at all: there would be nothing to measure, and picking it would persist a
        // selection that cannot connect.
        if (!ui.noSubscriptions) {
            item(key = "auto") {
                Box(Modifier.padding(bottom = AfterglowTokens.cardGap)) {
                    val auto = nodeLatencyView(ui.delays[NodeSelection.AUTO_ID], tested = false, stored = null, connected = ui.connected, nowMs = now)
                    AfterglowCard(outline = AfterglowTheme.colors.cardOutline(ui.autoSelected)) {
                        ServerRow(
                            title = stringResource(R.string.common_auto_fastest),
                            selected = ui.autoSelected,
                            onClick = {
                                actions.selectAuto()
                                onSelected()
                            },
                            latency = auto.reading,
                            showLatency = auto.reading != null,
                            caption = stringResource(R.string.home_auto_subtitle),
                        )
                    }
                }
            }
        }

        ui.groups.forEach { group ->
            itemsIndexed(group.nodes, key = { _, node -> "node-${group.subscriptionId}-${node.id}" }) { index, node ->
                val last = index == group.nodes.lastIndex
                val header = if (index == 0 && showHeaders) {
                    val name = group.subscriptionName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.common_subscription_numbered, group.subscriptionId)
                    stringResource(R.string.picker_group_header, name, group.nodes.size)
                } else null
                Box(if (last) Modifier.padding(bottom = AfterglowTokens.cardGap) else Modifier) {
                    ProviderCardSlice(first = index == 0, last = last, header = header) {
                        ServerNodeRow(
                            node = node,
                            selected = node.id == ui.selectedNodeId,
                            latency = nodeLatencyView(ui.delays[node.id], node.id in ui.testedNodeIds, ui.storedLatency[node.id], ui.connected, now),
                            nowMs = now,
                            // A disabled or hidden node stays rendered (the user manages it) but tapping it must
                            // not persist a selection the engine can't honor / the lists don't show — keep the row, drop the click.
                            onClick = {
                                if (node.canSelect) {
                                    actions.selectNode(node.id)
                                    onSelected()
                                }
                            },
                            onToggleFavorite = { actions.toggleFavorite(node.id) },
                            onRename = { newName -> actions.setNodeCustomName(node.id, newName) },
                            onSetEnabled = { enabled -> actions.setNodeEnabled(node.id, enabled) },
                            onSetHidden = { hidden -> actions.setNodeHidden(node.id, hidden) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SortSelector(
    sortMode: ServerSortMode,
    onSelect: (ServerSortMode) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        // Bare mode label, no "Sort:" prefix — the long Russian label
        // ("Сортировка: по задержке") pushed the Filters chip off-screen.
        // The dropdown arrow already signals a menu; the items name it.
        FilterChip(
            selected = sortMode != ServerSortMode.Default,
            onClick = { open = true },
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sortModeLabel(sortMode))
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(scaledDp(AfterglowTokens.chipIconSize)),
                    )
                }
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ServerSortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(sortModeLabel(mode)) },
                    onClick = {
                        open = false
                        onSelect(mode)
                    },
                    trailingIcon = {
                        if (mode == sortMode) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(scaledDp(AfterglowTokens.chipIconSize)),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FiltersEntry(
    ui: ServersUiState,
    actions: ServersActions,
) {
    var open by remember { mutableStateOf(false) }
    val active = activeFilterCount(ui.subscriptionFilter, ui.protocolFilter, ui.showHidden)
    FilterChip(
        selected = active > 0,
        onClick = { open = true },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.servers_filters) +
                        if (active > 0) " · $active" else "",
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(scaledDp(AfterglowTokens.chipIconSize)),
                )
            }
        },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.servers_filters)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (ui.subscriptionOptions.size > 1) {
                        FilterHeader(stringResource(R.string.servers_filter_subscription))
                        FilterOptionRow(
                            stringResource(R.string.servers_filter_all),
                            ui.subscriptionFilter == null,
                        ) { actions.setSubscriptionFilter(null) }
                        ui.subscriptionOptions.forEach { option ->
                            FilterOptionRow(
                                option.name.ifBlank {
                                    stringResource(R.string.common_subscription_numbered, option.id)
                                },
                                ui.subscriptionFilter == option.id,
                            ) { actions.setSubscriptionFilter(option.id) }
                        }
                    }
                    if (ui.protocolOptions.size > 1) {
                        FilterHeader(stringResource(R.string.servers_filter_protocol))
                        FilterOptionRow(
                            stringResource(R.string.servers_filter_all),
                            ui.protocolFilter == null,
                        ) { actions.setProtocolFilter(null) }
                        ui.protocolOptions.forEach { protocol ->
                            FilterOptionRow(
                                protocolLabel(protocol),
                                ui.protocolFilter == protocol,
                            ) { actions.setProtocolFilter(protocol) }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(AfterglowTokens.chipShape)
                            .clickable { actions.setShowHidden(!ui.showHidden) }
                            .heightIn(min = AfterglowTokens.touchTarget),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AfterglowCheckbox(checked = ui.showHidden, onCheckedChange = null)
                        Text(
                            stringResource(R.string.servers_show_hidden),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.common_done))
                }
            },
            dismissButton = {
                if (active > 0) {
                    TextButton(onClick = {
                        actions.setSubscriptionFilter(null)
                        actions.setProtocolFilter(null)
                        actions.setShowHidden(false)
                    }) {
                        Text(stringResource(R.string.servers_filter_reset))
                    }
                }
            },
        )
    }
}

@Composable
private fun FilterHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.headingSemantics().padding(top = AfterglowTokens.headerGap, bottom = AfterglowTokens.headingBottom),
    )
}

@Composable
private fun FilterOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(AfterglowTokens.chipShape).clickable(onClick = onClick)
            .heightIn(min = AfterglowTokens.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AfterglowRadioButton(selected = selected, onClick = null)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun sortModeLabel(mode: ServerSortMode): String =
    when (mode) {
        ServerSortMode.Default -> stringResource(R.string.sort_default)
        ServerSortMode.Latency -> stringResource(R.string.sort_latency)
        ServerSortMode.Name -> stringResource(R.string.sort_name)
    }

@Composable
private fun ServerNodeRow(
    node: ServerNode,
    selected: Boolean,
    latency: NodeLatencyView,
    nowMs: Long,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: (String?) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetHidden: (Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showTlsDetails by remember { mutableStateOf(false) }
    val c = AfterglowTheme.colors
    val iconSize = scaledDp(AfterglowTokens.iconSize)

    // Method and age live in the row's spoken description; an outdated chip is only muted.
    val history = latency.history
    val historyAge = history?.let { relativeTimeText(maxOf(0L, nowMs - it.checkedAtMs)) }
    val freshness = stringResource(if (latency.stale) R.string.servers_latency_stale else R.string.servers_latency_earlier)
    val spoken =
        listOfNotNull(
            latency.reading?.let { stringResource(it.methodRes) },
            history?.let { "$freshness, $historyAge" },
        ).joinToString(" · ").ifEmpty { null }
    // Disabled / hidden are honest state — the row stays rendered (the user manages it), but neither is engine-pickable.
    val caption =
        listOfNotNull(
            if (!node.encrypted && node.tunnelAllowed) stringResource(R.string.servers_local_sidecar) else null,
            if (!node.enabled) stringResource(R.string.servers_disabled_label) else null,
            if (node.hidden) stringResource(R.string.servers_hidden_label) else null,
        ).joinToString("\n").ifEmpty { null }
    // An insecure TLS config is a risk the user must see even before connecting — the details dialog carries the full picture.
    val warning =
        listOfNotNull(
            if (node.encrypted) null else stringResource(if (node.tunnelAllowed) R.string.servers_unencrypted else R.string.servers_connection_blocked),
            if (node.tls.insecure) stringResource(R.string.servers_tls_insecure_warning) else null,
        ).joinToString("\n").ifEmpty { null }

    ServerRow(
        title = node.name,
        selected = selected,
        onClick = onClick,
        protocol = protocolLabel(node.protocol),
        latency = latency.reading,
        caption = caption,
        warning = warning,
        spoken = spoken,
        actions = {
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(AfterglowTokens.touchTarget)) {
                Icon(
                    imageVector = AfterglowStar.icon(node.favorite),
                    contentDescription = stringResource(if (node.favorite) R.string.servers_favorite_remove else R.string.servers_favorite_add),
                    tint = if (node.favorite) c.coral else c.muted,
                    modifier = Modifier.size(iconSize),
                )
            }
            // Per-node management — enable/hide/rename live in prefs and survive refresh; they never rewrite the subscription row.
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(AfterglowTokens.touchTarget)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.servers_node_menu),
                        tint = c.muted,
                        modifier = Modifier.size(iconSize),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.servers_tls_details)) },
                        onClick = {
                            menuOpen = false
                            showTlsDetails = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.servers_rename)) },
                        onClick = {
                            menuOpen = false
                            showRename = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (node.enabled) R.string.servers_disable else R.string.servers_enable)) },
                        onClick = {
                            menuOpen = false
                            onSetEnabled(!node.enabled)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (node.hidden) R.string.servers_unhide else R.string.servers_hide)) },
                        onClick = {
                            menuOpen = false
                            onSetHidden(!node.hidden)
                        },
                    )
                }
            }
        },
    )
    if (showRename) {
        var text by remember { mutableStateOf(node.customName ?: node.providerName) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.servers_rename_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(node.providerName) },
                    supportingText = {
                        Text(stringResource(R.string.servers_rename_hint))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Blank input = "clear the override" → provider name.
                        onRename(text.trim().ifBlank { null })
                        showRename = false
                    },
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    if (showTlsDetails) {
        NodeTlsDialog(node = node, onDismiss = { showTlsDetails = false })
    }
}

/**
 * Read-only TLS posture of the stored node config — provenance (share link
 * vs imported config; the original format isn't stored, so it is never
 * guessed), authentication mode, and sanitized SNI/ALPN. Reachable from the
 * node menu while connected or disconnected; values describe the imported
 * config, never an observed handshake.
 */
@Composable
private fun NodeTlsDialog(
    node: ServerNode,
    onDismiss: () -> Unit,
) {
    val tls = node.tls
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.servers_tls_details)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                TlsDetailRow(
                    label = stringResource(R.string.servers_tls_origin),
                    value =
                        stringResource(
                            if (node.entity.rawUri != null) {
                                R.string.servers_tls_origin_sharelink
                            } else {
                                R.string.servers_tls_origin_subscription
                            },
                        ),
                )
                TlsDetailRow(
                    label = stringResource(R.string.servers_tls_mode),
                    value =
                        stringResource(
                            when (tls.mode) {
                                NodeTlsSummary.Mode.NONE -> R.string.servers_tls_mode_none
                                NodeTlsSummary.Mode.CERTIFICATE ->
                                    R.string.servers_tls_mode_certificate
                                NodeTlsSummary.Mode.REALITY -> R.string.servers_tls_mode_reality
                                NodeTlsSummary.Mode.UNKNOWN -> R.string.servers_tls_mode_unknown
                            },
                        ),
                )
                if (!node.encrypted) {
                    Text(stringResource(R.string.servers_unencrypted), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(if (node.tunnelAllowed) R.string.servers_local_sidecar
                        else R.string.home_error_unencrypted))
                }
                if (tls.insecure) {
                    TlsDetailRow(
                        label = stringResource(R.string.servers_tls_insecure_row),
                        value = stringResource(R.string.servers_tls_insecure_disabled),
                        warning = true,
                    )
                }
                tls.serverName?.let {
                    TlsDetailRow(label = stringResource(R.string.servers_tls_sni), value = it)
                }
                if (tls.alpn.isNotEmpty()) {
                    TlsDetailRow(
                        label = stringResource(R.string.servers_tls_alpn),
                        value = tls.alpn.joinToString(", "),
                    )
                }
                Text(
                    text = stringResource(R.string.servers_tls_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AfterglowTokens.headerGap),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_done))
            }
        },
    )
}

@Composable
private fun TlsDetailRow(
    label: String,
    value: String,
    warning: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = AfterglowTokens.headerInset)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (warning) {
                    MaterialTheme.colorScheme.error
                } else {
                    Color.Unspecified
                },
        )
    }
}
