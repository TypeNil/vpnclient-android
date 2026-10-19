package dev.typenil.vpnclient.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Shared editorial spacing, shapes and motion; touch targets remain at least 48 dp. */
object AfterglowTokens {
    val screenPadding = 16.dp
    val cardGap = 12.dp
    val sectionGap = 20.dp
    val headerGap = 8.dp
    val headerInset = 4.dp
    val rowPaddingH = 16.dp
    val rowPaddingV = 10.dp
    val rowMinHeight = 64.dp
    val rowGap = 14.dp
    val tileSize = 44.dp
    val iconSize = 20.dp
    val chipIconSize = 18.dp
    val progressSize = 16.dp
    val progressStroke = 2.dp
    val emptyTop = 48.dp
    val emptyInset = 32.dp
    val headingBottom = 2.dp
    val starStroke = 1.75f
    val border = 1.dp
    val touchTarget = 48.dp
    val buttonHeight = 52.dp
    val fieldHeight = 56.dp
    val rowHeight = 56.dp
    val noticePadding = 12.dp
    val rowInset = 4.dp
    val serverRowPaddingV = 8.dp
    val chipPaddingH = 8.dp
    val chipPaddingV = 4.dp
    /** Jade/amber latency chip wash over the row surface; its ink text keeps AA (ContrastTest). */
    const val latencyTint = .28f
    val chartHeight = 40.dp
    val chartLine = 2.dp
    const val chartFillAlpha = .16f
    val statTileSize = 28.dp
    val statArrowSize = 18.dp
    val connArrowSize = 16.dp
    /** Tint of the Statistics direction tile; the arrow keeps AA/non-text contrast over it (ContrastTest). */
    const val statTileTint = .12f
    val chartGap = 4.dp
    val actionBarHeight = 76.dp
    val actionBarHeightCompact = 64.dp
    val actionPadding = 12.dp
    val actionTileSize = 52.dp
    val actionIconSize = 24.dp
    val actionStroke = 2.5.dp
    val mascotReserve = 64.dp
    val mascotHeight = 72.dp
    val mascotLift = 52.dp
    val mascotInset = 7.dp
    val chipShape = RoundedCornerShape(8.dp)
    val tileShape = RoundedCornerShape(12.dp)
    val inputShape = RoundedCornerShape(12.dp)
    val cardRadius = 16.dp
    val cardShape = RoundedCornerShape(cardRadius)
    val barShape = RoundedCornerShape(20.dp)
    val actionTileShape = RoundedCornerShape(14.dp)
    val dialogShape = RoundedCornerShape(20.dp)
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    const val motionFast = 150
    const val motionStandard = 220
    const val motionEmphasized = 360
}
