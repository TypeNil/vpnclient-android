package dev.typenil.vpnclient.core.engine

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Single gate for user-authored rule patterns. The value is stored verbatim
 * in Room and read back into the engine config, so anything that isn't a
 * compilable matcher must be rejected here — a bad spec would otherwise
 * fail the next connect inside checkConfig.
 *
 * Returns the canonical pattern (trimmed, lowercased, bare IPs given their
 * /32 or /128 prefix) or null when the input is invalid.
 *
 * Pure JVM — no engine calls, and IP_CIDR never resolves a name: IPv4 is
 * parsed octet-by-octet, and ':'-bearing text can only be an IPv6 literal
 * (a hostname cannot contain ':').
 */
object RoutingRuleValidator {

    /** Validate [raw] for [kind]; null when the pattern is not compilable. */
    fun validatePattern(
        kind: RoutingRule.Kind,
        raw: String,
    ): String? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        return when (kind) {
            // domain_suffix: a literal domain or leading-dot suffix. Strip
            // a leading "*." so "*.example.com" stores as "example.com".
            RoutingRule.Kind.DOMAIN -> {
                val host = s.removePrefix("*.").removeSuffix(".")
                val valid = host.isNotEmpty() && host.length <= 253 &&
                    host.split('.').all { label ->
                        label.isNotEmpty() && label.length <= 63 &&
                            label.all { it.isLetterOrDigit() || it == '-' } &&
                            !label.startsWith('-') && !label.endsWith('-')
                    }
                host.takeIf { valid && it.contains('.') }
            }
            // CIDR notation only — a bare IP gets its /32 (v4) or /128
            // (v6) appended so the user can type either. The prefix must
            // match the address family.
            RoutingRule.Kind.IP_CIDR -> {
                val withPrefix =
                    if ('/' in s) {
                        s
                    } else {
                        s + if (':' in s) "/128" else "/32"
                    }
                val host = withPrefix.substringBefore('/')
                val bitsText = withPrefix.substringAfter('/')
                // Digits only and short enough for toInt: toIntOrNull would
                // also accept "+8"/"-1", and a long digit run would overflow.
                if (bitsText.isEmpty() || bitsText.length > 3 || bitsText.any { it !in '0'..'9' }) {
                    return null
                }
                val bits = bitsText.toInt()
                val isV6 = ':' in host
                if (bits !in 0..(if (isV6) 128 else 32)) return null
                // Canonical decimal prefix — strips leading zeros.
                "$host/$bits".takeIf { if (isV6) isValidIpv6(host) else isValidIpv4(host) }
            }
            // A single port or an ordered range "8000:8080".
            RoutingRule.Kind.PORT -> {
                val parts = s.split(':')
                if (parts.size > 2 || parts.any { !isValidPort(it) }) return null
                if (parts.size == 2 && parts[0].toInt() > parts[1].toInt()) return null
                s
            }
        }
    }

    /** Strict dotted-quad: exactly four octets 0..255. Leading zeros are
     *  rejected ("01.2.3.4") — resolvers differ on reading them as octal,
     *  so the same text can mean two different addresses. */
    private fun isValidIpv4(host: String): Boolean =
        host.split('.').let { parts ->
            parts.size == 4 && parts.all { octet ->
                octet.isNotEmpty() && octet.length <= 3 && octet.all { it in '0'..'9' } &&
                    !(octet.length > 1 && octet[0] == '0') && octet.toInt() in 0..255
            }
        }

    /** ':'-bearing text is parsed as an IPv6 literal only — a hostname
     *  cannot contain ':', so no DNS lookup can happen. The charset check
     *  keeps zone indices ("fe80::1%eth0") out before the parse; requiring
     *  an Inet6Address result also rejects IPv4-mapped forms like
     *  "::ffff:1.2.3.4", which the resolver reports as Inet4Address —
     *  type the plain IPv4 instead. */
    private fun isValidIpv6(host: String): Boolean =
        host.isNotEmpty() && host.all { it in IPV6_CHARS } &&
            runCatching { InetAddress.getByName(host) as? Inet6Address }.getOrNull() != null

    /** Non-empty, at most five ASCII digits, 1..65535. */
    private fun isValidPort(text: String): Boolean =
        text.isNotEmpty() && text.length <= 5 && text.all { it in '0'..'9' } &&
            text.toInt() in 1..65535

    private const val IPV6_CHARS = "0123456789abcdefABCDEF:."
}
