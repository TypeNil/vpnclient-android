package dev.typenil.vpnclient.core.common

import java.util.Locale

/** Human-readable byte counts: `512 B`, `1.5 KB`, `2.0 MB`… */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1024.0
        unit++
    } while (value >= 1024.0 && unit < units.lastIndex)
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

/** `1.5 MB/s` style rates. */
fun formatRate(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"
