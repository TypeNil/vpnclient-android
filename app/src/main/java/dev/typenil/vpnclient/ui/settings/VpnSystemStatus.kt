package dev.typenil.vpnclient.ui.settings

import android.content.Context
import android.net.VpnService
import android.os.Build

/**
 * Real system-VPN status for the app's own [android.net.VpnService].
 *
 * `VpnService.isAlwaysOn()` / `isLockdownEnabled()` are instance methods,
 * public since API 29 (`Build.VERSION_CODES.Q`) — they report this app's VPN
 * state on the service instance. Outside a running service they can't be
 * queried, so the flags are read through a lightweight [VpnService] subclass
 * instance (`onBind` is abstract; a null-returning stub satisfies it).
 * Below Q the methods don't exist — we report `null` so the UI can render
 * the row without fake "disabled" values on devices where the platform
 * can't tell us.
 */
data class VpnSystemStatus(
    /** True when this app's VPN is set as always-on for the current user;
     *  null when the API isn't available (< Q). */
    val alwaysOn: Boolean?,
    /** True when always-on + lockdown (kill switch) is set; null on < Q. */
    val lockdownEnabled: Boolean?,
) {
    val available: Boolean get() = alwaysOn != null
}

/** Minimal VpnService instance for reading the platform flags — never
 *  bound, never started. */
private class ProbeVpnService : VpnService() {
    // attachBaseContext is protected — the subclass exposes it so the
    // probe gets a Context for the platform binder lookup.
    fun attach(context: Context) = attachBaseContext(context)
    override fun onBind(intent: android.content.Intent?) = null
}

/**
 * Read the live system state. Safe to call on the main thread — both flags
 * are cached by the framework process-side (no binder round trip on Q+).
 */
fun readVpnSystemStatus(context: Context): VpnSystemStatus =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val service = ProbeVpnService()
        service.attach(context)
        VpnSystemStatus(
            alwaysOn = runCatching { service.isAlwaysOn() }.getOrNull(),
            lockdownEnabled = runCatching { service.isLockdownEnabled() }.getOrNull(),
        )
    } else {
        // Platform can't report it — honest "unknown", never a fake "off".
        VpnSystemStatus(alwaysOn = null, lockdownEnabled = null)
    }
