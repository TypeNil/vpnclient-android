package dev.typenil.vpnclient.ui.home

import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.data.LatencyMethod
import dev.typenil.vpnclient.data.NodeLatency
import dev.typenil.vpnclient.ui.theme.LatencyReading

/** One provider's nodes in the picker. [name] null = the profile row is gone (the UI shows "Subscription N"). */
internal data class PickerGroup(val id: Long?, val name: String?, val options: List<ServerOption>)

/** Groups keep first-seen order; the view model hands nodes over already contiguous per subscription. */
internal fun groupPickerOptions(nodes: List<ServerOption>): List<PickerGroup> =
    nodes.groupBy { it.groupId }.map { (id, list) -> PickerGroup(id, list.first().groupName, list) }

/** A stored verdict counts only while fresh; an outdated one is shown as "no data", never as current. */
internal fun NodeLatency.pickerReading(nowMs: Long): LatencyReading? =
    if (!isFresh(nowMs)) {
        null
    } else {
        LatencyReading(
            latencyMs,
            when (method) {
                LatencyMethod.Tcp -> R.string.servers_latency_method_tcp
                LatencyMethod.Proxy -> R.string.servers_latency_method_proxy
            },
        )
    }
