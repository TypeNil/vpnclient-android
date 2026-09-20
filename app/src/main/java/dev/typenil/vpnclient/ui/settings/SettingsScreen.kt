package dev.typenil.vpnclient.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Pinned in gradle/libs.versions.toml (`vpnCore`). */
private const val CORE_VERSION = "sing-box libbox 1.14.1"

@Composable
fun SettingsScreen(
    onOpenAppFilter: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

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
            modifier = Modifier.fillMaxWidth(),
        )
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
