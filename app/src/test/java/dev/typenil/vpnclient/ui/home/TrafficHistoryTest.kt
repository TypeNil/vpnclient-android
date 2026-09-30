package dev.typenil.vpnclient.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficHistoryTest {
    @Test fun `new receipt after cached ticker stays fresh for this frame`() {
        val history = TrafficHistory()
        val tick = 1_000_000_000_000L
        val receipt = tick + 400_000_000L
        assertFalse(history.isFresh(tick, receipt)) // The old ticker caused the flicker.
        assertTrue(history.isFresh(frameTimeNanos(tick, receipt + 1), receipt))
        assertFalse(history.isFresh(frameTimeNanos(tick, receipt + TrafficHistory.STALE_NANOS), receipt))
    }

    @Test fun `identical idle snapshots remain fresh until arrivals actually stop`() {
        val history = TrafficHistory()
        val start = 1_000_000_000_000L
        history.observe(start, 0, 0)
        history.observe(start + 1_000_000_000L, 0, 0)
        assertEquals(2, history.recent(start + 1_000_000_000L).size)
        assertTrue(history.isFresh(start + 10_000_000_000L, start + 1_000_000_000L))
        assertFalse(history.isFresh(start + 11_000_000_000L, start + 1_000_000_000L))
    }

    @Test fun `chart dampens bursts but starts fresh after a reporting gap`() {
        val history = TrafficHistory()
        val start = 1_000_000_000_000L
        history.observe(start, 0, 0)
        history.observe(start + 1_000_000_000L, 1000, 500)
        val smoothed = history.recent(start + 1_000_000_000L).last()
        assertTrue(smoothed.down in 1..999)
        assertTrue(smoothed.up in 1..499)
        history.observe(start + 5_000_000_000L, 0, 0)
        assertEquals(0, history.recent(start + 5_000_000_000L).last().down)
    }

    @Test fun `paused status creates a real time gap and resume does not backfill`() {
        val history = TrafficHistory()
        val start = 1_000_000_000_000L
        history.observe(start, 16, 8)
        history.observe(start + 1_000_000_000L, 16, 8)
        assertFalse(history.isFresh(start + 13_000_000_000L, start + 1_000_000_000L))
        history.observe(start + 13_000_000_000L, 0, 0)
        val points = history.recent(start + 13_000_000_000L)
        assertEquals(3, points.size)
        assertTrue(points.last().atNanos - points[1].atNanos > TrafficHistory.GAP_NANOS)
        assertTrue(history.isFresh(start + 13_000_000_000L, points.last().atNanos))
        assertEquals(1, history.recent(start + 32_000_000_000L).size)
        assertTrue(TrafficHistory().recent(start + 32_000_000_000L).isEmpty())
    }
}
