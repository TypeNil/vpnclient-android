package dev.typenil.vpnclient.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsRepositoryHwidTest {
    private val idKey = stringPreferencesKey("remnawave_hwid")
    private val allowedKey = booleanPreferencesKey("hwid_allowed")
    private val syntheticId = "00000000-0000-0000-0000-000000000000"

    @Test
    fun `new installation creates no id before Allowed and retains it after denial`() {
        val prefs = mutablePreferencesOf()
        var creations = 0
        val create = { creations++; syntheticId }
        assertNull(SettingsRepository.permittedHwid(prefs, create))
        assertNull(prefs[idKey])
        assertNull(prefs[allowedKey])
        prefs[allowedKey] = false
        assertNull(SettingsRepository.permittedHwid(prefs, create))
        assertEquals(0, creations)
        prefs[allowedKey] = true
        assertEquals(syntheticId, SettingsRepository.permittedHwid(prefs, create))
        assertEquals(syntheticId, SettingsRepository.permittedHwid(prefs, create))
        assertEquals(1, creations)
        prefs[allowedKey] = false
        assertNull(SettingsRepository.permittedHwid(prefs, create))
        assertEquals(syntheticId, prefs[idKey])
        assertFalse(prefs[allowedKey]!!)
    }

    @Test
    fun `legacy id grandfathers only absent consent and migration is idempotent`() {
        val prefs = mutablePreferencesOf(idKey to syntheticId)
        SettingsRepository.migrateHwidConsent(prefs)
        assertTrue(prefs[allowedKey]!!)
        assertEquals(syntheticId, SettingsRepository.permittedHwid(prefs) { error("must not create") })
        prefs[allowedKey] = false
        SettingsRepository.migrateHwidConsent(prefs)
        assertFalse(prefs[allowedKey]!!)
        assertNull(SettingsRepository.permittedHwid(prefs) { error("must not create") })
    }


    @Test
    fun `undashed 32-hex hwid is reformatted to dashed uuid`() {
        assertEquals(
            "550e8400-e29b-41d4-a716-446655440000",
            SettingsRepository.normalizeHwid("550e8400e29b41d4a716446655440000"),
        )
    }

    @Test
    fun `dashed uuid hwid is left alone`() {
        assertNull(SettingsRepository.normalizeHwid("550e8400-e29b-41d4-a716-446655440000"))
    }

    @Test
    fun `other hwid formats are left alone`() {
        assertNull(SettingsRepository.normalizeHwid("custom-device-id-123"))
        assertNull(SettingsRepository.normalizeHwid("550e8400e29b41d4a71644665544"))
    }
}
