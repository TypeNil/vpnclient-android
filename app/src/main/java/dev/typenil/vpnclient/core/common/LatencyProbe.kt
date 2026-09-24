package dev.typenil.vpnclient.core.common

import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Direct TCP-connect latency probe.
 *
 * The app's own package rides the tunnel in every per-app mode, so the
 * probe socket is explicitly kept on the underlay via [VpnSocketProtector]
 * (`VpnService.protect` while connected, no-op otherwise) — the measurement
 * is never inflated by the tunnel itself.
 *
 * This measures reachability of the server's TCP port, not the full proxy
 * handshake. While connected, the engine's `urltest` gives the end-to-end
 * number; this probe exists so the server list can show real values before
 * connecting (and as a reachability signal for nodes the core can't test).
 */
@Singleton
class LatencyProbe @Inject constructor(
    private val socketProtector: VpnSocketProtector,
) {

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
        // One deadline for DNS + connect: InetSocketAddress resolves eagerly
        // and its lookup is NOT covered by Socket.connect's timeout — a slow
        // resolver would otherwise stall the probe far past timeoutMs.
        // (JVM DNS isn't interruptible; on expiry the worker thread may
        // linger briefly in the syscall, but the caller is bounded.)
        withTimeoutOrNull(timeoutMs.toLong()) {
            try {
                Socket().use { socket ->
                    // bind(null) materializes the fd — VpnService.protect
                    // reads it and fails outright on an unbound socket.
                    socket.bind(null)
                    // Keep the probe on the underlay. A failed protect means
                    // the socket would ride the tunnel — the number would be
                    // tunnel latency mislabeled as direct, so report nothing.
                    if (!socketProtector.protect(socket)) return@withTimeoutOrNull null
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
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000
    }
}
