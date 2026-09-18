package dev.typenil.vpnclient.ui.common

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

/** "just now" / "5 min ago" / "3 h ago" / "2 d ago", or "never" for null. */
fun formatRelativeTime(instant: Instant?, now: Instant = Instant.now()): String {
    if (instant == null) return "never"
    val seconds = Duration.between(instant, now).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3_600} h ago"
        else -> "${seconds / 86_400} d ago"
    }
}

/** `2026-05-01` style date for subscription expiry (epoch seconds). */
fun formatDate(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ISO_LOCAL_DATE)
