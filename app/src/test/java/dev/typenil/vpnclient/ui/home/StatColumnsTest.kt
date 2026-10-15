package dev.typenil.vpnclient.ui.home

import dev.typenil.vpnclient.core.common.formatBytes
import dev.typenil.vpnclient.core.common.formatRate
import dev.typenil.vpnclient.core.engine.TrafficStats
import androidx.compose.ui.text.font.FontWeight
import dev.typenil.vpnclient.ui.theme.AfterglowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class StatColumnsTest {
    private val units = listOf("B", "KB", "MB", "GB", "TB")
    private val texts = StatTexts(
        downloadLabel = "Download", uploadLabel = "Upload",
        rate = { formatRate(it, units, "%1\$s/s", Locale.US) },
        bytes = { formatBytes(it, units, Locale.US) },
        totalLine = { "$it total" },
        describe = { l, r, t -> "$l, $r, total $t" },
        describeEmpty = { "$it, no data yet" },
        dash = "—",
    )

    private fun stats(up: Long, down: Long, upTotal: Long, downTotal: Long) =
        TrafficStats(up, down, upTotal, downTotal, 0, 0, 0, 0)

    @Test fun downloadFirstThenUpload() {
        val (d, u) = statColumns(stats(up = 2048, down = 1024, upTotal = 10, downTotal = 20), texts)
        assertTrue(d.download); assertFalse(u.download)
        assertEquals("Download", d.label); assertEquals("Upload", u.label)
        assertEquals("1.0 KB/s", d.value); assertEquals("2.0 KB/s", u.value)
        assertEquals("20 B total", d.total); assertEquals("10 B total", u.total)
    }

    @Test fun descriptionNamesDirectionRateAndTotal() {
        val (d, _) = statColumns(stats(0, 1536, 0, 5L * 1024 * 1024), texts)
        assertEquals("Download, 1.5 KB/s, total 5.0 MB", d.description)
    }

    @Test fun missingStatsShowDashNotZero() {
        val (d, u) = statColumns(null, texts)
        for (c in listOf(d, u)) {
            assertEquals("—", c.value); assertEquals("—", c.total)
            assertFalse(c.value.contains("0"))
        }
        assertEquals("Download, no data yet", d.description)
        assertEquals("Upload, no data yet", u.description)
    }

    @Test fun hugeValuesStayInLargestUnit() {
        val (d, _) = statColumns(stats(0, Long.MAX_VALUE, 0, 5L * 1024 * 1024 * 1024 * 1024), texts)
        assertTrue(d.value.endsWith("TB/s"))
        assertEquals("5.0 TB total", d.total)
    }

    @Test fun zeroIsRealZeroWhenStatsExist() {
        val (d, _) = statColumns(stats(0, 0, 0, 0), texts)
        assertEquals("0 B/s", d.value)
    }

    private val connTexts = ConnectionsTexts(
        describe = { i, o -> "Connections, $i incoming, $o outgoing" },
        describeEmpty = "Connections, no data yet",
        dash = "—",
    )

    @Test fun connectionsRowKeepsIncomingAndOutgoingApart() {
        val row = connectionsRow(TrafficStats(0, 0, 0, 0, connectionsIn = 3, connectionsOut = 17, goroutines = 0, memoryBytes = 0), connTexts)
        assertEquals("3", row.incoming); assertEquals("17", row.outgoing)
        assertEquals("Connections, 3 incoming, 17 outgoing", row.description)
    }

    @Test fun connectionsRowShowsDashNotZeroWithoutStats() {
        val row = connectionsRow(null, connTexts)
        assertEquals("—", row.incoming); assertEquals("—", row.outgoing)
        assertEquals("Connections, no data yet", row.description)
    }

    @Test fun connectionsRowShowsRealZero() {
        val row = connectionsRow(TrafficStats(0, 0, 0, 0, 0, 0, 0, 0), connTexts)
        assertEquals("0", row.incoming); assertEquals("0", row.outgoing)
    }

    @Test fun statTypeUsesOnlyAllowedWeights() {
        val allowed = setOf(FontWeight.Normal, FontWeight.SemiBold, FontWeight.ExtraBold)
        for (style in listOf(AfterglowType.statValue, AfterglowType.statLabel, AfterglowType.statTotal))
            assertTrue("${style.fontWeight}", style.fontWeight in allowed)
    }
}
