package dev.typenil.vpnclient.core.vpn

/**
 * Per-app VPN routing mode.
 *
 * Persisted in DataStore as [key] — never ordinal, so reordering or
 * renaming entries can't silently reinterpret a stored preference.
 */
enum class PerAppMode(val key: String) {
    /** Every app's traffic goes through the tunnel (self included). */
    ALL("all"),
    /** Only the selected packages use the tunnel. */
    INCLUDE("include"),
    /** Everything except the selected packages uses the tunnel. */
    EXCLUDE("exclude");

    companion object {
        /** Unknown/absent keys fall back to [ALL] — the safest default. */
        fun fromKey(value: String?): PerAppMode =
            entries.firstOrNull { it.key == value } ?: ALL

        /** Maps a legacy ordinal-stored value (pre-v2 DataStore key). */
        fun fromOrdinal(value: Int): PerAppMode =
            entries.getOrElse(value) { ALL }
    }
}

/**
 * Resolved builder input: `VpnService.Builder` rejects mixing
 * `addAllowedApplication` and `addDisallowedApplication`
 * (UnsupportedOperationException), so a plan may fill exactly one side.
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
 * - our own package always rides the tunnel: whenever an allow-list exists
 *   it joins it, it is never disallowed, and an empty INCLUDE selection
 *   degenerates to self alone — subscription refreshes and rule-set
 *   downloads must work on endpoints only reachable via VPN;
 * - engine core sockets stay off the TUN via `VpnService.protect()`
 *   (`ClientVpnService.protectSocket` → `protect(fd)`,
 *   `SingBoxEngine.autoDetectInterfaceControl`) — the only bypass mechanism;
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
    }.distinct()
    // INCLUDE with an empty selection still means "only selected apps" —
    // the allow-list degenerates to self alone rather than falling back to
    // allow-all (an empty Builder allow-list routes everything).
    if (allowed.isNotEmpty() || mode == PerAppMode.INCLUDE) {
        return PerAppPlan(allowed = (allowed + selfPackage).distinct())
    }

    // Self is never disallowed — it rides the tunnel in every mode.
    val disallowed = buildList {
        addAll(coreExclude)
        if (mode == PerAppMode.EXCLUDE) addAll(selected)
    }.distinct()
    return PerAppPlan(disallowed = disallowed)
}
