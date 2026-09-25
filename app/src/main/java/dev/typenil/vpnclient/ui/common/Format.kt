package dev.typenil.vpnclient.ui.common

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import dev.typenil.vpnclient.core.common.formatBytes as coreFormatBytes
import dev.typenil.vpnclient.core.common.formatRate as coreFormatRate

// Byte/rate formatting lives in core.common so the service layer can use it
// without a UI dependency; these aliases keep existing call sites working.

/** Human-readable byte counts: `512 B`, `1.5 KB`, `2.0 MB`… */
fun formatBytes(bytes: Long): String = coreFormatBytes(bytes)

/** `1.5 MB/s` style rates. */
fun formatRate(bytesPerSec: Long): String = coreFormatRate(bytesPerSec)

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
