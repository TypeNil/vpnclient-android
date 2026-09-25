package dev.typenil.vpnclient.ui.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoutingUiState(
    /** The persisted pick — takes effect on the next connect/reconnect. */
    val routeMode: RouteMode = RouteMode.ALL,
    /** Saved (not necessarily applied) LAN bypass flag. */
    val bypassLan: Boolean = false,
    /** The routing plan the live engine actually runs — null when idle. */
    val appliedRouteMode: RouteMode? = null,
    val appliedBypassLan: Boolean? = null,
    /** A compiled-in setting changed while a session is alive. */
    val reconnectRecommended: Boolean = false,
    val sessionActive: Boolean = false,
)

@HiltViewModel
class RoutingViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val connectionManager: ConnectionManager,
    ) : ViewModel() {
        private val reconnectRecommended = MutableStateFlow(false)

        val uiState: StateFlow<RoutingUiState> =
            combine(
                settings.routeMode,
                settings.bypassLan,
                connectionManager.appliedSessionConfig,
                connectionManager.state,
                reconnectRecommended,
            ) { routeMode, bypassLan, applied, state, recommended ->
                RoutingUiState(
                    routeMode = routeMode,
                    bypassLan = bypassLan,
                    appliedRouteMode = applied?.routeMode,
                    appliedBypassLan = applied?.bypassLan,
                    reconnectRecommended = recommended,
                    sessionActive = state.hasLiveConfig(),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = RoutingUiState(),
            )

        init {
            // A dead session picks the new value up on the next connect —
            // the recommendation is only meaningful while a tunnel is alive.
            viewModelScope.launch {
                connectionManager.state.collect { state ->
                    if (!state.hasLiveConfig()) reconnectRecommended.value = false
                }
            }
        }

        /** Compiled into the engine config — a live tunnel keeps its mode. */
        fun setRouteMode(mode: RouteMode) {
            viewModelScope.launch {
                val changed = settings.routeMode.first() != mode
                settings.setRouteMode(mode)
                if (changed) recommendReconnectIfSessionActive()
            }
        }

        /** Baked into the tun inbound's route table at compile/openTun time. */
        fun setBypassLan(enabled: Boolean) {
            viewModelScope.launch {
                val changed = settings.bypassLan.first() != enabled
                settings.setBypassLan(enabled)
                if (changed) recommendReconnectIfSessionActive()
            }
        }

        fun reconnect() {
            reconnectRecommended.value = false
            viewModelScope.launch { connectionManager.reconnect() }
        }

        private fun recommendReconnectIfSessionActive() {
            if (connectionManager.state.value.hasLiveConfig()) {
                reconnectRecommended.value = true
            }
        }

        private fun VpnConnectionState.hasLiveConfig(): Boolean =
            when (this) {
                is VpnConnectionState.Connected,
                is VpnConnectionState.Connecting,
                is VpnConnectionState.Reconnecting,
                is VpnConnectionState.Preparing,
                is VpnConnectionState.PermissionRequired,
                -> {
                    true
                }

                else -> {
                    false
                }
            }
    }
