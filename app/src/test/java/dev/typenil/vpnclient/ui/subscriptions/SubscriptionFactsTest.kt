package dev.typenil.vpnclient.ui.subscriptions

import dev.typenil.vpnclient.core.subscription.model.SubscriptionUserInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionFactsTest {
    private fun info(up: Long = 0, down: Long = 0, total: Long = 0, expire: Long? = null) = SubscriptionUserInfo(up, down, total, expire)

    @Test
    fun `usage bar needs a total and never leaves 0 to 1`() {
        assertNull(usageFraction(null))
        assertNull(usageFraction(info(down = 5, total = 0)))
        assertEquals(.25f, usageFraction(info(up = 10, down = 15, total = 100))!!, 1e-6f)
        assertEquals(1f, usageFraction(info(down = 500, total = 100))!!, 0f)
    }

    @Test
    fun `expiry is past, future or absent, and zero means absent`() {
        assertNull(expiryFact(null, 0))
        assertNull(expiryFact(info(expire = null), 0))
        assertNull(expiryFact(info(expire = 0), 0))
        assertTrue(expiryFact(info(expire = 100), nowMs = 100_001)!!.expired)
        assertFalse(expiryFact(info(expire = 100), nowMs = 99_000)!!.expired)
        assertEquals(100L, expiryFact(info(expire = 100), 0)!!.epochSeconds)
    }
}
