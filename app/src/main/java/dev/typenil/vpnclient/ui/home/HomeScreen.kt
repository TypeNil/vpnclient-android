package dev.typenil.vpnclient.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.engine.TrafficStats
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.vpn.PerAppMode
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.core.vpn.VpnError
import dev.typenil.vpnclient.ui.common.CORE_VERSION
import dev.typenil.vpnclient.ui.common.DetailRow
import dev.typenil.vpnclient.ui.common.appliedDnsSummary
import dev.typenil.vpnclient.ui.common.formatBytes
import dev.typenil.vpnclient.ui.common.formatRate
import dev.typenil.vpnclient.ui.common.perAppSummary
import dev.typenil.vpnclient.ui.common.routeModeSummary
import dev.typenil.vpnclient.ui.common.underlayLabel
import dev.typenil.vpnclient.ui.common.uptimeText
import dev.typenil.vpnclient.ui.theme.AfterglowChevron
import dev.typenil.vpnclient.ui.theme.AfterglowSheet
import dev.typenil.vpnclient.ui.theme.AfterglowSheetHeader
import dev.typenil.vpnclient.ui.theme.AfterglowTheme
import dev.typenil.vpnclient.ui.theme.AfterglowTokens
import dev.typenil.vpnclient.ui.theme.LocalSheetMaxHeight
import kotlinx.coroutines.delay
import java.time.Instant
import androidx.compose.foundation.layout.PaddingValues
import dev.typenil.vpnclient.ui.theme.ProviderGroupCard
import dev.typenil.vpnclient.ui.theme.ServerRow
import dev.typenil.vpnclient.ui.theme.ServerRowDivider
import dev.typenil.vpnclient.ui.theme.showProviderHeader

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenConnections: () -> Unit = {},
    onAddServer: () -> Unit = {},
    onOpenServers: () -> Unit = {},
    onOpenSubscriptions: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenRouting: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val connection = ui.connection
    val batteryHintVisible by viewModel.batteryHintVisible.collectAsStateWithLifecycle()
    val batteryLifecycle = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(batteryLifecycle, viewModel) {
        viewModel.refreshBatteryStatus()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshBatteryStatus()
        }
        batteryLifecycle.lifecycle.addObserver(observer)
        onDispose { batteryLifecycle.lifecycle.removeObserver(observer) }
    }
    // On-demand health probes only while Home is visible (STARTED); the observer
    // replays ON_START if already started and stops everything on ON_STOP/dispose.
    androidx.compose.runtime.DisposableEffect(batteryLifecycle, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.onHomeStarted()
            if (event == Lifecycle.Event.ON_STOP) viewModel.onHomeStopped()
        }
        batteryLifecycle.lifecycle.addObserver(observer)
        onDispose {
            batteryLifecycle.lifecycle.removeObserver(observer)
            viewModel.onHomeStopped()
        }
    }

    var showPicker by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    AfterglowHomeContent(
        ui = ui,
        modifier = modifier,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onPick = { showPicker = true },
        onAddServer = onAddServer,
        onOpenConnections = onOpenConnections,
        onOpenDetails = { showDetails = true },
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenRouting = onOpenRouting,
        onOpenServers = onOpenServers,
        onOpenSubscriptions = onOpenSubscriptions,
        onOpenSettings = onOpenSettings,
        onDismissGuard = viewModel::dismissRestartGuardWarning,
        notices = homeNotices(ui, batteryHintVisible),
        onDismissSelection = viewModel::dismissSelectionNotice,
        onDismissBatteryHint = viewModel::dismissBatteryHint,
    )
    if (showPicker) {
        ServerPickerSheet(
            options = ui.serverOptions,
            selectedId = ui.selectedOptionId,
            onPick = { showPicker = false; viewModel.selectServer(it) },
            onOpenServers = { showPicker = false; onOpenServers() },
            onDismiss = { showPicker = false },
        )
    }
    if (showDetails && connection is VpnConnectionState.Connected) {
        SessionDetailsSheet(connection, ui.sessionDetails, onDismiss = { showDetails = false },
            onOpenDiagnostics = { showDetails = false; onOpenDiagnostics() })
    }
}

@Composable
internal fun errorText(error: VpnError): String =
    when (error) {
        VpnError.PermissionDenied -> stringResource(R.string.home_error_permission_denied)
        VpnError.PermissionRevoked -> stringResource(R.string.home_error_permission_revoked)
        VpnError.NoNodeSelected -> stringResource(R.string.home_error_no_node)
        VpnError.UnencryptedTransport -> stringResource(R.string.home_error_unencrypted)
        is VpnError.ConfigInvalid -> stringResource(R.string.home_error_config, error.detail)
        is VpnError.EngineFailed -> stringResource(R.string.home_error_engine, error.detail)
        is VpnError.TunnelFailed -> stringResource(R.string.home_error_tunnel, error.detail)
        is VpnError.Unexpected -> error.detail
    }

