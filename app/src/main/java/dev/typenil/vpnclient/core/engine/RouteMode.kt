package dev.typenil.vpnclient.core.engine

/**
 * Region/domain routing policy applied when the engine config is compiled.
 *
 * Persisted in DataStore as [key] — never ordinal, so reordering or
 * renaming entries can't silently reinterpret a stored preference.
 * Changes apply on the next connect; a running tunnel keeps the mode it
 * was compiled with.
 */
enum class RouteMode(val key: String) {
    /** Every tunneled flow goes through the selected proxy. */
    ALL("all"),

    /** Russian resources (geoip-ru + geosite-category-ru) go direct;
     *  everything else is proxied. */
    BYPASS_RU("bypass_ru"),

    /** Only the curated blocked-service list is proxied; everything else
     *  goes direct (bandwidth-saving mode). */
    PROXY_BLOCKED("proxy_blocked");

    companion object {
        /** Unknown/absent keys fall back to [ALL] — the safest default. */
        fun fromKey(value: String?): RouteMode =
            entries.firstOrNull { it.key == value } ?: ALL
    }
}
