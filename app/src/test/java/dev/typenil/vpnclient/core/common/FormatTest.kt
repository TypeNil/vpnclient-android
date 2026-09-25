package dev.typenil.vpnclient.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `formatBytes handles sub-kilo and unit boundaries`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 MB", formatBytes(1024L * 1024 * 3 / 2))
        assertEquals("2.0 GB", formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `formatBytes clamps negatives to zero`() {
        assertEquals("0 B", formatBytes(-1))
    }

    @Test
    fun `formatRate appends per-second suffix`() {
        assertEquals("1.5 KB/s", formatRate(1536))
    }
}
