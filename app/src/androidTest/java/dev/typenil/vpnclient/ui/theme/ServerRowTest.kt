package dev.typenil.vpnclient.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
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
            ServerRow(title = "Local", selected = false, onClick = {}, protocol = "SOCKS", latency = tcp, unencryptedLabel = label)
        }
        compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Local, SOCKS, $ms5, $label").assertExists()
    }

    @Test fun longNameStaysWithinTwoLinesAndTheRowKeepsItsHeight() {
        compose.setContent {
            ServerRow(title = "Very long server name ".repeat(20), selected = false, onClick = {}, protocol = "VLESS", latency = tcp)
        }
        val row = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        val h = row.getUnclippedBoundsInRoot().height
        assertTrue("row grew to $h", h < 140.dp)
    }

    @Test fun largestFontKeepsChipVisibleAndRowClickable() {
        var clicks = 0
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                ServerRow(title = "Demo Frankfurt", selected = true, onClick = { clicks++ }, protocol = "SOCKS", latency = tcp, actionSlots = true)
            }
        }
        compose.onNodeWithText(ms5, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    private val longTitle = "Long server name ".repeat(12)

    private fun titleWidthAfterSelecting(slots: Boolean) {
        val selected = mutableStateOf(false)
        compose.setContent {
            ServerRow(title = longTitle, selected = selected.value, onClick = {}, protocol = "SOCKS", latency = tcp, actionSlots = slots)
        }
        val title = compose.onNodeWithText(longTitle, useUnmergedTree = true)
        val before = title.getUnclippedBoundsInRoot()
        compose.runOnIdle { selected.value = true }
        compose.waitForIdle()
        assertEquals("slots=$slots", before, title.getUnclippedBoundsInRoot())
    }

    @Test fun selectingNeverMovesTheTextWithoutActionSlots() = titleWidthAfterSelecting(slots = false)

    @Test fun selectingNeverMovesTheTextWithActionSlots() = titleWidthAfterSelecting(slots = true)

    @Test fun actionSlotsReserveRoomForStarAndMenu() {
        compose.setContent {
            Column {
                ServerRow(title = "A $longTitle", selected = false, onClick = {}, actionSlots = false)
                ServerRow(title = "B $longTitle", selected = false, onClick = {}, actionSlots = true)
            }
        }
        val plain = compose.onNodeWithText("A $longTitle", useUnmergedTree = true).getUnclippedBoundsInRoot().width
        val reserved = compose.onNodeWithText("B $longTitle", useUnmergedTree = true).getUnclippedBoundsInRoot().width
        assertTrue("reserved=$reserved plain=$plain", reserved < plain)
    }
}
