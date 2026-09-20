package dev.typenil.vpnclient.core.engine

/** Tag constants live at file level — enum constructors can't touch the
 *  companion (it initializes after the entries). */
internal const val GEOIP_RU_TAG = "geoip-ru"
internal const val GEOSITE_RU_TAG = "geosite-category-ru"

/**
 * Region/domain routing policy applied when the engine config is compiled.
 *
 * Persisted in DataStore as [key] — never ordinal, so reordering or
 * renaming entries can't silently reinterpret a stored preference.
 * Changes apply on the next connect; a running tunnel keeps the mode it
 * was compiled with.
 *
 * [ruleSetTags] lists the binary .srs rule sets the mode needs — the
 * single source of truth shared by the compiler (which emits the local
 * `rule_set` declarations) and the store (which fetches the files).
 */
enum class RouteMode(val key: String, val ruleSetTags: List<String>) {
    /** Every tunneled flow goes through the selected proxy. */
    ALL("all", emptyList()),

    /** Russian resources (geoip-ru + geosite-category-ru) go direct;
     *  everything else is proxied. */
    BYPASS_RU("bypass_ru", listOf(GEOIP_RU_TAG, GEOSITE_RU_TAG)),

    /** Only the curated blocked-service list is proxied; everything else
     *  goes direct (bandwidth-saving mode). Tags follow the sing-geosite
     *  `rule-set` branch naming — each exists as `geosite-<name>.srs`
     *  there. Curating the mode is a one-line edit per service. */
    PROXY_BLOCKED(
        "proxy_blocked",
        listOf(
            "geosite-youtube",
            "geosite-telegram",
            "geosite-instagram",
            "geosite-facebook",
            "geosite-twitter",
            "geosite-discord",
            "geosite-tiktok",
            "geosite-linkedin",
            "geosite-medium",
            "geosite-openai",
            "geosite-whatsapp",
        ),
    );

    companion object {
        /** Unknown/absent keys fall back to [ALL] — the safest default. */
        fun fromKey(value: String?): RouteMode =
            entries.firstOrNull { it.key == value } ?: ALL
    }
}
