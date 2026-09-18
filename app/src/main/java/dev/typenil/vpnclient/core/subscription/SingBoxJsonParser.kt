package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.core.subscription.parse.stableNodeId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts nodes from a sing-box config's top-level `outbounds` array.
 * Non-node outbounds (direct/block/dns/selector/urltest/…) are skipped.
 * Each kept outbound is re-serialized with `tag` overwritten to the node id.
 */
@Singleton
class SingBoxJsonParser @Inject constructor() : SubscriptionParser {

    private val json = Json { ignoreUnknownKeys = true }

    override fun parse(body: String, subscriptionId: Long): List<ProxyNode> {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw SubscriptionError.ParseFailed("invalid sing-box json")
        }
        val outbounds = (root["outbounds"] as? kotlinx.serialization.json.JsonArray)
            ?: throw SubscriptionError.EmptyResult()

        val nodes = outbounds.mapNotNull { element ->
            runCatching { toNode(element, subscriptionId) }.getOrNull()
        }
        if (nodes.isEmpty()) throw SubscriptionError.EmptyResult()
        return nodes
    }

    private fun toNode(element: kotlinx.serialization.json.JsonElement, subscriptionId: Long): ProxyNode? {
        val obj = element as? JsonObject ?: return null
        val type = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?: return null
        val protocol = NODE_TYPES[type] ?: return null
        val tag = obj["tag"]?.jsonPrimitive?.contentOrNull
        val server = obj["server"]?.jsonPrimitive?.contentOrNull ?: ""
        val port = obj["server_port"]?.jsonPrimitive?.intOrNull
            ?: obj["server_port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: 0
        val id = stableNodeId(subscriptionId, type, server, port, "${tag.orEmpty()}$type$server$port")
        // Strip fields that reference outbounds/DNS servers we don't compile —
        // a verbatim `detour` or foreign `domain_resolver` would fail checkConfig.
        val cleaned = JsonObject(
            obj - "detour" - "domain_resolver" - "default_domain_resolver" +
                ("tag" to JsonPrimitive(id)),
        )
        return ProxyNode(
            id = id,
            name = tag?.takeIf { it.isNotBlank() } ?: "$server:$port",
            protocol = protocol,
            server = server,
            port = port,
            outboundJson = cleaned.toString(),
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
        )
    }
}
