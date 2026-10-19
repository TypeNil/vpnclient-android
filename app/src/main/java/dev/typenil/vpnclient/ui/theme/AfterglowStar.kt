package dev.typenil.vpnclient.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser

/**
 * Favorite star. The state is carried by shape, not only color: favorites are a solid star,
 * everything else is the same polygon as an outline. 24 dp viewport; tint comes from the Icon.
 */
object AfterglowStar {
    private const val PATH = "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z"

    private fun build(name: String, filled: Boolean): ImageVector {
        val b = ImageVector.Builder(name, AfterglowTokens.iconSize, AfterglowTokens.iconSize, 24f, 24f)
        val nodes = PathParser().parsePathString(PATH).toNodes()
        return if (filled) {
            b.addPath(pathData = nodes, fill = SolidColor(Color.Black)).build()
        } else {
            b.addPath(
                pathData = nodes,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = AfterglowTokens.starStroke,
                strokeLineJoin = StrokeJoin.Round,
            ).build()
        }
    }

    val Filled: ImageVector = build("StarFilled", true)
    val Outlined: ImageVector = build("StarOutlined", false)

    fun icon(favorite: Boolean): ImageVector = if (favorite) Filled else Outlined
}
