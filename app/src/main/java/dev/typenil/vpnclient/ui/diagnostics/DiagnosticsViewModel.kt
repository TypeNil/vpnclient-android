package dev.typenil.vpnclient.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.vpn.AppliedSessionConfig
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.IpCheckResult
import dev.typenil.vpnclient.core.vpn.IpProbe
import dev.typenil.vpnclient.core.vpn.UnderlyingTransport
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the last "check my IP" run produced. */
enum class IpCheckStatus {
    /** Never run on this screen instance. */
    Idle,

    /** Probe in flight. */
    Running,

    /** Finished — [DiagnosticsUiState.ipCheck] carries the typed result. */
    Done,
}

data class DiagnosticsUiState(
    /** The real connection state machine — rendered as-is. */
    val connection: VpnConnectionState = VpnConnectionState.Idle,
    /** The routing/per-app/DNS plan the live engine actually runs — null
     *  outside a session; nothing is guessed from current settings. */
    val applied: AppliedSessionConfig? = null,
    /** Physical underlay reported by the service's network tracker. */
    val underlay: UnderlyingTransport = UnderlyingTransport.UNKNOWN,
    val ipCheckStatus: IpCheckStatus = IpCheckStatus.Idle,
    /** Result of the last probe, kept after completion for display. */
    val ipCheck: IpCheckResult? = null,
)

@HiltViewModel
class DiagnosticsViewModel
    @Inject
    constructor(
        private val connectionManager: ConnectionManager,
        private val ipProbe: IpProbe,
    ) : ViewModel() {
        private val ipCheckStatus = MutableStateFlow(IpCheckStatus.Idle)
        private val ipCheck = MutableStateFlow<IpCheckResult?>(null)
        private var probeJob: Job? = null

        val uiState: StateFlow<DiagnosticsUiState> =
            combine(
                connectionManager.state,
                connectionManager.appliedSessionConfig,
                connectionManager.underlyingTransport,
                ipCheckStatus,
                ipCheck,
            ) { connection, applied, underlay, status, result ->
                DiagnosticsUiState(
                    connection = connection,
                    applied = applied,
                    underlay = underlay,
                    ipCheckStatus = status,
                    ipCheck = result,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = DiagnosticsUiState(connection = connectionManager.state.value),
            )

        /** Run the through-tunnel IP echo probe. One at a time — a new tap while
         *  a probe is in flight is ignored rather than stacking requests. */
        fun checkIp() {
            if (probeJob?.isActive == true) return
            probeJob =
                viewModelScope.launch {
                    ipCheckStatus.value = IpCheckStatus.Running
                    ipCheck.value = null
                    val result = ipProbe.check()
                    ipCheck.value = result
                    ipCheckStatus.value = IpCheckStatus.Done
                }
        }
    }
