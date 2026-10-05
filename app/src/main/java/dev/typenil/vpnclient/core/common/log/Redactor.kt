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

    private val coreUrl = Regex("[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s<>]+")
    private val coreField = Regex(
        "(\\b(?:password|passwd|token|uuid|public[_ -]?key|private[_ -]?key|pbk|short[_ -]?id|sid|secret|authorization|name|tag)\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)",
        RegexOption.IGNORE_CASE,
    )
    private val coreAddress = Regex(
        "\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b|" +
            "(?<![\\w:])(?:[0-9a-fA-F]{0,4}:){2,}[0-9a-fA-F:.]*(?:%[\\w]+)?|" +
            "\\b(?:[a-zA-Z0-9_-]+\\.)+[a-zA-Z]{2,}\\b",
    )

    /** Native colour escapes and other control chars are noise (and could split a secret). */
    internal val terminalControl = Regex("\\u001B\\[[0-9;?]*[ -/]*[@-~]|[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")

    /** Core diagnostics hide endpoints too. Context covers arbitrary names and
     * short credentials that cannot be distinguished from ordinary prose. */
    fun redactCore(text: String, sensitiveValues: Collection<String> = emptyList()): String {
        var safe = text.replace(terminalControl, "")
        for (value in sensitiveValues.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            safe = safe.replace(value, "<redacted>", ignoreCase = true)
        }
        return redact(safe.replace(coreUrl, "<url>").replace(coreField, "$1<redacted>"))
            .replace(coreAddress, "<address>")
            .replace('\r', ' ').replace('\n', ' ')
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
