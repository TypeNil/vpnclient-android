package dev.typenil.vpnclient.core.common

/**
 * Persisted theme choice — stored as a string key in DataStore (never the
 * ordinal: enum reordering must not silently change the user's theme).
 */
enum class ThemeMode(
    val key: String,
) {
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: System
    }
}
