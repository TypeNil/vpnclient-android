package dev.typenil.vpnclient.ui.servers

import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.data.LatencyMethod
import dev.typenil.vpnclient.data.NodeLatency
import dev.typenil.vpnclient.ui.theme.LatencyReading

/** Favourites first inside each subscription; subscription order and the order within each part are kept. */
internal fun <T> favoritesFirstPerGroup(nodes: List<T>, groupOf: (T) -> Long, isFavorite: (T) -> Boolean): List<T> =
    nodes.groupBy(groupOf).values.flatMap { group -> group.sortedByDescending(isFavorite) }

/** Only an enabled, visible node can be picked: a disabled one isn't in the compiled set, a hidden one isn't shown in any list. */
internal val ServerNode.canSelect: Boolean get() = enabled && !hidden

/** The number on "Filters · n": how many of subscription, protocol and "show hidden" are on (0 → no number). */
internal fun activeFilterCount(subscriptionFilter: Long?, protocolFilter: String?, showHidden: Boolean): Int =
    listOfNotNull(subscriptionFilter, protocolFilter).size + if (showHidden) 1 else 0

/**
 * What a row shows for latency. [reading] feeds the chip; [history] is the persisted verdict behind
 * it (fresh → the chip shows it; [stale] → the chip shows the value muted, no tint — age rides on the spoken description).
 */
internal data class NodeLatencyView(val reading: LatencyReading?, val history: NodeLatency? = null, val stale: Boolean = false)

internal fun nodeLatencyView(delayMs: Int?, tested: Boolean, stored: NodeLatency?, connected: Boolean, nowMs: Long): NodeLatencyView {
    val live = if (connected) R.string.servers_latency_method_proxy else R.string.servers_latency_method_tcp
    return when {
        delayMs != null -> NodeLatencyView(LatencyReading(delayMs, live))
        tested -> NodeLatencyView(LatencyReading(null, live))
        stored == null -> NodeLatencyView(null)
        stored.isFresh(nowMs) -> NodeLatencyView(
            LatencyReading(stored.latencyMs, storedMethodRes(stored)),
            history = stored,
        )
        else -> NodeLatencyView(LatencyReading(stored.latencyMs, storedMethodRes(stored), stale = true), history = stored, stale = true)
    }
}

private fun storedMethodRes(stored: NodeLatency): Int =
    when (stored.method) {
        LatencyMethod.Tcp -> R.string.servers_latency_method_tcp
        LatencyMethod.Proxy -> R.string.servers_latency_method_proxy
    }
