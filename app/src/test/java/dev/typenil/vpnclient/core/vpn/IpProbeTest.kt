package dev.typenil.vpnclient.core.vpn

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class IpProbeTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val probe = IpProbe(client)

    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    @Test fun `numeric IPv4 and IPv6 responses accepted`() = runBlocking {
        listOf("192.0.2.1", "2001:db8::1", "::ffff:192.0.2.1").forEach { address ->
            server.enqueue(MockResponse().setBody("$address\n"))
            val result = probe.check(server.url("/").toString())
            assertTrue(result.ok)
            assertEquals(address, result.ip)
            assertNotNull(result.latencyMs)
        }
    }

    @Test fun `hostile and malformed bodies never become IP results`() = runBlocking {
        listOf("deadbeef", "...", "999.1.1.1", "01.2.3.4", "192.0.2.1\n192.0.2.2",
            "2001:::1", "fe80::1%wlan0", "example.invalid", "192.0.2.1" + " ".repeat(300)).forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            val result = probe.check(server.url("/").toString())
            assertFalse(result.ok)
            assertNull(result.ip)
            assertEquals("unexpected response", result.error)
        }
    }

    @Test fun `HTTP errors and redirects are sanitized and not followed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals("http 503", probe.check(server.url("/").toString()).error)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/redirect")))
        assertEquals("http 302", probe.check(server.url("/").toString()).error)
        assertEquals(2, server.requestCount)
    }

    @Test fun `whole call timeout includes a stalled response body`() = runBlocking {
        server.enqueue(MockResponse().setBody("192.0.2.1").throttleBody(1, 2, TimeUnit.SECONDS))
        val result = withTimeout(2_000) { probe.check(server.url("/").toString(), timeoutMs = 100) }
        assertEquals("timeout", result.error)
        assertNull(result.ip)
    }

    @Test fun `network failure has no raw exception details`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals("network", probe.check(server.url("/").toString()).error)
    }

    @Test fun `cancellation promptly aborts in flight response body read`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CountDownLatch(1)
        val listener = object : EventListener() {
            override fun responseBodyStart(call: Call) { started.complete(Unit) }
            override fun canceled(call: Call) { cancelled.countDown() }
        }
        val cancellableProbe = IpProbe(client.newBuilder().eventListener(listener).build())
        server.enqueue(MockResponse().setBody("192.0.2.1").throttleBody(1, 2, TimeUnit.SECONDS))
        val request = async(Dispatchers.Default) { cancellableProbe.check(server.url("/").toString()) }
        withTimeout(2_000) { started.await() }
        request.cancel()
        withTimeout(1_000) { request.join() }
        assertTrue(request.isCancelled)
        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
    }

    @Test fun `each probe uses a fresh connection not the base clients pre VPN pool`() = runBlocking {
        server.enqueue(MockResponse().setBody("baseline"))
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body!!.string() }
        assertEquals(0, server.takeRequest().sequenceNumber)
        repeat(2) {
            server.enqueue(MockResponse().setBody("192.0.2.1"))
            assertTrue(probe.check(server.url("/").toString()).ok)
            assertEquals(0, server.takeRequest().sequenceNumber)
        }
    }
}
