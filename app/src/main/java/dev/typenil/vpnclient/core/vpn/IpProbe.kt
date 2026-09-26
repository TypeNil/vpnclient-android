package dev.typenil.vpnclient.core.vpn

import dev.typenil.vpnclient.core.common.log.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a through-tunnel IP echo probe. Never carries raw exception
 * text — [error] is a short category the UI maps to a string resource.
 */
data class IpCheckResult(
    /** The address the endpoint saw — the tunnel's egress IP while
     *  connected, the device's own when not. Null on failure. */
    val ip: String?,
    /** Whole-request wall time in milliseconds, null on failure. */
    val latencyMs: Long?,
    /** Failure category ("timeout", "http 403", "network") — already
     *  sanitized, null on success. */
    val error: String?,
) {
    val ok: Boolean get() = ip != null && error == null
}

/**
 * Lightweight connectivity check: fetch an IP-echo endpoint and report what
 * egress address it sees. The request deliberately uses a plain OkHttp call
 * — the app's own package rides the tunnel in every per-app mode
 * (`resolvePerAppPlan` always include/never-disallows self), so while
 * connected this probe travels through the TUN like any other app traffic.
 * It is a real connectivity signal, not a promise of what a given app does.
 *
 * Bounded by a per-call timeout: a dedicated short-deadline client is used
 * instead of the shared one so the check can't stall behind the 60 s
 * callTimeout the subscription fetcher tolerates.
 */
@Singleton
class IpProbe
    @Inject
    constructor(
        private val baseClient: OkHttpClient,
    ) {
        /** GET [endpoint], parse the echoed address, measure latency. Runs on
         *  IO; caller decides threading. */
        suspend fun check(
            endpoint: String = DEFAULT_ENDPOINT,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        ): IpCheckResult =
            withContext(Dispatchers.IO) {
                val client =
                    baseClient
                        .newBuilder()
                        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .build()
                val request =
                    Request
                        .Builder()
                        .url(endpoint)
                        .get()
                        .build()
                val start = System.nanoTime()
                try {
                    client.newCall(request).execute().use { response ->
                        val latency = (System.nanoTime() - start) / 1_000_000L
                        if (!response.isSuccessful) {
                            return@withContext IpCheckResult(
                                ip = null,
                                latencyMs = null,
                                error = "http ${response.code}",
                            )
                        }
                        // Bounded read (~256 bytes): echo endpoints return a
                        // short address line, so there is no reason to buffer
                        // a hostile/unbounded body into memory. One read is
                        // enough for ipify-style responses; MAX_IP_LEN trims
                        // any trailing garbage afterwards. (minSdk 26 — no
                        // readNBytes without desugaring.) The response's use
                        // block closes the body; closing the stream again is
                        // a no-op.
                        val bytes = response.body?.byteStream()?.use { stream ->
                            val buf = ByteArray(256)
                            val n = stream.read(buf)
                            if (n < 0) ByteArray(0) else buf.copyOf(n)
                        }
                        val body = bytes?.let { String(it, Charsets.UTF_8) }.orEmpty().trim()
                        val ip = body.takeIf { isPlausibleIp(it) }
                        IpCheckResult(
                            ip = ip,
                            latencyMs = latency,
                            error = if (ip == null) "unexpected response" else null,
                        )
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    IpCheckResult(null, null, "timeout")
                } catch (e: java.io.InterruptedIOException) {
                    // callTimeout expiry surfaces as InterruptedIOException too.
                    IpCheckResult(null, null, "timeout")
                } catch (e: IOException) {
                    // Exception text can echo request details — sanitize it anyway.
                    IpCheckResult(null, null, Redactor.redact(e.javaClass.simpleName))
                }
            }

        private fun isPlausibleIp(text: String): Boolean = text.length <= MAX_IP_LEN && IP_REGEX.matches(text)

        companion object {
            const val DEFAULT_ENDPOINT = "https://api.ipify.org"
            const val DEFAULT_TIMEOUT_MS = 8_000L
            private const val MAX_IP_LEN = 45 // longest textual IPv6
            private val IP_REGEX = Regex("[0-9a-fA-F.:]+")
        }
    }
