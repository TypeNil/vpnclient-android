package dev.typenil.vpnclient.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
        content = content,
    )
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
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Close, contentDescription = closeLabel, tint = colors.ink)
        }
    }
    Spacer(Modifier.height(8.dp))
}
