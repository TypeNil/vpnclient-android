package dev.typenil.vpnclient.data.settings

import dev.typenil.vpnclient.core.vpn.PerAppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `per_app_mode` ordinal→`per_app_mode_v2` string migration decision.
 * The DataStore edit is trivial; what matters is that it stays idempotent
 * and never overwrites a v2 value.
 */
class SettingsRepositoryPerAppMigrationTest {

    @Test
    fun `legacy ordinal maps to the matching v2 key`() {
        PerAppMode.entries.forEach { mode ->
            assertEquals(
                mode.key,
                SettingsRepository.migratedPerAppModeKey(mode.ordinal, null),
            )
        }
    }

    @Test
    fun `out-of-range legacy ordinals migrate to the default key`() {
        assertEquals(PerAppMode.ALL.key, SettingsRepository.migratedPerAppModeKey(-1, null))
        assertEquals(
            PerAppMode.ALL.key,
            SettingsRepository.migratedPerAppModeKey(PerAppMode.entries.size, null),
        )
    }

    @Test
    fun `no legacy key means nothing to migrate`() {
        assertNull(SettingsRepository.migratedPerAppModeKey(null, null))
        assertNull(SettingsRepository.migratedPerAppModeKey(null, PerAppMode.EXCLUDE.key))
    }

    @Test
    fun `a stored v2 value always wins — never overwritten`() {
        // Even with a stale ordinal alongside, the v2 write stands; the edit
        // just drops the legacy key.
        assertNull(
            SettingsRepository.migratedPerAppModeKey(
                PerAppMode.ALL.ordinal, PerAppMode.EXCLUDE.key,
            ),
        )
    }
}
