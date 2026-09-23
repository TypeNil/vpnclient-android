package dev.typenil.vpnclient.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val reconnectOnNetworkChange: Boolean = true,
    val ipv6Enabled: Boolean = true,
    val dozePowerSave: Boolean = false,
    /** <0 = manual only, 0 = provider-driven, >0 = fixed minutes. */
    val autoRefreshMinutes: Int = 0,
    val routeMode: RouteMode = RouteMode.ALL,
) {
    val autoRefreshEnabled: Boolean get() = autoRefreshMinutes >= 0
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    /** One-shot "reconnect to apply" prompt — emitted when a change is
     *  baked into the engine config and can't reach a running tunnel. */
    private val _promptReconnect = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val promptReconnect: SharedFlow<Unit> = _promptReconnect

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.reconnectOnNetworkChange,
        settings.ipv6Enabled,
        settings.dozePowerSave,
        settings.autoRefreshMinutes,
        settings.routeMode,
    ) { reconnect, ipv6, doze, refreshMinutes, routeMode ->
        SettingsUiState(
            reconnectOnNetworkChange = reconnect,
            ipv6Enabled = ipv6,
            dozePowerSave = doze,
            autoRefreshMinutes = refreshMinutes,
            routeMode = routeMode,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun setReconnectOnNetworkChange(enabled: Boolean) {
        viewModelScope.launch { settings.setReconnectOnNetworkChange(enabled) }
    }

    /** IPv6 is baked into the TUN addresses + DNS strategy at compile time —
     *  a live tunnel keeps its old setup until reconnect. */
    fun setIpv6Enabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setIpv6Enabled(enabled)
            promptReconnectIfTunnelActive()
        }
    }

    fun setDozePowerSave(enabled: Boolean) {
        viewModelScope.launch { settings.setDozePowerSave(enabled) }
    }

    /** Routing policy — baked into the config at compile time, so a running
     *  tunnel keeps its mode until the next connect. */
    fun setRouteMode(mode: RouteMode) {
        viewModelScope.launch {
            settings.setRouteMode(mode)
            promptReconnectIfTunnelActive()
        }
    }

    fun setAutoRefreshEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoRefreshEnabled(enabled) }
    }

    /** Persist a user override interval; 0/blank falls back to the provider hint. */
    fun setAutoRefreshMinutes(minutes: Int) {
        viewModelScope.launch { settings.setAutoRefreshMinutes(minutes.coerceAtLeast(0)) }
    }

    /** Restart the tunnel so compiled-in settings take effect. */
    fun reconnect() {
        viewModelScope.launch { connectionManager.reconnect() }
    }

    /** Only prompt while a session is alive — an idle tunnel picks the new
     *  value up on the next connect, and a dying one is already gone. */
    private fun promptReconnectIfTunnelActive() {
        when (connectionManager.state.value) {
            is VpnConnectionState.Connected,
            is VpnConnectionState.Connecting,
            is VpnConnectionState.Reconnecting,
            is VpnConnectionState.Preparing,
            is VpnConnectionState.PermissionRequired,
            -> _promptReconnect.tryEmit(Unit)
            else -> Unit
        }
    }
}
