package dev.typenil.vpnclient.ui.home

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import dev.typenil.vpnclient.ui.theme.AfterglowTokens
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.SolidColor

// material-icons-core ships no ArrowDownward/ArrowUpward (and extended is not a dependency):
// the two stock Material paths, 24 dp viewport.
private fun arrow(name: String, path: String): ImageVector =
    ImageVector.Builder(name, AfterglowTokens.actionIconSize, AfterglowTokens.actionIconSize, 24f, 24f).addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()

private val ArrowDown = arrow("StatArrowDown", "M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8 8,-8z")
private val ArrowUp = arrow("StatArrowUp", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z")

/** Decorative direction arrow; the owning column/row announces direction in its own description. */
@Composable
internal fun StatArrow(download: Boolean, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Icon(if (download) ArrowDown else ArrowUp, contentDescription = null, tint = tint, modifier = modifier.size(size))
}
