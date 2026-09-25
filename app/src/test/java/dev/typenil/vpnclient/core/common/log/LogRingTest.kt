package dev.typenil.vpnclient.core.common.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRingTest {
    @Test
    fun `snapshot returns lines oldest to newest`() {
        val ring = LogRing(capacity = 10)
        ring.add("first")
        ring.add("second")
        ring.add("third")
        assertEquals(listOf("first", "second", "third"), ring.snapshot())
    }

    @Test
    fun `oldest lines are evicted past capacity`() {
        val ring = LogRing(capacity = 3)
        repeat(5) { ring.add("line-$it") }
        assertEquals(listOf("line-2", "line-3", "line-4"), ring.snapshot())
    }

    @Test
    fun `size never exceeds capacity`() {
        val ring = LogRing(capacity = 2)
        repeat(10) { ring.add("x$it") }
        assertEquals(2, ring.size)
    }

    @Test
    fun `append is defensively redacted`() {
        // The contract is "already-redacted input" via SecureLog; a direct
        // caller slipping raw text in still can't put secrets in the buffer.
        val ring = LogRing()
        ring.add("node 550e8400-e29b-41d4-a716-446655440000 failed")
        val out = ring.snapshot().single()
        assertFalse(out.contains("550e8400"))
        assertTrue(out.contains("<uuid>"))
    }

    @Test
    fun `clear empties the buffer`() {
        val ring = LogRing()
        ring.add("a")
        ring.clear()
        assertTrue(ring.snapshot().isEmpty())
    }

    @Test
    fun `capacity must be positive`() {
        val thrown = runCatching { LogRing(capacity = 0) }.isFailure
        assertTrue(thrown)
    }
}
