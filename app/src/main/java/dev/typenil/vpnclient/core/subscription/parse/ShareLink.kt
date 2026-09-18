package dev.typenil.vpnclient.core.subscription.parse

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Parsed `scheme://[userinfo@]host:port[?query][#fragment]` share link.
 * Query keys/values and the fragment are percent-decoded; userinfo is left raw
 * (callers decode it — some protocols base64-wrap it instead).
 */
internal class ShareLink private constructor(
    val scheme: String,
    val userinfo: String?,
    val host: String,
    val port: Int,
    val params: Map<String, String>,
    val fragment: String?,
) {
    companion object {
        fun parse(line: String): ShareLink {
            val schemeEnd = line.indexOf("://")
            require(schemeEnd > 0) { "missing scheme" }
            val scheme = line.substring(0, schemeEnd).lowercase()
            var rest = line.substring(schemeEnd + 3)

            var fragment: String? = null
            val hash = rest.indexOf('#')
            if (hash >= 0) {
                fragment = percentDecode(rest.substring(hash + 1))
                rest = rest.substring(0, hash)
            }
            var query = ""
            val q = rest.indexOf('?')
            if (q >= 0) {
                query = rest.substring(q + 1)
                rest = rest.substring(0, q)
            }

            val at = rest.lastIndexOf('@')
            val userinfo = if (at >= 0) rest.substring(0, at) else null
            val hostPort = if (at >= 0) rest.substring(at + 1) else rest
            val (host, port) = splitHostPort(hostPort)
            return ShareLink(scheme, userinfo, host, port, parseQuery(query), fragment)
        }

        /** `host:port`, `[v6]:port` — throws on missing/non-numeric port. */
        fun splitHostPort(authority: String): Pair<String, Int> {
            val host: String
            val portStr: String
            if (authority.startsWith("[")) {
                val end = authority.indexOf(']')
                require(end > 0) { "bad ipv6 authority" }
                host = authority.substring(1, end)
                portStr = authority.substring(end + 1).removePrefix(":")
            } else {
                val i = authority.lastIndexOf(':')
                require(i > 0) { "missing port" }
                host = authority.substring(0, i)
                portStr = authority.substring(i + 1)
            }
            val port = portStr.substringBefore('/').toIntOrNull()
                ?: throw IllegalArgumentException("bad port")
            require(host.isNotBlank()) { "missing host" }
            return host to port
        }

        fun parseQuery(query: String): Map<String, String> =
            query.split('&')
                .filter { it.isNotBlank() }
                .associate { part ->
                    val i = part.indexOf('=')
                    if (i < 0) percentDecode(part) to ""
                    else percentDecode(part.substring(0, i)) to percentDecode(part.substring(i + 1))
                }
    }
}

/** First non-null value among alternative param spellings. */
internal fun Map<String, String>.param(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { this[it] }

internal fun truthyParam(value: String?): Boolean =
    value != null && (value == "1" || value.equals("true", ignoreCase = true) || value.equals("yes", ignoreCase = true))

internal fun commaList(value: String?): List<String>? =
    value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

/**
 * Percent-decodes %XX sequences. Unlike [java.net.URLDecoder] it does NOT turn
 * '+' into a space — '+' is a legitimate character in passwords/userinfo.
 */
internal fun percentDecode(value: String): String {
    if (value.indexOf('%') < 0) return value
    val out = ByteArrayOutputStream(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length) {
            val hi = value[i + 1].digitToIntOrNull(16)
            val lo = value[i + 2].digitToIntOrNull(16)
            if (hi != null && lo != null) {
                out.write(hi * 16 + lo)
                i += 3
                continue
            }
        }
        out.write(c.toString().toByteArray(Charsets.UTF_8))
        i++
    }
    return out.toString(Charsets.UTF_8.name())
}

/** Base64 decode tolerant of url-safe alphabets and missing padding. Null on failure. */
internal fun base64Decode(text: String): ByteArray? {
    val cleaned = text.trim()
    if (cleaned.isEmpty()) return null
    val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
    return runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
        ?: runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull()
}
