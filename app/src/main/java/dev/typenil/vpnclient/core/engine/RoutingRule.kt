package dev.typenil.vpnclient.core.engine

/**
 * A user-authored routing rule as the compiler consumes it. The DB row maps
 * here; the compiler emits the sing-box `route.rules` entry (domain suffix /
 * keyword, ip_cidr, or port) plus the action's outbound.
 */
data class RoutingRule(
    val kind: Kind,
    val pattern: String,
    val action: Action,
) {
    enum class Kind(
        val key: String,
    ) {
        DOMAIN("domain"),
        IP_CIDR("ip_cidr"),
        PORT("port"),
        ;

        companion object {
            fun fromKey(key: String?): Kind? = entries.firstOrNull { it.key == key }
        }
    }

    enum class Action(
        val key: String,
    ) {
        PROXY("proxy"),
        DIRECT("direct"),
        BLOCK("block"),
        ;

        companion object {
            fun fromKey(key: String?): Action? = entries.firstOrNull { it.key == key }
        }
    }

    /** Sing-box outbound tag for [action]. BLOCK is expressed as a reject
     *  action, not an outbound, so it isn't mapped here. */
    val outboundTag: String?
        get() =
            when (action) {
                Action.PROXY -> "proxy"
                Action.DIRECT -> "direct"
                Action.BLOCK -> null
            }

    /** The sing-box matcher field for [kind]. */
    val matchField: String
        get() =
            when (kind) {
                Kind.DOMAIN -> "domain_suffix"
                Kind.IP_CIDR -> "ip_cidr"
                Kind.PORT -> "port"
            }
}
