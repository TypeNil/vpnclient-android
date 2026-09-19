package dev.typenil.vpnclient.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Interval precedence: user override > provider hint > manual-only;
 * platform floor at 15 minutes.
 */
class RefreshIntervalTest {

    @Test
    fun `user override beats provider hint`() {
        assertEquals(60L, resolveRefreshIntervalMinutes(60, 120, enabled = true))
    }

    @Test
    fun `provider hint used when no override`() {
        assertEquals(120L, resolveRefreshIntervalMinutes(0, 120, enabled = true))
    }

    @Test
    fun `no override and no provider means manual only`() {
        assertNull(resolveRefreshIntervalMinutes(0, null, enabled = true))
    }

    @Test
    fun `auto refresh off cancels regardless of hints`() {
        assertNull(resolveRefreshIntervalMinutes(-1, 120, enabled = true))
        assertNull(resolveRefreshIntervalMinutes(-1, null, enabled = true))
    }

    @Test
    fun `disabled subscription never schedules`() {
        assertNull(resolveRefreshIntervalMinutes(0, 120, enabled = false))
        assertNull(resolveRefreshIntervalMinutes(60, 120, enabled = false))
    }

    @Test
    fun `interval is floored at the platform minimum`() {
        assertEquals(15L, resolveRefreshIntervalMinutes(5, null, enabled = true))
        assertEquals(15L, resolveRefreshIntervalMinutes(0, 5, enabled = true))
    }
}
