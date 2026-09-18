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
            val obj = element as? JsonObject ?: return@mapNotNull null
            val type = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
                ?: return@mapNotNull null
            val protocol = NODE_TYPES[type] ?: return@mapNotNull null
            val tag = obj["tag"]?.jsonPrimitive?.contentOrNull
            val server = obj["server"]?.jsonPrimitive?.contentOrNull ?: ""
            val port = obj["server_port"]?.jsonPrimitive?.intOrNull ?: 0
            val id = stableNodeId(subscriptionId, type, server, port, "${tag.orEmpty()}$type$server$port")
            ProxyNode(
                id = id,
                name = tag?.takeIf { it.isNotBlank() } ?: "$server:$port",
                protocol = protocol,
                server = server,
                port = port,
                outboundJson = JsonObject(obj + ("tag" to JsonPrimitive(id))).toString(),
                rawUri = null,
                subscriptionId = subscriptionId,
            )
        }
        if (nodes.isEmpty()) throw SubscriptionError.EmptyResult()
        return nodes
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
