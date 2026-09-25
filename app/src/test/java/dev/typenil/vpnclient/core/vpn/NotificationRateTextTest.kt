package dev.typenil.vpnclient.core.vpn

import dev.typenil.vpnclient.core.engine.TrafficStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationRateTextTest {

    private fun stats(down: Long, up: Long) = TrafficStats(
        uplinkBytesPerSec = up,
        downlinkBytesPerSec = down,
        uplinkTotalBytes = 0,
        downlinkTotalBytes = 0,
        connectionsIn = 0,
        connectionsOut = 0,
        goroutines = 0,
        memoryBytes = 0,
    )

    @Test
    fun `null stats produce no rate text`() {
        assertNull(rateText(null))
    }

    @Test
    fun `rates render as downlink then uplink`() {
        assertEquals(
            "↓ 1.5 KB/s · ↑ 512 B/s",
            rateText(stats(down = 1536, up = 512)),
        )
    }
}
