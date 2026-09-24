package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.ParseResult
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.SkippedNode
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.core.subscription.parse.stableNodeId
import dev.typenil.vpnclient.core.subscription.parse.wireguardEndpoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts nodes from a sing-box config's top-level `outbounds` and
 * `endpoints` arrays. Non-node outbounds (direct/block/dns/selector/…)
 * are ignored silently; node-shaped entries that can't be used are
 * reported in [ParseResult.skipped]. Each kept entry is re-serialized with
 * `tag` overwritten to the node id.
 */
@Singleton
class SingBoxJsonParser @Inject constructor() : SubscriptionParser {

    private val json = Json { ignoreUnknownKeys = true }

    override fun parse(body: String, subscriptionId: Long): ParseResult {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw SubscriptionError.ParseFailed("invalid sing-box json")
        }
        val outbounds = root["outbounds"] as? JsonArray
        val endpoints = root["endpoints"] as? JsonArray
        if (outbounds == null && endpoints == null) throw SubscriptionError.EmptyResult()

        val skipped = mutableListOf<SkippedNode>()
        val nodes = mutableListOf<ProxyNode>()
        outbounds?.forEach { element ->
            collect(element, subscriptionId, skipped, nodes, ::toNode)
        }
        endpoints?.forEach { element ->
            collect(element, subscriptionId, skipped, nodes, ::endpointToNode)
        }
        if (nodes.isEmpty()) throw SubscriptionError.EmptyResult()
        return ParseResult(nodes, skipped)
    }

    private fun collect(
        element: JsonElement,
        subscriptionId: Long,
        skipped: MutableList<SkippedNode>,
        nodes: MutableList<ProxyNode>,
        convert: (JsonElement, Long) -> ProxyNode?,
    ) {
        try {
            convert(element, subscriptionId)?.let { nodes += it }
        } catch (e: SkipNode) {
            skipped += SkippedNode(e.nodeName, e.message!!)
        } catch (e: Exception) {
            skipped += SkippedNode(safeTag(element), "malformed")
        }
    }

    /** `tag` for diagnostics — a non-primitive tag must not crash the
     *  skip handler itself. */
    private fun safeTag(element: JsonElement): String? =
        ((element as? JsonObject)?.get("tag") as? JsonPrimitive)?.contentOrNull

    private fun toNode(element: JsonElement, subscriptionId: Long): ProxyNode? {
        val obj = element as? JsonObject
            ?: throw SkipNode(null, "malformed")
        val tag = safeTag(element)
        val type = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?: throw SkipNode(tag, "malformed")
        if (type in NON_NODE_TYPES) return null
        val protocol = NODE_TYPES[type]
            ?: throw SkipNode(tag, "unsupported protocol: $type")
        // Pre-1.13 wireguard outbounds can't be passed through — the type
        // was removed from sing-box; convert to the endpoints[] shape.
        if (protocol == ProtocolType.WIREGUARD) {
            return wireguardToNode(obj, tag, subscriptionId)
        }
        val server = obj["server"]?.jsonPrimitive?.contentOrNull ?: ""
        val port = obj["server_port"]?.jsonPrimitive?.intOrNull
            ?: obj["server_port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: 0
        return finishNode(obj, tag, protocol, server, port, subscriptionId)
    }

    private fun endpointToNode(element: JsonElement, subscriptionId: Long): ProxyNode? {
        val obj = element as? JsonObject
            ?: throw SkipNode(null, "malformed")
        val tag = safeTag(element)
        val type = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?: throw SkipNode(tag, "malformed")
        if (type != "wireguard") {
            throw SkipNode(tag, "unsupported endpoint: $type")
        }
        // Endpoint shape: the peer carries server/port, not the top level.
        // Apply the same normalization wireguardToNode gives legacy
        // outbounds: private_key is required, peers need allowed_ips, and a
        // top-level reserved is inherited by peers that omit it.
        obj["private_key"]?.jsonPrimitive?.contentOrNull
            ?: throw SkipNode(tag, "malformed")
        val peers = obj["peers"] as? JsonArray
            ?: throw SkipNode(tag, "malformed")
        val inheritedReserved = obj["reserved"]
        val normalized = peers.map { peerEl ->
            val peer = peerEl as? JsonObject ?: throw SkipNode(tag, "malformed")
            JsonObject(
                peer + ("allowed_ips" to (peer["allowed_ips"]
                    ?: JsonArray(listOf(JsonPrimitive("0.0.0.0/0"), JsonPrimitive("::/0"))))) +
                    if (peer["reserved"] == null && inheritedReserved != null) {
                        mapOf("reserved" to inheritedReserved)
                    } else {
                        emptyMap()
                    },
            )
        }
        val first = normalized.firstOrNull() ?: throw SkipNode(tag, "malformed")
        val server = first["address"]?.jsonPrimitive?.contentOrNull ?: ""
        val port = first["port"]?.jsonPrimitive?.intOrNull
            ?: first["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: 0
        val endpoint = JsonObject(
            obj - "peers" - "reserved" + ("peers" to JsonArray(normalized)),
        )
        return finishNode(endpoint, tag, ProtocolType.WIREGUARD, server, port, subscriptionId)
    }

    /** Legacy `outbounds[]` wireguard (pre-1.13) → canonical endpoint shape.
     *  Multi-peer configs keep every peer; single-peer configs build one
     *  peer from the top-level fields. */
    private fun wireguardToNode(
        obj: JsonObject,
        tag: String?,
        subscriptionId: Long,
    ): ProxyNode {
        val peers = obj["peers"] as? JsonArray
        if (!peers.isNullOrEmpty()) {
            // Endpoint requires private_key even when peers carry the rest.
            obj["private_key"]?.jsonPrimitive?.contentOrNull
                ?: throw SkipNode(tag, "malformed")
            // Normalize legacy peer keys (server/server_port → address/port),
            // default allowed_ips (endpoint peers require it), and inherit
            // the legacy top-level `reserved` into peers that omit it —
            // endpoint shape has no top-level reserved.
            val inheritedReserved = obj["reserved"]
            val normalized = peers.map { peerEl ->
                val peer = peerEl as? JsonObject ?: throw SkipNode(tag, "malformed")
                JsonObject(
                    peer.mapKeys { (k, _) ->
                        when (k) {
                            "server" -> "address"
                            "server_port" -> "port"
                            else -> k
                        }
                    } + ("allowed_ips" to (peer["allowed_ips"]
                        ?: JsonArray(listOf(JsonPrimitive("0.0.0.0/0"), JsonPrimitive("::/0"))))) +
                        if (peer["reserved"] == null && inheritedReserved != null) {
                            mapOf("reserved" to inheritedReserved)
                        } else {
                            emptyMap()
                        },
                )
            }
            val first = normalized.first()
            val server = first["address"]?.jsonPrimitive?.contentOrNull ?: ""
            val port = first["port"]?.jsonPrimitive?.intOrNull
                ?: first["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val endpoint = JsonObject(
                obj - "server" - "server_port" - "peer_public_key" - "peers" -
                    "local_address" - "reserved" +
                    ("peers" to JsonArray(normalized)) +
                    ("address" to (obj["address"] ?: obj["local_address"]
                        ?: throw SkipNode(tag, "malformed"))),
            )
            return finishNode(endpoint, tag, ProtocolType.WIREGUARD, server, port, subscriptionId)
        }
        val server = obj["server"]?.jsonPrimitive?.contentOrNull
            ?: throw SkipNode(tag, "malformed")
        val port = obj["server_port"]?.jsonPrimitive?.intOrNull
            ?: obj["server_port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: throw SkipNode(tag, "malformed")
        val privateKey = obj["private_key"]?.jsonPrimitive?.contentOrNull
            ?: throw SkipNode(tag, "malformed")
        val peerPublicKey = obj["peer_public_key"]?.jsonPrimitive?.contentOrNull
            ?: throw SkipNode(tag, "malformed")
        val localAddress = (obj["local_address"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: throw SkipNode(tag, "malformed")
        val reserved = (obj["reserved"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        val endpoint = wireguardEndpoint(
            tag = "",
            server = server,
            port = port,
            privateKey = privateKey,
            peerPublicKey = peerPublicKey,
            localAddress = localAddress,
            preSharedKey = obj["pre_shared_key"]?.jsonPrimitive?.contentOrNull,
            reserved = reserved,
            mtu = obj["mtu"]?.jsonPrimitive?.intOrNull,
        )
        return finishNode(endpoint, tag, ProtocolType.WIREGUARD, server, port, subscriptionId)
    }

    private fun finishNode(
        obj: JsonObject,
        tag: String?,
        protocol: ProtocolType,
        server: String,
        port: Int,
        subscriptionId: Long,
    ): ProxyNode {
        // Strip fields that reference outbounds/DNS servers we don't compile —
        // a verbatim `detour` or foreign `domain_resolver` would fail
        // checkConfig. Resolvers are dropped unconditionally: the compiled
        // config sets route.default_domain_resolver=local anyway, so keeping
        // an explicit "local" would only perturb the node id (and an
        // object-form resolver would crash jsonPrimitive). `tag` is dropped
        // here too: identity is the tagless outbound, so two outbounds
        // differing only in display tag collapse to the same id.
        val cleaned = JsonObject(
            obj.filterKeys { key ->
                key != "detour" && key != "tag" &&
                    key != "domain_resolver" && key != "default_domain_resolver"
            },
        )
        val id = stableNodeId(subscriptionId, cleaned)
        val tagged = JsonObject(cleaned + ("tag" to JsonPrimitive(id)))
        return ProxyNode(
            id = id,
            name = tag?.takeIf { it.isNotBlank() } ?: "$server:$port",
            protocol = protocol,
            server = server,
            port = port,
            outboundJson = tagged.toString(),
            rawUri = null,
            subscriptionId = subscriptionId,
        )
    }

    private companion object {
        val NODE_TYPES = mapOf(
            "vless" to ProtocolType.VLESS,
            "vmess" to ProtocolType.VMESS,
            "trojan" to ProtocolType.TROJAN,
            "shadowsocks" to ProtocolType.SHADOWSOCKS,
            "ss" to ProtocolType.SHADOWSOCKS,
            "hysteria2" to ProtocolType.HYSTERIA2,
            "hy2" to ProtocolType.HYSTERIA2,
            "tuic" to ProtocolType.TUIC,
            "socks" to ProtocolType.SOCKS,
            "http" to ProtocolType.HTTP,
            "anytls" to ProtocolType.ANYTLS,
            "wireguard" to ProtocolType.WIREGUARD,
        )

        /** sing-box config plumbing — expected in configs, never nodes.
         *  Includes protocols the engine dropped (hysteria1, ssr): they
         *  can't run here, so they're ignored rather than reported. */
        val NON_NODE_TYPES = setOf(
            "direct", "block", "dns", "selector", "urltest", "mixed",
            "tun", "redirect", "tproxy", "shadowtls", "hysteria",
            "shadowsocksr", "group",
        )
    }
}
