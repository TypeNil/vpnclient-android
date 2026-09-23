package dev.typenil.vpnclient.core.subscription.model

import java.time.Instant

/** A user-added subscription source (URL). */
data class SubscriptionProfile(
    val id: Long,
    val name: String,
    val url: String,
    val createdAt: Instant,
    val lastUpdatedAt: Instant?,
    val lastAttemptAt: Instant?,
    val lastError: String?,
    val nodeCount: Int,
    val enabled: Boolean,
    /** Metadata parsed from response headers (Remnawave-compatible). */
    val userInfo: SubscriptionUserInfo?,
    val supportUrl: String?,
    val updateIntervalMinutes: Int?,
    /** Provider announcement text, when the panel publishes one. */
    val announce: String?,
    /** Provider asks the client to refresh this subscription on every launch. */
    val updateAlways: Boolean,
    /** User opted this source into cleartext HTTP fetches. */
    val allowInsecureHttp: Boolean,
)

/** `subscription-userinfo` header data (bytes; expire is epoch seconds). */
@kotlinx.serialization.Serializable
data class SubscriptionUserInfo(
    val uploadBytes: Long,
    val downloadBytes: Long,
    val totalBytes: Long,
    val expireEpochSeconds: Long?,
) {
    val usedBytes: Long get() = uploadBytes + downloadBytes
}

/** What a fetched body looks like after classification. */
enum class SubscriptionFormat {
    /** Newline-separated share links, base64-wrapped. */
    Base64UriList,
    /** Newline-separated share links, plain text. */
    UriList,
    /** A full sing-box JSON config/subscription. */
    SingBoxJson,
    /** Xray JSON config (outbounds[].protocol style). */
    XrayJson,
    /** Clash/Mihomo YAML. */
    ClashYaml,
    Unknown,
}

/** Typed subscription failures — never bare strings. */
sealed class SubscriptionError : Exception() {
    data class Http(val code: Int, override val message: String) : SubscriptionError()
    data object Network : SubscriptionError() {
        override val message = "network unavailable"
    }
    data object Timeout : SubscriptionError() {
        override val message = "request timed out"
    }
    data class TooLarge(val limitBytes: Int) : SubscriptionError()
    data class UnsupportedFormat(val detail: String) : SubscriptionError()
    data class ParseFailed(override val message: String) : SubscriptionError()
    data class EmptyResult(override val message: String = "no usable nodes in subscription") : SubscriptionError()
    /** Parsed nodes fail engine validation — the candidate must not replace
     *  a working subscription. */
    data object ConfigRejected : SubscriptionError() {
        override val message = "nodes rejected by engine validation"
    }
    /** Cleartext HTTP used without the per-subscription opt-in, or an
     *  https→http downgrade redirect. */
    data object InsecureTransport : SubscriptionError() {
        override val message = "insecure transport not allowed for this subscription"
    }
    /** A redirect hop pointed at a loopback/private/link-local address while
     *  the previous hop was public — classic SSRF pivot. The origin URL is
     *  user-confirmed, so private origins stay legal; only the public→private
     *  transition is rejected. */
    data object ForbiddenAddress : SubscriptionError() {
        override val message = "redirect to a private/local address is not allowed"
    }
    /** Remnawave HWID device-limit rejection. */
    data class DeviceLimitReached(val detail: String?) : SubscriptionError()
    /** The subscription row no longer exists (removed mid-flight). */
    data object NotFound : SubscriptionError() {
        override val message = "subscription no longer exists"
    }
}
