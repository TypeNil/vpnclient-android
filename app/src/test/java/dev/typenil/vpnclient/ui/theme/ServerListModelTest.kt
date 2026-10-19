package dev.typenil.vpnclient.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerListModelTest {
    private fun tone(ms: Int?) = latencyTone(LatencyReading(ms, 0))

    @Test
    fun `latency tone follows the named thresholds at both edges`() {
        assertEquals(LatencyTone.Good, tone(0))
        assertEquals(LatencyTone.Good, tone(LATENCY_GOOD_MAX_MS))
        assertEquals(LatencyTone.Fair, tone(LATENCY_GOOD_MAX_MS + 1))
        assertEquals(LatencyTone.Fair, tone(LATENCY_FAIR_MAX_MS))
        assertEquals(LatencyTone.Poor, tone(LATENCY_FAIR_MAX_MS + 1))
    }

    @Test
    fun `a failed probe is poor and no data is neutral`() {
        assertEquals(LatencyTone.Poor, tone(null))
        assertEquals(LatencyTone.None, latencyTone(null))
    }

    @Test
    fun `an outdated reading is muted whatever its value, a fresh one keeps its tone`() {
        for (ms in listOf(5, 300, 900, null)) assertEquals(LatencyTone.None, latencyTone(LatencyReading(ms, 0, stale = true)))
        assertEquals(LatencyTone.Good, latencyTone(LatencyReading(5, 0, stale = false)))
    }

    @Test
    fun `chip colours come from the palette and only failures are red`() {
        for (p in listOf(LightAfterglow, DarkAfterglow)) {
            assertEquals(p.jade, p.latencyChipColors(LatencyTone.Good).background.copy(alpha = 1f))
            assertEquals(p.amber, p.latencyChipColors(LatencyTone.Fair).background.copy(alpha = 1f))
            assertEquals(p.error, p.latencyChipColors(LatencyTone.Poor).text)
            assertEquals(p.muted, p.latencyChipColors(LatencyTone.None).text)
            assertNotEquals(p.error, p.latencyChipColors(LatencyTone.Good).text)
            assertNotEquals(p.error, p.latencyChipColors(LatencyTone.Fair).text)
        }
    }

    @Test
    fun `selected row is a neutral surface without its own frame, never the error surface`() {
        for (p in listOf(LightAfterglow, DarkAfterglow)) {
            assertEquals(p.paperSecondary, p.serverRowContainer(selected = true))
            assertNotEquals(p.errorSurface, p.serverRowContainer(selected = true))
            assertEquals(Color.Transparent, p.serverRowContainer(selected = false))
        }
    }

    @Test
    fun `only a standalone selected card is outlined in coral`() {
        for (p in listOf(LightAfterglow, DarkAfterglow)) {
            assertEquals(p.coral, p.cardOutline(selected = true))
            assertEquals(null, p.cardOutline(selected = false))
        }
    }

    @Test
    fun `provider header appears only when there is more than one group`() {
        assertFalse(showProviderHeader(0))
        assertFalse(showProviderHeader(1))
        assertTrue(showProviderHeader(2))
    }

    @Test
    fun `row description joins the present parts in reading order`() {
        assertEquals(
            "Demo Frankfurt, SOCKS, 5 ms, Selected",
            serverRowDescription("Demo Frankfurt", "SOCKS", "5 ms", null, null, "Selected"),
        )
        assertEquals("A", serverRowDescription("A", null, "", null))
    }
}
