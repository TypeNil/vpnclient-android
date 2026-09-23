package dev.typenil.vpnclient.core.common

import java.net.ServerSocket
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyProbeTest {

    private val probe = LatencyProbe()

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

}
