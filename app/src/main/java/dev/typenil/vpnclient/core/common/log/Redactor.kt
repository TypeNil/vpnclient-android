package dev.typenil.vpnclient.core.common.log

/**
 * Redacts credentials and identifiers from strings before they reach logs or
 * exported diagnostics. Everything derived from subscription URLs, node URIs or
 * engine output must pass through here.
 */
object Redactor {

    private val uuidRegex =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    // vless://uuid@host:port, trojan://password@host, ss://base64@host
    private val userInfoAtHost = Regex("(?<=://)[^/@\\s]+@")

    // Sensitive query parameters in share links and subscription URLs.
    private val sensitiveParams = Regex(
        "([?&](?:password|passwd|token|uuid|id|key|pbk|sid|private_key|seed|secret|access_token|hwid)=)[^&#\\s]*",
        RegexOption.IGNORE_CASE,
    )

    // Authorization-style header values.
    private val bearerRegex = Regex("(Bearer\\s+)[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE)

    // Long opaque base64/hex blobs (≥20 chars) that are likely keys or tokens.
    private val longTokenRegex = Regex("\\b[A-Za-z0-9+/=_-]{20,}={0,2}\\b")

    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        return text
            .replace(bearerRegex, "$1<redacted>")
            .replace(uuidRegex, "<uuid>")
            .replace(sensitiveParams, "$1<redacted>")
            .replace(userInfoAtHost, "<redacted>@")
            .replace(longTokenRegex, "<token>")
    }

    /** Redact a URL for display: keep scheme + host + port, hide path/query/userinfo. */
    fun urlForDisplay(url: String): String = try {
        val uri = java.net.URI(url)
        buildString {
            append(uri.scheme ?: "").append("://")
            if (!uri.userInfo.isNullOrEmpty()) append("<redacted>@")
            append(uri.host ?: "")
            if (uri.port >= 0) append(':').append(uri.port)
            if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") append("/…")
        }
    } catch (_: Exception) {
        redact(url)
    }
}
