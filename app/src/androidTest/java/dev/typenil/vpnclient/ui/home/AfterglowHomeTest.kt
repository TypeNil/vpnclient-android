package dev.typenil.vpnclient.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.ui.theme.AfterglowNotice
import dev.typenil.vpnclient.ui.theme.NoticeTone
import dev.typenil.vpnclient.core.common.formatBytes
import dev.typenil.vpnclient.core.common.formatRate
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

    private val testNode = NodeSummary("node-1", "Test Server", ProtocolType.VLESS, "1.1.1.1:443")

    /** The hero headline is the Polite live region (67abd85 replaced the old stateDescription). */
    private fun heroHeadline(text: String) = hasText(text) and
        SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test fun emptyHomeShowsAddServerCta() {
        var imports = 0
        compose.setContent {
            AfterglowHomeContent(
                ui = HomeUiState(),
                onConnect = {},
                onDisconnect = {},
                                onAddServer = { imports++ },
                onOpenConnections = {},
                onOpenDetails = {},
                onOpenDiagnostics = {},
                onOpenRouting = {},
                onDismissGuard = {},
                notices = emptyList(),
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
                    noNodesAtAll = false, serverCount = 1,
                    selectedNodeName = "Test Server",
                ),
                onConnect = { connects++ },
                onDisconnect = {},
                                onAddServer = {},
                onOpenConnections = {},
                onOpenDetails = {},
                onOpenDiagnostics = {},
                onOpenRouting = {},
                onDismissGuard = {},
                notices = emptyList(),
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
                    noNodesAtAll = false, serverCount = 1,
                    selectedNodeName = "Test Server",
                ),
                onConnect = { retries++ },
                onDisconnect = {},
                                onAddServer = {},
                onOpenConnections = {},
                onOpenDetails = {},
                onOpenDiagnostics = {},
                onOpenRouting = {},
                onDismissGuard = {},
                notices = listOf(HomeNotice.Failure(VpnError.PermissionDenied)),
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.afterglow_retry)).assertExists().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText(context.getString(R.string.home_error_permission_denied)).assertExists()
    }

    @Test fun connectedHomeShowsTwoColumnsAndOpensDetails() {
        var detailsOpened = 0
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
                    noNodesAtAll = false, serverCount = 1,
                    selectedNodeName = "Test Server",
                ),
                onConnect = {},
                onDisconnect = {},
                                onAddServer = {},
                onOpenConnections = { connectionsOpened++ },
                onOpenDetails = { detailsOpened++ },
                onOpenDiagnostics = {},
                onOpenRouting = {},
                onDismissGuard = {},
                notices = emptyList(),
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Stats sit below the server/routing card — scroll them into view. No
        // toggle: both columns are always visible, one announced node each.
        compose.onNode(hasContentDescription(
            context.getString(R.string.home_stat_column_desc, context.getString(R.string.home_stat_download),
                context.resources.formatRate(2048), context.resources.formatBytes(20000))))
            .performScrollTo().assertExists()
        compose.onNode(hasContentDescription(
            context.getString(R.string.home_stat_column_desc, context.getString(R.string.home_stat_upload),
                context.resources.formatRate(1024), context.resources.formatBytes(10000))))
            .assertExists()
        // The Connections row is one announced node and opens the Connections screen.
        compose.onNode(hasContentDescription(context.getString(R.string.home_stat_connections_desc, 2, 5)))
            .performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, connectionsOpened) }
        compose.onNodeWithText(context.getString(R.string.home_details_title))
            .performScrollTo()
            .performClick()
        compose.runOnIdle { assertEquals(1, detailsOpened) }
    }

    @Test fun staleOrMissingStatsShowNoDataColumnsNotZeroRates() {
        compose.setContent {
            AfterglowHomeContent(
                ui = HomeUiState(
                    connection = VpnConnectionState.Connected(
                        node = testNode, since = Instant.now(), stats = null, statsReceivedAtNanos = System.nanoTime(),
                    ),
                    noNodesAtAll = false, serverCount = 1,
                    selectedNodeName = "Test Server",
                ),
                onConnect = {}, onDisconnect = {}, onAddServer = {}, onOpenConnections = {}, onOpenDetails = {},
                onOpenDiagnostics = {}, onOpenRouting = {}, onDismissGuard = {}, notices = emptyList(),
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNode(hasContentDescription(
            context.getString(R.string.home_stat_column_empty, context.getString(R.string.home_stat_download))))
            .performScrollTo().assertExists()
        compose.onNode(hasContentDescription(
            context.getString(R.string.home_stat_column_empty, context.getString(R.string.home_stat_upload))))
            .assertExists()
        compose.onNode(hasContentDescription(context.getString(R.string.home_stat_connections_empty)))
            .assertExists()
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
                    noNodesAtAll = false, serverCount = 1,
                    selectedNodeName = "Test Server",
                ),
                onConnect = {},
                onDisconnect = {},
                                onAddServer = {},
                onOpenConnections = {},
                onOpenDetails = {},
                onOpenDiagnostics = {},
                onOpenRouting = {},
                onDismissGuard = {},
                notices = emptyList(),
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
                        noNodesAtAll = false, serverCount = 1,
                        selectedNodeName = "An extremely long node display name that keeps going",
                    ),
                    onConnect = {},
                    onDisconnect = { disconnects++ },
                                        onAddServer = {},
                    onOpenConnections = {},
                    onOpenDetails = {},
                    onOpenDiagnostics = {},
                    onOpenRouting = {},
                    onDismissGuard = {},
                    notices = emptyList(),
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.action_disconnect)).performClick()
        compose.runOnIdle { assertEquals(1, disconnects) }
        compose.onNodeWithText(context.getString(R.string.home_details_title))
            .performScrollTo()
            .assertExists()
    }

    @Test fun autoKeepsFallbackUntilLiveMemberResolves() {
        var picks = 0
        val ui = mutableStateOf(HomeUiState(
            autoSelected = true,
            noNodesAtAll = false,
            serverCount = 1,
        ))
        compose.setContent {
            AfterglowHomeContent(
                ui = ui.value,
                onConnect = {}, onDisconnect = {}, onOpenServers = { picks++ },
                onAddServer = {}, onOpenConnections = {}, onOpenDetails = {},
                onOpenDiagnostics = {}, onOpenRouting = {}, onDismissGuard = {},
                notices = emptyList(),
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.home_auto_subtitle)).assertExists()
        compose.onNode(heroHeadline(context.getString(R.string.home_status_disconnected)))
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
        compose.onNode(heroHeadline(context.getString(R.string.home_status_connected)))
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

    private fun homeWith(connection: VpnConnectionState, session: dev.typenil.vpnclient.core.vpn.LastSession?) {
        compose.setContent {
            AfterglowHomeContent(
                ui = HomeUiState(
                    connection = connection,
                    noNodesAtAll = false, serverCount = 1,
                    selectedNodeName = "Test Server",
                    lastSession = session,
                ),
                onConnect = {}, onDisconnect = {}, onAddServer = {},
                onOpenConnections = {}, onOpenDetails = {}, onOpenDiagnostics = {}, onOpenRouting = {},
                onDismissGuard = {}, notices = emptyList(),
            )
        }
    }

    private val lastSession = dev.typenil.vpnclient.core.vpn.LastSession(
        endedAtEpochMs = System.currentTimeMillis(), durationMs = 125_000L,
        downBytes = 2_048L, upBytes = 512L, nodeName = "Test Server",
    )

    @Test fun lastSessionCardShowsWhenIdleWithRecord() {
        homeWith(VpnConnectionState.Idle, lastSession)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.home_last_session_title).uppercase()).performScrollTo().assertExists()
        compose.onNode(hasContentDescription("${context.getString(R.string.home_last_session_downloaded)}, ${context.resources.formatBytes(2_048L)}")).assertExists()
        compose.onNode(hasContentDescription("${context.getString(R.string.home_last_session_uploaded)}, ${context.resources.formatBytes(512L)}")).assertExists()
        compose.onNodeWithText(context.getString(R.string.home_last_session_ended, context.getString(R.string.relative_now))).assertExists()
    }

    @Test fun lastSessionCardHiddenWithoutRecordAndWhileConnected() {
        homeWith(VpnConnectionState.Idle, null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.home_last_session_title).uppercase()
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    @Test fun lastSessionCardHiddenWhileConnected() {
        homeWith(VpnConnectionState.Connected(testNode, Instant.now(), null), lastSession)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.home_last_session_title).uppercase()).assertDoesNotExist()
    }

    @Test fun connectionsArrowsScaleWithFontScale() {
        val width = mutableStateOf(1f)
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = width.value)) {
                AfterglowHomeContent(
                    ui = HomeUiState(
                        connection = VpnConnectionState.Connected(testNode, Instant.now(), null),
                        noNodesAtAll = false, serverCount = 1, selectedNodeName = "Test Server",
                    ),
                    onConnect = {}, onDisconnect = {}, onAddServer = {},
                    onOpenConnections = {}, onOpenDetails = {}, onOpenDiagnostics = {}, onOpenRouting = {},
                    onDismissGuard = {}, notices = emptyList(),
                )
            }
        }
        fun arrowWidth() = compose.onNodeWithTag(CONN_ARROW_DOWN_TAG, useUnmergedTree = true).performScrollTo()
            .fetchSemanticsNode().size.width
        val normal = arrowWidth()
        width.value = 2f
        compose.waitForIdle()
        val large = arrowWidth()
        // sp-based: at 2.0 the arrow is clearly larger than at 1.0 (fixed dp would be equal).
        assert(large >= normal * 3 / 2) { "arrow $normal px -> $large px at font 2.0" }
    }

    @Test fun noticeActionAlignsToTextLeftEdge() {
        compose.setContent {
            AfterglowNotice(NoticeTone.Warning, "Body text", title = "Title text", actionLabel = "Do it", onAction = {})
        }
        fun left(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        assertEquals(left("Body text"), left("Do it"), 1f)
        assertEquals(left("Title text"), left("Do it"), 1f)
    }
}
