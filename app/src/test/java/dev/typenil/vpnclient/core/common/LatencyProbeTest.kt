package dev.typenil.vpnclient.core.common

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatencyProbeTest {

    private val probe = LatencyProbe(VpnSocketProtector())

    @Test
    fun `deadline returns even while an uninterruptible resolver lingers`() = runTest {
        val timed = LatencyProbe(VpnSocketProtector(), StandardTestDispatcher(testScheduler)) {
            // Simulate a JVM DNS syscall that ignores worker cancellation.
            withContext(NonCancellable) { delay(10_000) }
            InetAddress.getLoopbackAddress()
        }
        val start = testScheduler.currentTime
        assertNull(timed.measure("synthetic.example", 443, timeoutMs = 100))
        assertEquals(100L, testScheduler.currentTime - start)
    }

    @Test(timeout = 5_000)
    fun `blocked worker pool does not starve the caller deadline`() = runBlocking {
        val release = CountDownLatch(1)
        val blocked = LatencyProbe(
            VpnSocketProtector(), Dispatchers.IO.limitedParallelism(1), Dispatchers.IO,
        ) {
            release.await(2, TimeUnit.SECONDS)
            InetAddress.getLoopbackAddress()
        }
        val start = System.nanoTime()
        try {
            assertNull(blocked.measure("synthetic.example", 443, 100))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000)
        } finally { release.countDown() }
    }

    @Test
    fun `at most eight probes resolve concurrently`() = runTest {
        var active = 0
        var peak = 0
        val limited = LatencyProbe(VpnSocketProtector(), StandardTestDispatcher(testScheduler)) {
            active++
            peak = maxOf(peak, active)
            try {
                delay(10)
                InetAddress.getLoopbackAddress()
            } finally { active-- }
        }
        // Refused loopback port: no external network or credentials.
        val port = ServerSocket(0).use { it.localPort }
        List(24) { async { limited.measure("synthetic.example", port, 1_000) } }.awaitAll()
        assertTrue("peak=$peak", peak <= 8)
        assertEquals(0, active)
    }

    @Test
    fun `caller cancellation closes socket without publishing a result`() = runTest {
        var socket: java.net.Socket? = null
        val protector = VpnSocketProtector().apply { install { socket = it; true } }
        val cancellable = LatencyProbe(protector, StandardTestDispatcher(testScheduler)) {
            delay(10_000)
            InetAddress.getLoopbackAddress()
        }
        var published = false
        val job = launch { cancellable.measure("synthetic.example", 443); published = true }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(socket!!.isClosed)
        assertTrue(!published)
    }

    @Test
    fun `a listening socket measures a non-negative delay`() = runTest {
        ServerSocket(0).use { server ->
            val delay = probe.measure("127.0.0.1", server.localPort)
            assertNotNull(delay)
            assertTrue(delay!! >= 0)
        }
    }

    @Test
    fun `a refused connection returns null`() = runTest {
        // Bind-then-close leaves a port nothing listens on; the kernel
        // answers RST immediately. A tiny race with a stray listener is
        // acceptable — the port was free a moment ago.
        val port = ServerSocket(0).use { it.localPort }
        assertNull(probe.measure("127.0.0.1", port, timeoutMs = 500))
    }

    @Test
    fun `a failed protect reports no measurement`() = runTest {
        // protect()=false means the socket would ride the tunnel — the
        // result would be tunnel latency mislabeled as direct.
        val protector = VpnSocketProtector()
        protector.install { false }
        val failing = LatencyProbe(protector)
        ServerSocket(0).use { server ->
            assertNull(failing.measure("127.0.0.1", server.localPort))
        }
    }

}
