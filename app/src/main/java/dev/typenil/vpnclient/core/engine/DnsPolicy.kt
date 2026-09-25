package dev.typenil.vpnclient.core.engine

import dev.typenil.vpnclient.core.common.log.Redactor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

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
        override val hostname =
            spec
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore(':')
                .let { host ->
                    runCatching { InetAddress.getByName(host) }.getOrNull() == null ||
                        host.any { it.isLetter() }
                }
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

        private fun isIpLiteral(s: String): Boolean =
            runCatching {
                InetAddress.getByName(s)
            }.getOrNull()?.let {
                // getByName resolves hostnames too — require the input to be
                // numeric (digits/dots/colons only) to count as a literal.
                s.all { it.isDigit() || it == '.' || it == ':' }
            } == true
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
