package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
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

    @Test
    fun `cancelling the coroutine aborts the in-flight call`() = runTest {
        // The server accepts and never answers — without cancellation
        // bridging the fetch would block until the socket timeout.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val slowFetcher = SubscriptionFetcher(
            OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build(),
        )
        val outcome = CompletableDeferred<Result<FetchedSubscription>>()
        val job = launch(Dispatchers.IO) {
            outcome.complete(runCatching { slowFetcher.fetch(server.url("/sub").toString(), hwid, allowInsecure = true) })
        }
        // Wait until the request is actually in flight before cancelling.
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val start = System.nanoTime()
        job.cancel()
        job.join()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(
            "cancelled fetch took ${elapsedMs}ms — Call.cancel() did not abort it",
            elapsedMs < 30_000,
        )
        assertTrue(outcome.await().exceptionOrNull() is CancellationException)
    }

    @Test
    fun `whole-call deadline maps to Timeout`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val deadlineFetcher = SubscriptionFetcher(
            OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build(),
        )
        try {
            deadlineFetcher.fetch(server.url("/sub").toString(), hwid, allowInsecure = true)
            fail("expected SubscriptionError")
        } catch (e: SubscriptionError) {
            assertTrue(e is SubscriptionError.Timeout)
        }
    }
}
