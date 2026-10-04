package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.SubscriptionUserInfo
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic `subscription-userinfo` header values only. */
class SubscriptionUserInfoTest {
    private val fetcher = SubscriptionFetcher(OkHttpClient())
    private fun parse(v: String?) = fetcher.parseUserInfo(v)

    @Test
    fun `plain header parses all four fields`() {
        assertEquals(
            SubscriptionUserInfo(10, 20, 100, 1_900_000_000),
            parse("upload=10; download=20; total=100; expire=1900000000"),
        )
    }

    @Test
    fun `absent or blank header yields null`() {
        assertNull(parse(null))
        assertNull(parse("  "))
    }

    @Test
    fun `keys are case-insensitive and whitespace tolerant`() {
        assertEquals(
            SubscriptionUserInfo(1, 2, 3, 1_900_000_000),
            parse(" Upload = 1 ;DOWNLOAD=2;  Total=3 ; EXPIRE=1900000000 "),
        )
    }

    @Test
    fun `expire zero means no expiry`() {
        assertNull(parse("upload=1; download=1; total=0; expire=0")?.expireEpochSeconds)
    }

    @Test
    fun `negative values are clamped and negative expire dropped`() {
        val info = parse("upload=-5; download=-1; total=-9; expire=-100")
        assertEquals(SubscriptionUserInfo(0, 0, 0, null), info)
    }

    @Test
    fun `millisecond expire is normalised to seconds`() {
        assertEquals(1_900_000_000L, parse("expire=1900000000000")?.expireEpochSeconds)
    }

    @Test
    fun `decimal and exponent byte counts are accepted`() {
        val info = parse("upload=1.5e3; download=2048.0; total=1e9")
        assertEquals(1500L, info?.uploadBytes)
        assertEquals(2048L, info?.downloadBytes)
        assertEquals(1_000_000_000L, info?.totalBytes)
    }

    @Test
    fun `non-numeric values fall back without throwing`() {
        val info = parse("upload=abc; download=; total=NaN; expire=soon")
        assertEquals(SubscriptionUserInfo(0, 0, 0, null), info)
    }

    @Test
    fun `huge values saturate and used bytes never overflow`() {
        val info = parse("upload=9223372036854775807; download=9223372036854775807; total=1e30")
        assertNotNull(info)
        assertEquals(Long.MAX_VALUE, info!!.totalBytes)
        assertEquals(Long.MAX_VALUE, info.usedBytes)
    }

    @Test
    fun `usage above total is reported as is`() {
        val info = parse("upload=60; download=60; total=100")!!
        assertEquals(120L, info.usedBytes)
    }
}
