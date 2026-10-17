package dev.typenil.vpnclient.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.ui.common.CORE_VERSION
import dev.typenil.vpnclient.ui.common.appliedDnsSummary
import dev.typenil.vpnclient.ui.common.localizedResources
import dev.typenil.vpnclient.ui.common.perAppSummary
import dev.typenil.vpnclient.ui.common.routeModeSummary
import dev.typenil.vpnclient.ui.common.underlayLabel
import dev.typenil.vpnclient.ui.common.uptimeText
import dev.typenil.vpnclient.core.common.formatBytes
import dev.typenil.vpnclient.ui.theme.AfterglowCard
import dev.typenil.vpnclient.ui.theme.AfterglowChevron
import dev.typenil.vpnclient.ui.theme.AfterglowNotice
import dev.typenil.vpnclient.ui.theme.AfterglowSectionHeader
import dev.typenil.vpnclient.ui.theme.AfterglowSheet
import dev.typenil.vpnclient.ui.theme.AfterglowSheetHeader
import dev.typenil.vpnclient.ui.theme.AfterglowTheme
import dev.typenil.vpnclient.ui.theme.AfterglowTokens
import dev.typenil.vpnclient.ui.theme.AfterglowType
import dev.typenil.vpnclient.ui.theme.NoticeTone
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Real session details in four groups (Connection, Traffic, Routing, Technical). Facts come
 * from the Connected payload, the applied plan and the service; nothing is invented — absent
 * values render as "—" and stale traffic stats as dashes, not zeros.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionDetailsSheet(
    state: VpnConnectionState.Connected,
    details: SessionDetails?,
    onDismiss: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    // Uptime reads live: a 1 Hz ticker keeps "now" current while the sheet is open,
    // gated by lifecycle like the home stats ticker.
    var now by remember { mutableStateOf(Instant.now()) }
    var nowNanos by remember { mutableLongStateOf(System.nanoTime()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = Instant.now(); nowNanos = System.nanoTime(); delay(1_000) }
        }
    }
    val res = localizedResources()
    val none = stringResource(R.string.common_none)
    val fresh = remember { TrafficHistory() }
        .isFresh(frameTimeNanos(nowNanos, System.nanoTime()), state.statsReceivedAtNanos)
    val model = sessionDetailsModel(
        SessionDetailsInput(
            server = state.node.name,
            // The selector group's pick is what actually egresses — can diverge from the
            // session node after a live outbound switch.
            outbound = details?.activeOutbound ?: details?.activeOutboundRes?.let { stringResource(it) },
            network = details?.underlay?.let { underlayLabel(it) },
            uptime = uptimeText(state.since, now),
            stats = state.stats.takeIf { fresh },
            // Applied plan only — the settings value may not be live yet.
            routeMode = details?.applied?.let { routeModeSummary(it.routeMode) },
            perApp = details?.applied?.let { perAppSummary(it.perAppMode, it.perAppPackages.size) },
            dns = appliedDnsSummary(details?.applied?.dnsProfileSummary),
            pendingLabels = details?.pendingReconnect.orEmpty().map { stringResource(it) },
            vpnCore = CORE_VERSION,
            // Most recent Error seen this process — history, so it renders even when healthy.
            lastError = details?.lastErrorRes?.let { stringResource(it) } ?: details?.lastError,
            labels = DetailsLabels(
                server = stringResource(R.string.home_details_node),
                outbound = stringResource(R.string.home_details_outbound),
                network = stringResource(R.string.home_details_underlay),
                uptime = stringResource(R.string.home_details_uptime),
                downloaded = stringResource(R.string.home_details_downloaded),
                uploaded = stringResource(R.string.home_details_uploaded),
                routingMode = stringResource(R.string.common_routing_mode),
                perApp = stringResource(R.string.common_per_app_vpn),
                dns = stringResource(R.string.routing_dns_upstream),
                vpnCore = stringResource(R.string.common_vpn_core),
                lastError = stringResource(R.string.common_last_error),
            ),
            bytes = { res.formatBytes(it) },
            connectionsTexts = ConnectionsTexts(
                describe = { i, o -> res.getString(R.string.home_stat_connections_desc, i, o) },
                describeEmpty = res.getString(R.string.home_stat_connections_empty),
                dash = none,
            ),
            dash = none,
        ),
    )
    val largeFont = LocalDensity.current.fontScale > 1.3f
    AfterglowSheet(onDismiss = onDismiss) {
        AfterglowSheetHeader(
            title = stringResource(R.string.home_details_title),
            closeLabel = stringResource(R.string.common_dismiss),
            onDismiss = onDismiss,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AfterglowTokens.screenPadding)
                .padding(bottom = AfterglowTokens.sectionGap),
        ) {
            DetailsGroup(R.string.session_group_connection) { Lines(model.connection, largeFont) }
            DetailsGroup(R.string.session_group_traffic) {
                if (largeFont) Lines(listOf(model.downloaded, model.uploaded), true)
                else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    TotalCell(model.downloaded, Modifier.weight(1f))
                    VerticalHairline()
                    TotalCell(model.uploaded, Modifier.weight(1f))
                }
                Hairline()
                ActiveConnections(model.activeConnections)
            }
            DetailsGroup(R.string.session_group_routing) { Lines(model.routing, largeFont) }
            if (model.pending.isNotEmpty()) {
                Spacer(Modifier.height(AfterglowTokens.cardGap))
                AfterglowNotice(
                    NoticeTone.Warning,
                    stringResource(R.string.home_details_pending_notice, model.pending.joinToString(", ")),
                )
            }
            DetailsGroup(R.string.session_group_technical) {
                Lines(model.technical, largeFont)
                if (model.showDiagnostics) {
                    Hairline()
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenDiagnostics)
                            .heightIn(min = AfterglowTokens.touchTarget)
                            .padding(horizontal = AfterglowTokens.rowPaddingH),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.afterglow_open_diagnostics), style = AfterglowType.rowCaption,
                            fontWeight = FontWeight.SemiBold, color = AfterglowTheme.colors.ink, modifier = Modifier.weight(1f))
                        AfterglowChevron()
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsGroup(title: Int, content: @Composable () -> Unit) {
    AfterglowSectionHeader(
        stringResource(title),
        Modifier.fillMaxWidth().padding(start = AfterglowTokens.headerInset, top = AfterglowTokens.sectionGap, bottom = AfterglowTokens.headerGap),
    )
    AfterglowCard { content() }
}

