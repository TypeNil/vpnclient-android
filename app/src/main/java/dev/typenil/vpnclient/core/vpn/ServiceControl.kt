package dev.typenil.vpnclient.core.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android-side service/permission operations used by [ConnectionManager].
 * Kept behind an interface so the state machine stays JVM-testable without
 * Robolectric — the manager never calls `VpnService`/`Context` directly.
 */
interface ServiceControl {
    /** `VpnService.prepare()` — consent intent, or null if already granted. */
    fun prepareVpn(): Intent?

    /** `startForegroundService` with the connect action. */
    fun startConnectService()

    /** Plain `startService` with the disconnect action (teardown request). */
    fun startDisconnectService()
}

@Singleton
class AndroidServiceControl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ServiceControl {

    override fun prepareVpn(): Intent? = VpnService.prepare(context)

    override fun startConnectService() {
        ContextCompat.startForegroundService(context, ClientVpnService.connectIntent(context))
    }

    override fun startDisconnectService() {
        context.startService(ClientVpnService.disconnectIntent(context))
    }
}
