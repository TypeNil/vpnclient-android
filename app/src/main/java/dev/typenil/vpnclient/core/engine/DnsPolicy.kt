package dev.typenil.vpnclient.core.engine

import dev.typenil.vpnclient.core.common.log.Redactor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull


/**
 * DNS resolution mode — persisted in DataStore as [key].
 *
 * [POLICY]: DNS follows the RouteMode (RU/Blocked lists get their per-mode
 * resolver; default stays the proxied DoH). The honest promise: user-domain
 * queries ride the selected proxy; only *bootstrap* names (the DoH/DoT
 * server's own hostname, proxy server names) resolve via the local/system
 * resolver — that bootstrap is required to break the remote→proxy→remote
 * loop and is always documented, never hidden.
 *
 * [PROXY_ONLY]: every user DNS query goes through the proxied upstream;
 * `dns.final` is `remote` and no mode rule maps traffic to `local`.
 * Bootstrap still uses `local` (it only ever resolves infrastructure
 * names — no user traffic leaks there).
 */
enum class DnsMode(
    val key: String,
) {
    POLICY("policy"),
    PROXY_ONLY("proxy_only"),
    ;

    companion object {
        fun fromKey(key: String?): DnsMode = entries.firstOrNull { it.key == key } ?: POLICY
    }
}

/**
 * The upstream resolver choice. Presets are fixed (versioned) entries —
 * [custom] is a user-entered spec validated by [parseCustom].
 *
 * Persisted as [key] in DataStore: preset keys or `"custom:<spec>"`.
 */
sealed class DnsUpstream {
    abstract val key: String

    /** sing-box `server` URL for the engine config. */
    abstract val serverUrl: String

    /** Whether the server name is a hostname (needs bootstrap resolution). */
    abstract val hostname: Boolean

    /** User-facing short label (resource-resolved at the UI). */
    abstract val labelResName: String

    data object Cloudflare : DnsUpstream() {
        override val key = "cloudflare"
        override val serverUrl = "https://1.1.1.1/dns-query"
        override val hostname = false
        override val labelResName = "dns_upstream_cloudflare"
    }

    data object Google : DnsUpstream() {
        override val key = "google"
        override val serverUrl = "https://8.8.8.8/dns-query"
        override val hostname = false
        override val labelResName = "dns_upstream_google"
    }

    data object Quad9 : DnsUpstream() {
        override val key = "quad9"
        override val serverUrl = "https://9.9.9.9/dns-query"
        override val hostname = false
        override val labelResName = "dns_upstream_quad9"
    }

    data object AdGuard : DnsUpstream() {
        override val key = "adguard"
        override val serverUrl = "https://dns.adguard-dns.com/dns-query"
        override val hostname = true
        override val labelResName = "dns_upstream_adguard"
    }

    data class Custom(
        val spec: String,
    ) : DnsUpstream() {
        override val key = "custom:$spec"
        override val serverUrl = spec
        // Syntax check only — never resolve the host: this property runs on
        // the UI/main path during settings read and would otherwise fire a
        // real DNS lookup per custom upstream.
        override val hostname =
            spec
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore(':')
                .let { host -> !isIpLiteral(host) }
        override val labelResName = "dns_upstream_custom"
    }

    companion object {
        val presets = listOf(Cloudflare, Google, Quad9, AdGuard)
        val default = Cloudflare

        fun fromKey(key: String?): DnsUpstream {
            if (key == null) return default
            // Re-validate rather than trusting the store: a corrupt or
            // hand-edited value would otherwise compile verbatim into the
            // engine config and fail the whole connect in checkConfig.
            if (key.startsWith("custom:")) {
                return parseCustom(key.removePrefix("custom:")) ?: default
            }
            return presets.firstOrNull { it.key == key } ?: default
        }

        /**
         * Validate a user-entered resolver spec into its canonical form.
         * Accepts: `https://host/path` (DoH), `tls://host` (DoT), a bare IPv4
         * (plain UDP/53), or `udp://IP`. Rejects anything else — the value is
         * compiled verbatim into the engine config, so an unvalidated string
         * would fail the whole connect inside `checkConfig`.
         */
        fun parseCustom(input: String): Custom? {
            val s = input.trim()
            if (s.isEmpty()) return null
            return when {
                s.startsWith("https://") -> {
                    val url = s.toHttpUrlOrNull() ?: return null
                    if (url.host.isBlank() || url.username.isNotEmpty() ||
                        url.password.isNotEmpty() || url.querySize > 0
                    ) {
                        return null
                    }
                    Custom(s)
                }

                s.startsWith("tls://") || s.startsWith("quic://") -> {
                    val host = s.substringAfter("://").substringBefore(':').substringBefore('/')
                    if (host.isBlank() || host.any { it.isWhitespace() }) return null
                    Custom(s)
                }

                s.startsWith("udp://") -> {
                    // host[:port] — keep the user's port verbatim; only
                    // validate that the host is a literal (a hostname here
                    // would need bootstrap we don't emit for udp).
                    val rest = s.removePrefix("udp://")
                    val host = rest.substringBefore(':')
                    val port = rest.substringAfter(':', "")
                    if (!isIpLiteral(host)) return null
                    if (port.isNotEmpty() && port.toIntOrNull() !in 1..65535) return null
                    Custom("udp://$rest")
                }

                isIpLiteral(s) -> {
                    Custom("udp://$s")
                }

                else -> {
                    null
                }
            }
        }

        /** Syntax-only IP check — no DNS resolution. IPv4: dotted quad,
         *  each octet 0–255. IPv6: hex/colon groups, at least one ':' and
         *  no characters outside hex+colon. Anything else is a hostname. */
        private fun isIpLiteral(s: String): Boolean {
            if (s.isEmpty()) return false
            return when {
                // IPv4
                '.' in s && ':' !in s -> {
                    val parts = s.split('.')
                    parts.size == 4 && parts.all { p ->
                        p.isNotEmpty() && p.length <= 3 &&
                            p.all { it.isDigit() } && p.toIntOrNull()?.let { it in 0..255 } == true
                    }
                }
                // IPv6 — has at least one ':', only hex digits + colons,
                // groups within bounds (single '::' compression allowed).
                ':' in s -> {
                    s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' } &&
                        !s.contains(":::")
                }
                else -> false
            }
        }
    }
}

/** Compiled DNS profile — carried on EngineConfig for applied-vs-saved UI. */
data class DnsProfile(
    val mode: DnsMode,
    val upstream: DnsUpstream,
) {
    /** Safe display label — never logs the raw custom spec's credentials-free
     *  host only; for Custom the host is what the user typed (their own data). */
    val summary: String =
        when (upstream) {
            is DnsUpstream.Custom -> "custom:${Redactor.urlForDisplay(upstream.spec)}"
            else -> "${mode.key}:${upstream.key}"
        }
}
