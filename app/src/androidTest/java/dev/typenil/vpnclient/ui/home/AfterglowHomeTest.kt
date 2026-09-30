package dev.typenil.vpnclient.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.engine.TrafficStats
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
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
        // Session stats now sit below the server/routing card — a legitimate
        // scroll brings the row into view before tapping it.
        compose.onNodeWithText(context.getString(R.string.home_stat_connections))
            .performScrollTo()
            .performClick()
        compose.runOnIdle { assertEquals(1, connectionsOpened) }
    }

    @Test fun connectedHomeShowsDayScaleUptime() {
        // 2 d 4 h 30 min 50 s of session — the uptime must render as explicit
        // localized units (days branch), never a "52:30:50" clock.
        // Exact minute boundary: the visible "30 min" can't roll to 31
        // within the test's runtime.
        val connectedState = VpnConnectionState.Connected(
            node = testNode,
            since = Instant.now().minusSeconds(2 * 86_400 + 4 * 3_600 + 30 * 60),
            stats = null,
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
                onOpenConnections = {},
                onOpenDetails = {},
                onOpenDiagnostics = {},
                onOpenRouting = {},
                onDismissGuard = {},
                errorMessage = null,
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = context.getString(
            R.string.afterglow_session_elapsed,
            context.getString(R.string.home_uptime_dhm, 2, 4, 30),
        )
        compose.onNodeWithText(expected).assertExists()
    }

    @Test fun largeFontHomeKeepsDisconnectCtaUsable() {
        var disconnects = 0
        val connectedState = VpnConnectionState.Connected(
            node = testNode,
            since = Instant.now().minusSeconds(65),
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
            // Simulate OS font-scale 1.6 — keep the device density (the real
            // device is 1440px/density-640) and override fontScale only, so
            // the layout matches a real large-font user on this hardware.
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(deviceDensity, fontScale = 1.6f),
            ) {
                AfterglowHomeContent(
                    ui = HomeUiState(
                        connection = connectedState,
                        serverOptions = listOf(testOption),
                        selectedNodeName = "An extremely long node display name that keeps going",
                    ),
                    onConnect = {},
                    onDisconnect = { disconnects++ },
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
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.action_disconnect)).performClick()
        compose.runOnIdle { assertEquals(1, disconnects) }
        compose.onNodeWithText(context.getString(R.string.home_stat_connections))
            .performScrollTo()
            .assertExists()
    }

    @Test fun autoKeepsFallbackUntilLiveMemberResolves() {
        var picks = 0
        val ui = mutableStateOf(HomeUiState(
            autoSelected = true,
            noNodesAtAll = false,
            serverOptions = listOf(ServerOption(
                id = NodeSelection.AUTO_ID,
                titleRes = R.string.common_auto_fastest,
            ), testOption),
        ))
        compose.setContent {
            AfterglowHomeContent(
                ui = ui.value,
                onConnect = {}, onDisconnect = {}, onPick = { picks++ },
                onAddServer = {}, onOpenConnections = {}, onOpenDetails = {},
                onOpenDiagnostics = {}, onOpenRouting = {}, onDismissGuard = {},
                errorMessage = null,
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.home_auto_subtitle)).assertExists()
        compose.onNode(hasStateDescription(context.getString(R.string.home_status_disconnected)))
            .assertExists()
        compose.runOnIdle {
            ui.value = ui.value.copy(bestLatencyNodeName = "Measured Candidate", selectedDelayMs = 42)
        }
        val measured = context.getString(R.string.afterglow_auto_best_latency, "Measured Candidate", 42)
        compose.onNodeWithText(measured).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.home_auto_subtitle)).assertExists()
        compose.runOnIdle {
            ui.value = ui.value.copy(connection = VpnConnectionState.Connecting(testNode))
        }
        compose.onNodeWithText(measured).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.home_auto_subtitle)).assertExists()
        compose.runOnIdle {
            ui.value = ui.value.copy(connection = VpnConnectionState.Connected(
                node = testNode.copy(name = "Auto → Live Member"),
                since = Instant.now(),
                stats = null,
                statsReceivedAtNanos = System.nanoTime(),
            ))
        }
        compose.onNodeWithText(context.getString(R.string.afterglow_auto)).assertExists()
        compose.onNodeWithText("Live Member").performScrollTo().performClick()
        compose.onNodeWithText(measured).assertDoesNotExist()
        compose.onNode(hasStateDescription(context.getString(R.string.home_status_connected)))
            .assertExists()
        compose.runOnIdle { assertEquals(1, picks) }
        compose.runOnIdle {
            ui.value = ui.value.copy(autoSelected = false, selectedNodeName = "Manual Member",
                selectedNodeProtocol = "VLESS", subscriptionName = "Test Provider")
        }
        compose.onNodeWithText("Manual Member").assertExists()
        compose.onNodeWithText("VLESS").assertExists()
        compose.onNodeWithText("Test Provider").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.afterglow_auto)).assertDoesNotExist()
    }

    @Test fun pickerQueryMatchingAutoDoesNotShowNoMatch() {
        compose.setContent {
            ServerPickerSheet(
                options = listOf(
                    ServerOption(
                        id = NodeSelection.AUTO_ID,
                        titleRes = R.string.common_auto_fastest,
                        subtitleRes = R.string.home_auto_subtitle,
                        searchText = NodeSelection.AUTO_ID,
                    ),
                    ServerOption(id = "node-1", title = "Test Server", searchText = "Test Server 1.1.1.1"),
                ),
                selectedId = null,
                onPick = {},
                onOpenServers = {},
                onDismiss = {},
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNode(hasSetTextAction()).performTextInput("auto")
        compose.onNodeWithText(context.getString(R.string.common_auto_fastest)).assertExists()
        compose.onNodeWithText(context.getString(R.string.home_picker_no_match, "auto"))
            .assertDoesNotExist()
    }

    @Test fun sessionDetailsShowsLocalizedUptime() {
        // Day branch is stable for the whole test run — a "1 min 5 s" value
        // would race the live 1 Hz ticker.
        compose.setContent {
            SessionDetailsSheet(
                state = VpnConnectionState.Connected(
                    node = testNode,
                    since = Instant.now().minusSeconds(2 * 86_400 + 4 * 3_600 + 30 * 60),
                    stats = null,
                    statsReceivedAtNanos = System.nanoTime(),
                ),
                details = null,
                onDismiss = {},
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.home_details_uptime)).assertExists()
        compose.onNodeWithText(context.getString(R.string.home_uptime_dhm, 2, 4, 30)).assertExists()
    }
}
