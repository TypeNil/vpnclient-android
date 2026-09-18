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
    /** Remnawave HWID device-limit rejection. */
    data class DeviceLimitReached(val detail: String?) : SubscriptionError()
    data class RemnawaveError(val statusCode: Int, override val message: String) : SubscriptionError()
}
