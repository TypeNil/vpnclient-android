package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.parse.percentDecode

/**
 * Maps an incoming share/deep-link intent to a subscription URL.
 *
 * Recognized sources:
 * - bare `http(s)://` links (ACTION_VIEW on a subscription URL, or a shared
 *   link via ACTION_SEND text/plain);
 * - `sing-box://import-remote-profile?url=…` (sing-box / SFA / husi);
 * - `clash://install-config?url=…` and `clashmeta://install-config?url=…`.
 *
 * Pure string parsing — no android.net.Uri so it stays JVM-testable.
 */
object ImportUrlExtractor {

    private const val ACTION_SEND = "android.intent.action.SEND"

    fun extract(action: String?, data: String?, extraText: String?): String? {
        val raw = when (action) {
            ACTION_SEND -> extraText
            else -> data
        }?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // Schemes are case-insensitive per RFC 3986 — OEM browsers and
        // keyboards emit HTTPS://… often enough to matter.
        if (raw.startsWithHttp()) return raw
        return when (raw.substringBefore("://").lowercase()) {
            "sing-box", "clash", "clashmeta" -> extractUrlParam(raw)
            else -> null
        }
    }

    private fun extractUrlParam(uri: String): String? {
        val query = uri.substringAfter('?', "")
        // url= runs to end-of-string: splitting the query on '&' truncates
        // subscription URLs carrying unencoded '&' params (the same defect
        // as Uri.getQueryParameter). `name` is the only other param these
        // schemes define and is conventionally last — a literal "&name="
        // inside an unencoded URL is ambiguous, so the last occurrence wins.
        val start = when {
            query.startsWith("url=") -> "url=".length
            else -> query.indexOf("&url=")
                .takeIf { it >= 0 }
                ?.plus("&url=".length)
                ?: return null
        }
        val encoded = query.substring(start).substringBeforeLast("&name=")
        // percentDecode, not URLDecoder: '+' is a literal in a nested URL's
        // query (e.g. ?token=a+b) — form semantics would corrupt it to 'a b'.
        return percentDecode(encoded).takeIf { it.startsWithHttp() }
    }

    private fun String.startsWithHttp(): Boolean =
        startsWith("https://", ignoreCase = true) ||
            startsWith("http://", ignoreCase = true)
}
