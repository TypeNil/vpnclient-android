package dev.typenil.vpnclient.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.common.formatBytes
import dev.typenil.vpnclient.core.vpn.LastSession
import dev.typenil.vpnclient.ui.common.localizedResources
import dev.typenil.vpnclient.ui.common.relativeTimeText
import dev.typenil.vpnclient.ui.common.uptimeText
import dev.typenil.vpnclient.ui.theme.AfterglowCard
import dev.typenil.vpnclient.ui.theme.AfterglowSectionHeader
import dev.typenil.vpnclient.ui.theme.AfterglowTheme
import dev.typenil.vpnclient.ui.theme.AfterglowTokens
import dev.typenil.vpnclient.ui.theme.AfterglowType
import kotlinx.coroutines.delay
import java.time.Instant

private const val ENDED_REFRESH_MS = 60_000L

/** Idle-time replacement for Statistics: totals of the last finished session, no charts. */
@Composable
internal fun LastSessionCard(session: LastSession) {
    val colors = AfterglowTheme.colors
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { nowMs = System.currentTimeMillis(); delay(ENDED_REFRESH_MS) }
        }
    }
    val compact = LocalDensity.current.fontScale > 1.3f || LocalConfiguration.current.screenWidthDp < 340
    val res = localizedResources()
    val age = endedAge(nowMs, session.endedAtEpochMs)
    val ended = when {
        age == null -> stringResource(R.string.home_last_session_ended_earlier)
        else -> stringResource(
            R.string.home_last_session_ended,
            res.relativeTimeText(nowMs - session.endedAtEpochMs),
        )
    }
    val (down, up) = lastSessionColumns(
        session,
        LastSessionTexts(
            downloadedLabel = stringResource(R.string.home_last_session_downloaded),
            uploadedLabel = stringResource(R.string.home_last_session_uploaded),
            bytes = { res.formatBytes(it) },
            describe = { label, value -> res.getString(R.string.home_last_session_column_desc, label, value) },
        ),
    )
    val headerPadding = Modifier.fillMaxWidth()
        .padding(start = AfterglowTokens.headerInset, top = AfterglowTokens.sectionGap, bottom = AfterglowTokens.headerGap)
    if (compact) {
        Column(headerPadding) {
            AfterglowSectionHeader(stringResource(R.string.home_last_session_title))
            Text(ended, style = AfterglowType.statLabel, color = colors.muted)
        }
    } else {
        Row(headerPadding) {
            AfterglowSectionHeader(stringResource(R.string.home_last_session_title), Modifier.alignByBaseline())
            Spacer(Modifier.weight(1f))
            Text(ended, style = AfterglowType.statLabel, color = colors.muted, textAlign = TextAlign.End,
                modifier = Modifier.alignByBaseline())
        }
    }
    AfterglowCard {
        if (compact) {
            LastSessionColumnView(down, Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth().height(AfterglowTokens.border).background(colors.border))
            LastSessionColumnView(up, Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                LastSessionColumnView(down, Modifier.weight(1f))
                Box(Modifier.width(AfterglowTokens.border).fillMaxHeight().background(colors.border))
                LastSessionColumnView(up, Modifier.weight(1f))
            }
        }
        Box(Modifier.fillMaxWidth().height(AfterglowTokens.border).background(colors.border))
        val duration = uptimeText(Instant.EPOCH, Instant.ofEpochMilli(session.durationMs))
        Text(
            stringResource(R.string.home_last_session_summary, duration, session.nodeName),
            style = AfterglowType.statLabel, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = AfterglowTokens.rowPaddingH, vertical = AfterglowTokens.rowPaddingV),
        )
    }
}

@Composable
private fun LastSessionColumnView(col: LastSessionColumn, modifier: Modifier) {
    val colors = AfterglowTheme.colors
    val arrowColor = if (col.download) colors.ink else colors.coral
    val accent = if (col.download) colors.jade else colors.coral
    Column(modifier.clearAndSetSemantics { contentDescription = col.description }
        .padding(horizontal = AfterglowTokens.noticePadding, vertical = AfterglowTokens.rowPaddingV)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(AfterglowTokens.statTileSize).clip(AfterglowTokens.chipShape)
                .background(accent.copy(alpha = AfterglowTokens.statTileTint)), contentAlignment = Alignment.Center) {
                StatArrow(col.download, arrowColor, AfterglowTokens.statArrowSize)
            }
            Text(col.label, style = AfterglowType.statLabel, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = AfterglowTokens.headerGap))
        }
        Text(col.value, style = AfterglowType.statValue, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = AfterglowTokens.headerGap))
    }
}
