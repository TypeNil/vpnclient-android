package dev.typenil.vpnclient.ui.theme

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
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

/** Selected is a neutral surface, no frame of its own: inside a provider card a second outline would double the card's. Error red stays reserved for failures. */
fun AfterglowPalette.serverRowContainer(selected: Boolean): Color = if (selected) paperSecondary else Color.Transparent

/** A card that is itself the selection (Auto) carries the coral outline; group cards keep the plain border. */
fun AfterglowPalette.cardOutline(selected: Boolean): Color? = if (selected) coral else null

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

/** A dp size that follows the font scale (dp → sp → dp), so glyphs grow with the text they sit beside — the 12C-5 arrow mechanism. */
@Composable
fun scaledDp(size: Dp): Dp = with(LocalDensity.current) { size.value.sp.toDp() }

/**
 * Compact selectable server row. The check slot and [actions] (star, menu: each its own 48 dp
 * target) are always composed, so selecting never moves the text or the chip. [caption] and
 * [warning] are lines under the title; [spoken] is extra text for the row's accessibility
 * description only (latency method and age).
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
    warning: String? = null,
    spoken: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val c = AfterglowTheme.colors
    val selectedLabel = stringResource(R.string.common_selected)
    val description = serverRowDescription(
        title, protocol, if (showLatency && latency != null) latencyText(latency) else null, caption,
        warning, spoken, if (selected) selectedLabel else null,
    )
    Row(
        modifier
            .fillMaxWidth()
            .padding(AfterglowTokens.rowInset)
            .clip(AfterglowTokens.chipShape)
            .background(c.serverRowContainer(selected)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Only this part is the radio: the star and the menu beside it stay their own accessibility nodes.
        Row(
            Modifier
                .weight(1f)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .clearAndSetSemantics { contentDescription = description }
                .heightIn(min = AfterglowTokens.touchTarget)
                .padding(start = AfterglowTokens.rowPaddingH - AfterglowTokens.rowInset, top = AfterglowTokens.serverRowPaddingV, bottom = AfterglowTokens.serverRowPaddingV),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AfterglowTokens.headerGap),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = AfterglowType.rowTitle, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (protocol != null || showLatency) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AfterglowTokens.headerInset)) {
                        if (protocol != null) Text(protocol, style = AfterglowType.rowCaption, color = c.muted, maxLines = 1)
                        if (protocol != null && showLatency) Text("·", style = AfterglowType.rowCaption, color = c.muted)
                        if (showLatency) LatencyChip(latency)
                    }
                }
                if (caption != null) Text(caption, style = AfterglowType.rowCaption, color = c.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (warning != null) Text(warning, style = AfterglowType.statLabel, color = c.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(scaledDp(AfterglowTokens.iconSize)), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = c.coral, modifier = Modifier.size(scaledDp(AfterglowTokens.iconSize)))
            }
        }
        // Without actions the row keeps the trailing inset its padding used to give.
        if (actions != null) actions() else Spacer(Modifier.width(AfterglowTokens.rowPaddingH - AfterglowTokens.rowInset))
    }
}

/** One card per provider: optional [header] ("Provider · N"), then [content] rows split by thin dividers. Not sticky. [selectedOutline] = the card itself is the selection (Auto). */
@Composable
fun ProviderGroupCard(header: String?, modifier: Modifier = Modifier, selectedOutline: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    AfterglowCard(modifier, outline = AfterglowTheme.colors.cardOutline(selectedOutline)) {
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
