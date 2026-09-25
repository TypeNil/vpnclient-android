package dev.typenil.vpnclient.core.subscription.model

/**
 * The user's persisted server pick, decoded from `selected_node_id`.
 *
 * Storage stays a single string preference: [Auto] is the [AUTO_ID] sentinel,
 * anything else is a concrete node id. The "auto" spelling matches the
 * sing-box `urltest` group tag (`ConfigCompiler.AUTO_TAG`) so the same value
 * selects the group inside the "proxy" selector — the tag constant stays
 * engine-internal; only the persisted id crosses this boundary.
 */
sealed interface NodeSelection {
    /** Latency-testing "Auto / Fastest" — routes through the urltest group. */
    data object Auto : NodeSelection

    /** A concrete node by id. */
    data class Node(
        val id: String,
    ) : NodeSelection

    companion object {
        /** `selected_node_id` value that encodes [Auto]. */
        const val AUTO_ID = "auto"

        fun fromId(id: String?): NodeSelection? =
            when {
                id == null -> null
                id == AUTO_ID -> Auto
                else -> Node(id)
            }

        fun NodeSelection.toId(): String =
            when (this) {
                Auto -> AUTO_ID
                is Node -> id
            }
    }
}
