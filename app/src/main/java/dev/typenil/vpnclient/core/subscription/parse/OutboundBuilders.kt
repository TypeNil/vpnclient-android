package dev.typenil.vpnclient.core.subscription.parse

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Deterministic node id — stable across re-parses of identical input.
 *
 * Identity is the subscription plus the canonical serialization of the
 * outbound object with its generated `tag` removed: two share links that
 * differ only in display name/tag collapse to one id, while links that
 * share uuid/host/port but differ in transport, TLS, or SNI do not.
 * Canonicalization sorts object keys recursively so input key order
 * (sing-box configs are user-authored) can't perturb the hash.
 */
internal fun stableNodeId(subscriptionId: Long, outbound: JsonObject): String {
    val input = "$subscriptionId|${canonicalJson(JsonObject(outbound - "tag"))}"
    return MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Deterministic serialization: object keys sorted, arrays order-preserved. */
private fun canonicalJson(element: JsonElement): String = when (element) {
    is JsonObject -> element.entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}", separator = ",") {
            "${JsonPrimitive(it.key)}:${canonicalJson(it.value)}"
        }
    is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",") {
        canonicalJson(it)
    }
    else -> element.toString() // JsonPrimitive / JsonNull serialize canonically
}

/**
 * Shared builders for engine-native sing-box outbound objects (v1.13/1.14).
 *
 * Every outbound gets `"domain_resolver": "local"` so a proxy-domain is resolved
 * through the local DNS resolver instead of looping through the proxy itself.
 * Output is compact — [JsonObject.toString] is the serialized form.
 */

/** Networks sing-box cannot express — nodes on them must be skipped entirely. */
internal fun isUnsupportedNetwork(network: String?): Boolean =
    network?.lowercase() in setOf("xhttp", "splithttp")

/** `"tls"` block shared by all TLS-capable outbounds. */
internal fun tlsBlock(
    serverName: String?,
    insecure: Boolean = false,
    alpn: List<String>? = null,
    fingerprint: String? = null,
    realityPublicKey: String? = null,
    realityShortId: String? = null,
    /** Base64 ECHConfigList — passed through verbatim to `tls.ech.config`. */
    ech: String? = null,
): JsonObject = buildJsonObject {
    put("enabled", true)
    if (!serverName.isNullOrBlank()) put("server_name", serverName)
    if (insecure) put("insecure", true)
    if (!alpn.isNullOrEmpty()) putJsonArray("alpn") { alpn.forEach { add(it) } }
    putJsonObject("utls") {
        put("enabled", true)
        put("fingerprint", fingerprint ?: "chrome")
    }
    if (!realityPublicKey.isNullOrBlank()) {
        putJsonObject("reality") {
            put("enabled", true)
            put("public_key", realityPublicKey)
            if (!realityShortId.isNullOrBlank()) put("short_id", realityShortId)
        }
    }
    if (!ech.isNullOrBlank()) {
        putJsonObject("ech") {
            put("enabled", true)
            put("config", ech)
        }
    }
}

/** `"transport"` block; null for tcp-like networks (tcp/none/raw/empty/unmapped). */
internal fun transportBlock(
    network: String?,
    host: String? = null,
    path: String? = null,
    serviceName: String? = null,
    maxEarlyData: Int? = null,
    earlyDataHeaderName: String? = null,
): JsonObject? = when (network?.lowercase()) {
    "ws" -> buildJsonObject {
        put("type", "ws")
        put("path", path ?: "/")
        if (!host.isNullOrBlank()) putJsonObject("headers") { put("Host", host) }
        if (maxEarlyData != null) put("max_early_data", maxEarlyData)
        if (!earlyDataHeaderName.isNullOrBlank()) put("early_data_header_name", earlyDataHeaderName)
    }
    "grpc" -> buildJsonObject {
        put("type", "grpc")
        if (!serviceName.isNullOrBlank()) put("service_name", serviceName)
    }
    "httpupgrade" -> buildJsonObject {
        put("type", "httpupgrade")
        if (!host.isNullOrBlank()) put("host", host)
        put("path", path ?: "/")
    }
    "http", "h2" -> buildJsonObject {
        put("type", "http")
        if (!host.isNullOrBlank()) put("host", host)
        if (!path.isNullOrBlank()) put("path", path)
        put("method", "GET")
    }
    else -> null
}

internal fun vlessOutbound(
    tag: String, server: String, port: Int, uuid: String,
    flow: String? = null,
    tls: JsonObject? = null,
    transport: JsonObject? = null,
    packetEncoding: String? = null,
): JsonObject = baseOutbound("vless", tag, server, port) {
    put("uuid", uuid)
    if (!flow.isNullOrBlank()) put("flow", flow)
    if (!packetEncoding.isNullOrBlank()) put("packet_encoding", packetEncoding)
    if (tls != null) put("tls", tls)
    if (transport != null) put("transport", transport)
}

