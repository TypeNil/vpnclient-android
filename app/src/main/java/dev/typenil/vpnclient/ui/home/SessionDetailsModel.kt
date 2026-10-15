package dev.typenil.vpnclient.ui.home

import dev.typenil.vpnclient.core.engine.TrafficStats

/** One "label — value" line; [value] is display text, "—" when unknown. */
internal data class DetailLine(val label: String, val value: String) {
    /** TalkBack reads a line as one node. */
    val description: String get() = "$label, $value"
}

/** Resolved label strings, injected so the model stays a pure function. */
internal class DetailsLabels(
    val server: String, val outbound: String, val network: String, val uptime: String,
    val downloaded: String, val uploaded: String, val activeConnections: String,
    val routingMode: String, val perApp: String, val dns: String,
    val vpnCore: String, val lastError: String,
)

/** Everything the sheet shows, already localized. `null` = unknown (shown as [dash]). */
internal class SessionDetailsInput(
    val server: String,
    val outbound: String?,
    val network: String?,
    val uptime: String,
    /** Fresh stats only; stale or missing → dashes, never zeros. */
    val stats: TrafficStats?,
    val routeMode: String?,
    val perApp: String?,
    val dns: String?,
    val pendingLabels: List<String>,
    val vpnCore: String,
    val lastError: String?,
    val labels: DetailsLabels,
    val bytes: (Long) -> String,
    val connectionsTexts: ConnectionsTexts,
    val dash: String,
)

internal class SessionDetailsModel(
    val connection: List<DetailLine>,
    val downloaded: DetailLine,
    val uploaded: DetailLine,
    val activeConnections: ConnectionsRow,
    val routing: List<DetailLine>,
    /** Changed-but-unapplied settings; empty = nothing pending. */
    val pending: List<String>,
    val technical: List<DetailLine>,
    /** Last error is the only reason to point at Diagnostics from here. */
    val showDiagnostics: Boolean,
)

/** A long value (DNS summaries, server names) gets its own line instead of squeezing the label. */
internal fun DetailLine.stacked(largeFont: Boolean): Boolean = largeFont || value.length > STACK_VALUE_CHARS

private const val STACK_VALUE_CHARS = 24

internal fun sessionDetailsModel(i: SessionDetailsInput): SessionDetailsModel {
    val l = i.labels
    fun line(label: String, value: String?) = DetailLine(label, value?.takeIf { it.isNotBlank() } ?: i.dash)
    // The selector pick is what egresses; it only earns a line when it differs from the session server.
    val outbound = i.outbound?.takeIf { it.isNotBlank() && it != i.server }
    val lastError = i.lastError?.takeIf { it.isNotBlank() }
    return SessionDetailsModel(
        connection = listOfNotNull(
            line(l.server, i.server),
            outbound?.let { line(l.outbound, it) },
            line(l.network, i.network),
            line(l.uptime, i.uptime),
        ),
        downloaded = line(l.downloaded, i.stats?.let { i.bytes(it.downlinkTotalBytes) }),
        uploaded = line(l.uploaded, i.stats?.let { i.bytes(it.uplinkTotalBytes) }),
        activeConnections = connectionsRow(i.stats, i.connectionsTexts),
        routing = listOf(line(l.routingMode, i.routeMode), line(l.perApp, i.perApp), line(l.dns, i.dns)),
        pending = i.pendingLabels,
        technical = listOfNotNull(line(l.vpnCore, i.vpnCore), lastError?.let { line(l.lastError, it) }),
        showDiagnostics = lastError != null,
    )
}
