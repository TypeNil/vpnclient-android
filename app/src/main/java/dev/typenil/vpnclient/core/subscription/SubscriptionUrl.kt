package dev.typenil.vpnclient.core.subscription

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Identity of a subscription link, for duplicate detection only (never stored or sent).
 *
 * Equal after: trimming; scheme and host case; default port; the `#fragment` (never sent to a server);
 * trailing slashes of the path (`/sub/` = `/sub`). Kept as is: userinfo, the rest of the path and the
 * query (tokens are case-sensitive and usually live there). Anything that isn't an http(s) URL
 * compares by its trimmed text.
 */
fun normalizedSubscriptionUrl(raw: String): String {
    val trimmed = raw.trim()
    val url = trimmed.toHttpUrlOrNull() ?: return trimmed
    val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().dropLast(1)
    return origin + url.encodedPath.trimEnd('/') + (url.encodedQuery?.let { "?$it" } ?: "")
}

fun sameSubscriptionUrl(a: String, b: String): Boolean = normalizedSubscriptionUrl(a) == normalizedSubscriptionUrl(b)
