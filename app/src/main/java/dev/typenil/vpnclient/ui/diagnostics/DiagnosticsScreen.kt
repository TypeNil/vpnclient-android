package dev.typenil.vpnclient.ui.diagnostics

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.common.log.LogExporter
import dev.typenil.vpnclient.core.common.log.LogRing
import dev.typenil.vpnclient.core.vpn.ConnectionHealth
import dev.typenil.vpnclient.core.vpn.HealthLevel
import dev.typenil.vpnclient.core.vpn.HealthStatus
import dev.typenil.vpnclient.core.vpn.HealthReason
import dev.typenil.vpnclient.core.vpn.HealthSource
import dev.typenil.vpnclient.core.vpn.HealthScope
import dev.typenil.vpnclient.core.vpn.HealthFreshness
import dev.typenil.vpnclient.core.vpn.IpCheckResult
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.ui.common.CORE_VERSION
import dev.typenil.vpnclient.ui.common.DetailRow
import dev.typenil.vpnclient.ui.common.appliedDnsSummary
import dev.typenil.vpnclient.ui.common.perAppSummary
import dev.typenil.vpnclient.ui.common.routeModeSummary
import dev.typenil.vpnclient.ui.common.underlayLabel
import dev.typenil.vpnclient.ui.common.uptimeText

/**
 * Session diagnostics: what the tunnel is actually doing right now plus the
 * two user actions (IP probe, log export). Every rendered value comes from
 * ConnectionManager/AppliedSessionConfig or a real probe — absent values
 * render as "—", never a guess.
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareChooserTitle = stringResource(R.string.diag_share_chooser)
    // Injected into the composable — the VM stays Context-free.
    val exporter = rememberLogExporter()

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                )
            }
            Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.titleLarge)
        }

        val none = stringResource(R.string.common_none)
        val applied = ui.applied
        val connection = ui.connection
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DetailRow(
                stringResource(R.string.diag_session_state),
                connectionStateText(connection),
            )
            // The applied plan only exists inside a session — the settings
            // values are intent, not what the tunnel runs.
            DetailRow(
                stringResource(R.string.home_details_node),
                when (connection) {
                    is VpnConnectionState.Connected -> connection.node.name
                    is VpnConnectionState.Connecting -> connection.node.name
                    is VpnConnectionState.Reconnecting -> connection.node.name
                    is VpnConnectionState.Preparing -> connection.node?.name ?: none
                    else -> none
                },
            )
            DetailRow(
                stringResource(R.string.common_routing_mode),
                applied?.let { routeModeSummary(it.routeMode) } ?: none,
            )
            DetailRow(
                stringResource(R.string.common_per_app_vpn),
                applied?.let { perAppSummary(it.perAppMode, it.perAppPackages.size) } ?: none,
            )
            DetailRow(
                stringResource(R.string.routing_dns_upstream),
                appliedDnsSummary(applied?.dnsProfileSummary),
            )
            DetailRow(
                stringResource(R.string.home_details_underlay),
                underlayLabel(ui.underlay),
            )
            (connection as? VpnConnectionState.Connected)?.let {
                DetailRow(stringResource(R.string.home_details_uptime), uptimeText(it.since))
            }
            DetailRow(stringResource(R.string.common_vpn_core), CORE_VERSION)
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        HealthTable(ui.health)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // App-HTTP IP echo, route unverified: the request is an ordinary app
        // socket — an echo response can't prove it rode the TUN. With no
        // session there is no tunnel at all, and the screen says so.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (connection !is VpnConnectionState.Connected) {
                Text(
                    text = stringResource(R.string.diag_ip_not_connected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = viewModel::checkIp,
                enabled = ui.ipCheckStatus != IpCheckStatus.Running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (ui.ipCheckStatus == IpCheckStatus.Running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.diag_check_ip_running))
                } else {
                    Text(stringResource(R.string.diag_check_ip))
                }
            }
            Text(
                text = stringResource(R.string.diag_check_ip_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            ui.ipCheck?.let { result ->
                Spacer(Modifier.height(8.dp))
                IpResultText(result)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedButton(
                onClick = {
                    val intent = exporter.buildShareIntent(context)
                    context.startActivity(Intent.createChooser(intent, shareChooserTitle))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.diag_export_log))
            }
            Text(
                text = stringResource(R.string.diag_export_log_sub, LogRing.DEFAULT_CAPACITY),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun HealthTable(health: ConnectionHealth) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.health_title), style = MaterialTheme.typography.titleMedium)
        health.observations.forEach { observation ->
            Column {
                DetailRow(healthLabel(observation.level), healthLabel(observation.status))
                Text(
                    stringResource(R.string.health_evidence,
                        healthLabel(observation.reason), healthLabel(observation.source),
                        healthLabel(observation.scope), healthLabel(observation.freshness), observation.generation),
                    style = MaterialTheme.typography.bodySmall,
                )
                val time = observation.checkedAt?.let {
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM)
                        .format(java.util.Date.from(it))
                } ?: stringResource(R.string.common_none)
                Text(stringResource(R.string.health_checked, time, observation.ttlMillis / 1_000), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun healthLabel(value: Enum<*>): String = stringResource(when (value) {
    HealthLevel.VpnConsent -> R.string.health_consent
    HealthLevel.TunEstablished -> R.string.health_tun
    HealthLevel.EngineRunning -> R.string.health_engine
    HealthLevel.UnderlyingNetwork -> R.string.health_underlay
    HealthLevel.OutboundReachable -> R.string.health_outbound
    HealthLevel.TrafficForwarding -> R.string.health_traffic
    HealthLevel.DnsReachable -> R.string.health_dns
    HealthLevel.LastSuccessfulCheck -> R.string.health_last_check
    HealthStatus.Ok -> R.string.health_ok
    HealthStatus.Degraded -> R.string.health_degraded
    HealthStatus.Failed -> R.string.health_failed
    HealthStatus.Unverified -> R.string.health_unverified
    HealthReason.NotObserved -> R.string.health_not_observed
    HealthReason.ConsentGranted -> R.string.health_consent_granted
    HealthReason.ConsentRequired -> R.string.health_consent_required
    HealthReason.ConsentDenied -> R.string.health_consent_denied
    HealthReason.Revoked -> R.string.health_revoked
    HealthReason.Started -> R.string.health_started
    HealthReason.Stopped -> R.string.health_stopped
    HealthReason.RuntimeInvalidated -> R.string.health_runtime_invalidated
    HealthReason.NetworkAvailable -> R.string.health_network_available
    HealthReason.NetworkLost -> R.string.health_network_lost
    HealthReason.PathChanged -> R.string.health_path_changed
    HealthReason.StartFailed -> R.string.health_start_failed
    HealthReason.HttpResponseRouteUnverified -> R.string.health_http_response
    HealthReason.HttpTimeout -> R.string.health_http_timeout
    HealthReason.HttpError -> R.string.health_http_error
    HealthReason.HttpNetworkError -> R.string.health_http_network
    HealthReason.HttpUnexpectedResponse -> R.string.health_http_unexpected
    HealthReason.Expired, HealthFreshness.Expired -> R.string.health_expired
    HealthSource.None, HealthScope.None -> R.string.health_none
    HealthSource.VpnConsent -> R.string.health_source_consent
    HealthSource.ServiceLifecycle -> R.string.health_source_service
    HealthSource.PlatformUnderlay -> R.string.health_source_underlay
    HealthSource.IpEcho -> R.string.health_source_ip_echo
    HealthScope.LocalRuntime -> R.string.health_scope_runtime
    HealthScope.PhysicalUnderlay -> R.string.health_scope_underlay
    HealthScope.AppHttpRouteUnverified -> R.string.health_scope_app_http
    HealthFreshness.Unobserved -> R.string.health_not_observed
    HealthFreshness.Fresh -> R.string.health_fresh
    else -> R.string.health_unverified
})

/** Hilt entry point for the composable — same pattern ViewModels use. */
@Composable
private fun rememberLogExporter(): LogExporter {
    val context = LocalContext.current
    return remember {
        EntryPointAccessors
            .fromApplication(
                context.applicationContext,
                LogExporterEntryPoint::class.java,
            ).logExporter()
    }
}

