package dev.typenil.vpnclient.core.subscription.model

/** Proxy protocols the client understands. */
enum class ProtocolType(val scheme: String, val label: String) {
    VLESS("vless", "VLESS"),
    VMESS("vmess", "VMess"),
    TROJAN("trojan", "Trojan"),
    SHADOWSOCKS("ss", "Shadowsocks"),
    HYSTERIA2("hysteria2", "Hysteria2"),
    TUIC("tuic", "TUIC"),
    SOCKS("socks", "SOCKS"),
    HTTP("http", "HTTP"),
    ANYTLS("anytls", "AnyTLS"),
    WIREGUARD("wireguard", "WireGuard"),
    OTHER("", "Other"),
    ;

    companion object {
        fun fromScheme(scheme: String): ProtocolType? = when (scheme.lowercase()) {
            "vless" -> VLESS
            "vmess" -> VMESS
            "trojan" -> TROJAN
            "ss", "shadowsocks" -> SHADOWSOCKS
            "hysteria2", "hy2" -> HYSTERIA2
            "tuic" -> TUIC
            "socks", "socks5" -> SOCKS
            "http", "https" -> HTTP
            "anytls" -> ANYTLS
            "wireguard", "wg" -> WIREGUARD
            else -> null
        }
    }
}
