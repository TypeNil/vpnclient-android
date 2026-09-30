package dev.typenil.vpnclient.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.vpn.PerAppMode
import dev.typenil.vpnclient.core.vpn.UnderlyingTransport
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

/** Elapsed since Connected — whole units, no fake precision. Days fold
 *  into `d h min` (never "52:30:50"); under a day the leading unit rules. */
@Composable
fun uptimeText(
    since: Instant,
    now: Instant = Instant.now(),
): String {
    val p = uptimeParts(Duration.between(since, now).seconds)
    return when {
        p.days > 0 -> stringResource(R.string.home_uptime_dhm, p.days, p.hours, p.minutes)
        p.hours > 0 -> stringResource(R.string.home_uptime_hms, p.hours, p.minutes, p.seconds)
        p.minutes > 0 -> stringResource(R.string.home_uptime_ms, p.minutes, p.seconds)
        else -> stringResource(R.string.home_uptime_s, p.seconds)
    }
}

/**
 * Localized display for the compiled DNS profile summary
 * ("policy:cloudflare"-style). Only known safe preset keys map to
 * resources; `custom:` summaries are already redacted by the profile and
 * pass through verbatim (never parsed here); anything else renders "—"
 * rather than a raw internal token.
 */
@Composable
fun appliedDnsSummary(summary: String?): String {
    val none = stringResource(R.string.common_none)
    if (summary == null) return none
    if (summary.startsWith("custom:")) return summary
    val sep = summary.indexOf(':')
    if (sep <= 0 || sep == summary.length - 1) return none
    val modeRes =
        when (summary.substring(0, sep)) {
            "policy" -> R.string.dns_mode_policy
            "proxy_only" -> R.string.dns_mode_proxy_only
            else -> null
        }
    val upstreamRes =
        when (summary.substring(sep + 1)) {
            "cloudflare" -> R.string.dns_upstream_cloudflare
            "google" -> R.string.dns_upstream_google
            "quad9" -> R.string.dns_upstream_quad9
            "adguard" -> R.string.dns_upstream_adguard
            else -> null
        }
    return if (modeRes != null && upstreamRes != null) {
        "${stringResource(modeRes)} · ${stringResource(upstreamRes)}"
    } else {
        none
    }
}

/** Localized underlay transport — UNKNOWN maps to "—", never an invented state. */
@Composable
fun underlayLabel(transport: UnderlyingTransport): String =
    stringResource(
        when (transport) {
            UnderlyingTransport.WIFI -> R.string.underlay_wifi
            UnderlyingTransport.CELLULAR -> R.string.underlay_cellular
            UnderlyingTransport.OTHER -> R.string.underlay_other
            UnderlyingTransport.UNKNOWN -> R.string.common_none
        },
    )

/**
 * Stacked label/value row for session detail sheets — small muted caption
 * over the value, always full width and unclipped: nothing truncates away
 * real session data at any font scale or label length.
 */
@Composable
fun DetailRow(
    label: String,
    value: String,
    error: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color =
                if (error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
    }
}
