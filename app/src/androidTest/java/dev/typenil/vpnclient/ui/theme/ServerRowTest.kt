package dev.typenil.vpnclient.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.typenil.vpnclient.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerRowTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tcp = LatencyReading(5, R.string.servers_latency_method_tcp)
    private val ms5 get() = context.getString(R.string.servers_latency_ms, 5)
    private val selectedWord get() = context.getString(R.string.common_selected)

    @Test fun selectedRowIsOneAccessibleRadioWithTheFullDescription() {
        var clicks = 0
        compose.setContent {
            ServerRow(title = "Demo Frankfurt", selected = true, onClick = { clicks++ }, protocol = "SOCKS", latency = tcp)
        }
        val row = compose.onNodeWithContentDescription("Demo Frankfurt, SOCKS, $ms5, $selectedWord")
        row.assertIsSelected().assertHeightIsAtLeast(48.dp)
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        row.performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun unselectedRowIsNotSelectedAndDoesNotSayIt() {
        compose.setContent { ServerRow(title = "Demo Berlin", selected = false, onClick = {}, protocol = "VLESS") }
        compose.onNodeWithContentDescription("Demo Berlin, VLESS").assertIsNotSelected()
    }

    @Test fun unencryptedMarkerIsTextAndSpoken() {
        val label = context.getString(R.string.servers_unencrypted)
        compose.setContent {
            ServerRow(title = "Local", selected = false, onClick = {}, protocol = "SOCKS", latency = tcp, warning = label)
        }
        compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Local, SOCKS, $ms5, $label").assertExists()
    }

    // Three lines, not two (WP-12D-5): mixed-script provider names differ in their tail, and two lines cut it off.
    @Test fun longNameGetsThreeLinesAndTheRowKeepsItsHeight() {
        val title = "Very long server name ".repeat(20)
        compose.setContent {
            ServerRow(title = title, selected = false, onClick = {}, protocol = "VLESS", latency = tcp)
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(3, layouts.single().lineCount)
        assertTrue("third line is the last visible one", layouts.single().isLineEllipsized(2))
        val row = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        val h = row.getUnclippedBoundsInRoot().height
        assertTrue("row grew to $h", h < 140.dp)
    }

    @Test fun outdatedChipKeepsTheValueAndTheRowSpeaksIt() {
        compose.setContent {
            ServerRow(
                title = "Demo", selected = false, onClick = {}, protocol = "SOCKS",
                latency = LatencyReading(5, R.string.servers_latency_method_tcp, stale = true),
                spoken = "outdated, 2 days ago",
            )
        }
        compose.onNodeWithText(ms5, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("outdated, 2 days ago", substring = true).assertIsDisplayed()
    }

    @Test fun largestFontKeepsChipVisibleAndRowClickable() {
        var clicks = 0
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                ServerRow(title = "Demo Frankfurt", selected = true, onClick = { clicks++ }, protocol = "SOCKS", latency = tcp, actions = actionBox)
            }
        }
        compose.onNodeWithText(ms5, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    private val actionBox: @Composable () -> Unit = { Spacer(Modifier.size(96.dp, 48.dp)) }

    private val longTitle = "Long server name ".repeat(12)

    private fun titleWidthAfterSelecting(slots: Boolean) {
        val selected = mutableStateOf(false)
        compose.setContent {
            ServerRow(title = longTitle, selected = selected.value, onClick = {}, protocol = "SOCKS", latency = tcp, actions = if (slots) actionBox else null)
        }
        val title = compose.onNodeWithText(longTitle, useUnmergedTree = true)
        val before = title.getUnclippedBoundsInRoot()
        compose.runOnIdle { selected.value = true }
        compose.waitForIdle()
        assertEquals("slots=$slots", before, title.getUnclippedBoundsInRoot())
    }

    @Test fun selectingNeverMovesTheTextWithoutActions() = titleWidthAfterSelecting(slots = false)

    @Test fun selectingNeverMovesTheTextWithActions() = titleWidthAfterSelecting(slots = true)

    @Test fun actionsReserveRoomForStarAndMenu() {
        compose.setContent {
            Column {
                ServerRow(title = "A $longTitle", selected = false, onClick = {})
                ServerRow(title = "B $longTitle", selected = false, onClick = {}, actions = actionBox)
            }
        }
        val plain = compose.onNodeWithText("A $longTitle", useUnmergedTree = true).getUnclippedBoundsInRoot().width
        val reserved = compose.onNodeWithText("B $longTitle", useUnmergedTree = true).getUnclippedBoundsInRoot().width
        assertTrue("reserved=$reserved plain=$plain", reserved < plain)
    }

    @Test fun selectedRowDrawsNoFrameOfItsOwn() {
        compose.setContent { ServerRow(title = "Demo Frankfurt", selected = true, onClick = {}, protocol = "SOCKS", latency = tcp) }
        val image = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).captureToImage()
        val coral = LightAfterglow.coral.toArgb()
        val edge = listOf(image.width / 2 to 0, image.width / 2 to image.height - 1, 0 to image.height / 2)
        for ((x, y) in edge) {
            val pixel = image.toPixelMap()[x, y].toArgb()
            assertTrue("coral frame pixel at $x,$y", pixel != coral)
        }
    }
}
