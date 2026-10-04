package dev.typenil.vpnclient.ui.servers

import dev.typenil.vpnclient.R
import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyAgeLabelTest {
    @Test
    fun `age rounds down into the largest whole unit`() {
        assertEquals(R.string.servers_latency_age_now to 0, latencyAgeLabel(59_999L))
        assertEquals(R.string.servers_latency_age_min to 1, latencyAgeLabel(60_000L))
        assertEquals(R.string.servers_latency_age_min to 59, latencyAgeLabel(3_599_999L))
        assertEquals(R.string.servers_latency_age_hour to 1, latencyAgeLabel(3_600_000L))
        assertEquals(R.string.servers_latency_age_hour to 23, latencyAgeLabel(86_399_999L))
        assertEquals(R.string.servers_latency_age_day to 3, latencyAgeLabel(3 * 86_400_000L + 5))
    }
}
