package dev.typenil.vpnclient.ui.common

import android.content.res.Resources
import dev.typenil.vpnclient.R
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.typenil.vpnclient.core.common.formatBytes
import dev.typenil.vpnclient.core.common.formatRate

/** Resources for the current UI locale; re-read when the configuration changes. */
@Composable
@ReadOnlyComposable
fun localizedResources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}

/** Localized byte count: `512 B` / `512 Б`. */
@Composable
@ReadOnlyComposable
fun formatBytes(bytes: Long): String = localizedResources().formatBytes(bytes)

/** Localized rate: `1.5 MB/s` / `1,5 МБ/с`. */
@Composable
@ReadOnlyComposable
fun formatRate(bytesPerSec: Long): String = localizedResources().formatRate(bytesPerSec)

enum class RelativeUnit { Now, Minutes, Hours, Days }

/** Age rounded down into the largest whole unit; pure so the buckets are testable. */
data class RelativeAge(val unit: RelativeUnit, val count: Int)

fun relativeAge(ageMs: Long): RelativeAge =
    when {
        ageMs < 60_000L -> RelativeAge(RelativeUnit.Now, 0)
        ageMs < 3_600_000L -> RelativeAge(RelativeUnit.Minutes, (ageMs / 60_000L).toInt())
        ageMs < 86_400_000L -> RelativeAge(RelativeUnit.Hours, (ageMs / 3_600_000L).toInt())
        else -> RelativeAge(RelativeUnit.Days, (ageMs / 86_400_000L).toInt())
    }

/** "just now" / "5 minutes ago" — plural forms come from `<plurals>` (ru: one/few/many). */
fun Resources.relativeTimeText(ageMs: Long): String {
    val age = relativeAge(ageMs.coerceAtLeast(0))
    return when (age.unit) {
        RelativeUnit.Now -> getString(R.string.relative_now)
        RelativeUnit.Minutes -> getQuantityString(R.plurals.relative_minutes_ago, age.count, age.count)
        RelativeUnit.Hours -> getQuantityString(R.plurals.relative_hours_ago, age.count, age.count)
        RelativeUnit.Days -> getQuantityString(R.plurals.relative_days_ago, age.count, age.count)
    }
}

/** Age of [instant], or "never" for null. */
fun Resources.formatRelativeTime(instant: Instant?, now: Instant = Instant.now()): String =
    if (instant == null) getString(R.string.relative_never)
    else relativeTimeText(Duration.between(instant, now).toMillis())

@Composable
@ReadOnlyComposable
fun relativeTimeText(ageMs: Long): String = localizedResources().relativeTimeText(ageMs)

@Composable
@ReadOnlyComposable
fun formatRelativeTime(instant: Instant?, now: Instant = Instant.now()): String =
    localizedResources().formatRelativeTime(instant, now)

/**
 * Whole-unit pieces of a session duration, consumed by the localized
 * `home_uptime_*` strings. Pure JVM — no Compose — so the unit boundaries
 * are testable. Negative input clamps to zero (a future `since` is a clock
 * artifact, not uptime).
 */
data class UptimeParts(
    val days: Long,
    val hours: Long,
    val minutes: Long,
    val seconds: Long,
)

fun uptimeParts(totalSeconds: Long): UptimeParts {
    val s = totalSeconds.coerceAtLeast(0)
    return UptimeParts(
        days = s / 86_400,
        hours = (s % 86_400) / 3_600,
        minutes = (s % 3_600) / 60,
        seconds = s % 60,
    )
}

/** Localized medium date for subscription expiry (epoch seconds): `May 1, 2026` / `1 мая 2026 г.`. */
fun formatDate(epochSeconds: Long, locale: Locale = Locale.getDefault()): String =
    Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
