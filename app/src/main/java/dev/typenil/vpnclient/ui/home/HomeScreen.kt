package dev.typenil.vpnclient.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.engine.TrafficStats
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.vpn.PerAppMode
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.core.vpn.VpnError
import dev.typenil.vpnclient.ui.common.CORE_VERSION
import dev.typenil.vpnclient.ui.common.formatBytes
import dev.typenil.vpnclient.ui.common.formatRate
import java.time.Duration
import java.time.Instant

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenConnections: () -> Unit = {},
    onAddServer: () -> Unit = {},
    onOpenServers: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val connection = ui.connection

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ui.subscriptionName?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = statusText(connection),
            style = MaterialTheme.typography.headlineMedium,
            color =
                when (connection) {
                    is VpnConnectionState.Connected -> MaterialTheme.colorScheme.primary
                    is VpnConnectionState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))

        var showPicker by remember { mutableStateOf(false) }
        NodeCard(
            ui = ui,
            onAddServer = onAddServer,
            onPick = { showPicker = true },
        )
        if (showPicker) {
            ServerPickerSheet(
                options = ui.serverOptions,
                selectedId = ui.selectedOptionId,
                onPick = {
                    showPicker = false
                    viewModel.selectServer(it)
                },
                onOpenServers = {
                    showPicker = false
                    onOpenServers()
                },
                onDismiss = { showPicker = false },
            )
        }

        if (ui.restartGuardTripped) {
            Spacer(Modifier.height(12.dp))
            RestartGuardCard(onDismiss = viewModel::dismissRestartGuardWarning)
        }

        if (connection is VpnConnectionState.Error) {
            Spacer(Modifier.height(12.dp))
            ErrorCard(connection.error)
        }

        if (connection is VpnConnectionState.Connected) {
            var showDetails by remember { mutableStateOf(false) }
            connection.stats?.let { stats ->
                Spacer(Modifier.height(12.dp))
                StatsCard(
                    stats = stats,
                    onOpenConnections = onOpenConnections,
                    onOpenDetails = { showDetails = true },
                )
            }
            if (showDetails) {
                SessionDetailsSheet(
                    state = connection,
                    details = ui.sessionDetails,
                    onDismiss = { showDetails = false },
                )
            }
        }

        Spacer(Modifier.weight(1f))

        ConnectButton(
            connection = connection,
            onConnect = viewModel::connect,
            onDisconnect = viewModel::disconnect,
        )
    }
}

private fun statusText(state: VpnConnectionState): String =
    when (state) {
        VpnConnectionState.Idle -> "Disconnected"
        is VpnConnectionState.Preparing -> "Preparing…"
        VpnConnectionState.PermissionRequired -> "Awaiting VPN permission…"
        is VpnConnectionState.Connecting -> "Connecting…"
        is VpnConnectionState.Connected -> "Connected"
        is VpnConnectionState.Reconnecting -> "Reconnecting… (attempt ${state.attempt})"
        VpnConnectionState.Stopping -> "Disconnecting…"
        is VpnConnectionState.Error -> "Connection failed"
    }

private fun errorText(error: VpnError): String =
    when (error) {
        VpnError.PermissionDenied -> "VPN permission denied"
        VpnError.PermissionRevoked -> "VPN permission was revoked"
        VpnError.NoNodeSelected -> "No server selected — pick one in Servers"
        is VpnError.ConfigInvalid -> "Invalid configuration: ${error.detail}"
        is VpnError.EngineFailed -> "VPN core failed: ${error.detail}"
        is VpnError.TunnelFailed -> "Tunnel failed: ${error.detail}"
        is VpnError.Unexpected -> error.detail
    }

private fun protocolLabel(protocol: String): String = runCatching { ProtocolType.valueOf(protocol) }.getOrNull()?.label ?: protocol

/**
 * Display parts for the session node. The Auto sentinel stands for the
 * urltest group, not for one of its members: until the group reports a winner
 * it has no protocol or server of its own (its OTHER protocol is internal
 * plumbing, not a user-facing description), and a resolved Auto carries the
 * leaf's real values.
 */
private fun nodeDetail(node: NodeSummary): Pair<String?, String?> =
    if (node.id == NodeSelection.AUTO_ID && node.protocol == ProtocolType.OTHER) {
        null to null
    } else {
        node.protocol.label to node.server
    }

/** Tap opens the quick-pick sheet — but only when there is something to
 *  pick: with no nodes the card is an add prompt, not a picker. */
