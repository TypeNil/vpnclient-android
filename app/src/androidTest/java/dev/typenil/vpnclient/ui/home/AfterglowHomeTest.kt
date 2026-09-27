package dev.typenil.vpnclient.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.engine.TrafficStats
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.core.vpn.VpnError
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AfterglowHomeTest {
    @get:Rule val compose = createComposeRule()

    private val testOption = ServerOption(id = "node-1", title = "Test Server")
    private val testNode = NodeSummary("node-1", "Test Server", ProtocolType.VLESS, "1.1.1.1:443")

    @Test fun emptyHomeShowsAddServerCta() {
        var imports = 0
        compose.setContent {
            AfterglowHomeContent(
                ui = HomeUiState(serverOptions = emptyList()),
                onConnect = {},
                onDisconnect = {},
                onPick = {},
                onAddServer = { imports++ },
                onOpenConnections = {},
                onOpenDetails = {},
                onOpenDiagnostics = {},
                onOpenRouting = {},
                onDismissGuard = {},
                errorMessage = null,
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.common_add_server_cta)).performClick()
        compose.runOnIdle { assertEquals(1, imports) }
        compose.onNodeWithText(context.getString(R.string.afterglow_start)).assertDoesNotExist()
    }

    @Test fun populatedHomeShowsStartConnectionAndConnects() {
        var connects = 0
        compose.setContent {
            AfterglowHomeContent(
                ui = HomeUiState(
                    serverOptions = listOf(testOption),
                    selectedNodeName = testOption.title,
                ),
                onConnect = { connects++ },
                onDisconnect = {},
                onPick = {},
                onAddServer = {},
                onOpenConnections = {},
                onOpenDetails = {},
                onOpenDiagnostics = {},
                onOpenRouting = {},
                onDismissGuard = {},
                errorMessage = null,
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.afterglow_start)).performClick()
        compose.runOnIdle { assertEquals(1, connects) }
    }

    @Test fun failureWithServersOffersRetryWithoutClaimingConnected() {
        var retries = 0
        compose.setContent {
            AfterglowHomeContent(
                ui = HomeUiState(
                    connection = VpnConnectionState.Error(VpnError.PermissionDenied, testNode),
                    serverOptions = listOf(testOption),
                    selectedNodeName = testOption.title,
                ),
                onConnect = { retries++ },
                onDisconnect = {},
                onPick = {},
                onAddServer = {},
                onOpenConnections = {},
                onOpenDetails = {},
                onOpenDiagnostics = {},
                onOpenRouting = {},
                onDismissGuard = {},
                errorMessage = "VPN permission denied",
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.afterglow_retry)).assertExists().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("VPN permission denied").assertExists()
    }

    @Test fun connectedHomeInvokesOpenConnections() {
        var connectionsOpened = 0
        val connectedState = VpnConnectionState.Connected(
            node = testNode,
            since = Instant.now(),
            stats = TrafficStats(
                uplinkBytesPerSec = 1024,
                downlinkBytesPerSec = 2048,
                uplinkTotalBytes = 10000,
                downlinkTotalBytes = 20000,
                connectionsIn = 2,
                connectionsOut = 5,
                goroutines = 10,
                memoryBytes = 1000000,
            ),
            statsReceivedAtNanos = System.nanoTime(),
        )
        compose.setContent {
            AfterglowHomeContent(
                ui = HomeUiState(
                    connection = connectedState,
                    serverOptions = listOf(testOption),
                    selectedNodeName = testOption.title,
                ),
                onConnect = {},
                onDisconnect = {},
                onPick = {},
                onAddServer = {},
                onOpenConnections = { connectionsOpened++ },
                onOpenDetails = {},
                onOpenDiagnostics = {},
                onOpenRouting = {},
                onDismissGuard = {},
                errorMessage = null,
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.home_stat_connections)).performClick()
        compose.runOnIdle { assertEquals(1, connectionsOpened) }
    }
}
