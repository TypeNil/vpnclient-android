package dev.typenil.vpnclient.core.common

/**
 * Persisted UI language choice — stored as a string key in DataStore (never
 * an ordinal or a raw locale tag: the mapping is explicit and versioned).
 *
 * [tag] is a BCP-47 tag for the concrete choices; null for [System], which
 * defers to the OS (and to Android 13+ per-app language settings).
 */
enum class AppLanguage(
    val key: String,
    val tag: String?,
) {
    System("system", null),
    English("en", "en"),
    Russian("ru", "ru"),
    ;

    companion object {
        fun fromKey(key: String?): AppLanguage = entries.firstOrNull { it.key == key } ?: System

        /** Reverse of [tag] — resolve the platform's applied locale string
         *  back to the enum ("ru"→Russian, "en"→English, empty/anything
         *  else → System). Used on 33+ where LocaleManager is authoritative. */
        fun fromTag(tag: String?): AppLanguage =
            when {
                tag.isNullOrEmpty() -> System
                else -> entries.firstOrNull { it.tag != null && tag.startsWith(it.tag) } ?: System
            }
    }
}
