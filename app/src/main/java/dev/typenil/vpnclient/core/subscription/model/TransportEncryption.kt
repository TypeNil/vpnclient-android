package dev.typenil.vpnclient.core.subscription.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Configured confidentiality to the endpoint, not certificate trust or a live probe. */
fun isEncrypted(node: ProxyNode): Boolean = node.outbound()?.encrypted() == true

/** The local sidecar exception does not turn plaintext into encrypted transport. */
fun isTunnelAllowed(node: ProxyNode): Boolean {
    val outbound = node.outbound() ?: return false
    return outbound.encrypted() ||
        (outbound.text("type") in SUPPORTED_TYPES && isLoopbackServer(outbound.text("server")))
}

internal fun isLoopbackServer(server: String?): Boolean {
    if (server.equals("localhost", ignoreCase = true)) return true
    val octets = server?.split('.') ?: return false
    return octets.size == 4 && octets.first() == "127" && octets.all {
        it.isNotEmpty() && it.all { ch -> ch in '0'..'9' } &&
            (it.length == 1 || it.first() != '0') && it.toIntOrNull() in 0..255
    }
}

private fun ProxyNode.outbound(): JsonObject? =
    try {
        Json.parseToJsonElement(outboundJson) as? JsonObject
    } catch (_: Exception) {
        null
    }

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.encrypted(): Boolean {
    val tls = (this["tls"] as? JsonObject)?.get("enabled") as? JsonPrimitive
    val tlsEnabled = tls?.isString == false && tls.booleanOrNull == true
    return when (text("type")) {
        "http", "vless", "trojan", "hysteria2", "tuic", "anytls" -> tlsEnabled
        "vmess" -> {
            val security = if ("security" !in this) "auto" else text("security")?.trim()?.lowercase()
            tlsEnabled || security in setOf("", "auto", "aes-128-gcm", "chacha20-poly1305")
        }
        "shadowsocks" -> text("method")?.trim()?.lowercase() in ENCRYPTED_SS_METHODS
        "wireguard" -> true // Native validation checks endpoint keys and peers.
        else -> false // SOCKS has no TLS field; plugins/unknown types prove nothing.
    }
}

private val SUPPORTED_TYPES = setOf(
    "http", "socks", "vless", "trojan", "vmess", "shadowsocks",
    "hysteria2", "tuic", "anytls", "wireguard",
)

// Supported legacy methods encrypt too; this is not an integrity/modern-cipher endorsement.
private val ENCRYPTED_SS_METHODS = setOf(
    "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
    "aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
    "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
    "aes-128-ctr", "aes-192-ctr", "aes-256-ctr",
    "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
    "rc4-md5", "chacha20-ietf", "xchacha20",
)