// Not private: Hilt generates the implementation in this package.
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LogExporterEntryPoint {
    fun logExporter(): LogExporter
}

@Composable
private fun connectionStateText(state: VpnConnectionState): String =
    when (state) {
        VpnConnectionState.Idle -> {
            stringResource(R.string.home_status_disconnected)
        }

        is VpnConnectionState.Preparing -> {
            stringResource(R.string.home_status_preparing)
        }

        VpnConnectionState.PermissionRequired -> {
            stringResource(R.string.home_status_permission)
        }

        is VpnConnectionState.Connecting -> {
            stringResource(R.string.home_status_connecting)
        }

        is VpnConnectionState.Connected -> {
            stringResource(R.string.home_status_connected)
        }

        is VpnConnectionState.Reconnecting -> {
            stringResource(R.string.home_status_reconnecting, state.attempt)
        }

        VpnConnectionState.Stopping -> {
            stringResource(R.string.home_status_disconnecting)
        }

        is VpnConnectionState.Error -> {
            stringResource(R.string.home_status_failed)
        }
    }

@Composable
private fun IpResultText(result: IpCheckResult) {
    if (result.ok) {
        Text(
            text =
                stringResource(
                    R.string.diag_ip_result,
                    result.ip.orEmpty(),
                    result.latencyMs ?: 0L,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Text(
            text = stringResource(R.string.diag_ip_error, ipErrorText(result.error)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Map the probe's short error category to a localized message — the raw
 *  string is never rendered (it can echo request details). */
@Composable
private fun ipErrorText(error: String?): String =
    when {
        error == "timeout" -> {
            stringResource(R.string.diag_ip_timeout)
        }

        error == "unexpected response" -> {
            stringResource(R.string.diag_ip_unexpected)
        }

        error != null && error.startsWith("http ") -> {
            stringResource(R.string.diag_ip_http, error.removePrefix("http "))
        }

        error != null -> {
            stringResource(R.string.diag_ip_network, error)
        }

        else -> {
            stringResource(R.string.common_none)
        }
    }
