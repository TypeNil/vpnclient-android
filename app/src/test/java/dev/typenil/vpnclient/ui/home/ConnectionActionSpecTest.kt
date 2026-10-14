package dev.typenil.vpnclient.ui.home

import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.ui.theme.StateTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionActionSpecTest {
    private fun spec(state: VisualState) = connectionActionSpec(state, noServers = false, hasNodes = true)

    @Test fun `idle offers start with the off caption and a power icon`() {
        val s = spec(VisualState.Offline)
        assertEquals(R.string.afterglow_start, s.labelRes)
        assertEquals(R.string.afterglow_offline, s.captionRes)
        assertEquals(ActionIcon.Power, s.icon)
        assertEquals(StateTone.Neutral, s.tone)
        assertTrue(s.enabled)
        assertEquals(ActionTap.Start, s.tap)
    }

    @Test fun `connected offers disconnect with the active caption`() {
        val s = spec(VisualState.Connected)
        assertEquals(R.string.action_disconnect, s.labelRes)
        assertEquals(R.string.afterglow_online, s.captionRes)
        assertEquals(ActionIcon.Power, s.icon)
        assertEquals(StateTone.Positive, s.tone)
        assertEquals(ActionTap.Stop, s.tap)
    }

    @Test fun `error offers retry as a start, never disconnect`() {
        val s = spec(VisualState.Error)
        assertEquals(R.string.afterglow_retry, s.labelRes)
        assertEquals(R.string.afterglow_error_caption, s.captionRes)
        assertEquals(ActionIcon.Refresh, s.icon)
        assertEquals(StateTone.Error, s.tone)
        assertTrue(s.enabled)
        assertEquals(ActionTap.Start, s.tap)
    }

    @Test fun `preparing is the only cancellable phase`() {
        val s = spec(VisualState.Preparing)
        assertEquals(R.string.common_cancel, s.labelRes)
        assertEquals(ActionIcon.Spinner, s.icon)
        assertTrue(s.enabled)
        assertEquals(ActionTap.Stop, s.tap)
        val others = listOf(VisualState.Connecting, VisualState.Permission, VisualState.Stopping)
        others.forEach {
            assertFalse("$it must be inert", spec(it).enabled)
            assertEquals(ActionIcon.Spinner, spec(it).icon)
        }
    }

    @Test fun `transitional states name their action and invent no stage caption`() {
        assertEquals(R.string.home_status_connecting, spec(VisualState.Connecting).labelRes)
        assertEquals(R.string.home_status_permission, spec(VisualState.Permission).labelRes)
        assertEquals(R.string.home_status_disconnecting, spec(VisualState.Stopping).labelRes)
        listOf(VisualState.Preparing, VisualState.Connecting, VisualState.Permission, VisualState.Stopping, VisualState.Reconnecting)
            .forEach { assertNull("$it has no modelled stage", spec(it).captionRes) }
    }

    @Test fun `reconnecting keeps disconnect available without claiming the VPN is active`() {
        val s = spec(VisualState.Reconnecting)
        assertEquals(R.string.action_disconnect, s.labelRes)
        assertNull(s.captionRes)
        assertEquals(ActionTap.Stop, s.tap)
        assertEquals(StateTone.Progress, s.tone)
    }

    @Test fun `tone follows the visual state for every state`() {
        VisualState.entries.forEach { assertEquals(it.tone(), spec(it).tone) }
    }

    @Test fun `only stable states carry a caption`() {
        val withCaption = VisualState.entries.filter { spec(it).captionRes != null }.toSet()
        assertEquals(setOf(VisualState.Offline, VisualState.Connected, VisualState.Error), withCaption)
    }

    @Test fun `no servers turns the bar into a navigation shortcut`() {
        val add = connectionActionSpec(VisualState.Offline, noServers = true, hasNodes = false)
        assertEquals(R.string.common_add_server_cta, add.labelRes)
        val pick = connectionActionSpec(VisualState.Error, noServers = true, hasNodes = true)
        assertEquals(R.string.nav_servers, pick.labelRes)
        listOf(add, pick).forEach {
            assertNull(it.captionRes)
            assertEquals(ActionIcon.Next, it.icon)
            assertTrue(it.enabled)
            assertEquals(ActionTap.Start, it.tap)
        }
    }

    @Test fun `error then retry then connecting then connected moves through the right actions`() {
        val path = listOf(VisualState.Error, VisualState.Preparing, VisualState.Connecting, VisualState.Connected, VisualState.Stopping, VisualState.Offline)
        val labels = path.map { spec(it).labelRes }
        assertEquals(
            listOf(R.string.afterglow_retry, R.string.common_cancel, R.string.home_status_connecting,
                R.string.action_disconnect, R.string.home_status_disconnecting, R.string.afterglow_start),
            labels,
        )
    }
}
