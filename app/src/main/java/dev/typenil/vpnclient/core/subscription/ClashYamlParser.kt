package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.core.subscription.parse.hysteria2Outbound
import dev.typenil.vpnclient.core.subscription.parse.isUnsupportedNetwork
import dev.typenil.vpnclient.core.subscription.parse.shadowsocksOutbound
import dev.typenil.vpnclient.core.subscription.parse.stableNodeId
import dev.typenil.vpnclient.core.subscription.parse.tlsBlock
import dev.typenil.vpnclient.core.subscription.parse.transportBlock
import dev.typenil.vpnclient.core.subscription.parse.trojanOutbound
import dev.typenil.vpnclient.core.subscription.parse.tuicOutbound
import dev.typenil.vpnclient.core.subscription.parse.vlessOutbound
import dev.typenil.vpnclient.core.subscription.parse.vmessOutbound
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import org.yaml.snakeyaml.Yaml

/**
 * Extracts nodes from a Clash/Mihomo config's `proxies:` list and re-emits them
 * as sing-box outbound JSON via the shared builders. Unknown proxy types
 * (ssr, hysteria1, snell, socks5, http, …) and `proxy-groups` are skipped.
 */
@Singleton
class ClashYamlParser @Inject constructor() : SubscriptionParser {

    override fun parse(body: String, subscriptionId: Long): List<ProxyNode> {
        val loaded = try {
            Yaml().load<Any?>(body)
        } catch (e: Exception) {
            throw SubscriptionError.ParseFailed("invalid clash yaml")
        }
        val root = loaded as? Map<*, *> ?: throw SubscriptionError.ParseFailed("not a clash config")
        val proxies = root["proxies"] as? List<*> ?: throw SubscriptionError.EmptyResult()

        val nodes = proxies.mapNotNull { it as? Map<*, *> }
            .mapNotNull { proxy -> runCatching { toNode(proxy, subscriptionId) }.getOrNull() }
        if (nodes.isEmpty()) throw SubscriptionError.EmptyResult()
        return nodes
    }

