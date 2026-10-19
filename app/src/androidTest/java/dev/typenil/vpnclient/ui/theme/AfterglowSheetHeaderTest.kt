package dev.typenil.vpnclient.ui.theme

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AfterglowSheetHeaderTest {
    @get:Rule val compose = createComposeRule()

    private var scale by mutableFloatStateOf(1f)

    private fun inkPixels(): Int {
        val img = compose.onNodeWithContentDescription("Close").captureToImage().toPixelMap()
        val bg = img[1, 1]
        return (0 until img.height).sumOf { y -> (0 until img.width).count { x -> img[x, y] != bg } }
    }

    @Test fun closeGlyphGrowsWithTheFontButTheTargetStaysAtLeast48dp() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) {
                AfterglowSheetHeader("Servers", "Close", {})
            }
        }
        val normal = inkPixels()
        scale = 2f
        compose.waitForIdle()
        assertTrue("glyph should grow with the font", inkPixels() > normal * 2)
        compose.onNodeWithContentDescription("Close").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }
}
