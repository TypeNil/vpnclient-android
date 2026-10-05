package dev.typenil.vpnclient.ui.common

import dev.typenil.vpnclient.core.vpn.VpnConnectionState

/** Null means unknown; this query cannot diagnose a particular disconnect. */
fun interface BatteryOptimizationStatus {
    fun enabled(): Boolean?
}

internal fun showBatteryHint(state: VpnConnectionState, enabled: Boolean?, dismissed: Boolean?): Boolean =
    state is VpnConnectionState.Connected && enabled == true && dismissed == false