/**
 * Quick-pick bottom sheet: Auto first, then one card per provider, all behind a
 * search box. The Servers tab stays the place for filters, sorting and latency
 * tests. The list takes whatever height the sheet really has (so the IME shrinks
 * it) instead of a fixed cap that cut the last row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerPickerSheet(
    options: List<ServerOption>,
    selectedId: String?,
    onPick: (String) -> Unit,
    onOpenServers: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val auto = options.firstOrNull { it.id == NodeSelection.AUTO_ID }
    val autoTitle = auto?.titleRes?.let { stringResource(it) }
    val nodes = options.filter { it.id != NodeSelection.AUTO_ID }
    val trimmed = query.trim()
    val matches =
        if (trimmed.isEmpty()) {
            nodes
        } else {
            // searchText carries name + host for real nodes; the Auto row's
            // localized title is matched too so a translated label is found.
            nodes.filter { option ->
                option.searchText?.contains(trimmed, ignoreCase = true) == true ||
                    option.title?.contains(trimmed, ignoreCase = true) == true
            }
        }
    val autoMatches =
        trimmed.isEmpty() ||
            autoTitle?.contains(trimmed, ignoreCase = true) == true ||
            auto?.searchText?.contains(trimmed, ignoreCase = true) == true
    // Headers depend on how many providers exist, not on how many survive the search.
    val showHeaders = showProviderHeader(groupPickerOptions(nodes).size)
    val groups = groupPickerOptions(matches)

    val colors = AfterglowTheme.colors
    AfterglowSheet(onDismiss = onDismiss) {
        val sheetMax = LocalSheetMaxHeight.current
        Column(Modifier.then(if (sheetMax != null) Modifier.heightIn(max = sheetMax) else Modifier)) {
            AfterglowSheetHeader(
                title = stringResource(R.string.picker_title),
                closeLabel = stringResource(R.string.common_dismiss),
                onDismiss = onDismiss,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AfterglowTokens.sectionGap)
                        .heightIn(min = AfterglowTokens.fieldHeight),
                shape = AfterglowTokens.inputShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.coral,
                    unfocusedBorderColor = colors.borderStrong,
                    focusedContainerColor = colors.paperSecondary,
                    unfocusedContainerColor = colors.paperSecondary,
                    cursorColor = colors.coral,
                ),
                placeholder = { Text(stringResource(R.string.home_picker_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.common_clear_search),
                            )
                        }
                    }
                },
                singleLine = true,
            )
            // Outside the list: the escape hatch must stay reachable with the
            // soft keyboard open, which covers the bottom of the sheet.
            TextButton(
                onClick = onOpenServers,
                modifier = Modifier.padding(horizontal = AfterglowTokens.cardGap).heightIn(min = AfterglowTokens.touchTarget),
            ) {
                Text(stringResource(R.string.home_picker_all_servers), color = colors.coral)
            }
            // ponytail: a provider card composes all its rows at once; segment the rows if a single provider reaches hundreds.
            LazyColumn(
                Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(start = AfterglowTokens.screenPadding, end = AfterglowTokens.screenPadding, bottom = AfterglowTokens.cardGap),
                verticalArrangement = Arrangement.spacedBy(AfterglowTokens.cardGap),
            ) {
                // Auto is the picker's first-class choice — shown on an
                // empty query or when its own label/tag matches the search.
                if (autoMatches && auto != null) {
                    item(key = auto.id) {
                        ProviderGroupCard(header = null) {
                            ServerRow(
                                title = autoTitle.orEmpty(),
                                selected = auto.id == selectedId,
                                onClick = { onPick(auto.id) },
                                showLatency = false,
                                caption = auto.subtitleRes?.let { stringResource(it) },
                            )
                        }
                    }
                }
                items(groups, key = { "group-${it.id}" }) { group ->
                    val name = group.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.common_subscription_numbered, group.id ?: 0L)
                    ProviderGroupCard(
                        header = if (showHeaders) stringResource(R.string.picker_group_header, name, group.options.size) else null,
                    ) {
                        group.options.forEachIndexed { index, option ->
                            if (index > 0) ServerRowDivider()
                            PickerRow(option, selected = option.id == selectedId, onClick = { onPick(option.id) })
                        }
                    }
                }
                // A query that hits only the Auto row is still a match —
                // don't pair the result with a "no match" caption.
                if (matches.isEmpty() && !(autoMatches && auto != null)) {
                    item(key = "no-match") {
                        Text(
                            text = stringResource(R.string.home_picker_no_match, trimmed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = AfterglowTokens.cardGap),
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
    ServerRow(
        title = option.title.orEmpty(),
        selected = selected,
        onClick = onClick,
        protocol = option.protocol,
        latency = option.latency,
        unencryptedLabel =
            if (option.encrypted) {
                null
            } else {
                stringResource(if (option.tunnelAllowed) R.string.home_picker_unencrypted_short else R.string.servers_connection_blocked)
            },
    )
}
