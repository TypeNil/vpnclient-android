package dev.typenil.vpnclient.core.subscription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpiryAlertTest {

    private val now = 1_800_000_000_000L // fixed epoch ms

    @Test
    fun `due inside the window`() {
        val expire = (now + 3600_000) / 1000 // +1h
        assertTrue(expiryAlertDue(expire, now, alreadyAlerted = false))
    }

    @Test
    fun `due at the window boundary`() {
        val expire = (now + EXPIRY_ALERT_WINDOW_MS) / 1000
        assertTrue(expiryAlertDue(expire, now, alreadyAlerted = false))
    }

    @Test
    fun `not due beyond the window`() {
        val expire = (now + EXPIRY_ALERT_WINDOW_MS + 60_000) / 1000
        assertFalse(expiryAlertDue(expire, now, alreadyAlerted = false))
    }

    @Test
    fun `not due when already expired`() {
        val expire = (now - 60_000) / 1000
        assertFalse(expiryAlertDue(expire, now, alreadyAlerted = false))
    }

    @Test
    fun `not due when already alerted`() {
        val expire = (now + 3600_000) / 1000
        assertFalse(expiryAlertDue(expire, now, alreadyAlerted = true))
    }

    @Test
    fun `not due without an expiry`() {
        assertFalse(expiryAlertDue(null, now, alreadyAlerted = false))
    }

    @Test
    fun `legacy key format counts as already alerted`() {
        val alerted = setOf("expiry_alerted_7_1800003600")
        assertTrue(expiryAlreadyAlerted(alerted, 7, 1_800_003_600))
    }

    @Test
    fun `canonical key format counts as already alerted`() {
        val alerted = setOf("7:1800003600")
        assertTrue(expiryAlreadyAlerted(alerted, 7, 1_800_003_600))
    }

    @Test
    fun `different expiry is not alerted`() {
        val alerted = setOf("7:1800003600", "expiry_alerted_7_1800003600")
        assertFalse(expiryAlreadyAlerted(alerted, 7, 1_900_000_000))
    }
}
