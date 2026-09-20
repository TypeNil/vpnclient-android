package dev.typenil.vpnclient.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.data.settings.SettingsRepository
import javax.inject.Inject
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
) {
    val autoRefreshEnabled: Boolean get() = autoRefreshMinutes >= 0
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.reconnectOnNetworkChange,
        settings.ipv6Enabled,
        settings.dozePowerSave,
        settings.autoRefreshMinutes,
    ) { reconnect, ipv6, doze, refreshMinutes ->
        SettingsUiState(
            reconnectOnNetworkChange = reconnect,
            ipv6Enabled = ipv6,
            dozePowerSave = doze,
            autoRefreshMinutes = refreshMinutes,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun setReconnectOnNetworkChange(enabled: Boolean) {
        viewModelScope.launch { settings.setReconnectOnNetworkChange(enabled) }
    }

    fun setIpv6Enabled(enabled: Boolean) {
        viewModelScope.launch { settings.setIpv6Enabled(enabled) }
    }

    fun setDozePowerSave(enabled: Boolean) {
        viewModelScope.launch { settings.setDozePowerSave(enabled) }
    }

    fun setAutoRefreshEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoRefreshEnabled(enabled) }
    }

    /** Persist a user override interval; 0/blank falls back to the provider hint. */
    fun setAutoRefreshMinutes(minutes: Int) {
        viewModelScope.launch { settings.setAutoRefreshMinutes(minutes.coerceAtLeast(0)) }
    }
}
