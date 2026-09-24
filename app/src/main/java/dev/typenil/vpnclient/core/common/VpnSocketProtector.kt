package dev.typenil.vpnclient.core.common

import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide seam letting plain-`java.net.Socket` clients opt out of the
 * TUN. The app's own package is routed through the VPN in every per-app
 * mode, so sockets that must stay on the underlay (latency probes) call
 * [protect] before connecting. `ClientVpnService` installs the real
 * implementation (`VpnService.protect`) for its lifetime; with no VPN
 * running the default no-op is correct — the socket is already direct.
 */
@Singleton
class VpnSocketProtector @Inject constructor() {

    @Volatile
    private var protectFn: ((Socket) -> Boolean)? = null

    fun install(fn: (Socket) -> Boolean) {
        protectFn = fn
    }

    fun uninstall() {
        protectFn = null
    }

    /** True when the socket was marked to bypass the VPN — or when no VPN
     *  is running, where direct is already the only path. False means the
     *  protect call failed; the socket may ride the tunnel. */
    fun protect(socket: Socket): Boolean = protectFn?.invoke(socket) ?: true
}
