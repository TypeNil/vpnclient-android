package dev.typenil.vpnclient.ui.theme

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.typenil.vpnclient.R

/** Latency up to this is [LatencyTone.Good]; up to [LATENCY_FAIR_MAX_MS] [LatencyTone.Fair]; above (or a timeout) [LatencyTone.Poor]. */
const val LATENCY_GOOD_MAX_MS = 150
const val LATENCY_FAIR_MAX_MS = 400

enum class LatencyTone { Good, Fair, Poor, None }

/** A stored verdict as the chip shows it: [ms] null = the probe failed; [methodRes] names what was measured. */
data class LatencyReading(val ms: Int?, @param:StringRes val methodRes: Int)

fun latencyTone(reading: LatencyReading?): LatencyTone {
    val ms = reading?.ms
    return when {
        reading == null -> LatencyTone.None
        ms == null -> LatencyTone.Poor
        ms <= LATENCY_GOOD_MAX_MS -> LatencyTone.Good
        ms <= LATENCY_FAIR_MAX_MS -> LatencyTone.Fair
        else -> LatencyTone.Poor
    }
}

/** [background] may be translucent: it is drawn over the row surface (ContrastTest composites it). */
data class ChipColors(val text: Color, val background: Color)

fun AfterglowPalette.latencyChipColors(tone: LatencyTone): ChipColors =
    when (tone) {
        LatencyTone.Good -> ChipColors(ink, jade.copy(alpha = AfterglowTokens.latencyTint))
        LatencyTone.Fair -> ChipColors(ink, amber.copy(alpha = AfterglowTokens.latencyTint))
        LatencyTone.Poor -> ChipColors(error, errorSurface)
        LatencyTone.None -> ChipColors(muted, Color.Transparent)
    }

/** Selected is a neutral surface with a coral frame; error red stays reserved for failures. Unselected keeps a clear frame so nothing shifts. */
data class RowColors(val container: Color, val frame: Color)

fun AfterglowPalette.serverRowColors(selected: Boolean): RowColors =
    if (selected) RowColors(paperSecondary, coral) else RowColors(Color.Transparent, Color.Transparent)

/** One provider's header is noise; it appears once there is more than one group to tell apart. */
fun showProviderHeader(groupCount: Int): Boolean = groupCount > 1

/** Spoken row: "Name, PROTO, 5 ms, Unencrypted, Selected" — absent parts are skipped. */
fun serverRowDescription(vararg parts: String?): String = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(", ")

@Composable
private fun latencyText(reading: LatencyReading?): String =
    when {
        reading == null -> stringResource(R.string.common_none)
        reading.ms == null -> stringResource(R.string.servers_latency_timeout)
        else -> stringResource(R.string.servers_latency_ms, reading.ms)
    }

/** Always text, never colour alone; "—" when nothing was measured. */
@Composable
fun LatencyChip(reading: LatencyReading?, modifier: Modifier = Modifier) {
    val colors = AfterglowTheme.colors.latencyChipColors(latencyTone(reading))
    val description =
        if (reading == null) {
            stringResource(R.string.picker_latency_unknown)
        } else {
            "${latencyText(reading)}, ${stringResource(reading.methodRes)}"
        }
    Text(
        text = latencyText(reading),
        style = AfterglowType.statLabel,
        color = colors.text,
        maxLines = 1,
        modifier = modifier
            .semantics { contentDescription = description }
            .clip(AfterglowTokens.chipShape)
            .background(colors.background)
            .padding(horizontal = AfterglowTokens.chipPaddingH, vertical = AfterglowTokens.chipPaddingV),
    )
}

/**
 * Compact selectable server row. The check slot (and, with [actionSlots], two 48 dp slots for
 * star and menu) is always reserved, so selecting never moves the text or the chip.
 */
@Composable
fun ServerRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    protocol: String? = null,
    latency: LatencyReading? = null,
    showLatency: Boolean = true,
    caption: String? = null,
    unencryptedLabel: String? = null,
    actionSlots: Boolean = false,
) {
    val c = AfterglowTheme.colors
    val row = c.serverRowColors(selected)
    val selectedLabel = stringResource(R.string.common_selected)
    val description = serverRowDescription(
        title, protocol, if (showLatency && latency != null) latencyText(latency) else null, caption,
        unencryptedLabel, if (selected) selectedLabel else null,
    )
    Row(
        modifier
            .fillMaxWidth()
            .padding(AfterglowTokens.rowInset)
            .clip(AfterglowTokens.chipShape)
            .background(row.container)
            .border(AfterglowTokens.border, row.frame, AfterglowTokens.chipShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description }
            .heightIn(min = AfterglowTokens.touchTarget)
            .padding(horizontal = AfterglowTokens.rowPaddingH - AfterglowTokens.rowInset, vertical = AfterglowTokens.serverRowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AfterglowTokens.headerGap),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AfterglowType.rowTitle, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AfterglowTokens.headerInset)) {
                if (protocol != null) Text(protocol, style = AfterglowType.rowCaption, color = c.muted, maxLines = 1)
                if (protocol != null && (showLatency || caption != null)) Text("·", style = AfterglowType.rowCaption, color = c.muted)
                if (showLatency) LatencyChip(latency)
                if (caption != null) {
                    Text(caption, style = AfterglowType.rowCaption, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (unencryptedLabel != null) {
                Text(unencryptedLabel, style = AfterglowType.statLabel, color = c.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.size(AfterglowTokens.iconSize), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = c.coral)
        }
        // ponytail: empty reserved slots; the unified Servers sheet puts the star and the menu in them.
        if (actionSlots) Box(Modifier.size(width = AfterglowTokens.touchTarget * 2, height = AfterglowTokens.touchTarget))
    }
}

/** One card per provider: optional [header] ("Provider · N"), then [content] rows split by thin dividers. Not sticky. */
@Composable
fun ProviderGroupCard(header: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    AfterglowCard(modifier) {
        if (header != null) {
            Text(
                header,
                style = AfterglowType.statLabel,
                color = AfterglowTheme.colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .semantics { heading() }
                    .padding(start = AfterglowTokens.rowPaddingH, end = AfterglowTokens.rowPaddingH, top = AfterglowTokens.headerGap),
            )
        }
        content()
    }
}

/** Hairline between two rows of a [ProviderGroupCard]. */
@Composable
fun ServerRowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AfterglowTokens.rowPaddingH)
            .height(AfterglowTokens.border)
            .background(AfterglowTheme.colors.border),
    )
}
