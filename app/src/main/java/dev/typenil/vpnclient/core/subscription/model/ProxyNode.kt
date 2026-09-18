package dev.typenil.vpnclient.core.subscription.model

/**
 * A selectable proxy endpoint parsed from a subscription or a pasted URI.
 *
 * [outboundJson] is the engine-native (sing-box) outbound object as JSON —
 * the UI and domain logic never inspect its contents; it is handed to the
 * config compiler verbatim. [rawUri] is the original share link when the node
 * came from a URI list; it is kept for re-parse/debug but never logged.
 */
data class ProxyNode(
    val id: String,
    val name: String,
    val protocol: ProtocolType,
    val server: String,
    val port: Int,
    val outboundJson: String,
    val rawUri: String?,
    val subscriptionId: Long,
)

/** Compact node info for state-machine/UI display — no secrets. */
data class NodeSummary(
    val id: String,
    val name: String,
    val protocol: ProtocolType,
    val server: String,
)

fun ProxyNode.summary() = NodeSummary(id, name, protocol, server)
