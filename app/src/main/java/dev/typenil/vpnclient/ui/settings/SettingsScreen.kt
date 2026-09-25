package dev.typenil.vpnclient.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.ui.common.CORE_VERSION

@Composable
fun SettingsScreen(
    onOpenAppFilter: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Pending-state prompt: shows once per recommendation, re-shows after a
    // process/config change only if the flag flips back to true.
    LaunchedEffect(ui.reconnectRecommended) {
        if (ui.reconnectRecommended) {
            val result = snackbarHostState.showSnackbar(
                message = "Reconnect the VPN to apply the new settings",
                actionLabel = "Reconnect",
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.reconnect()
        }
    }


    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SwitchRow(
            title = "Reconnect on network change",
            subtitle = "Restart the tunnel when connectivity changes",
            checked = ui.reconnectOnNetworkChange,
            onCheckedChange = viewModel::setReconnectOnNetworkChange,
        )
        SwitchRow(
            title = "IPv6",
            subtitle = "Route IPv6 traffic through the tunnel",
            checked = ui.ipv6Enabled,
            onCheckedChange = viewModel::setIpv6Enabled,
        )
        SwitchRow(
            title = "Doze power save",
            subtitle = "Pause the core when the device idles — drops open connections",
            checked = ui.dozePowerSave,
            onCheckedChange = viewModel::setDozePowerSave,
        )
        SwitchRow(
            title = "Connect on launch",
            subtitle = "Start the VPN when the app opens",
            checked = ui.autoConnectOnLaunch,
            onCheckedChange = viewModel::setAutoConnectOnLaunch,
        )
        RouteModeRow(
            mode = ui.routeMode,
            onSelect = viewModel::setRouteMode,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenAppFilter)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Per-app VPN", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Choose which apps use the tunnel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AlwaysOnRow()
        SwitchRow(
            title = "Auto-refresh subscriptions",
            subtitle = if (ui.autoRefreshEnabled) {
                if (ui.autoRefreshMinutes > 0) {
                    // Periodic work can't run faster than the platform floor.
                    "Every ${maxOf(ui.autoRefreshMinutes, 15)} min"
                } else {
                    "Using each provider's update interval"
                }
            } else {
                "Manual refresh only"
            },
            checked = ui.autoRefreshEnabled,
            onCheckedChange = viewModel::setAutoRefreshEnabled,
        )
        if (ui.autoRefreshEnabled) {
            AutoRefreshIntervalRow(
                minutes = ui.autoRefreshMinutes,
                onMinutes = viewModel::setAutoRefreshMinutes,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        NotificationPermissionRow()

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        InfoRow(title = "VPN core", value = CORE_VERSION)
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
        modifier = Modifier
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

/** Region/domain routing picker. The mode is baked into the engine config
 *  at compile time — like per-app, a running tunnel keeps its old mode. */
@Composable
private fun RouteModeRow(mode: RouteMode, onSelect: (RouteMode) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Routing mode", style = MaterialTheme.typography.bodyLarge)
            Text(
                routeModeLabel(mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Routing mode") },
            text = {
                Column(Modifier.selectableGroup()) {
                    RouteMode.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = option == mode,
                                    onClick = {
                                        onSelect(option)
                                        showDialog = false
                                    },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 6.dp),
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
                        "Applies on next connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
        )
    }
}

private fun routeModeLabel(mode: RouteMode): String = when (mode) {
    RouteMode.ALL -> "Proxy everything"
    RouteMode.BYPASS_RU -> "Bypass Russian resources"
    RouteMode.PROXY_BLOCKED -> "Only blocked services"
}

private fun routeModeSubtitle(mode: RouteMode): String = when (mode) {
    RouteMode.ALL -> "All traffic goes through the selected server"
    RouteMode.BYPASS_RU -> "Russian sites and IPs go direct, the rest is proxied"
    RouteMode.PROXY_BLOCKED -> "Blocked services use the proxy, the rest goes direct"
}

/** Optional user interval override; empty field = follow the provider hint.
 *  Committed on IME Done — per-keystroke writes would re-enqueue all work. */
@Composable
private fun AutoRefreshIntervalRow(
    minutes: Int,
    onMinutes: (Int) -> Unit,
) {
    var text by remember {
        mutableStateOf(TextFieldValue(if (minutes > 0) minutes.toString() else ""))
    }
    var focused by remember { mutableStateOf(false) }
    // Adopt the persisted value once it loads — but never while the user is
    // typing, so an in-progress edit isn't clobbered by a DataStore round-trip.
    LaunchedEffect(minutes, focused) {
        if (!focused) {
            val persisted = if (minutes > 0) minutes.toString() else ""
            if (text.text != persisted) text = TextFieldValue(persisted)
        }
    }
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                val digits = input.text.filter { it.isDigit() }
                text = input.copy(text = digits)
            },
            label = { Text("Interval override (minutes, min 15)") },
            placeholder = { Text("provider") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            keyboardActions = KeyboardActions(
                onDone = {
                    onMinutes(text.text.toIntOrNull() ?: 0)
                    focusManager.clearFocus()
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
        )
    }
}

/**
 * Real always-on/kill-switch status (VpnService.isAlwaysOn /
 * isLockdownEnabled on API 29+), refreshed whenever the screen resumes —
 * the user configures it in system VPN settings, so returning from that
 * activity must show the new value. Click still opens the system screen;
 * apps can't toggle these flags themselves.
 */
@Composable
private fun AlwaysOnRow() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(readVpnSystemStatus(context)) }

    // Re-read when returning from the system VPN settings screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = readVpnSystemStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val subtitle = when {
        !status.available -> "Configure in system VPN settings"
        else -> buildString {
            append("Always-on: ")
            append(if (status.alwaysOn == true) "enabled" else "disabled")
            append(" · Kill switch: ")
            // Lockdown is only meaningful when always-on is set — the system
            // reports its raw flag, so surface what it actually says.
            append(if (status.lockdownEnabled == true) "enabled" else "disabled")
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // Apps can't enable lockdown themselves — the system VPN
                // settings screen is the only supported path.
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                }.onFailure {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Always-on & kill switch", style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** POST_NOTIFICATIONS is a runtime permission on API 33+. */
@Composable
private fun NotificationPermissionRow() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    fun isGranted() = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(isGranted()) }

    // Re-check when returning from the system permission dialog/settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = isGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result -> granted = result }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Notifications", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (granted) "Granted" else "Not granted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!granted) {
            TextButton(
                onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            ) {
                Text("Allow")
            }
        }
    }
}