@Composable
private fun NodeCard(
    ui: HomeUiState,
    onAddServer: () -> Unit,
    onPick: () -> Unit,
) {
    val state = ui.connection
    // While a connection is (being) established, show the node it uses;
    // otherwise show the currently selected node.
    val name: String?
    val protocol: String?
    val server: String?
    when (state) {
        is VpnConnectionState.Connected -> {
            name = state.node.name
            val (p, s) = nodeDetail(state.node)
            protocol = p
            server = s
        }

        is VpnConnectionState.Connecting -> {
            name = state.node.name
            val (p, s) = nodeDetail(state.node)
            protocol = p
            server = s
        }

        is VpnConnectionState.Reconnecting -> {
            name = state.node.name
            val (p, s) = nodeDetail(state.node)
            protocol = p
            server = s
        }

        is VpnConnectionState.Preparing -> {
            name = state.node.name
            val (p, s) = nodeDetail(state.node)
            protocol = p
            server = s
        }

        is VpnConnectionState.Error -> {
            name = state.node?.name ?: ui.selectedNodeName
            protocol = state.node?.protocol?.label
                ?: ui.selectedNodeProtocol?.let(::protocolLabel)
            server = state.node?.server ?: ui.selectedNodeServer
        }

        else -> {
            name = ui.selectedNodeName
            protocol = ui.selectedNodeProtocol?.let(::protocolLabel)
            server = ui.selectedNodeServer
        }
    }

    val pickable = ui.serverOptions.isNotEmpty()
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (pickable) Modifier.clickable(onClick = onPick) else Modifier,
                ),
    ) {
        Column(Modifier.padding(16.dp)) {
            // First-run context: no node rows at all — a "No server
            // selected" label would be a dead end, so offer the add
            // action instead. Only when the card has no node to show
            // (idle/error with nothing selected).
            if (name == null && ui.noNodesAtAll) {
                Text(
                    text = "No servers yet",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Add a subscription or paste a share link to get started.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAddServer) {
                    Text("Add subscription or share link")
                }
            } else {
                Text(
                    text = name ?: "No server selected",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (protocol != null || server != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = listOfNotNull(protocol, server).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pickable) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tap to change",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Quick-pick bottom sheet: Auto pinned first, then every enabled node behind a
 * search box. Bounded and lazy — a subscription with hundreds of nodes must not
 * turn the picker into a full-screen list, and the Servers tab stays the place
 * for filters, sorting and latency tests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerPickerSheet(
    options: List<ServerOption>,
    selectedId: String?,
    onPick: (String) -> Unit,
    onOpenServers: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val auto = options.firstOrNull { it.id == NodeSelection.AUTO_ID }
    val nodes = options.filter { it.id != NodeSelection.AUTO_ID }
    val trimmed = query.trim()
    val matches =
        if (trimmed.isEmpty()) {
            nodes
        } else {
            nodes.filter { it.searchText.contains(trimmed, ignoreCase = true) }
        }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                text = "Server",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                placeholder = { Text("Search servers") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
            )
            Spacer(Modifier.height(4.dp))
            // Outside the list: the escape hatch must stay reachable with the
            // soft keyboard open, which covers the bottom of the sheet.
            TextButton(
                onClick = onOpenServers,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text("All servers, filters & latency")
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                // Auto is the picker's first-class choice — a search for a
                // node name must not hide it.
                auto?.let { option ->
                    item(key = option.id) {
                        PickerRow(
                            option = option,
                            selected = option.id == selectedId,
                            onClick = { onPick(option.id) },
                        )
                    }
                }
                items(matches, key = { it.id }) { option ->
                    PickerRow(
                        option = option,
                        selected = option.id == selectedId,
                        onClick = { onPick(option.id) },
                    )
                }
                if (matches.isEmpty()) {
                    item(key = "no-match") {
                        Text(
                            text = "No servers match \"$trimmed\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    option: ServerOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = option.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            option.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ErrorCard(error: VpnError) {
    // Informational only — the Error state is terminal until the next connect.
    var dismissed by remember(error) { mutableStateOf(false) }
    if (dismissed) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = errorText(error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { dismissed = true }) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun RestartGuardCard(onDismiss: () -> Unit) {
    // Persisted counterpart of the restart-guard alert notification — shown
    // even when notifications are denied, until dismissed or a new connect.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "VPN auto-restart stopped — repeated failures. Reconnect manually.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun StatsCard(
    stats: TrafficStats,
    onOpenConnections: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatRow("Download", "${formatRate(stats.downlinkBytesPerSec)}  (${formatBytes(stats.downlinkTotalBytes)} total)")
            StatRow("Upload", "${formatRate(stats.uplinkBytesPerSec)}  (${formatBytes(stats.uplinkTotalBytes)} total)")
            StatRow(
                label = "Connections",
                value = "${stats.connectionsIn} in / ${stats.connectionsOut} out",
                // clickable before padding — the padded area stays tappable.
                modifier =
                    Modifier
                        .clickable(onClick = onOpenConnections)
                        .padding(vertical = 4.dp),
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            StatRow(
                label = "Session details",
                value = "",
                modifier =
                    Modifier
                        .clickable(onClick = onOpenDetails)
                        .padding(vertical = 4.dp),
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

private fun routeModeSummary(mode: RouteMode): String =
    when (mode) {
        RouteMode.ALL -> "Proxy everything"
        RouteMode.BYPASS_RU -> "Bypass Russian resources"
        RouteMode.PROXY_BLOCKED -> "Only blocked services"
    }

private fun perAppSummary(
    mode: PerAppMode,
    count: Int,
): String =
    when (mode) {
        PerAppMode.ALL -> "All apps"
        PerAppMode.INCLUDE -> "Include $count app${if (count == 1) "" else "s"}"
        PerAppMode.EXCLUDE -> "Exclude $count app${if (count == 1) "" else "s"}"
    }

/** Elapsed since Connected — whole units, no fake precision. */
private fun uptimeText(
    since: Instant,
    now: Instant = Instant.now(),
): String {
    val seconds = Duration.between(since, now).seconds.coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m ${s}s"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

/**
 * Real session details: engine-reported outbound, persisted routing/per-app
 * settings, the service's underlay transport, and stats from the live
 * Connected payload. Nothing is invented — absent values render as "—".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailsSheet(
    state: VpnConnectionState.Connected,
    details: SessionDetails?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Session details",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            DetailRow("Node", state.node.name)
            // The selector group's pick is what actually egresses — can
            // diverge from the session node after a live outbound switch.
            DetailRow("Outbound", details?.activeOutbound ?: "—")
            // Applied plan only — the settings value may not be live yet
            // (a route-mode change needs a reconnect, per-app a rebuild).
            DetailRow("Routing mode", details?.applied?.let { routeModeSummary(it.routeMode) } ?: "—")
            DetailRow(
                "Per-app VPN",
                details?.applied?.let { perAppSummary(it.perAppMode, it.perAppPackageCount) } ?: "—",
            )
            // Named, not silently ignored: these are changed-but-unapplied.
            details?.takeIf { it.pendingReconnect.isNotEmpty() }?.let {
                DetailRow("Pending reconnect", it.pendingReconnect.joinToString(", "))
            }
            DetailRow("Underlying network", details?.underlay?.label ?: "—")
            DetailRow("Uptime", uptimeText(state.since))
            state.stats?.let { stats ->
                DetailRow(
                    "Data used",
                    "↓ ${formatBytes(stats.downlinkTotalBytes)} · ↑ ${formatBytes(stats.uplinkTotalBytes)}",
                )
            }
            DetailRow("VPN core", CORE_VERSION)
            // Most recent Error seen this process — historical context, not
            // live state, so it renders even when the session is healthy.
            details?.lastError?.let { DetailRow("Last error", it) }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Floor under the label: a long value (raw outbound tag) would
            // otherwise squeeze it down to one letter per line.
            modifier = Modifier.weight(1.1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.9f),
        )
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
        trailing?.invoke()
    }
}

@Composable
private fun ConnectButton(
    connection: VpnConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val label: String
    val enabled: Boolean
    val action: () -> Unit
    when (connection) {
        is VpnConnectionState.Connected,
        is VpnConnectionState.Reconnecting,
        -> {
            label = "Disconnect"
            enabled = true
            action = onDisconnect
        }

        VpnConnectionState.Stopping -> {
            label = "Disconnecting…"
            enabled = false
            action = {}
        }

        is VpnConnectionState.Preparing,
        VpnConnectionState.PermissionRequired,
        is VpnConnectionState.Connecting,
        -> {
            label = "Connecting…"
            enabled = false
            action = {}
        }

        VpnConnectionState.Idle,
        is VpnConnectionState.Error,
        -> {
            label = "Connect"
            enabled = true
            action = onConnect
        }
    }

    Button(
        onClick = action,
        enabled = enabled,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
