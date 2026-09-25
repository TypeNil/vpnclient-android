package dev.typenil.vpnclient.core.vpn

/**
 * Coarse label for the physical network carrying the tunnel — reported by
 * ClientVpnService's underlay tracker, consumed by the session-details UI.
 * Deliberately small: this is a display label, not a routing input.
 */
enum class UnderlyingTransport {
    WIFI,
    CELLULAR,
    OTHER,
    UNKNOWN;

    /** Sorted for stability — labels appear in detail rows, not logs. */
    val label: String
        get() = when (this) {
            WIFI -> "Wi-Fi"
            CELLULAR -> "Cellular"
            OTHER -> "Other"
            UNKNOWN -> "Unknown"
        }
}
