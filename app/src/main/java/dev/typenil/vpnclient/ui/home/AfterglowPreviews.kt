package dev.typenil.vpnclient.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.core.vpn.VpnError
import dev.typenil.vpnclient.core.common.ThemeMode
import dev.typenil.vpnclient.ui.theme.VPNClientTheme
import java.time.Instant

// Preview-only example: never used as a production state or subscription fixture.
private val exampleNode = NodeSummary("preview", "Example server", ProtocolType.OTHER, "example.invalid")

@Composable
private fun PreviewHome(connection: VpnConnectionState, auto: Boolean = false, empty: Boolean = false, longName: Boolean = false) {
    AfterglowHomeContent(
        ui =
            HomeUiState(
                connection = connection,
                selectedNodeName = if (longName) "🌙 Amsterdam Premium Ultra Low Latency · Europe 04" else exampleNode.name,
                autoSelected = auto,
                noNodesAtAll = empty,
                serverOptions = if (empty) emptyList() else listOf(ServerOption(id = exampleNode.id, title = exampleNode.name)),
            ),
        onConnect = {},
        onDisconnect = {},
        onPick = {},
        onAddServer = {},
        onOpenConnections = {},
        onOpenDetails = {},
        onOpenDiagnostics = {},
        onOpenRouting = {},
        onDismissGuard = {},
        errorMessage = if (connection is VpnConnectionState.Error) "Example connection failure" else null,
    )
}

@Preview(name = "Afterglow Offline", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun OfflinePreview() = PreviewHome(VpnConnectionState.Idle)

@Preview(name = "Afterglow Connecting", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ConnectingPreview() = PreviewHome(VpnConnectionState.Connecting(exampleNode))

@Preview(name = "Afterglow Connected", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ConnectedPreview() = PreviewHome(VpnConnectionState.Connected(exampleNode, Instant.EPOCH, null))

@Preview(name = "Afterglow Reconnecting", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ReconnectingPreview() =
    PreviewHome(
        VpnConnectionState.Reconnecting(
            exampleNode,
            VpnConnectionState.Reconnecting.Reason.NetworkUnavailable,
            2,
        ),
    )

@Preview(name = "Afterglow Permission", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PermissionPreview() = PreviewHome(VpnConnectionState.PermissionRequired)

@Preview(name = "Afterglow Stopping", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun StoppingPreview() = PreviewHome(VpnConnectionState.Stopping)

@Preview(name = "Afterglow Error", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ErrorPreview() = PreviewHome(VpnConnectionState.Error(VpnError.PermissionDenied, exampleNode))

@Preview(name = "RU narrow auto", locale = "ru", showBackground = true, widthDp = 320, heightDp = 720, fontScale = 1.3f)
@Composable
private fun NarrowAutoPreview() = PreviewHome(VpnConnectionState.Idle, auto = true)

@Preview(name = "EN long name", locale = "en", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun LongNamePreview() = PreviewHome(VpnConnectionState.Idle, longName = true)

@Preview(name = "RU no servers", locale = "ru", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun EmptyPreview() = PreviewHome(VpnConnectionState.Idle, empty = true)

@Preview(name = "Afterglow Dark 360", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun DarkCompactPreview() {
    VPNClientTheme(themeMode = ThemeMode.Dark) { PreviewHome(VpnConnectionState.Idle) }
}
