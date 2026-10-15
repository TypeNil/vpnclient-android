package dev.typenil.vpnclient.ui.home

import dev.typenil.vpnclient.core.engine.TrafficStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDetailsModelTest {
    private val labels = DetailsLabels(
        server = "Server", outbound = "Outbound", network = "Network", uptime = "Uptime",
        downloaded = "Downloaded", uploaded = "Uploaded", activeConnections = "Active connections",
        routingMode = "Routing mode", perApp = "Per-app VPN", dns = "DNS upstream",
        vpnCore = "VPN core", lastError = "Last error",
    )

    private fun input(
        server: String = "Demo Frankfurt",
        outbound: String? = null,
        network: String? = "Wi-Fi",
        stats: TrafficStats? = null,
        pending: List<String> = emptyList(),
        lastError: String? = null,
        dns: String? = null,
    ) = SessionDetailsInput(
        server = server, outbound = outbound, network = network, uptime = "5 min 3 s", stats = stats,
        routeMode = "All traffic", perApp = "All apps", dns = dns, pendingLabels = pending,
        vpnCore = "1.2.3", lastError = lastError, labels = labels, bytes = { "$it B" },
        connectionsTexts = ConnectionsTexts({ i, o -> "$i in, $o out" }, "no data", "—"), dash = "—",
    )

    private val stats = TrafficStats(1, 2, uplinkTotalBytes = 300, downlinkTotalBytes = 4000, connectionsIn = 7, connectionsOut = 9, goroutines = 0, memoryBytes = 0)

    @Test fun connectionGroupOrderIsServerNetworkUptime() {
        val m = sessionDetailsModel(input())
        assertEquals(listOf("Server", "Network", "Uptime"), m.connection.map { it.label })
        assertEquals("Wi-Fi", m.connection[1].value)
    }

    @Test fun outboundHiddenWhenUnknownOrSameAsServer() {
        assertFalse(sessionDetailsModel(input(outbound = null)).connection.any { it.label == "Outbound" })
        assertFalse(sessionDetailsModel(input(outbound = "Demo Frankfurt")).connection.any { it.label == "Outbound" })
    }

    @Test fun outboundShownWhenItDiffersFromServer() {
        val m = sessionDetailsModel(input(outbound = "Demo Berlin"))
        assertEquals(listOf("Server", "Outbound", "Network", "Uptime"), m.connection.map { it.label })
        assertEquals("Demo Berlin", m.connection[1].value)
    }

    @Test fun unknownNetworkIsDashNotBlank() {
        assertEquals("—", sessionDetailsModel(input(network = null)).connection.first { it.label == "Network" }.value)
    }

    @Test fun trafficShowsTotalsAndDirectionalConnections() {
        val m = sessionDetailsModel(input(stats = stats))
        assertEquals("4000 B", m.downloaded.value); assertEquals("300 B", m.uploaded.value)
        assertEquals("7", m.activeConnections.incoming); assertEquals("9", m.activeConnections.outgoing)
        assertEquals("7 in, 9 out", m.activeConnections.description)
    }

    @Test fun missingStatsShowDashesNeverZeros() {
        val m = sessionDetailsModel(input(stats = null))
        for (v in listOf(m.downloaded.value, m.uploaded.value, m.activeConnections.incoming, m.activeConnections.outgoing))
            assertEquals("—", v)
    }

    @Test fun routingGroupAlwaysHasThreeLinesWithDashForMissing() {
        val m = sessionDetailsModel(input(dns = null))
        assertEquals(listOf("Routing mode", "Per-app VPN", "DNS upstream"), m.routing.map { it.label })
        assertEquals("—", m.routing[2].value)
    }

    @Test fun pendingLabelsPassThroughOnlyWhenPresent() {
        assertTrue(sessionDetailsModel(input()).pending.isEmpty())
        assertEquals(listOf("Routing mode"), sessionDetailsModel(input(pending = listOf("Routing mode"))).pending)
    }

    @Test fun lastErrorLineAndDiagnosticsOnlyWithError() {
        val ok = sessionDetailsModel(input())
        assertEquals(listOf("VPN core"), ok.technical.map { it.label }); assertFalse(ok.showDiagnostics)
        val blank = sessionDetailsModel(input(lastError = "  "))
        assertFalse(blank.showDiagnostics)
        val bad = sessionDetailsModel(input(lastError = "tunnel failed"))
        assertEquals(listOf("VPN core", "Last error"), bad.technical.map { it.label }); assertTrue(bad.showDiagnostics)
    }

    @Test fun lineIsOneTalkBackNodeAndLongValuesStack() {
        val line = DetailLine("DNS upstream", "Through the proxy · Cloudflare")
        assertEquals("DNS upstream, Through the proxy · Cloudflare", line.description)
        assertTrue(line.stacked(largeFont = false))
        assertFalse(DetailLine("Uptime", "5 min").stacked(largeFont = false))
        assertTrue(DetailLine("Uptime", "5 min").stacked(largeFont = true))
    }
}
