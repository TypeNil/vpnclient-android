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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
        if (onClick != null) AfterglowChevron()
    }
}

/** Decorative trailing chevron of a tappable row (the row itself carries the button role). */
@Composable
fun AfterglowChevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = AfterglowTheme.colors.muted,
        modifier = Modifier.size(AfterglowTokens.iconSize),
    )
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

enum class NoticeTone { Error, Warning, Info }

/**
 * The one inline notice: tone icon (shape, not colour, tells the tone) + optional bold title + text,
 * optional extra line, at most one text action and an optional close. Targets are >= [AfterglowTokens.touchTarget].
 */
@Composable
fun AfterglowNotice(
    tone: NoticeTone,
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val c = AfterglowTheme.colors
    val (icon, tint) = when (tone) {
        NoticeTone.Error -> Icons.Default.Warning to c.error
        NoticeTone.Warning -> Icons.Default.Notifications to c.amber
        NoticeTone.Info -> Icons.Default.Info to c.ink
    }
    val bg = if (tone == NoticeTone.Error) c.errorSurface else c.paperSecondary
    Row(
        modifier
            .fillMaxWidth()
            .clip(AfterglowTokens.tileShape)
            .background(bg)
            .padding(AfterglowTokens.noticePadding),
        horizontalArrangement = Arrangement.spacedBy(AfterglowTokens.noticePadding),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(AfterglowTokens.iconSize))
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, style = AfterglowType.rowCaption, fontWeight = FontWeight.Bold, color = c.ink)
            Text(text, style = AfterglowType.rowCaption, color = c.ink)
            if (detail != null) Text(detail, style = AfterglowType.rowCaption, color = c.ink)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = AfterglowTokens.touchTarget)) {
                    Text(actionLabel, color = c.coral, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (onDismiss != null) {
            IconButton(onClick = onDismiss, modifier = Modifier.sizeIn(minWidth = AfterglowTokens.touchTarget, minHeight = AfterglowTokens.touchTarget)) {
                Icon(Icons.Default.Close, dismissLabel, tint = c.muted)
            }
        }
    }
}
