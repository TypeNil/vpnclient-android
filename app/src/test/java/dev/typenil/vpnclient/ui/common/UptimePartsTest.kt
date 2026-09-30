package dev.typenil.vpnclient.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit boundaries for the duration parts feeding `home_uptime_*` strings. */
class UptimePartsTest {
    @Test
    fun `negative clamps to zero`() {
        assertEquals(UptimeParts(0, 0, 0, 0), uptimeParts(-42))
    }

    @Test
    fun `59 seconds stays seconds`() {
        assertEquals(UptimeParts(0, 0, 0, 59), uptimeParts(59))
    }

    @Test
    fun `60 seconds rolls into one minute`() {
        assertEquals(UptimeParts(0, 0, 1, 0), uptimeParts(60))
    }

    @Test
    fun `one hour rolls minutes`() {
        assertEquals(UptimeParts(0, 1, 0, 0), uptimeParts(3_600))
    }

    @Test
    fun `one day rolls hours`() {
        assertEquals(UptimeParts(1, 0, 0, 0), uptimeParts(86_400))
    }

    @Test
    fun `multiple days keep the hour-minute-second remainder`() {
        val seconds = 2L * 86_400 + 4 * 3_600 + 30 * 60 + 50
        assertEquals(UptimeParts(2, 4, 30, 50), uptimeParts(seconds))
    }
}
