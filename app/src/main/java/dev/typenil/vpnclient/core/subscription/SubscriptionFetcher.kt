package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.BuildConfig
import dev.typenil.vpnclient.core.common.log.Redactor
import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.core.subscription.model.SubscriptionUserInfo
import dev.typenil.vpnclient.core.subscription.parse.percentDecode
import dev.typenil.vpnclient.core.subscription.parse.truthyParam
import java.io.IOException
import java.io.InterruptedIOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class FetchedSubscription(
    val body: ByteArray,
    val contentType: String?,
    /** Base64-decoded `profile-title` if present. */
    val profileTitle: String?,
    val userInfo: SubscriptionUserInfo?,
    val supportUrl: String?,
    val announce: String?,
    val updateIntervalMinutes: Int?,
    /** `update-always` — provider asks the client to refresh on every launch. */
    val updateAlways: Boolean,
    /** Provider migration hints — validated remote URLs, never applied blindly. */
    val movedPermanentlyTo: String?,
    val newUrl: String?,
    val newDomain: String?,
    /** Alternate fetch URL tried when the primary is unreachable. */
    val fallbackUrl: String?,
    /** Raw header values relevant to Remnawave HWID/device-limit diagnostics. */
    val hwidHeaders: Map<String, String>,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is FetchedSubscription && body.contentEquals(other.body))

    override fun hashCode(): Int = body.contentHashCode()
}

/**
 * HTTP fetch for subscription URLs.
 *
 * - Caps the body at [MAX_BODY_BYTES]; rejects larger responses.
 * - Follows redirects.
 * - Sends a stable client User-Agent so Remnawave panels return a predictable format.
 * - Optionally sends Remnawave HWID headers.
 */