@Composable
private fun Lines(lines: List<DetailLine>, largeFont: Boolean) {
    lines.forEachIndexed { i, line ->
        if (i > 0) Hairline()
        DetailLineView(line, largeFont)
    }
}

/** Muted label left, ink value right; long values or large fonts stack label over value. One TalkBack node. */
@Composable
private fun DetailLineView(line: DetailLine, largeFont: Boolean) {
    val colors = AfterglowTheme.colors
    val modifier = Modifier.fillMaxWidth().padding(horizontal = AfterglowTokens.rowPaddingH, vertical = AfterglowTokens.noticePadding)
        .clearAndSetSemantics { contentDescription = line.description }
    if (line.stacked(largeFont)) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(AfterglowTokens.headerInset)) {
            Text(line.label, style = AfterglowType.rowCaption, color = colors.muted)
            Text(line.value, style = AfterglowType.rowTitle, color = line.valueColor())
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(AfterglowTokens.cardGap)) {
            Text(line.label, style = AfterglowType.rowCaption, color = colors.muted, modifier = Modifier.weight(1f))
            Text(line.value, style = AfterglowType.rowTitle, color = line.valueColor(),
                textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

/** Values read in ink; the last error alone turns to the error tone (AA on the card in both themes). */
@Composable
private fun DetailLine.valueColor() = if (isError) AfterglowTheme.colors.error else AfterglowTheme.colors.ink

@Composable
private fun TotalCell(line: DetailLine, modifier: Modifier) {
    val colors = AfterglowTheme.colors
    Column(
        modifier.padding(horizontal = AfterglowTokens.rowPaddingH, vertical = AfterglowTokens.noticePadding)
            .clearAndSetSemantics { contentDescription = line.description },
        verticalArrangement = Arrangement.spacedBy(AfterglowTokens.headerInset),
    ) {
        Text(line.label, style = AfterglowType.rowCaption, color = colors.muted)
        Text(line.value, style = AfterglowType.rowTitle, color = colors.ink)
    }
}

/** "Active connections ↓in ↑out" — a plain row, not a link (Home's Connections row owns navigation). */
@Composable
private fun ActiveConnections(row: ConnectionsRow) {
    val colors = AfterglowTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = AfterglowTokens.rowPaddingH, vertical = AfterglowTokens.noticePadding)
            .semantics(mergeDescendants = true) { contentDescription = row.description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_details_active_connections), style = AfterglowType.rowCaption,
                color = colors.muted, modifier = Modifier.weight(1f))
            ConnectionsCount(true, row.incoming, colors.ink)
            Spacer(Modifier.width(AfterglowTokens.noticePadding))
            ConnectionsCount(false, row.outgoing, colors.coral)
        }
    }
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(AfterglowTokens.border).background(AfterglowTheme.colors.border))
}

@Composable
private fun VerticalHairline() {
    Box(Modifier.width(AfterglowTokens.border).fillMaxHeight().background(AfterglowTheme.colors.border))
}
