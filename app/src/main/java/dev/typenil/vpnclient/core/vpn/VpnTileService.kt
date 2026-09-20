package dev.typenil.vpnclient.core.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import dev.typenil.vpnclient.MainActivity
import dev.typenil.vpnclient.R
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: tap toggles the tunnel.
 *
 * Connect goes through [MainActivity] — the consent-dialog flow lives in the
 * UI layer (`prepareIntent` → activity-result launcher), so the tile only
 * kicks an intent that delegates there. Disconnect needs no UI.
 */
@AndroidEntryPoint
class VpnTileService : TileService() {

    @Inject
    lateinit var connectionManager: ConnectionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listenJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listenJob = scope.launch {
            connectionManager.state.collect { updateTile(it) }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        when (connectionManager.state.value) {
            is VpnConnectionState.Connected,
            is VpnConnectionState.Connecting,
            is VpnConnectionState.Reconnecting,
            is VpnConnectionState.Stopping,
            -> connectionManager.disconnect()
            else -> openMainForConnect()
        }
    }

    // PendingIntent overload exists only on API 34+; the Intent overload is
    // the only path below that.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openMainForConnect() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_CONNECT, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(state: VpnConnectionState) {
        val tile = qsTile ?: return
        // Transitional states map to INACTIVE, not UNAVAILABLE — an
        // unavailable tile swallows clicks, so the user couldn't cancel a
        // connect in flight (or re-tap while consent is outstanding).
        tile.state = when (state) {
            is VpnConnectionState.Connected,
            is VpnConnectionState.Reconnecting,
            -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        // Some SystemUI builds render a blank tile when updates carry no icon.
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_vpn)
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (state) {
                is VpnConnectionState.Connected -> state.node.name
                is VpnConnectionState.Reconnecting -> "Reconnecting…"
                is VpnConnectionState.Connecting -> "Connecting…"
                else -> null
            }
        }
        tile.updateTile()
    }
}
