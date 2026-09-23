package dev.typenil.vpnclient.core.common

import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Direct TCP-connect latency probe.
 *
 * The app's own package is always disallowed on the TUN interface
 * (`resolvePerAppPlan` adds self to the disallowed list in every mode), so
 * these sockets ride the real underlay even while the VPN is connected —
 * the measurement is never inflated by the tunnel itself.
 *
 * This measures reachability of the server's TCP port, not the full proxy
 * handshake. While connected, the engine's `urltest` gives the end-to-end
 * number; this probe exists so the server list can show real values before
 * connecting (and as a reachability signal for nodes the core can't test).
 */
@Singleton
class LatencyProbe @Inject constructor() {

    /**
     * Milliseconds for a TCP connect to `host:port` (DNS resolution
     * included — it's part of real-world reachability), or null on
     * timeout/refusal/DNS failure.
     */
    suspend fun measure(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Int? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                ((System.nanoTime() - start) / 1_000_000L).toInt()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000
    }
}
