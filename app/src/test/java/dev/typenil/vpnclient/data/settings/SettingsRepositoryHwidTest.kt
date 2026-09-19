package dev.typenil.vpnclient.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsRepositoryHwidTest {

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