internal fun vmessOutbound(
    tag: String, server: String, port: Int, uuid: String,
    security: String? = null,
    alterId: Int = 0,
    tls: JsonObject? = null,
    transport: JsonObject? = null,
    packetEncoding: String? = null,
): JsonObject = baseOutbound("vmess", tag, server, port) {
    put("uuid", uuid)
    put("security", security?.takeIf { it.isNotBlank() } ?: "auto")
    put("alter_id", alterId)
    put("authenticated_length", true)
    put("global_padding", false)
    if (!packetEncoding.isNullOrBlank()) put("packet_encoding", packetEncoding)
    if (tls != null) put("tls", tls)
    if (transport != null) put("transport", transport)
}

internal fun trojanOutbound(
    tag: String, server: String, port: Int, password: String,
    tls: JsonObject? = null,
    transport: JsonObject? = null,
): JsonObject = baseOutbound("trojan", tag, server, port) {
    put("password", password)
    if (tls != null) put("tls", tls)
    if (transport != null) put("transport", transport)
}

internal fun shadowsocksOutbound(
    tag: String, server: String, port: Int,
    method: String, password: String,
    plugin: String? = null,
    pluginOpts: String? = null,
): JsonObject = baseOutbound("shadowsocks", tag, server, port) {
    put("method", method)
    put("password", password)
    if (!plugin.isNullOrBlank()) {
        put("plugin", plugin)
        if (!pluginOpts.isNullOrBlank()) put("plugin_opts", pluginOpts)
    }
    // `network` omitted — sing-box default enables TCP+UDP; "tcp_and_udp" is
    // rejected by checkConfig on 1.14.
}

internal fun hysteria2Outbound(
    tag: String, server: String, port: Int, password: String,
    tls: JsonObject,
    obfsPassword: String? = null,
): JsonObject = baseOutbound("hysteria2", tag, server, port) {
    put("password", password)
    put("tls", tls)
    if (obfsPassword != null) {
        putJsonObject("obfs") {
            put("type", "salamander")
            put("password", obfsPassword)
        }
    }
}

internal fun tuicOutbound(
    tag: String, server: String, port: Int,
    uuid: String, password: String,
    congestionControl: String? = null,
    udpRelayMode: String? = null,
    tls: JsonObject,
): JsonObject = baseOutbound("tuic", tag, server, port) {
    put("uuid", uuid)
    put("password", password)
    put("congestion_control", congestionControl?.takeIf { it.isNotBlank() } ?: "bbr")
    put("udp_relay_mode", udpRelayMode?.takeIf { it.isNotBlank() } ?: "native")
    put("tls", tls)
}

internal fun anytlsOutbound(
    tag: String, server: String, port: Int, password: String,
    tls: JsonObject,
): JsonObject = baseOutbound("anytls", tag, server, port) {
    put("password", password)
    put("tls", tls)
}

/**
 * WireGuard as a sing-box `endpoints[]` entry — the `wireguard` *outbound*
 * was removed in sing-box 1.13 (pinned core is 1.14.x); endpoints are the
 * supported shape and are referenceable from selector/urltest groups.
 */
internal fun wireguardEndpoint(
    tag: String, server: String, port: Int,
    privateKey: String,
    peerPublicKey: String,
    localAddress: List<String>,
    preSharedKey: String? = null,
    reserved: List<Int>? = null,
    mtu: Int? = null,
): JsonObject = buildJsonObject {
    put("type", "wireguard")
    put("tag", tag)
    putJsonArray("address") { localAddress.forEach { add(it) } }
    put("private_key", privateKey)
    putJsonArray("peers") {
        addJsonObject {
            put("address", server)
            put("port", port)
            put("public_key", peerPublicKey)
            putJsonArray("allowed_ips") {
                add("0.0.0.0/0")
                add("::/0")
            }
            if (!preSharedKey.isNullOrBlank()) put("pre_shared_key", preSharedKey)
            if (!reserved.isNullOrEmpty()) {
                putJsonArray("reserved") { reserved.forEach { add(it) } }
            }
        }
    }
    if (mtu != null) put("mtu", mtu)
    // Same resolver rule as outbounds: the peer's domain resolves locally,
    // never through the tunnel the endpoint is part of.
    put("domain_resolver", "local")
}

internal fun socksOutbound(
    tag: String, server: String, port: Int,
    username: String? = null,
    password: String? = null,
): JsonObject = baseOutbound("socks", tag, server, port) {
    put("version", "5")
    if (!username.isNullOrBlank()) put("username", username)
    if (!password.isNullOrBlank()) put("password", password)
}

private fun baseOutbound(
    type: String, tag: String, server: String, port: Int,
    extra: JsonObjectBuilder.() -> Unit,
): JsonObject = buildJsonObject {
    put("type", type)
    put("tag", tag)
    put("server", server)
    put("server_port", port)
    extra()
    put("domain_resolver", "local")
}