    private fun toNode(m: Map<*, *>, subscriptionId: Long): ProxyNode? {
        val type = m.str("type")?.lowercase() ?: return null
        val name = m.str("name")
        val server = m.str("server") ?: ""
        val port = m.int("port") ?: 0
        val network = m.str("network") ?: "tcp"
        if (isUnsupportedNetwork(network)) return null

        val (protocol, credential, outbound) = when (type) {
            "vless" -> {
                val uuid = m.str("uuid") ?: return null
                val reality = m.map("reality-opts")
                val tlsEnabled = reality != null || m.bool("tls")
                    || m.str("security")?.lowercase() in setOf("tls", "reality")
                val tls = if (tlsEnabled) tlsBlock(
                    serverName = m.str("servername") ?: m.str("sni"),
                    insecure = m.bool("skip-cert-verify"),
                    alpn = m.strList("alpn"),
                    fingerprint = m.str("client-fingerprint") ?: m.str("fingerprint"),
                    realityPublicKey = reality?.str("public-key"),
                    realityShortId = reality?.str("short-id"),
                ) else null
                Triple(ProtocolType.VLESS, uuid) { tag: String ->
                    vlessOutbound(
                        tag, server, port, uuid,
                        flow = m.str("flow"),
                        tls = tls,
                        transport = clashTransport(m, network),
                        packetEncoding = m.str("packet-encoding"),
                    )
                }
            }
            "vmess" -> {
                val uuid = m.str("uuid") ?: return null
                val tls = if (m.bool("tls")) tlsBlock(
                    serverName = m.str("servername") ?: m.str("sni"),
                    insecure = m.bool("skip-cert-verify"),
                    alpn = m.strList("alpn"),
                    fingerprint = m.str("client-fingerprint") ?: m.str("fingerprint"),
                ) else null
                Triple(ProtocolType.VMESS, uuid) { tag: String ->
                    vmessOutbound(
                        tag, server, port, uuid,
                        security = m.str("cipher"),
                        alterId = m.int("alterId") ?: m.int("alter-id") ?: 0,
                        tls = tls,
                        transport = clashTransport(m, network),
                        packetEncoding = m.str("packet-encoding"),
                    )
                }
            }
            "trojan" -> {
                val password = m.str("password") ?: return null
                val tls = tlsBlock(
                    serverName = m.str("sni") ?: m.str("servername") ?: server,
                    insecure = m.bool("skip-cert-verify"),
                    alpn = m.strList("alpn"),
                    fingerprint = m.str("client-fingerprint") ?: m.str("fingerprint"),
                )
                Triple(ProtocolType.TROJAN, password) { tag: String ->
                    trojanOutbound(tag, server, port, password, tls = tls, transport = clashTransport(m, network))
                }
            }
            "ss", "shadowsocks" -> {
                val method = m.str("cipher") ?: return null
                val password = m.str("password") ?: return null
                Triple(ProtocolType.SHADOWSOCKS, password) { tag: String ->
                    shadowsocksOutbound(tag, server, port, method, password)
                }
            }
            "hysteria2", "hy2" -> {
                val password = m.str("password") ?: return null
                val tls = tlsBlock(
                    serverName = m.str("sni") ?: m.str("servername") ?: server,
                    insecure = m.bool("skip-cert-verify"),
                )
                val obfsPassword = if (m.str("obfs") != null) m.str("obfs-password").orEmpty() else null
                Triple(ProtocolType.HYSTERIA2, password) { tag: String ->
                    hysteria2Outbound(tag, server, port, password, tls = tls, obfsPassword = obfsPassword)
                }
            }
            "tuic" -> {
                val uuid = m.str("uuid") ?: return null
                val password = m.str("password") ?: return null
                val tls = tlsBlock(
                    serverName = if (m.bool("disable-sni")) null else m.str("sni") ?: m.str("servername") ?: server,
                    insecure = m.bool("skip-cert-verify"),
                    alpn = m.strList("alpn") ?: listOf("h3"),
                )
                Triple(ProtocolType.TUIC, uuid) { tag: String ->
                    tuicOutbound(
                        tag, server, port, uuid, password,
                        congestionControl = m.str("congestion-controller") ?: m.str("congestion_control"),
                        udpRelayMode = m.str("udp-relay-mode") ?: m.str("udp_relay_mode"),
                        tls = tls,
                    )
                }
            }
            // ssr, hysteria1, snell, socks5, http, … — unsupported for MVP
            else -> return null
        }

        val id = stableNodeId(subscriptionId, type, server, port, "${name.orEmpty()}$type$server$port")
        return ProxyNode(
            id = id,
            name = name ?: "$server:$port",
            protocol = protocol,
            server = server,
            port = port,
            outboundJson = outbound(id).toString(),
            rawUri = null,
            subscriptionId = subscriptionId,
        )
    }

    /** Clash `network` + `*-opts` maps → sing-box transport block. */
    private fun clashTransport(m: Map<*, *>, network: String): JsonObject? =
        when (network.lowercase()) {
            "ws" -> {
                val opts = m.map("ws-opts")
                transportBlock(
                    "ws",
                    host = opts?.map("headers")?.str("Host") ?: opts?.map("headers")?.str("host"),
                    path = opts?.str("path"),
                    maxEarlyData = opts?.int("max-early-data"),
                    earlyDataHeaderName = opts?.str("early-data-header-name"),
                )
            }
            "grpc" -> {
                val opts = m.map("grpc-opts")
                transportBlock("grpc", serviceName = opts?.str("grpc-service-name") ?: opts?.str("grpc_service_name"))
            }
            "httpupgrade" -> {
                val opts = m.map("httpupgrade-opts")
                transportBlock(
                    "httpupgrade",
                    host = opts?.map("headers")?.str("Host") ?: opts?.map("headers")?.str("host") ?: opts?.str("host"),
                    path = opts?.str("path"),
                )
            }
            "http", "h2" -> {
                val opts = m.map("h2-opts") ?: m.map("http-opts")
                transportBlock("http", host = opts?.str("host"), path = opts?.str("path"))
            }
            else -> null
        }

    private fun Map<*, *>.str(key: String): String? = this[key]?.toString()
    private fun Map<*, *>.int(key: String): Int? = when (val v = this[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
    private fun Map<*, *>.bool(key: String): Boolean = when (val v = this[key]) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true) || v == "1"
        else -> false
    }
    private fun Map<*, *>.strList(key: String): List<String>? = when (val v = this[key]) {
        is List<*> -> v.mapNotNull { it?.toString() }.takeIf { it.isNotEmpty() }
        is String -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
        else -> null
    }
    private fun Map<*, *>.map(key: String): Map<*, *>? = this[key] as? Map<*, *>
}
