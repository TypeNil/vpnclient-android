package dev.typenil.vpnclient.ui.home

import dev.typenil.vpnclient.core.common.formatBytes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.engine.TrafficStats
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.vpn.UnderlyingTransport
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SessionDetailsSheetTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val node = NodeSummary("node-1", "Test Server", ProtocolType.VLESS, "1.1.1.1:443")

    private fun connected(stats: TrafficStats? = null, since: Instant = Instant.now()) =
        VpnConnectionState.Connected(node = node, since = since, stats = stats, statsReceivedAtNanos = System.nanoTime())

    private fun details(pending: List<Int> = emptyList(), lastError: String? = null) = SessionDetails(
        activeOutbound = null, applied = null, pendingReconnect = pending,
        underlay = UnderlyingTransport.WIFI, lastError = lastError,
    )

    private fun heading(text: String) = hasText(text.uppercase()) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test fun fourGroupsAndNoConnectionsLink() {
        compose.setContent { SessionDetailsSheet(connected(), details(), onDismiss = {}, onOpenDiagnostics = {}) }
        for (g in listOf(R.string.session_group_connection, R.string.session_group_traffic,
            R.string.session_group_routing, R.string.session_group_technical)) {
            compose.onNode(heading(context.getString(g))).performScrollTo().assertExists()
        }
        // Home's Connections row owns navigation; the sheet only reports counts.
        compose.onNodeWithText(context.getString(R.string.home_stat_connections)).assertDoesNotExist()
    }

    @Test fun uptimeIsOneLocalizedNode() {
        // Day branch stays stable for the whole run (a seconds value would race the 1 Hz ticker).
        val since = Instant.now().minusSeconds(2 * 86_400 + 4 * 3_600 + 30 * 60)
        compose.setContent { SessionDetailsSheet(connected(since = since), details(), onDismiss = {}, onOpenDiagnostics = {}) }
        compose.onNode(hasContentDescription(
            "${context.getString(R.string.home_details_uptime)}, ${context.getString(R.string.home_uptime_dhm, 2, 4, 30)}"))
            .performScrollTo().assertExists()
    }

    @Test fun trafficTotalsAndConnectionsReadFromFreshStats() {
        val stats = TrafficStats(0, 0, uplinkTotalBytes = 2048, downlinkTotalBytes = 4096, connectionsIn = 3, connectionsOut = 8, goroutines = 0, memoryBytes = 0)
        compose.setContent { SessionDetailsSheet(connected(stats), details(), onDismiss = {}, onOpenDiagnostics = {}) }
        compose.onNode(hasContentDescription(context.getString(R.string.home_stat_connections_desc, 3, 8))).performScrollTo().assertExists()
        compose.onNode(hasContentDescription("${context.getString(R.string.home_details_downloaded)}, ${context.resources.formatBytes(4096)}")).assertExists()
    }

    @Test fun missingStatsShowDashesInConnectionsRow() {
        compose.setContent { SessionDetailsSheet(connected(), details(), onDismiss = {}, onOpenDiagnostics = {}) }
        compose.onNode(hasContentDescription(context.getString(R.string.home_stat_connections_empty))).performScrollTo().assertExists()
    }

    @Test fun pendingChangesShowAsWarningNamingTheSettings() {
        compose.setContent {
            SessionDetailsSheet(connected(), details(pending = listOf(R.string.common_routing_mode)), onDismiss = {}, onOpenDiagnostics = {})
        }
        compose.onNode(hasText(context.getString(R.string.home_details_pending_notice, context.getString(R.string.common_routing_mode)),
            substring = true)).performScrollTo().assertExists()
    }

    @Test fun noPendingNoticeWhenNothingPending() {
        compose.setContent { SessionDetailsSheet(connected(), details(), onDismiss = {}, onOpenDiagnostics = {}) }
        compose.onNode(hasText(context.getString(R.string.home_details_pending_notice, "").trim(), substring = true)).assertDoesNotExist()
    }

    @Test fun lastErrorOffersDiagnostics() {
        var opened = 0
        compose.setContent {
            SessionDetailsSheet(connected(), details(lastError = "tunnel failed"), onDismiss = {}, onOpenDiagnostics = { opened++ })
        }
        compose.onNode(hasContentDescription("${context.getString(R.string.common_last_error)}, tunnel failed")).performScrollTo().assertExists()
        compose.onNodeWithText(context.getString(R.string.afterglow_open_diagnostics)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun noLastErrorNoDiagnosticsRow() {
        compose.setContent { SessionDetailsSheet(connected(), details(), onDismiss = {}, onOpenDiagnostics = {}) }
        compose.onNodeWithText(context.getString(R.string.afterglow_open_diagnostics)).assertDoesNotExist()
        compose.onNode(hasContentDescription(context.getString(R.string.common_last_error), substring = true)).assertDoesNotExist()
    }
}
