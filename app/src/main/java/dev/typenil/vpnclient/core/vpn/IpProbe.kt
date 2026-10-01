package dev.typenil.vpnclient.core.vpn

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Sanitized IP-echo result. An HTTP response does not prove its proxy route. */
data class IpCheckResult(
    /** Address seen by the endpoint, not proof of the selected outbound. */
    val ip: String?,
    val latencyMs: Long?,
    /** Stable categories: timeout, http N, network, unexpected response. */
    val error: String?,
) {
    val ok: Boolean get() = ip != null && error == null
}

/** App HTTP request: never protected, never reuses a pre-VPN connection.
 * Cancellation aborts the call even while its response body is being read.
 */
@Singleton
class IpProbe @Inject constructor(private val baseClient: OkHttpClient) {
    suspend fun check(
        endpoint: String = DEFAULT_ENDPOINT,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): IpCheckResult {
        val deadline = timeoutMs.coerceIn(1, DEFAULT_TIMEOUT_MS)
        val pool = ConnectionPool()
        val client = baseClient.newBuilder()
            .connectionPool(pool)
            .cache(null)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(deadline, TimeUnit.MILLISECONDS)
            .build()
        val call = client.newCall(Request.Builder().url(endpoint).get().build())
        val start = System.nanoTime()
        try {
            // Includes time queued behind other calls on the shared OkHttp dispatcher.
            return withTimeoutOrNull(deadline) {
                suspendCancellableCoroutine<IpCheckResult> { continuation ->
                // This handler runs on the cancelling thread — call.cancel()
                // closes the TLS socket, a guarded network op on Android's
                // main thread. Plain dispatch has no Job to inherit, so the
                // cancelled Job can't suppress it; IO keeps close off Main.
                continuation.invokeOnCancellation {
                    Dispatchers.IO.dispatch(EmptyCoroutineContext) { call.cancel() }
                }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWith(Result.success(failure(e)))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = try {
                            response.use {
                                if (!it.isSuccessful) {
                                    IpCheckResult(null, null, "http ${it.code}")
                                } else {
                                    // One extra byte distinguishes a bounded body from a truncated prefix.
                                    val bytes = it.body?.byteStream()?.use { stream ->
                                        val buffer = ByteArray(MAX_READ_BYTES + 1)
                                        var size = 0
                                        while (size < buffer.size) {
                                            val count = stream.read(buffer, size, buffer.size - size)
                                            if (count < 0) break
                                            size += count
                                        }
                                        buffer.copyOf(size)
                                    }
                                    val text = bytes?.toString(Charsets.UTF_8)?.trim().orEmpty()
                                    val ip = text.takeIf { address -> bytes != null && bytes.size <= MAX_READ_BYTES && isNumericIp(address) }
                                    IpCheckResult(ip, if (ip != null) (System.nanoTime() - start) / 1_000_000 else null,
                                        if (ip == null) "unexpected response" else null)
                                }
                            }
                        } catch (e: IOException) {
                            failure(e)
                        }
                        continuation.resumeWith(Result.success(result))
                    }
                })
                }
            } ?: IpCheckResult(null, null, "timeout")
        } finally {
            // Evicting closes the pooled socket — for TLS, SSLSocket.close()
            // writes close_notify, which Android's main-thread network policy
            // kills (NetworkOnMainThreadException). Run it off the caller's
            // dispatcher; NonCancellable so a cancelled probe still evicts.
            withContext(NonCancellable + Dispatchers.IO) { pool.evictAll() }
        }
    }

    private fun failure(error: IOException) =
        IpCheckResult(null, null, if (error is InterruptedIOException) "timeout" else "network")

    private fun isNumericIp(text: String): Boolean {
        if (text.isEmpty() || text.length > 45) return false
        if (':' in text) {
            // HttpUrl parses IPv6 numerically; it performs no DNS resolution.
            return text.all { it in "0123456789abcdefABCDEF:." } &&
                "http://[$text]/".toHttpUrlOrNull() != null
        }
        val octets = text.split('.')
        return octets.size == 4 && octets.all {
            it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } &&
                (it.length == 1 || it[0] != '0') && (it.toIntOrNull() ?: -1) in 0..255
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.ipify.org"
        const val DEFAULT_TIMEOUT_MS = 8_000L
        private const val MAX_READ_BYTES = 256
    }
}
