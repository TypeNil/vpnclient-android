package dev.typenil.vpnclient.core.vpn

/** Per-app VPN routing mode persisted in DataStore as [ordinal]. */
enum class PerAppMode {
    /** Every app's traffic goes through the tunnel (self excluded). */
    ALL,
    /** Only the selected packages use the tunnel. */
    INCLUDE,
    /** Everything except the selected packages uses the tunnel. */
    EXCLUDE;

    companion object {
        fun fromOrdinal(value: Int): PerAppMode =
            entries.getOrElse(value) { ALL }
    }
}

/**
 * Resolved builder input: `VpnService.Builder` rejects mixing
 * `addAllowedApplication` and `addDisallowedApplication` (IllegalStateException),
 * so a plan may fill exactly one side.
 */
data class PerAppPlan(
    val allowed: List<String> = emptyList(),
    val disallowed: List<String> = emptyList(),
)

/**
 * Merge the user's per-app policy with whatever the core requested via
 * `TunOptions`. Rules:
 * - include-mode always wins when any allowed package is present — the two
 *   lists can never be mixed on the Builder;
 * - our own package is *never* allowed (its core sockets would loop back into
 *   the TUN); in include-mode it's excluded by omission, in exclude-mode it's
 *   disallowed explicitly;
 * - disallowed packages are only applied when no include list exists.
 */
internal fun resolvePerAppPlan(
    mode: PerAppMode,
    selected: Set<String>,
    selfPackage: String,
    coreInclude: List<String> = emptyList(),
    coreExclude: List<String> = emptyList(),
): PerAppPlan {
    val allowed = buildList {
        addAll(coreInclude)
        if (mode == PerAppMode.INCLUDE) addAll(selected)
    }.distinct().filter { it != selfPackage }
    if (allowed.isNotEmpty()) return PerAppPlan(allowed = allowed)

    val disallowed = buildList {
        addAll(coreExclude)
        if (mode == PerAppMode.EXCLUDE) addAll(selected)
        add(selfPackage)
    }.distinct()
    return PerAppPlan(disallowed = disallowed)
}
