package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.common.log.Redactor
import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.core.subscription.model.SubscriptionUserInfo
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun fetch(url: String, hwid: String?): FetchedSubscription =
        withContext(Dispatchers.IO) {
            val origin = try {
                url.toHttpUrl()
            } catch (e: Exception) {
                throw SubscriptionError.ParseFailed("bad url")
            }
            var current = origin
            var redirectsLeft = MAX_REDIRECTS
            while (true) {
                val sendHwid = hwid != null && current.host == origin.host
                val request = Request.Builder()
                    .url(current)
                    .header("User-Agent", USER_AGENT)
                    .apply {
                        if (sendHwid) {
                            header("x-hwid", hwid!!)
                            header("x-device-os", "android")
                            header("x-ver-os", android.os.Build.VERSION.RELEASE ?: "unknown")
                            header("x-device-model", android.os.Build.MODEL ?: "unknown")
                        }
                    }
                    .build()

                val response = try {
                    noRedirectClient.newCall(request).execute()
                } catch (e: java.net.SocketTimeoutException) {
                    throw SubscriptionError.Timeout
                } catch (e: IOException) {
                    throw SubscriptionError.Network
                }

                if (response.isRedirect && redirectsLeft > 0) {
                    val location = response.header("Location")
                    response.close()
                    if (location == null) throw SubscriptionError.Http(response.code, current.host)
                    current = current.resolve(location)
                        ?: throw SubscriptionError.Http(response.code, current.host)
                    redirectsLeft--
                    continue
                }

                return@withContext readResponse(response, url)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
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
                    profileTitle = decodeHeaderValue(headers["profile-title"]),
                    userInfo = parseUserInfo(headers["subscription-userinfo"]),
                    supportUrl = headers["profile-web-page-url"] ?: headers["support-url"],
                    announce = decodeHeaderValue(headers["announce"]),
                    updateIntervalMinutes = headers["profile-update-interval"]?.toIntOrNull(),
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

    private fun String.hostOrNull(): String =
        runCatching { this.toHttpUrl().host }.getOrNull() ?: "unknown"
}
