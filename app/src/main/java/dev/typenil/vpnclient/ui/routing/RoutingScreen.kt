package dev.typenil.vpnclient.ui.routing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.engine.RouteMode

/**
 * Routing policy screen: RouteMode + per-app entry + LAN bypass. Everything
 * here is compiled into the tunnel at connect time, so changes on a live
 * session surface the reconnect prompt rather than pretending to apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingScreen(
    onBack: () -> Unit,
    onOpenAppFilter: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: RoutingViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(ui.reconnectRecommended) {
        if (ui.reconnectRecommended) {
            val result =
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.settings_snackbar_reconnect_message),
                    actionLabel = context.getString(R.string.settings_snackbar_reconnect_action),
                )
            if (result == SnackbarResult.ActionPerformed) viewModel.reconnect()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.routing_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
        ) {
            RouteModeRow(
                mode = ui.routeMode,
                applied = ui.appliedRouteMode,
                onSelect = viewModel::setRouteMode,
            )
            SwitchRow(
                title = stringResource(R.string.routing_bypass_lan),
                subtitle = stringResource(R.string.routing_bypass_lan_sub),
                checked = ui.bypassLan,
                onCheckedChange = viewModel::setBypassLan,
            )
            // Saved-vs-applied: the switch shows the stored value; a diverged
            // live session calls out the applied state honestly.
            if (ui.sessionActive && ui.appliedBypassLan != null &&
                ui.appliedBypassLan != ui.bypassLan
            ) {
                Text(
                    text =
                        stringResource(
                            if (ui.appliedBypassLan == true) {
                                R.string.routing_applied_on
                            } else {
                                R.string.routing_applied_off
                            },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAppFilter)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.common_per_app_vpn),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.settings_per_app_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Region/domain routing picker. When a live session runs a different mode
 *  the subtitle shows the applied one — pending change is honest, not hidden. */
@Composable
private fun RouteModeRow(
    mode: RouteMode,
    applied: RouteMode?,
    onSelect: (RouteMode) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { showDialog = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.common_routing_mode),
                style = MaterialTheme.typography.bodyLarge,
            )
            val subtitle =
                if (applied != null && applied != mode) {
                    stringResource(
                        R.string.routing_saved_applied,
                        routeModeLabel(mode),
                        routeModeLabel(applied),
                    )
                } else {
                    routeModeLabel(mode)
                }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.common_routing_mode)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    RouteMode.entries.forEach { option ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = option == mode,
                                        onClick = {
                                            onSelect(option)
                                            showDialog = false
                                        },
                                        role = Role.RadioButton,
                                    ).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == mode, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(
                                    routeModeLabel(option),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    routeModeSubtitle(option),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.routing_applies_next_connect),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun routeModeLabel(mode: RouteMode): String =
    stringResource(
        when (mode) {
            RouteMode.ALL -> R.string.route_mode_all
            RouteMode.BYPASS_RU -> R.string.route_mode_bypass_ru
            RouteMode.PROXY_BLOCKED -> R.string.route_mode_proxy_blocked
        },
    )

@Composable
private fun routeModeSubtitle(mode: RouteMode): String =
    stringResource(
        when (mode) {
            RouteMode.ALL -> R.string.route_mode_sub_all
            RouteMode.BYPASS_RU -> R.string.route_mode_sub_bypass_ru
            RouteMode.PROXY_BLOCKED -> R.string.route_mode_sub_proxy_blocked
        },
    )
