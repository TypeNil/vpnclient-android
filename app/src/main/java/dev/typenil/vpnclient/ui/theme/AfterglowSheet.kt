package dev.typenil.vpnclient.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Real vertical space the sheet gave its content — bounded by the visible
 * sheet window (so it shrinks under the IME), unlike raw screen-height
 * fractions. Null outside a sheet.
 */
val LocalSheetMaxHeight = compositionLocalOf<Dp?> { null }

/** Material owns swipe, scrim, IME and system insets; Afterglow owns the surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AfterglowSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AfterglowTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // The partial anchor would open a tall list sheet collapsed onto its
        // controls alone; expanded opens at the bounded full height — short
        // sheets still wrap their content.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Lift the sheet above the IME so fields/actions stay visible; the
        // content bound below shrinks with it.
        modifier = Modifier.imePadding(),
        containerColor = colors.paper,
        contentColor = colors.ink,
        shape = AfterglowTokens.sheetShape,
        tonalElevation = 0.dp,
        scrimColor =
            androidx.compose.ui.graphics.Color.Black
                .copy(alpha = .55f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 16.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(colors.border, RoundedCornerShape(4.dp)),
            )
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            CompositionLocalProvider(LocalSheetMaxHeight provides maxHeight) {
                Column(Modifier.fillMaxWidth(), content = content)
            }
        }
    }
}

@Composable
fun AfterglowSheetHeader(
    title: String,
    closeLabel: String,
    onDismiss: () -> Unit,
) {
    val colors = AfterglowTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            color = colors.ink,
            // A hostile/verbose name can't starve the scroll body — capped at
            // two lines; full names stay editable in the rename form.
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Close, contentDescription = closeLabel, tint = colors.ink)
        }
    }
    Spacer(Modifier.height(8.dp))
}
