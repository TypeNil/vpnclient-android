package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Transport-security tests: HTTPS by default, cleartext only via opt-in,
 * https→http downgrade blocked, HWID headers scoped to the exact origin.
 */
class SubscriptionFetcherTest {

    private val server = MockWebServer()
    private val other = MockWebServer()
    private val fetcher = SubscriptionFetcher(OkHttpClient())

    private val hwid = "00000000-0000-0000-0000-000000000000"
    private val body = "vless://11111111-2222-3333-4444-555555555555@a.example.com:443" +
        "?security=reality&sni=www.example.org&fp=chrome&pbk=abc&sid=01ab&type=tcp#A"

    @Before
    fun start() {
        server.start()
        other.start()
    }

    @After
    fun stop() {
        server.shutdown()
        other.shutdown()
    }

    private suspend fun fetchExpectingError(url: String, allowInsecure: Boolean): SubscriptionError {
        try {
            fetcher.fetch(url, hwid, allowInsecure)
        } catch (e: SubscriptionError) {
            return e
        }
        throw AssertionError("expected SubscriptionError")
    }

    @Test
    fun `plain http is rejected without opt-in`() = runTest {
        val error = fetchExpectingError(server.url("/sub").toString(), allowInsecure = false)
        assertTrue(error is SubscriptionError.InsecureTransport)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `plain http works with opt-in`() = runTest {
        server.enqueue(MockResponse().setBody(body))
        val fetched = fetcher.fetch(server.url("/sub").toString(), hwid, allowInsecure = true)
        assertEquals(1, server.requestCount)
        assertTrue(fetched.body.isNotEmpty())
    }

    @Test
    fun `transport policy matrix`() {
        val https = "https://panel.example.com/sub".toHttpUrl()
        val http = "http://panel.example.com/sub".toHttpUrl()

        // https anywhere is always fine.
        assertTrue(fetcher.isAllowedTransport(https, https, allowInsecure = false))
        assertTrue(fetcher.isAllowedTransport(http, https, allowInsecure = false))
        // cleartext origin requires opt-in.
        assertFalse(fetcher.isAllowedTransport(http, http, allowInsecure = false))
        assertTrue(fetcher.isAllowedTransport(http, http, allowInsecure = true))
        // https → http downgrade is never allowed, opt-in or not.
        assertFalse(fetcher.isAllowedTransport(https, http, allowInsecure = true))
        assertFalse(fetcher.isAllowedTransport(https, http, allowInsecure = false))
    }

    @Test
    fun `hwid headers are not forwarded across origins`() = runTest {
        // Same host (localhost), different port — a different origin.
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", other.url("/landing").toString()),
        )
        other.enqueue(MockResponse().setBody(body))

        fetcher.fetch(server.url("/sub").toString(), hwid, allowInsecure = true)

        val first = server.takeRequest()
        assertEquals(hwid, first.getHeader("x-hwid"))
        val second = other.takeRequest()
        assertNull(second.getHeader("x-hwid"))
        assertNull(second.getHeader("x-device-model"))
    }

    @Test
    fun `hwid headers survive same-origin redirect`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", "/sub/final"),
        )
        server.enqueue(MockResponse().setBody(body))

        fetcher.fetch(server.url("/sub").toString(), hwid, allowInsecure = true)

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals(hwid, first.getHeader("x-hwid"))
        assertEquals(hwid, second.getHeader("x-hwid"))
    }
}
