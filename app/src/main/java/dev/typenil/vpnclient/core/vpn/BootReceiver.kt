package dev.typenil.vpnclient.core.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.typenil.vpnclient.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restores the tunnel after boot / app update when the user left it running.
 *
 * Guards: `desiredVpnRunning` must still be set, and `VpnService.prepare`
 * must report consent is not needed (a revoked permission means no silent
 * restart — the user has to grant it again in the UI).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pending = goAsync()
        scope.launch {
            try {
                val wanted = settings.desiredVpnRunning.first()
                val consented = VpnService.prepare(context) == null
                if (wanted && consented) {
                    ContextCompat.startForegroundService(
                        context, ClientVpnService.connectIntent(context),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
