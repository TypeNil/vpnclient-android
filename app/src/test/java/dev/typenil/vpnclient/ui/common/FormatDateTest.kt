package dev.typenil.vpnclient.ui.common

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatDateTest {
    private val noon = 1_781_524_800L // 2026-06-15 12:00 UTC: June in every time zone

    @Test
    fun `expiry date follows the locale instead of ISO`() {
        val en = formatDate(noon, Locale.US)
        val ru = formatDate(noon, Locale("ru"))
        assertTrue(en, en.startsWith("Jun") && en.contains("2026"))
        assertTrue(ru, ru.contains("июн") && ru.contains("2026"))
        assertFalse(en, Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(en))
    }
}
