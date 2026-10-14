package dev.typenil.vpnclient.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/** Elevated, rounded, 1 dp bordered container; rows inside own their padding. */
@Composable
fun AfterglowCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = AfterglowTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(AfterglowTokens.cardShape)
            .background(c.surfaceElevated)
            .border(AfterglowTokens.border, c.border, AfterglowTokens.cardShape),
        content = content,
    )
}

/** Rounded square for a bare coral glyph (no frame of its own). */
@Composable
fun AfterglowIconTile(modifier: Modifier = Modifier, icon: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .size(AfterglowTokens.tileSize)
            .clip(AfterglowTokens.tileShape)
            .background(AfterglowTheme.colors.paperSecondary),
        contentAlignment = Alignment.Center,
        content = icon,
    )
}

/**
 * Leading tile, optional overline, title, optional caption, trailing chevron.
 * With [onClick] the whole row is one button, at least [AfterglowTokens.rowMinHeight] tall.
 */
@Composable
fun AfterglowRow(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    caption: String? = null,
    captionColor: Color = AfterglowTheme.colors.muted,
    overline: (@Composable () -> Unit)? = null,
    leading: @Composable BoxScope.() -> Unit,
) {
    val c = AfterglowTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .heightIn(min = AfterglowTokens.rowMinHeight)
            .padding(horizontal = AfterglowTokens.rowPaddingH, vertical = AfterglowTokens.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AfterglowTokens.rowGap),
    ) {
        AfterglowIconTile(icon = leading)
        Column(Modifier.weight(1f)) {
            overline?.invoke()
            Text(title, style = AfterglowType.rowTitle, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (caption != null) {
                Text(caption, style = AfterglowType.rowCaption, color = captionColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = c.muted,
                modifier = Modifier.size(AfterglowTokens.iconSize),
            )
        }
    }
}

@Composable
fun AfterglowOverline(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = AfterglowType.overline, color = AfterglowTheme.colors.muted, modifier = modifier)
}

/** Divider that starts under the row text, not under the tile. */
@Composable
fun AfterglowInsetDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(
                start = AfterglowTokens.rowPaddingH + AfterglowTokens.tileSize + AfterglowTokens.rowGap,
                end = AfterglowTokens.rowPaddingH,
            )
            .height(AfterglowTokens.border)
            .background(AfterglowTheme.colors.border),
    )
}

@Composable
fun AfterglowSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = AfterglowType.sectionHeader,
        color = AfterglowTheme.colors.coral,
        modifier = modifier.semantics { heading() },
    )
}
