package dev.typenil.vpnclient.ui.servers

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.data.db.NodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServersContentTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun node(id: String, sub: Long = 1L, enabled: Boolean = true, hidden: Boolean = false, favorite: Boolean = false) =
        ServerNode(
            entity = NodeEntity(
                id = id, subscriptionId = sub, name = id, protocol = "VLESS", server = "127.0.0.1",
                port = 1, outboundJson = "{}", rawUri = null, position = 0,
            ),
            favorite = favorite, enabled = enabled, hidden = hidden, encrypted = true,
        )

    private fun state(vararg groups: ServerGroup, selected: String? = null, auto: Boolean = false) =
        ServersUiState(
            groups = groups.toList(),
            selectedNodeId = selected,
            autoSelected = auto,
            delays = mapOf("a1" to 42),
            showHeaders = true,
            noSubscriptions = false,
        )

    private val oneGroup get() = ServerGroup(1L, "Alpha", listOf(node("a1"), node("a2")))

    private class Recorder {
        val picked = mutableListOf<String>()
        val favorites = mutableListOf<String>()
        var closed = 0
        var autos = 0
        var cleared = 0
        var refreshed = 0
        var added = 0
        val actions = ServersActions(
            selectNode = { picked += it },
            toggleFavorite = { favorites += it },
            selectAuto = { autos++ },
            clearFilters = { cleared++ },
            refreshSubscriptions = { refreshed++ },
        )
    }

    private fun show(ui: ServersUiState, rec: Recorder = Recorder(), fontScale: Float? = null) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale ?: d.fontScale)) {
                ServersContent(ui, testing = false, progress = null, actions = rec.actions, onAddServer = { rec.added++ }, onSelected = { rec.closed++ })
            }
        }
    }

    @Test fun favoriteStarIsSolidAndNonFavoriteStarIsAnOutline() {
        show(state(ServerGroup(1L, "Alpha", listOf(node("a1"), node("a2", favorite = true)))))
        fun centreAndCorner(desc: String): Pair<Int, Int> {
            val img = compose.onNodeWithContentDescription(desc).captureToImage()
            return img.toPixelMap()[img.width / 2, img.height / 2].toArgb() to img.toPixelMap()[2, 2].toArgb()
        }
        val (offCentre, offCorner) = centreAndCorner(str(R.string.servers_favorite_add, "a1"))
        val (onCentre, onCorner) = centreAndCorner(str(R.string.servers_favorite_remove, "a2"))
        assertEquals("outlined star is hollow", offCorner, offCentre)
        assertNotEquals("filled star is solid", onCorner, onCentre)
    }

    @Test fun pickingARowSelectsItAndClosesTheSheet() {
        val rec = Recorder()
        show(state(oneGroup), rec)
        compose.onNodeWithContentDescription("a2, VLESS", substring = true).performClick()
        compose.runOnIdle {
            assertEquals(listOf("a2"), rec.picked)
            assertEquals(1, rec.closed)
        }
    }

    @Test fun pickingAutoPersistsAutoAndClosesTheSheet() {
        val rec = Recorder()
        show(state(oneGroup), rec)
        compose.onNodeWithContentDescription(str(R.string.common_auto_fastest), substring = true).performClick()
        compose.runOnIdle {
            assertEquals(1, rec.autos)
            assertEquals(1, rec.closed)
        }
    }

    @Test fun starAndMenuNeitherSelectNorCloseTheSheet() {
        val rec = Recorder()
        show(state(oneGroup), rec)
        compose.onNodeWithContentDescription(str(R.string.servers_favorite_add, "a1")).performClick()
        compose.onNodeWithContentDescription(str(R.string.servers_node_menu, "a1")).performClick()
        compose.onNodeWithText(str(R.string.servers_rename)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("a1"), rec.favorites)
            assertEquals(emptyList<String>(), rec.picked)
            assertEquals(0, rec.closed)
        }
    }

    @Test fun sortFilterAndLatencyTestNeverCloseTheSheet() {
        var tests = 0
        val rec = Recorder()
        compose.setContent {
            ServersContent(
                state(oneGroup), testing = false, progress = null,
                actions = ServersActions(testLatency = { tests++ }), onSelected = { rec.closed++ },
            )
        }
        compose.onNodeWithText(str(R.string.servers_test_latency)).performClick()
        compose.onNodeWithText(str(R.string.servers_filters)).performClick()
        compose.runOnIdle {
            assertEquals(1, tests)
            assertEquals(0, rec.closed)
        }
    }

    @Test fun disabledAndHiddenRowsAreRenderedButIgnoreTheTap() {
        val rec = Recorder()
        show(state(ServerGroup(1L, "Alpha", listOf(node("off", enabled = false), node("gone", hidden = true)))), rec)
        compose.onNodeWithText(str(R.string.servers_disabled_label), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.servers_hidden_label), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("off, VLESS", substring = true).performClick()
        compose.onNodeWithContentDescription("gone, VLESS", substring = true).performClick()
        compose.runOnIdle {
            assertEquals(emptyList<String>(), rec.picked)
            assertEquals(0, rec.closed)
        }
    }

    @Test fun selectingARowNeverMovesItsText() {
        val ui = mutableStateOf(state(oneGroup))
        compose.setContent { ServersContent(ui.value, testing = false, progress = null, actions = ServersActions()) }
        val title = compose.onNodeWithText("a2", useUnmergedTree = true)
        val before = title.getUnclippedBoundsInRoot()
        compose.runOnIdle { ui.value = state(oneGroup, selected = "a2") }
        compose.waitForIdle()
        assertEquals(before, title.getUnclippedBoundsInRoot())
        compose.onNodeWithContentDescription("a2, VLESS", substring = true).assertIsSelected()
    }

    @Test fun rowSpeaksNameProtocolLatencyAndMethod() {
        show(state(oneGroup, selected = "a1"))
        val desc = "a1, VLESS, ${str(R.string.servers_latency_ms, 42)}, ${str(R.string.servers_latency_method_tcp)}, ${str(R.string.common_selected)}"
        compose.onNodeWithContentDescription("a1, VLESS", substring = true).assertContentDescriptionEquals(desc)
    }

    @Test fun oneProviderShowsNoHeader() {
        show(state(oneGroup))
        compose.onNodeWithText(str(R.string.picker_group_header, "Alpha", 2)).assertDoesNotExist()
    }

    @Test fun twoProvidersShowCountedHeaders() {
        show(state(oneGroup, ServerGroup(2L, "Beta", listOf(node("b1", 2L)))))
        compose.onNodeWithText(str(R.string.picker_group_header, "Alpha", 2)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.picker_group_header, "Beta", 1)).assertIsDisplayed()
    }

    @Test fun filtersEntryShowsTheCountOnlyWhenSomethingIsOn() {
        show(state(oneGroup).copy(subscriptionFilter = 1L, showHidden = true))
        compose.onNodeWithText("${str(R.string.servers_filters)} · 2").assertIsDisplayed()
    }

    @Test fun noFiltersMeansNoNumber() {
        show(state(oneGroup))
        compose.onNodeWithText(str(R.string.servers_filters)).assertIsDisplayed()
    }

    @Test fun noMatchShowsTheMessage() {
        show(ServersUiState(noSubscriptions = false, groups = emptyList()))
        compose.onNodeWithText(str(R.string.servers_no_match)).assertIsDisplayed()
    }

    @Test fun noMatchOffersToClearTheFilters() {
        val rec = Recorder()
        show(ServersUiState(noSubscriptions = false, groups = emptyList()), rec)
        compose.onNodeWithText(str(R.string.servers_clear_filters)).performClick()
        compose.runOnIdle { assertEquals(1, rec.cleared) }
    }

    @Test fun emptySubscriptionsOfferRefresh() {
        val rec = Recorder()
        show(ServersUiState(noSubscriptions = true, hasSubscriptions = true, groups = emptyList()), rec)
        compose.onNodeWithText(str(R.string.servers_no_servers_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.subs_refresh_now)).performClick()
        compose.runOnIdle { assertEquals(1, rec.refreshed) }
    }

    @Test fun noSubscriptionsOfferToAddOne() {
        val rec = Recorder()
        show(ServersUiState(noSubscriptions = true, hasSubscriptions = false, groups = emptyList()), rec)
        compose.onNodeWithText(str(R.string.subs_add_cta)).performClick()
        compose.runOnIdle { assertEquals(1, rec.added) }
    }

    @Test fun noMatchMessageDoesNotTouchTheAutoCard() {
        show(ServersUiState(noSubscriptions = false, groups = emptyList()))
        val message = compose.onNodeWithText(str(R.string.servers_no_match)).getUnclippedBoundsInRoot()
        val auto = compose.onNodeWithContentDescription(str(R.string.common_auto_fastest), substring = true).getUnclippedBoundsInRoot()
        assertTrue("message bottom=${message.bottom} auto top=${auto.top}", auto.top - message.bottom >= 8.dp)
    }

    @Test fun autoRowCarriesItsOwnSelection() {
        show(state(oneGroup, selected = NodeSelection.AUTO_ID, auto = true))
        compose.onNodeWithContentDescription(str(R.string.common_auto_fastest), substring = true).assertIsSelected()
    }

    @Test fun largestFontKeepsEveryControlReachableAndTargetsBigEnough() {
        show(state(oneGroup), fontScale = 2f)
        compose.onNodeWithText(str(R.string.servers_test_latency)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.servers_filters)).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.servers_favorite_add, "a1"))
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription(str(R.string.servers_node_menu, "a1"))
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    @Test fun starAndMenuNameTheirServerSoTalkBackCanTellRowsApart() {
        show(state(oneGroup))
        compose.onNodeWithContentDescription(str(R.string.servers_favorite_add, "a2")).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.servers_node_menu, "a2")).assertIsDisplayed()
    }

    @Test fun rowsAreLazyAndTheCardKeepsItsHeaderOnTheFirstRow() {
        show(state(ServerGroup(1L, "Alpha", (0 until 300).map { node("n%03d".format(it)) }), ServerGroup(2L, "Beta", listOf(node("b1")))))
        compose.onNodeWithText(str(R.string.picker_group_header, "Alpha", 300)).assertIsDisplayed()
        compose.onNodeWithContentDescription("n000, VLESS", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("n299, VLESS", substring = true).assertDoesNotExist()
    }
}
