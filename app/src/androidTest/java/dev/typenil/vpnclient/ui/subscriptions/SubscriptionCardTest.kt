package dev.typenil.vpnclient.ui.subscriptions

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.subscription.model.RefreshPolicy
import dev.typenil.vpnclient.core.subscription.model.SubscriptionProfile
import dev.typenil.vpnclient.core.subscription.model.SubscriptionUserInfo
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionCardTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val name = "Demo Provider"

    private fun profile(
        userInfo: SubscriptionUserInfo? = SubscriptionUserInfo(1_000_000, 2_000_000, 10_000_000_000, 4_000_000_000L),
        announce: String? = null,
        supportUrl: String? = null,
        lastError: String? = null,
        updated: Instant? = Instant.now().minusSeconds(7_200),
    ) = SubscriptionProfile(
        id = 1L, name = name, url = "https://sub.invalid/feed", createdAt = Instant.EPOCH, lastUpdatedAt = updated,
        lastAttemptAt = null, lastError = lastError, nodeCount = 6, enabled = true, userInfo = userInfo,
        supportUrl = supportUrl, updateIntervalMinutes = null, announce = announce, updateAlways = false,
        allowInsecureHttp = false, refreshPolicy = RefreshPolicy.InheritGlobal,
    )

    private class Calls {
        var details = 0
        var refresh = 0
        var delete = 0
    }

    private fun show(p: SubscriptionProfile, calls: Calls = Calls(), fontScale: Float? = null, refreshing: Boolean = false) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale ?: d.fontScale)) {
                SubscriptionCard(
                    profile = p, nodeCount = 6, refreshing = refreshing, manual = false,
                    onOpenDetails = { calls.details++ }, onRefresh = { calls.refresh++ },
                    onRename = {}, onEditUrl = {}, onDelete = { calls.delete++ },
                )
            }
        }
    }

    private val serversText get() = context.resources.getQuantityString(R.plurals.subs_card_meta, 6, 6, "x").substringBefore(" ·")
    private val moreCd get() = str(R.string.subs_menu_more, name)
    private val refreshCd get() = str(R.string.subs_refresh_named, name)

    @Test fun refreshAndMoreAreAtLeast48dpAndThereIsNoTrashBesideThem() {
        show(profile())
        compose.onNodeWithContentDescription(refreshCd).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription(moreCd).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription(str(R.string.common_delete)).assertDoesNotExist()
    }

    @Test fun menuListsRenameEditUrlDetailsAndDeleteLast() {
        val calls = Calls()
        show(profile(), calls)
        compose.onNodeWithContentDescription(moreCd).performClick()
        val tops = listOf(R.string.subs_rename, R.string.subs_edit_url, R.string.subs_menu_details, R.string.common_delete)
            .map { compose.onNodeWithText(str(it)).assertIsDisplayed().getUnclippedBoundsInRoot().top }
        assertEquals("menu order", tops.sorted(), tops)
        compose.onNodeWithText(str(R.string.common_delete)).performClick()
        compose.runOnIdle { assertEquals(1, calls.delete) }
    }

    @Test fun detailsItemOpensTheDetailSheet() {
        val calls = Calls()
        show(profile(), calls)
        compose.onNodeWithContentDescription(moreCd).performClick()
        compose.onNodeWithText(str(R.string.subs_menu_details)).performClick()
        compose.runOnIdle { assertEquals(1, calls.details) }
    }

    @Test fun metaIsTwoColumnsAtNormalFont() {
        val usage = str(R.string.subs_usage_of, "", "").substringBefore(" ")
        show(profile(), fontScale = 1f)
        val servers = compose.onNodeWithText(serversText, substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val traffic = compose.onNodeWithText(usage, substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("same row at 1.0", kotlin.math.abs(servers.top.value - traffic.top.value) < 1f)
        assertTrue("side by side at 1.0", traffic.left > servers.left)
    }

    @Test fun metaStacksAtFontScale2() {
        val usage = str(R.string.subs_usage_of, "", "").substringBefore(" ")
        show(profile(), fontScale = 2f)
        val servers = compose.onNodeWithText(serversText, substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val traffic = compose.onNodeWithText(usage, substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("traffic below servers at 2.0", traffic.top > servers.bottom - 1.dp)
        assertEquals(servers.left, traffic.left)
    }

    @Test fun nameAndHostAreShownAndHostNeverTheFullUrl() {
        show(profile())
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText("sub.invalid").assertIsDisplayed()
        compose.onNodeWithText("https://sub.invalid/feed", substring = true).assertDoesNotExist()
    }

    @Test fun failedRefreshShowsNoticeWithAgeAndReason() {
        show(profile(lastError = "http 500"))
        compose.onNodeWithText(str(R.string.subs_refresh_failed_notice, "").trim().substringBefore(" —"), substring = true).assertIsDisplayed()
    }

    @Test fun failedRefreshWithoutPriorDataShowsOnlyTheReason() {
        show(profile(lastError = "http 500", updated = null))
        compose.onNodeWithText(str(R.string.subs_refresh_failed_notice, "").trim().substringBefore(" —"), substring = true).assertDoesNotExist()
    }

    @Test fun longAnnouncementIsClampedAndShowMoreOpensDetails() {
        val calls = Calls()
        show(profile(announce = "Synthetic announcement. ".repeat(40)), calls)
        compose.onNodeWithText(str(R.string.subs_announce_more)).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, calls.details) }
    }

    @Test fun shortAnnouncementHasNoShowMore() {
        show(profile(announce = "Short"))
        compose.onNodeWithText(str(R.string.subs_announce_more)).assertDoesNotExist()
    }

    @Test fun supportButtonIs48dpAndSpeaksTheHost() {
        show(profile(supportUrl = "https://support.invalid/help"))
        compose.onNodeWithContentDescription(str(R.string.subs_support_cd, "support.invalid")).assertHeightIsAtLeast(48.dp)
    }
}
