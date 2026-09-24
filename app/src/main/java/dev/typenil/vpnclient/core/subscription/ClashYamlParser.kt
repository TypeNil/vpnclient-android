package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.ParseResult
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.SkippedNode
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.core.subscription.parse.NetworkClass
import dev.typenil.vpnclient.core.subscription.parse.classifyNetwork
import dev.typenil.vpnclient.core.subscription.parse.hysteria2Outbound
import dev.typenil.vpnclient.core.subscription.parse.portHoppingList
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
import kotlinx.serialization.json.JsonPrimitive
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Extracts nodes from a Clash/Mihomo config's `proxies:` list and re-emits them
 * as sing-box outbound JSON via the shared builders. Unknown proxy types
 * (ssr, hysteria1, snell, socks5, http, …) and `proxy-groups` are skipped
 * and reported in [ParseResult.skipped].
 */
@Singleton
class ClashYamlParser @Inject constructor() : SubscriptionParser {

    override fun parse(body: String, subscriptionId: Long): ParseResult {
        val loaded = try {
            // SafeConstructor: subscription YAML is untrusted input — never
            // instantiate arbitrary classes via !!-tags.
            Yaml(SafeConstructor(LoaderOptions())).load<Any?>(body)
        } catch (e: Exception) {
            throw SubscriptionError.ParseFailed("invalid clash yaml")
        }
        val root = loaded as? Map<*, *> ?: throw SubscriptionError.ParseFailed("not a clash config")
        val proxies = root["proxies"] as? List<*> ?: throw SubscriptionError.EmptyResult()

        val skipped = mutableListOf<SkippedNode>()
        val nodes = proxies.mapNotNull { it as? Map<*, *> }
            .mapNotNull { proxy ->
                try {
                    toNode(proxy, subscriptionId)
                } catch (e: SkipNode) {
                    skipped += SkippedNode(e.nodeName, e.message!!)
                    null
                } catch (e: Exception) {
                    skipped += SkippedNode(proxy.str("name"), "malformed")
                    null
                }
            }
        if (nodes.isEmpty()) throw SubscriptionError.EmptyResult()
        return ParseResult(nodes, skipped)
    }

    private fun toNode(m: Map<*, *>, subscriptionId: Long): ProxyNode {
        val type = m.str("type")?.lowercase()
            ?: throw SkipNode(m.str("name"), "malformed")
        val name = m.str("name")
        val server = m.str("server") ?: ""
        val network = m.str("network") ?: "tcp"
        if (classifyNetwork(network) == NetworkClass.Unsupported) {
            throw SkipNode(name, "unsupported transport: $network")
        }
        // Port hopping (hysteria2): `ports`/`mport` as "a-b,c" or a YAML
        // list. server_port stays at the first hop port — sing-box ignores
        // server_port once server_ports is set, so the displayed port must
        // be the hop port, not the (often placeholder) `port` field.
        val isHy2 = type == "hysteria2" || type == "hy2"
        val serverPorts = (m.strList("ports") ?: m.strList("mport"))
            ?.flatMap(::portHoppingList)?.takeIf { it.isNotEmpty() }
        val port = if (isHy2) {
            serverPorts?.first()?.substringBefore(':')?.toIntOrNull()
                ?: m.int("port") ?: 0
        } else {
            m.int("port") ?: 0
        }

        val (protocol, outbound) = when (type) {
            "vless" -> {
                val uuid = m.str("uuid") ?: throw SkipNode(name, "malformed")
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
                ProtocolType.VLESS to { tag: String ->
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
                val uuid = m.str("uuid") ?: throw SkipNode(name, "malformed")
                val tls = if (m.bool("tls")) tlsBlock(
                    serverName = m.str("servername") ?: m.str("sni"),
                    insecure = m.bool("skip-cert-verify"),
                    alpn = m.strList("alpn"),
                    fingerprint = m.str("client-fingerprint") ?: m.str("fingerprint"),
                ) else null
                ProtocolType.VMESS to { tag: String ->
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
                val password = m.str("password") ?: throw SkipNode(name, "malformed")
                val tls = tlsBlock(
                    serverName = m.str("sni") ?: m.str("servername") ?: server,
                    insecure = m.bool("skip-cert-verify"),
                    alpn = m.strList("alpn"),
                    fingerprint = m.str("client-fingerprint") ?: m.str("fingerprint"),
                )
                ProtocolType.TROJAN to { tag: String ->
                    trojanOutbound(tag, server, port, password, tls = tls, transport = clashTransport(m, network))
                }
            }
            "ss", "shadowsocks" -> {
                val method = m.str("cipher") ?: throw SkipNode(name, "malformed")
                val password = m.str("password") ?: throw SkipNode(name, "malformed")
                ProtocolType.SHADOWSOCKS to { tag: String ->
                    shadowsocksOutbound(tag, server, port, method, password)
                }
            }
            "hysteria2", "hy2" -> {
                val password = m.str("password") ?: throw SkipNode(name, "malformed")
                val tls = tlsBlock(
                    serverName = m.str("sni") ?: m.str("servername") ?: server,
                    insecure = m.bool("skip-cert-verify"),
                )
                val obfsPassword = if (m.str("obfs") != null) m.str("obfs-password").orEmpty() else null
                ProtocolType.HYSTERIA2 to { tag: String ->
                    hysteria2Outbound(
                        tag, server, port, password,
                        tls = tls, obfsPassword = obfsPassword, serverPorts = serverPorts,
                    )
                }
            }
            "tuic" -> {
                val uuid = m.str("uuid") ?: throw SkipNode(name, "malformed")
                val password = m.str("password") ?: throw SkipNode(name, "malformed")
                val tls = tlsBlock(
                    serverName = if (m.bool("disable-sni")) null else m.str("sni") ?: m.str("servername") ?: server,
                    insecure = m.bool("skip-cert-verify"),
                    alpn = m.strList("alpn") ?: listOf("h3"),
                )
                ProtocolType.TUIC to { tag: String ->
                    tuicOutbound(
                        tag, server, port, uuid, password,
                        congestionControl = m.str("congestion-controller") ?: m.str("congestion_control"),
                        udpRelayMode = m.str("udp-relay-mode") ?: m.str("udp_relay_mode"),
                        tls = tls,
                    )
                }
            }
            // ssr, hysteria1, snell, socks5, http, … — unsupported for MVP
            else -> throw SkipNode(name, "unsupported protocol: $type")
        }

        // Identity is the tagless outbound: proxies differing only in `name`
        // collapse to one id, while transport/TLS differences do not.
        val template = outbound("")
        val id = stableNodeId(subscriptionId, template)
        return ProxyNode(
            id = id,
            name = name ?: "$server:$port",
            protocol = protocol,
            server = server,
            port = port,
            outboundJson = JsonObject(template + ("tag" to JsonPrimitive(id))).toString(),
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
            "quic" -> transportBlock("quic")
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
