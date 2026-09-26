package dev.typenil.vpnclient.ui.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.engine.DnsMode
import dev.typenil.vpnclient.core.engine.DnsUpstream
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.engine.RoutingRule
import dev.typenil.vpnclient.core.engine.RoutingRuleValidator
import dev.typenil.vpnclient.data.db.RoutingRuleDao
import dev.typenil.vpnclient.data.db.RoutingRuleEntity
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.data.RoutingRuleSetValidator
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
    /** DNS policy + upstream as persisted. */
    val dnsMode: DnsMode = DnsMode.POLICY,
    val dnsUpstream: DnsUpstream = DnsUpstream.Cloudflare,
    /** The DNS profile the live session resolves with — null when idle. */
    val appliedDnsSummary: String? = null,
    /** User routing rules in order — compiled in ahead of the mode rules. */
    val rules: List<RoutingRuleEntity> = emptyList(),
    /** The pending add failed — bad pattern or engine rejection. */
    val ruleError: Boolean = false,
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
        private val routingRuleDao: RoutingRuleDao,
        private val ruleSetValidator: RoutingRuleSetValidator,
    ) : ViewModel() {
        private val reconnectRecommended = MutableStateFlow(false)
        /** The pending add was rejected (bad pattern or engine check). */
        private val ruleError = MutableStateFlow(false)

        val uiState: StateFlow<RoutingUiState> =
            combine(
                settings.routeMode,
                settings.bypassLan,
                settings.dnsProfile,
                connectionManager.appliedSessionConfig,
                connectionManager.state,
                reconnectRecommended,
                routingRuleDao.observeAll(),
                ruleError,
            ) { values ->
                val routeMode = values[0] as RouteMode
                val bypassLan = values[1] as Boolean
                @Suppress("UNCHECKED_CAST")
                val dns = values[2] as dev.typenil.vpnclient.core.engine.DnsProfile
                @Suppress("UNCHECKED_CAST")
                val applied =
                    values[3] as dev.typenil.vpnclient.core.vpn.AppliedSessionConfig?
                val state = values[4] as VpnConnectionState
                val recommended = values[5] as Boolean
                @Suppress("UNCHECKED_CAST")
                val rules = values[6] as List<RoutingRuleEntity>
                RoutingUiState(
                    routeMode = routeMode,
                    bypassLan = bypassLan,
                    dnsMode = dns.mode,
                    dnsUpstream = dns.upstream,
                    appliedRouteMode = applied?.routeMode,
                    appliedBypassLan = applied?.bypassLan,
                    appliedDnsSummary = applied?.dnsProfileSummary,
                    rules = rules,
                    ruleError = values[7] as Boolean,
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

        /** Compiled into the dns block — a live tunnel keeps its profile. */
        fun setDnsMode(mode: DnsMode) {
            viewModelScope.launch {
                val changed = settings.dnsMode.first() != mode
                settings.setDnsMode(mode)
                if (changed) recommendReconnectIfSessionActive()
            }
        }

        /** Preset or validated custom upstream — same compiled-in contract. */
        fun setDnsUpstream(upstream: DnsUpstream) {
            viewModelScope.launch {
                val changed = settings.dnsUpstream.first() != upstream
                settings.setDnsUpstream(upstream)
                if (changed) recommendReconnectIfSessionActive()
            }
        }

        // ---- user routing rules -------------------------------------------

        /** Append a validated rule at the end of the list — a compile-in
         *  change, so a live session gets the reconnect hint.
         *
         *  The pattern is validated synchronously; the engine check runs in
         *  a coroutine and the row is inserted only after it passes, so a
         *  rejected rule never reaches Room. [ruleError] (same indicator as
         *  a bad pattern) is raised on failure and cleared on the next
         *  attempt or success. */
        fun addRule(
            kind: RoutingRule.Kind,
            pattern: String,
            action: RoutingRule.Action,
        ): Boolean {
            val canonical = RoutingRuleValidator.validatePattern(kind, pattern) ?: return false
            ruleError.value = false
            viewModelScope.launch {
                val engineOk =
                    ruleSetValidator.validateRules(
                        listOf(
                            RoutingRule(
                                kind = kind,
                                pattern = canonical,
                                action = action,
                            ),
                        ),
                    )
                if (!engineOk) {
                    ruleError.value = true
                    return@launch
                }
                routingRuleDao.insert(
                    RoutingRuleEntity(
                        kind = kind.key,
                        pattern = canonical,
                        action = action.key,
                        orderIndex = routingRuleDao.nextOrderIndex(),
                    ),
                )
                recommendReconnectIfSessionActive()
            }
            return true
        }

        fun updateRule(rule: RoutingRuleEntity) {
            viewModelScope.launch {
                routingRuleDao.update(rule)
                recommendReconnectIfSessionActive()
            }
        }

        /** The add dialog's error indicator — same signal for a bad pattern
         *  and an engine rejection, cleared on the next input or success. */
        fun clearRuleError() {
            ruleError.value = false
        }

        fun deleteRule(id: Long) {
            viewModelScope.launch {
                routingRuleDao.delete(id)
                recommendReconnectIfSessionActive()
            }
        }

        fun setRuleEnabled(
            rule: RoutingRuleEntity,
            enabled: Boolean,
        ) {
            updateRule(rule.copy(isEnabled = enabled))
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
