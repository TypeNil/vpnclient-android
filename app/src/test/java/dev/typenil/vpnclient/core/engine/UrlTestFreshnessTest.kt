package dev.typenil.vpnclient.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlTestFreshnessTest {
    private val now = 1_800_000_000_000L // ms
    private val window = URLTEST_FRESH_WINDOW_MS

    /** [ageMs] behind [now]; whole seconds, like the core reports. */
    private fun item(tag: String, delay: Int?, ageMs: Long?) =
        OutboundItemInfo(
            tag = tag,
            type = "vless",
            urlTestDelayMs = delay,
            urlTestTime = if (ageMs == null) 0 else (now - ageMs) / 1000,
        )

    @Test
    fun windowCoversTwoIntervalsPlusSlack() {
        val interval = URLTEST_INTERVAL_MINUTES * 60_000L
        assertTrue("window $window must exceed two intervals", window > 2 * interval)
    }

    @Test
    fun freshLowestWins() {
        val a = item("a", 200, 1_000)
        val b = item("b", 90, 5_000)
        assertSame(b, pickBestLatency(listOf(a, b), now))
    }

    @Test
    fun staleIgnoredEvenWithLowerDelay() {
        val stale = item("dead", 10, window + 10_000)
        val fresh = item("live", 300, 1_000)
        assertSame(fresh, pickBestLatency(listOf(stale, fresh), now))
    }

    @Test
    fun allStaleYieldsNull() {
        val items = listOf(item("a", 10, window + 10_000), item("b", 20, window + 60_000))
        assertNull(pickBestLatency(items, now))
    }

    @Test
    fun boundaryExactlyOnWindowIsFreshOneMsBeyondIsNot() {
        val on = OutboundItemInfo("a", "vless", 50, (now - window) / 1000)
        // now is a whole second, so on-window age is exactly `window`.
        assertEquals(0L, (now - window) % 1000)
        assertEquals(50, on.freshDelayMs(now))
        assertNull(on.freshDelayMs(now + 1))
    }

    @Test
    fun zeroTimeIsNotFresh() {
        val unmeasured = item("a", 40, null)
        assertNull(unmeasured.freshDelayMs(now))
        assertNull(pickBestLatency(listOf(unmeasured), now))
    }

    @Test
    fun futureTimeIsNotFresh() {
        val future = OutboundItemInfo("a", "vless", 40, now / 1000 + 5)
        assertNull(future.freshDelayMs(now))
        assertNull(pickBestLatency(listOf(future), now))
    }

    @Test
    fun missingOrNonPositiveDelayIsNeverBest() {
        val none = item("a", null, 1_000)
        val zero = item("b", 0, 1_000)
        assertNull(pickBestLatency(listOf(none, zero), now))
    }

    @Test
    fun manualSelectionFollowsTheSameFreshness() {
        assertEquals(120, item("m", 120, 1_000).freshDelayMs(now))
        assertNull(item("m", 120, window + 10_000).freshDelayMs(now))
    }
}
