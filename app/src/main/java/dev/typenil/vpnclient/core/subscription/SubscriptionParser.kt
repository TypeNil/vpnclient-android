package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.ParseResult
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.core.subscription.model.SubscriptionFormat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses a classified subscription body into nodes.
 *
 * Implementations produce engine-native outbound JSON ([ProxyNode.outboundJson]) —
 * the app never interprets protocol internals, it only carries them through.
 */
interface SubscriptionParser {
    /** Throws [SubscriptionError.ParseFailed] / [SubscriptionError.EmptyResult] on failure. */
    fun parse(body: String, subscriptionId: Long): ParseResult
}

/** Internal control flow: a node-shaped entry that can't be used — carries
 *  the skip reason up to the parser's per-entry catch, which records a
 *  [dev.typenil.vpnclient.core.subscription.model.SkippedNode]. */
internal class SkipNode(val nodeName: String?, reason: String) : Exception(reason)

@Singleton
class SubscriptionParserDispatcher @Inject constructor(
    private val uriListParser: UriListParser,
    private val singBoxJsonParser: SingBoxJsonParser,
    private val clashYamlParser: ClashYamlParser,
) {
    fun parse(format: SubscriptionFormat, body: String, subscriptionId: Long): ParseResult =
        when (format) {
            SubscriptionFormat.UriList,
            SubscriptionFormat.Base64UriList,
            -> uriListParser.parse(body, subscriptionId)
            SubscriptionFormat.SingBoxJson -> singBoxJsonParser.parse(body, subscriptionId)
            SubscriptionFormat.ClashYaml -> clashYamlParser.parse(body, subscriptionId)
            SubscriptionFormat.XrayJson -> throw SubscriptionError.UnsupportedFormat(
                "xray-json subscriptions are not supported by the sing-box engine",
            )
            SubscriptionFormat.Unknown -> throw SubscriptionError.UnsupportedFormat("unknown")
        }
}
