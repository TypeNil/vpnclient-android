package dev.typenil.vpnclient.data.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.typenil.vpnclient.core.vpn.LastSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsRepositoryLastSessionTest {
    private val first = LastSession(1_700_000_000_000L, 12_000L, Long.MAX_VALUE, 0L, "Demo Node")
    private val second = LastSession(1_700_000_900_000L, 5_000L, 7L, 3L, "Other Node")

    @Test
    fun `empty store has no record`() {
        assertNull(SettingsRepository.lastSessionOf(emptyPreferences()))
    }

    @Test
    fun `round trip`() {
        val prefs = mutablePreferencesOf()
        SettingsRepository.persistLastSession(prefs, first)
        assertEquals(first, SettingsRepository.lastSessionOf(prefs))
    }

    @Test
    fun `a new record overwrites the previous one`() {
        val prefs = mutablePreferencesOf()
        SettingsRepository.persistLastSession(prefs, first)
        SettingsRepository.persistLastSession(prefs, second)
        assertEquals(second, SettingsRepository.lastSessionOf(prefs))
    }

    @Test
    fun `clear removes the record and leaves other keys`() {
        val other = stringPreferencesKey("theme_mode")
        val prefs = mutablePreferencesOf(other to "dark")
        SettingsRepository.persistLastSession(prefs, first)
        SettingsRepository.removeLastSession(prefs)
        assertNull(SettingsRepository.lastSessionOf(prefs))
        assertEquals("dark", prefs[other])
    }

    @Test
    fun `a partial record is not shown`() {
        val prefs = mutablePreferencesOf()
        SettingsRepository.persistLastSession(prefs, first)
        prefs.remove(stringPreferencesKey("last_session_node"))
        assertNull(SettingsRepository.lastSessionOf(prefs))
    }
}
