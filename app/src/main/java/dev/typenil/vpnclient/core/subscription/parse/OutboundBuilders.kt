package dev.typenil.vpnclient.core.subscription.parse

import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Shared builders for engine-native sing-box outbound objects (v1.13/1.14).
 *
 * Every outbound gets `"domain_resolver": "local"` so a proxy-domain is resolved
 * through the local DNS resolver instead of looping through the proxy itself.
 * Output is compact — [JsonObject.toString] is the serialized form.
 */

/** Deterministic node id — stable across re-parses of identical input. */
internal fun stableNodeId(subscriptionId: Long, scheme: String, server: String, port: Int, credential: String): String {
    val input = "$subscriptionId|${scheme.lowercase()}|${server.lowercase()}:$port|$credential"
    return MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

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
