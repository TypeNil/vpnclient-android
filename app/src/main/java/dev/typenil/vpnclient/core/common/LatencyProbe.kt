package dev.typenil.vpnclient.core.common

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
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
class LatencyProbe internal constructor(
    private val socketProtector: VpnSocketProtector,
    private val ioDispatcher: CoroutineDispatcher,
    private val callerDispatcher: CoroutineDispatcher = ioDispatcher,
    private val resolver: suspend (String) -> InetAddress,
) {
    @Inject constructor(socketProtector: VpnSocketProtector) :
        this(socketProtector, workerDispatcher, Dispatchers.IO, { InetAddress.getByName(it) })

    private val permits = Semaphore(MAX_CONCURRENT_PROBES)

    /**
     * Milliseconds for a TCP connect to `host:port` (DNS resolution
     * included — it's part of real-world reachability), or null on
     * timeout/refusal/DNS failure. Cancellable queue wait is not measured
     * and does not consume the DNS + connect deadline.
     */
    suspend fun measure(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Int? = withContext(callerDispatcher) {
        permits.acquire()
        var workerOwnsPermit = false
        try {
            withTimeoutOrNull(timeoutMs.toLong()) {
                suspendCancellableCoroutine { continuation ->
                    val socket = Socket()
                    // Not a child: JVM DNS may ignore cancellation; the caller
                    // must not join it. The worker retains its permit until done.
                    val worker = CoroutineScope(ioDispatcher).launch {
                        try {
                            ensureActive()
                            socket.bind(null) // Materialize fd before protect.
                            val result = if (socketProtector.protect(socket)) {
                                val start = System.nanoTime()
                                val address = resolver(host)
                                ensureActive() // No connect after cancelled DNS.
                                socket.connect(InetSocketAddress(address, port), timeoutMs)
                                ((System.nanoTime() - start) / 1_000_000L).toInt()
                            } else null
                            continuation.resume(result)
                        } catch (e: CancellationException) {
                            continuation.cancel(e)
                        } catch (e: Exception) {
                            continuation.resume(null)
                        } finally {
                            runCatching { socket.close() }
                        }
                    }
                    // Completion also runs if cancellation wins before launch
                    // enters its body, so every acquired permit is released.
                    worker.invokeOnCompletion { permits.release() }
                    workerOwnsPermit = true
                    continuation.invokeOnCancellation {
                        runCatching { socket.close() }
                        worker.cancel()
                    }
                }
            }
        } finally {
            // Timeout/cancellation may prevent the bridge from starting at all.
            if (!workerOwnsPermit) permits.release()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000
        const val MAX_CONCURRENT_PROBES = 8
        private val workerDispatcher = Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_PROBES)
    }
}
