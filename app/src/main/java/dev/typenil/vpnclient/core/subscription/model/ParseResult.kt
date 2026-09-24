package dev.typenil.vpnclient.core.subscription.model

/**
 * A node the parser dropped, with a short stable reason token for UI
 * grouping — e.g. `"unsupported transport: xhttp"`,
 * `"unsupported protocol: ssr"`, `"malformed"`. Lowercase-stable by
 * convention; these are grouping keys, not typed errors.
 */
data class SkippedNode(val name: String?, val reason: String)

/** Parse output: the usable nodes plus every node that was dropped. */
data class ParseResult(
    val nodes: List<ProxyNode>,
    val skipped: List<SkippedNode> = emptyList(),
)

/** What a successful refresh committed — node count plus skip diagnostics. */
data class RefreshOutcome(
    val nodeCount: Int,
    val skipped: List<SkippedNode> = emptyList(),
)
