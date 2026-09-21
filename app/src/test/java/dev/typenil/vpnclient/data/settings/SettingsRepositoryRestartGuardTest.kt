package dev.typenil.vpnclient.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The crash-loop restart window: automatic starts inside a window count up,
 * an expired window starts a fresh one anchored at `now`.
 */
class SettingsRepositoryRestartGuardTest {

    @Test
    fun `first attempt opens a window anchored at now`() {
        assertEquals(
            NOW to 1,
            SettingsRepository.restartWindow(prevStart = 0L, prevCount = 0, now = NOW),
        )
    }

    @Test
    fun `attempts inside the window count up`() {
        val (start, count) = SettingsRepository.restartWindow(
            prevStart = NOW, prevCount = 1, now = NOW + 1_000,
        )
        assertEquals(NOW, start)
        assertEquals(2, count)
    }

    @Test
    fun `an expired window restarts the count`() {
        val (start, count) = SettingsRepository.restartWindow(
            prevStart = NOW,
            prevCount = SettingsRepository.RESTART_MAX_ATTEMPTS,
            now = NOW + SettingsRepository.RESTART_WINDOW_MS + 1,
        )
        assertEquals(NOW + SettingsRepository.RESTART_WINDOW_MS + 1, start)
        assertEquals(1, count)
    }

    @Test
    fun `the (max+1)th attempt inside a window exceeds the limit`() {
        val (_, count) = SettingsRepository.restartWindow(
            prevStart = NOW,
            prevCount = SettingsRepository.RESTART_MAX_ATTEMPTS,
            now = NOW + 1_000,
        )
        assertEquals(SettingsRepository.RESTART_MAX_ATTEMPTS + 1, count)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
