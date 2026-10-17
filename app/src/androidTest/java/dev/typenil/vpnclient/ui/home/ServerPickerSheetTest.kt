package dev.typenil.vpnclient.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.ui.theme.LatencyReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerPickerSheetTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val auto = ServerOption(
        id = NodeSelection.AUTO_ID,
        titleRes = R.string.common_auto_fastest,
        subtitleRes = R.string.home_auto_subtitle,
        searchText = NodeSelection.AUTO_ID,
    )

    private fun node(id: String, group: Long, name: String, latency: LatencyReading? = null, encrypted: Boolean = true) =
        ServerOption(
            id = id, title = id, protocol = "VLESS", groupId = group, groupName = name,
            latency = latency, encrypted = encrypted,
        )

    private fun show(options: List<ServerOption>, selected: String? = null, picks: MutableList<String> = mutableListOf()) {
        compose.setContent {
            ServerPickerSheet(options, selected, onPick = { picks += it }, onOpenServers = {}, onDismiss = {})
        }
    }

    @Test fun severalProvidersGetOneCardEachWithCountedHeaders() {
        show(listOf(auto, node("a1", 1, "Alpha"), node("a2", 1, "Alpha"), node("b1", 2, "Beta")))
        compose.onNodeWithText(context.getString(R.string.picker_group_header, "Alpha", 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.picker_group_header, "Beta", 1)).assertIsDisplayed()
    }

    @Test fun singleProviderShowsNoHeader() {
        show(listOf(auto, node("a1", 1, "Alpha"), node("a2", 1, "Alpha")))
        compose.onNodeWithText(context.getString(R.string.picker_group_header, "Alpha", 2)).assertDoesNotExist()
        compose.onNodeWithText("a1", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun pickingARowReportsItsIdAndSelectionIsMarked() {
        val picks = mutableListOf<String>()
        show(listOf(auto, node("a1", 1, "Alpha"), node("b1", 2, "Beta")), selected = NodeSelection.AUTO_ID, picks = picks)
        compose.onNodeWithContentDescription(
            "${context.getString(R.string.common_auto_fastest)}, ${context.getString(R.string.home_auto_subtitle)}, ${context.getString(R.string.common_selected)}",
        ).assertIsSelected()
        compose.onNodeWithContentDescription("b1, VLESS").performClick()
        compose.runOnIdle { assertEquals(listOf("b1"), picks) }
    }

    @Test fun latencyChipShowsTheValueOrADash() {
        show(listOf(auto, node("a1", 1, "Alpha", LatencyReading(5, R.string.servers_latency_method_tcp)), node("a2", 1, "Alpha")))
        compose.onNodeWithText(context.getString(R.string.servers_latency_ms, 5), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.common_none), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun unencryptedNodeIsMarkedWithText() {
        show(listOf(auto, node("a1", 1, "Alpha", encrypted = false)))
        compose.onNodeWithText(context.getString(R.string.home_picker_unencrypted_short), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun lastRowOfALongListIsReachableAtLargestFont() {
        val many = (1..36).map { node("n$it", 1 + it / 19L, "P${1 + it / 19L}") }
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                ServerPickerSheet(listOf(auto) + many, null, onPick = {}, onOpenServers = {}, onDismiss = {})
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("n36, VLESS"))
        val last = compose.onNodeWithContentDescription("n36, VLESS")
        last.assertIsDisplayed()
        val bottom = last.getUnclippedBoundsInRoot().bottom
        val screen = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.let { it.heightPixels / it.density }
        assertTrue("last row bottom=$bottom screen=$screen", bottom.value <= screen)
    }
}
