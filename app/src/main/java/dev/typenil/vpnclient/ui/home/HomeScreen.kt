package dev.typenil.vpnclient.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.typenil.vpnclient.core.engine.TrafficStats
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.core.vpn.VpnError
import dev.typenil.vpnclient.ui.common.formatBytes
import dev.typenil.vpnclient.ui.common.formatRate

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenConnections: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val connection = ui.connection

    Column(
        modifier = modifier
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
            color = when (connection) {
                is VpnConnectionState.Connected -> MaterialTheme.colorScheme.primary
                is VpnConnectionState.Error -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))

        NodeCard(ui = ui)

        if (ui.restartGuardTripped) {
            Spacer(Modifier.height(12.dp))
            RestartGuardCard(onDismiss = viewModel::dismissRestartGuardWarning)
        }

        if (connection is VpnConnectionState.Error) {
            Spacer(Modifier.height(12.dp))
            ErrorCard(connection.error)
        }

        if (connection is VpnConnectionState.Connected) {
            connection.stats?.let { stats ->
                Spacer(Modifier.height(12.dp))
                StatsCard(stats, onOpenConnections)
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

private fun statusText(state: VpnConnectionState): String = when (state) {
    VpnConnectionState.Idle -> "Disconnected"
    is VpnConnectionState.Preparing -> "Preparing…"
    VpnConnectionState.PermissionRequired -> "Awaiting VPN permission…"
    is VpnConnectionState.Connecting -> "Connecting…"
    is VpnConnectionState.Connected -> "Connected"
    is VpnConnectionState.Reconnecting -> "Reconnecting… (attempt ${state.attempt})"
    VpnConnectionState.Stopping -> "Disconnecting…"
    is VpnConnectionState.Error -> "Connection failed"
}

private fun errorText(error: VpnError): String = when (error) {
    VpnError.PermissionDenied -> "VPN permission denied"
    VpnError.PermissionRevoked -> "VPN permission was revoked"
    VpnError.NoNodeSelected -> "No server selected — pick one in Servers"
    is VpnError.ConfigInvalid -> "Invalid configuration: ${error.detail}"
    is VpnError.EngineFailed -> "VPN core failed: ${error.detail}"
    is VpnError.TunnelFailed -> "Tunnel failed: ${error.detail}"
    is VpnError.Unexpected -> error.detail
}

private fun protocolLabel(protocol: String): String =
    runCatching { ProtocolType.valueOf(protocol) }.getOrNull()?.label ?: protocol

@Composable
private fun NodeCard(ui: HomeUiState) {
    val state = ui.connection
    // While a connection is (being) established, show the node it uses;
    // otherwise show the currently selected node.
    val name: String?
    val protocol: String?
    val server: String?
    when (state) {
        is VpnConnectionState.Connected -> {
            name = state.node.name
            protocol = state.node.protocol.label
            server = state.node.server
        }
        is VpnConnectionState.Connecting -> {
            name = state.node.name
            protocol = state.node.protocol.label
            server = state.node.server
        }
        is VpnConnectionState.Reconnecting -> {
            name = state.node.name
            protocol = state.node.protocol.label
            server = state.node.server
        }
        is VpnConnectionState.Preparing -> {
            name = state.node.name
            protocol = state.node.protocol.label
            server = state.node.server
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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
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
        colors = CardDefaults.cardColors(
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
        colors = CardDefaults.cardColors(
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
private fun StatsCard(stats: TrafficStats, onOpenConnections: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatRow("Download", "${formatRate(stats.downlinkBytesPerSec)}  (${formatBytes(stats.downlinkTotalBytes)} total)")
            StatRow("Upload", "${formatRate(stats.uplinkBytesPerSec)}  (${formatBytes(stats.uplinkTotalBytes)} total)")
            StatRow(
                label = "Connections",
                value = "${stats.connectionsIn} in / ${stats.connectionsOut} out",
                // clickable before padding — the padded area stays tappable.
                modifier = Modifier
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
        }
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
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
