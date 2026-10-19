package dev.typenil.vpnclient.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.core.vpn.VpnError

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

    var showDetails by remember { mutableStateOf(false) }
    AfterglowHomeContent(
        ui = ui,
        modifier = modifier,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
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
