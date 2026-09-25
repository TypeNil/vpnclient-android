package dev.typenil.vpnclient.ui.servers

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.data.db.NodeEntity

@Composable
fun ServersScreen(
    modifier: Modifier = Modifier,
    onAddServer: () -> Unit = {},
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "controls", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search name or server") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (ui.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SortSelector(
                        sortMode = ui.sortMode,
                        onSelect = viewModel::setSortMode,
                    )
                    ui.subscriptionOptions.forEach { option ->
                        FilterChip(
                            selected = ui.subscriptionFilter == option.id,
                            onClick = {
                                viewModel.setSubscriptionFilter(
                                    if (ui.subscriptionFilter == option.id) null else option.id,
                                )
                            },
                            label = {
                                Text(
                                    text = option.name.ifBlank { "Subscription ${option.id}" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                    ui.protocolOptions.forEach { protocol ->
                        FilterChip(
                            selected = ui.protocolFilter == protocol,
                            onClick = {
                                viewModel.setProtocolFilter(
                                    if (ui.protocolFilter == protocol) null else protocol,
                                )
                            },
                            label = { Text(protocolLabel(protocol)) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(
                        onClick = viewModel::testLatency,
                        enabled = !testing,
                    ) {
                        Text("Test latency")
                    }
                    Text(
                        // Name the measurement: connected → urltest through the
                        // proxy chain; disconnected → direct TCP connect.
                        text = if (ui.connected) "via proxy" else "TCP connect",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
        }

        if (ui.groups.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (ui.noSubscriptions) {
                        // Nothing imported at all — offer the way forward,
                        // not just a dead label. Opens the Subscriptions
                        // tab's add dialog (URL or share link).
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No servers yet",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Add a subscription or paste a share link to get started.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp),
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onAddServer) {
                                Text("Add subscription or share link")
                            }
                        }
                    } else {
                        Text(
                            text = "No servers match the current filters",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // "Auto / Fastest" — persisted as the AUTO_ID sentinel; the engine
        // compiles/selects the urltest group for it, so it never maps to a
        // concrete node card. Hidden with no nodes at all: there would be
        // nothing to measure, and picking it would persist a selection that
        // cannot connect.
        if (!ui.noSubscriptions) {
            item(key = "auto", span = { GridItemSpan(maxLineSpan) }) {
                AutoCard(
                    selected = ui.autoSelected,
                    delayMs = ui.delays[NodeSelection.AUTO_ID],
                    onClick = viewModel::selectAuto,
                )
            }
        }

        ui.groups.forEach { group ->
            if (ui.showHeaders) {
                item(key = "header-${group.subscriptionId}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = group.subscriptionName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(
                items = group.nodes,
                key = { node -> node.id },
            ) { node ->
                ServerCard(
                    node = node,
                    selected = node.id == ui.selectedNodeId,
                    delayMs = ui.delays[node.id],
                    tested = node.id in ui.testedNodeIds,
                    onClick = { viewModel.selectNode(node.id) },
                )
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
        FilterChip(
            selected = sortMode != ServerSortMode.Default,
            onClick = { open = true },
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort: ${sortMode.name}")
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ServerSortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.name) },
                    onClick = {
                        open = false
                        onSelect(mode)
                    },
                    trailingIcon = {
                        if (mode == sortMode) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AutoCard(
    selected: Boolean,
    delayMs: Int?,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Auto · Fastest",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            LatencyBadge(delayMs = delayMs, tested = delayMs != null)
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ServerCard(
    node: NodeEntity,
    selected: Boolean,
    delayMs: Int?,
    tested: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    // minLines keeps every card the same height — a one-line
                    // name must not shrink the card below its two-line peers.
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProtocolBadge(node.protocol)
                Spacer(Modifier.weight(1f))
                LatencyBadge(delayMs = delayMs, tested = tested)
            }
        }
    }
}

@Composable
private fun LatencyBadge(
    delayMs: Int?,
    tested: Boolean,
) {
    when {
        delayMs != null -> {
            Text(
                text = "$delayMs ms",
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (delayMs < 800) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
        }

        tested -> {
            Text(
                text = "timeout",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        else -> {
            Text(
                text = "—",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProtocolBadge(protocol: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = protocolLabel(protocol),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
