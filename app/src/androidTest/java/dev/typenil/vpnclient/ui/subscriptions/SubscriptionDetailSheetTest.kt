package dev.typenil.vpnclient.ui.subscriptions

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** Detail sheet of one subscription; needs the app locale to be en (the strings are looked up from resources, not hard-coded). */
@RunWith(AndroidJUnit4::class)
class SubscriptionDetailSheetTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun profile(
        userInfo: SubscriptionUserInfo? = SubscriptionUserInfo(1_000_000, 2_000_000, 10_000_000_000, 4_000_000_000L),
        announce: String? = null,
        supportUrl: String? = null,
        lastError: String? = null,
    ) = SubscriptionProfile(
        id = 1L, name = "Demo Provider", url = "https://sub.invalid/feed", createdAt = Instant.EPOCH,
        lastUpdatedAt = Instant.now().minusSeconds(7_200), lastAttemptAt = null, lastError = lastError, nodeCount = 6,
        enabled = true, userInfo = userInfo, supportUrl = supportUrl, updateIntervalMinutes = null, announce = announce,
        updateAlways = false, allowInsecureHttp = false, refreshPolicy = RefreshPolicy.InheritGlobal,
    )

    private class Calls {
        var refresh = 0
        var delete = 0
    }

    private fun show(p: SubscriptionProfile, nodeCount: Int = 6, refreshing: Boolean = false, calls: Calls = Calls()) {
        compose.setContent {
            SubscriptionDetailSheet(
                profile = p, nodeCount = nodeCount, manualNodes = emptyList(), refreshing = refreshing, globalAutoRefreshMinutes = 0,
                onDismiss = {}, onToggle = {}, onRefresh = { calls.refresh++ }, onRename = {}, onEditUrl = {},
                onPolicyChange = {}, onRemoveNode = {}, onDelete = { calls.delete++ },
            )
        }
    }

    @Test fun refreshNowIsTheOnePrimaryActionAndCallsRefresh() {
        val calls = Calls()
        show(profile(), calls = calls)
        compose.onNodeWithText(str(R.string.subs_refresh_now)).assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, calls.refresh) }
    }

    @Test fun refreshIsDisabledAndSaysSoWhileRefreshing() {
        show(profile(), refreshing = true)
        compose.onNodeWithText(str(R.string.subs_refreshing)).assertIsNotEnabled()
    }

    @Test fun deleteIsTheLastActionBelowRenameAndEditUrl() {
        show(profile())
        val rename = compose.onNodeWithText(str(R.string.subs_rename)).getUnclippedBoundsInRoot()
        val edit = compose.onNodeWithText(str(R.string.subs_edit_url)).getUnclippedBoundsInRoot()
        val delete = compose.onNodeWithText(str(R.string.common_delete)).getUnclippedBoundsInRoot()
        assertTrue("rename < edit < delete", rename.top < edit.top && edit.top < delete.top)
    }

    @Test fun deleteCallsBackAndEveryActionIsAtLeast48dpHigh() {
        val calls = Calls()
        show(profile(), calls = calls)
        for (id in listOf(R.string.subs_rename, R.string.subs_edit_url, R.string.common_delete)) {
            compose.onNodeWithText(str(id)).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithText(str(R.string.common_delete)).performClick()
        compose.runOnIdle { assertEquals(1, calls.delete) }
    }

    @Test fun supportLinkIsATarget48dpBig() {
        show(profile(announce = "Synthetic announcement", supportUrl = "https://support.invalid/help"))
        compose.onNodeWithText("Synthetic announcement").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.subs_support)).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    @Test fun usageShowsUsedOfTotalOrJustUsedForUnlimited() {
        show(profile())
        compose.onNodeWithText("Used", substring = true).assertExists()
        compose.onNodeWithText(" of ", substring = true).assertExists()
    }

    @Test fun unlimitedTrafficShowsOnlyWhatWasUsed() {
        show(profile(userInfo = SubscriptionUserInfo(1_000_000, 2_000_000, 0, null)))
        compose.onNodeWithText("Used", substring = true).assertExists()
        compose.onNodeWithText(" of ", substring = true).assertDoesNotExist()
    }

    @Test fun pastExpiryIsLabelledExpiredNotExpires() {
        show(profile(userInfo = SubscriptionUserInfo(0, 0, 0, 1_000_000_000L)))
        compose.onNodeWithText(str(R.string.subs_detail_expired)).assertExists()
        compose.onNodeWithText(str(R.string.subs_detail_expires)).assertDoesNotExist()
    }

    @Test fun failedRefreshNoticeSitsAboveTheFacts() {
        show(profile(lastError = "http 500"))
        val notice = compose.onNodeWithText(str(R.string.subs_refresh_failed_notice, "").trim().substringBefore(" —"), substring = true).getUnclippedBoundsInRoot()
        val servers = compose.onNodeWithText(str(R.string.subs_detail_nodes)).getUnclippedBoundsInRoot()
        assertTrue("notice above facts", notice.bottom <= servers.top)
    }

    @Test fun subscriptionWithoutServersExplainsAndOffersRefresh() {
        val calls = Calls()
        show(profile(), nodeCount = 0, calls = calls)
        compose.onNodeWithText(str(R.string.subs_detail_no_servers)).assertExists()
        compose.onAllNodesWithText(str(R.string.subs_refresh_now))[0].performClick()
        compose.runOnIdle { assertEquals(1, calls.refresh) }
    }

}
