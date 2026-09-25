package dev.typenil.vpnclient.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.vpn.PerAppMode
import java.time.Duration
import java.time.Instant

/**
 * Shared session-summary labels — used by the Home session-details sheet and
 * the Diagnostics screen so both describe the same applied state the same way.
 */

@Composable
fun routeModeSummary(mode: RouteMode): String =
    when (mode) {
        RouteMode.ALL -> stringResource(R.string.route_mode_all)
        RouteMode.BYPASS_RU -> stringResource(R.string.route_mode_bypass_ru)
        RouteMode.PROXY_BLOCKED -> stringResource(R.string.route_mode_proxy_blocked)
    }

@Composable
fun perAppSummary(
    mode: PerAppMode,
    count: Int,
): String =
    when (mode) {
        PerAppMode.ALL -> {
            stringResource(R.string.per_app_mode_all)
        }

        PerAppMode.INCLUDE -> {
            stringResource(
                if (count == 1) R.string.home_per_app_include_one else R.string.home_per_app_include,
                count,
            )
        }

        PerAppMode.EXCLUDE -> {
            stringResource(
                if (count == 1) R.string.home_per_app_exclude_one else R.string.home_per_app_exclude,
                count,
            )
        }
    }

/** Elapsed since Connected — whole units, no fake precision. */
@Composable
fun uptimeText(
    since: Instant,
    now: Instant = Instant.now(),
): String {
    val seconds = Duration.between(since, now).seconds.coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> stringResource(R.string.home_uptime_hms, h, m, s)
        m > 0 -> stringResource(R.string.home_uptime_ms, m, s)
        else -> stringResource(R.string.home_uptime_s, s)
    }
}

/** Label/value row for session detail sheets. */
@Composable
fun DetailRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Floor under the label: a long value (raw outbound tag) would
            // otherwise squeeze it down to one letter per line.
            modifier = Modifier.weight(1.1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.9f),
        )
    }
}