@Singleton
class SubscriptionFetcher @Inject constructor(
    private val client: OkHttpClient,
) {

    companion object {
        private const val TAG = "SubscriptionFetcher"
        private const val MAX_BODY_BYTES = 8L * 1024 * 1024 // 8 MiB — generous for node lists
        private const val MAX_REDIRECTS = 5

        /** Container extensions stripped from a Content-Disposition title. */
        private val STRIPPABLE_EXTENSIONS = setOf(
            "sub", "txt", "yaml", "yml", "json", "conf", "base64",
        )

        /**
         * The panel chooses a response format from the path suffix and/or User-Agent.
         * "sing-box" in the UA makes Remnawave return sing-box JSON when a UA-based
         * response rule is configured; otherwise it falls back to a Base64 URI list,
         * which the classifier handles anyway.
         */
        const val USER_AGENT = "sing-box/1.13.0 (VPNClient; android)"
    }

    // Redirects are followed manually so HWID headers are only sent to the
    // original host — OkHttp would otherwise leak them cross-host.
    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    suspend fun fetch(
        url: String,
        hwid: String?,
        allowInsecure: Boolean = false,
    ): FetchedSubscription =
        withContext(Dispatchers.IO) {
            val origin = try {
                url.toHttpUrl()
            } catch (e: Exception) {
                throw SubscriptionError.ParseFailed("bad url")
            }
            if (!isAllowedTransport(origin, origin, allowInsecure)) {
                throw SubscriptionError.InsecureTransport
            }
            var current = origin
            // Credentials belong to the origin URL, not to whatever a redirect
            // target happens to carry — capture once so an absolute same-origin
            // Location without userinfo doesn't drop them.
            val originUser = origin.username
            val originPassword = origin.password
            var redirectsLeft = MAX_REDIRECTS
            while (true) {
                // HWID headers go only to the exact origin (scheme+host+port) —
                // a same-host redirect on another port/scheme must not see them.
                val sendHwid = hwid != null && current.sameOriginAs(origin)
                val request = Request.Builder()
                    .url(current)
                    .header("User-Agent", USER_AGENT)
                    .apply {
                        if (sendHwid) {
                            header("x-hwid", hwid!!)
                            header("x-device-os", "android")
                            header("x-ver-os", android.os.Build.VERSION.RELEASE ?: "unknown")
                            header("x-device-model", android.os.Build.MODEL ?: "unknown")
                            header("x-app-version", BuildConfig.VERSION_NAME)
                        }
                        // HTTP Basic auth (sing-box client spec): credentials
                        // embedded in the URL go only to the exact origin —
                        // same rule as the HWID headers above.
                        if (originUser.isNotEmpty() && current.sameOriginAs(origin)) {
                            header(
                                "Authorization",
                                Credentials.basic(originUser, originPassword),
                            )
                        }
                    }
                    .build()

                val response = try {
                    noRedirectClient.newCall(request).await()
                } catch (e: InterruptedIOException) {
                    // Covers socket timeouts AND the whole-call deadline
                    // (callTimeout) — both surface as InterruptedIOException.
                    throw SubscriptionError.Timeout
                } catch (e: IOException) {
                    throw SubscriptionError.Network
                }

                if (response.isRedirect && redirectsLeft > 0) {
                    val location = response.header("Location")
                    response.close()
                    if (location == null) throw SubscriptionError.Http(response.code, current.host)
                    val next = current.resolve(location)
                        ?: throw SubscriptionError.Http(response.code, current.host)
                    // Each hop is judged against the hop it came from: an
                    // https→http downgrade is rejected even when the origin
                    // itself was opted-in cleartext.
                    if (!isAllowedTransport(current, next, allowInsecure)) {
                        throw SubscriptionError.InsecureTransport
                    }
                    // SSRF guard: a public origin must not pivot the fetcher
                    // into loopback/private/link-local space (cloud metadata
                    // endpoints, LAN services). Private origins stay legal —
                    // the user confirmed that URL — so only the
                    // public→private transition is rejected. Literal IPs and
                    // "localhost" only; DNS names resolving to private
                    // addresses are out of scope (TOCTOU either way).
                    if (!isPrivateHost(current.host) && isPrivateHost(next.host)) {
                        throw SubscriptionError.ForbiddenAddress
                    }
                    current = next
                    redirectsLeft--
                    continue
                }

                return@withContext readResponse(response, url)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    /**
     * Executes [Call] with coroutine-cancellation bridging: cancelling the
     * calling coroutine calls [Call.cancel], aborting the in-flight request
     * instead of leaving a blocked IO thread running to the socket timeout.
     * A response delivered concurrently with cancellation is closed.
     */
    private suspend fun Call.await(): okhttp3.Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    cont.resume(response) { _, resp, _ -> resp.close() }
                }

                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWith(Result.failure(e))
                }
            })
        }

    private fun readResponse(response: okhttp3.Response, url: String): FetchedSubscription =
        response.use { resp ->
                val headers = resp.headers
                if (!resp.isSuccessful) {
                    throw mapHttpError(url, resp.code, headers)
                }
                val body = resp.body
                val declared = body.contentLength()
                if (declared > MAX_BODY_BYTES) {
                    throw SubscriptionError.TooLarge(MAX_BODY_BYTES.toInt())
                }
                val bytes = body.byteStream().use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BODY_BYTES) {
                            throw SubscriptionError.TooLarge(MAX_BODY_BYTES.toInt())
                        }
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                if (bytes.isEmpty()) throw SubscriptionError.EmptyResult()

                FetchedSubscription(
                    body = bytes,
                    contentType = body.contentType()?.toString(),
                    profileTitle = decodeHeaderValue(headers["profile-title"])
                        ?: contentDispositionFilename(headers["content-disposition"]),
                    userInfo = parseUserInfo(headers["subscription-userinfo"]),
                    supportUrl = headers["profile-web-page-url"] ?: headers["support-url"],
                    announce = decodeHeaderValue(headers["announce"]),
                    // Convention (Remnawave/Streisand): the header value is
                    // in HOURS; normalize to minutes for scheduling. Bound
                    // before multiplying — a hostile panel can send an
                    // Int-overflowing value that would floor to the minimum.
                    updateIntervalMinutes = headers["profile-update-interval"]
                        ?.toLongOrNull()
                        ?.takeIf { it in 1..(Int.MAX_VALUE / 60L) }
                        ?.let { (it * 60).toInt() },
                    updateAlways = truthyParam(headers["update-always"]),
                    movedPermanentlyTo = validRemoteUrl(headers["moved-permanently-to"]),
                    newUrl = validRemoteUrl(headers["new-url"]),
                    newDomain = validDomain(headers["new-domain"]),
                    fallbackUrl = validRemoteUrl(headers["fallback-url"]),
                    hwidHeaders = headers.names()
                        .filter { it.lowercase().startsWith("x-hwid") }
                        .associateWith { headers[it]!! },
                )
            }

    private fun mapHttpError(url: String, code: Int, headers: okhttp3.Headers): SubscriptionError {
        // Remnawave returns 404 both for an unknown subscription and for a missing/invalid
        // HWID when device limits are enabled — related headers disambiguate.
        val hwidHint = headers.names().any { it.lowercase().startsWith("x-hwid") }
        SecureLog.w(TAG, "subscription fetch failed http=$code url=${Redactor.urlForDisplay(url)} hwidHeaders=$hwidHint")
        return when {
            code == 404 && hwidHint -> SubscriptionError.DeviceLimitReached(
                "panel rejected request (missing/invalid device id)",
            )
            else -> SubscriptionError.Http(code, url.hostOrNull())
        }
    }

    /** `subscription-userinfo: upload=..; download=..; total=..; expire=..` */
    internal fun parseUserInfo(value: String?): SubscriptionUserInfo? {
        if (value.isNullOrBlank()) return null
        val parts = value.split(';')
            .mapNotNull {
                val kv = it.trim().split('=', limit = 2)
                if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
            }
            .toMap()
        val upload = parts["upload"]?.toLongOrNull() ?: 0
        val download = parts["download"]?.toLongOrNull() ?: 0
        val total = parts["total"]?.toLongOrNull() ?: 0
        val expire = parts["expire"]?.toLongOrNull()
        return SubscriptionUserInfo(upload, download, total, expire)
    }

    /** Header values may be `base64:<b64>` or plain text. */
    internal fun decodeHeaderValue(value: String?): String? {
        if (value.isNullOrBlank()) return null
        if (!value.startsWith("base64:")) return value
        return runCatching {
            String(java.util.Base64.getDecoder().decode(value.removePrefix("base64:")))
        }.getOrNull()
    }

    /**
     * A provider-supplied URL the client may later act on (migration target,
     * fallback). Must parse as http(s) and must not point at a private/local
     * host — the same SSRF rule as redirect targets.
     */
    internal fun validRemoteUrl(raw: String?): String? {
        val url = raw?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { it.toHttpUrl() }.getOrNull() }
            ?: return null
        if (isPrivateHost(url.host)) return null
        return url.toString()
    }

    /**
     * A bare replacement domain (`new-domain` header): no scheme, path,
     * userinfo, or port — and never a private/local host.
     */
    internal fun validDomain(raw: String?): String? {
        val domain = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (domain.contains("://") || domain.any { it == '/' || it == '@' || it == ':' || it.isWhitespace() }) {
            return null
        }
        if (isPrivateHost(domain)) return null
        return domain
    }

    /**
     * `Content-Disposition` filename as a profile-title fallback. RFC 5987
     * `filename*=UTF-8''…` wins over plain `filename=`; a known container
     * extension (.sub/.txt/.yaml/…) is stripped so the title reads cleanly.
     */
    internal fun contentDispositionFilename(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val params = value.split(';').map { it.trim() }
        val fromStar = params
            .firstOrNull { it.startsWith("filename*=", ignoreCase = true) }
            ?.substringAfter('=')?.trim()?.trim('"')
            ?.takeIf { it.startsWith("utf-8''", ignoreCase = true) }
            ?.substringAfter("''")
            ?.let { runCatching { percentDecode(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        val plain = params
            .firstOrNull { it.startsWith("filename=", ignoreCase = true) }
            ?.substringAfter('=')?.trim()?.trim('"')
            ?.takeIf { it.isNotBlank() }
        val name = fromStar ?: plain ?: return null
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext in STRIPPABLE_EXTENSIONS) name.substringBeforeLast('.') else name
    }

    private fun String.hostOrNull(): String =
        runCatching { this.toHttpUrl().host }.getOrNull() ?: "unknown"

    /**
     * True for hosts that must never be a redirect *target* from a public
     * origin: `localhost`, IPv4/IPv6 literals in loopback/link-local/
     * site-local/CGNAT/unspecified space. Hostnames are left alone —
     * resolving them here would be a DNS lookup the fetch itself repeats.
     */
    internal fun isPrivateHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        val literal = host.removePrefix("[").removeSuffix("]")
        val looksLikeIp = literal.all { it.isDigit() || it == '.' } ||
            literal.contains(':')
        if (!looksLikeIp) return false
        val addr = runCatching { java.net.InetAddress.getByName(literal) }
            .getOrNull() ?: return false
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isAnyLocalAddress ||
            addr.isMulticastAddress
        ) {
            return true
        }
        val raw = addr.address
        // CGNAT 100.64.0.0/10 — JDK has no helper for it.
        if (raw.size == 4 &&
            (raw[0].toInt() and 0xFF) == 100 &&
            ((raw[1].toInt() and 0xFF) in 64..127)
        ) {
            return true
        }
        // IPv6 ULA fc00::/7 — JDK's isSiteLocalAddress only covers fec0::/10.
        return raw.size == 16 && (raw[0].toInt() and 0xFE) == 0xFC
    }

    /**
     * Cleartext policy: http is allowed only when the user opted this
     * subscription in AND the hop we came from was already cleartext — so
     * a mid-chain https→http downgrade is rejected even under the opt-in.
     */
    internal fun isAllowedTransport(
        previous: okhttp3.HttpUrl,
        current: okhttp3.HttpUrl,
        allowInsecure: Boolean,
    ): Boolean = current.isHttps || (allowInsecure && previous.scheme == "http")

    private fun okhttp3.HttpUrl.sameOriginAs(other: okhttp3.HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port
}

