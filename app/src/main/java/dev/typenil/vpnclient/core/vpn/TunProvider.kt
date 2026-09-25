package dev.typenil.vpnclient.core.vpn

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The consent/platform-gated Android calls [ClientVpnService] makes —
 * `VpnService.prepare`, `VpnService.Builder.establish`, and the foreground
 * promotion — behind an interface so instrumented tests can run the service
 * without a real VPN permission grant or an upstream server.
 *
 * Everything else in `openTun` (route computation, per-app plan resolution,
 * `VpnService.Builder` configuration) is real logic and stays in the
 * service — this seam only isolates the parts that require platform VPN
 * consent.
 */
interface TunProvider {
    /**
     * `VpnService.prepare(context)` — returns the consent intent, or `null`
     * when VPN permission is already granted.
     */
    fun prepare(context: Context): Intent?

    /**
     * `VpnService.Builder.establish()` — returns the TUN interface's
     * [ParcelFileDescriptor], or `null` when not prepared/revoked.
     */
    fun establish(builder: VpnService.Builder): ParcelFileDescriptor?

    /**
     * Promote the service to foreground. The real call passes
     * `FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED`, which the platform only
     * accepts once an active VPN interface exists (`activate_vpn` appop) —
     * something a fake TUN fd can't produce, so tests substitute a no-op
     * promotion (the instrumentation process doesn't need FGS elevation).
     */
    fun startForeground(service: Service, id: Int, notification: Notification)
}

/** Production implementation backed by the real `VpnService` framework calls. */
@Singleton
class VpnTunProvider
    @Inject
    constructor() : TunProvider {
        override fun prepare(context: Context): Intent? = VpnService.prepare(context)

        override fun establish(builder: VpnService.Builder): ParcelFileDescriptor? = builder.establish()

        override fun startForeground(
            service: Service,
            id: Int,
            notification: Notification,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                service.startForeground(
                    id,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                )
            } else {
                service.startForeground(id, notification)
            }
        }
    }
