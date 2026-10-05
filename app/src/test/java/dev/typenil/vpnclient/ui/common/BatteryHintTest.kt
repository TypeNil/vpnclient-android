package dev.typenil.vpnclient.ui.common

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.core.vpn.VpnError
import dev.typenil.vpnclient.data.settings.SettingsRepository
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class BatteryHintTest {
    private val node = NodeSummary("synthetic", "Test", ProtocolType.VLESS, "192.0.2.1:443")
    private val connected = VpnConnectionState.Connected(node, Instant.EPOCH, null)

    @Test fun onlyActualConnectedQualifies() {
        val states = listOf(VpnConnectionState.Idle, VpnConnectionState.Preparing(null),
            VpnConnectionState.PermissionRequired, VpnConnectionState.Connecting(node),
            VpnConnectionState.Reconnecting(node, VpnConnectionState.Reconnecting.Reason.CoreFailure, 1),
            VpnConnectionState.Stopping, VpnConnectionState.Error(VpnError.PermissionDenied, node))
        states.forEach { assertFalse(it.toString(), showBatteryHint(it, true, false)) }
        assertTrue(showBatteryHint(connected, true, false))
    }

    @Test fun exemptionUnknownOrDismissalSuppressesHint() {
        for (enabled in listOf(true, false, null)) for (dismissed in listOf(true, false, null)) {
            val reader = BatteryOptimizationStatus { enabled }
            assertEquals(enabled == true && dismissed == false,
                showBatteryHint(connected, reader.enabled(), dismissed))
        }
    }

    @Test fun dismissalSurvivesPreferenceReloadAndReconnect() {
        val key = booleanPreferencesKey("battery_hint_dismissed")
        val prefs = mutablePreferencesOf()
        assertTrue(showBatteryHint(connected, true, prefs[key] ?: false))
        SettingsRepository.persistBatteryHintDismissal(prefs)
        val reloaded = prefs.toPreferences().toMutablePreferences()
        assertEquals(true, reloaded[key])
        assertFalse(showBatteryHint(connected.copy(since = Instant.ofEpochSecond(1)), true, reloaded[key]))
        SettingsRepository.persistBatteryHintDismissal(reloaded)
        assertEquals(true, reloaded[key])
    }
}
