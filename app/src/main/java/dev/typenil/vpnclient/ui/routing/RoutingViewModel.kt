package dev.typenil.vpnclient.ui.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.engine.DnsMode
import dev.typenil.vpnclient.core.engine.DnsUpstream
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.engine.RoutingRule
import dev.typenil.vpnclient.data.db.RoutingRuleDao
import dev.typenil.vpnclient.data.db.RoutingRuleEntity
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
    /** DNS policy + upstream as persisted. */
    val dnsMode: DnsMode = DnsMode.POLICY,
    val dnsUpstream: DnsUpstream = DnsUpstream.Cloudflare,
    /** The DNS profile the live session resolves with — null when idle. */
    val appliedDnsSummary: String? = null,
    /** User routing rules in order — compiled in ahead of the mode rules. */
    val rules: List<RoutingRuleEntity> = emptyList(),
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
    ) : ViewModel() {
        private val reconnectRecommended = MutableStateFlow(false)

        val uiState: StateFlow<RoutingUiState> =
            combine(
                settings.routeMode,
                settings.bypassLan,
                settings.dnsProfile,
                connectionManager.appliedSessionConfig,
                connectionManager.state,
                reconnectRecommended,
                routingRuleDao.observeAll(),
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
         *  change, so a live session gets the reconnect hint. */
        fun addRule(
            kind: RoutingRule.Kind,
            pattern: String,
            action: RoutingRule.Action,
        ): Boolean {
            val p = validatePattern(kind, pattern) ?: return false
            viewModelScope.launch {
                routingRuleDao.insert(
                    RoutingRuleEntity(
                        kind = kind.key,
                        pattern = p,
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

        /** Reject anything that isn't a compilable matcher — the value is
         *  stored verbatim and read back into the engine config, so a bad
         *  spec would fail the next connect inside checkConfig. Returns the
         *  canonical pattern or null. */
        private fun validatePattern(
            kind: RoutingRule.Kind,
            raw: String,
        ): String? {
            val s = raw.trim().lowercase()
            if (s.isEmpty()) return null
            return when (kind) {
                // domain_suffix: a literal domain or leading-dot suffix. Strip
                // a leading "*." so "*.example.com" stores as "example.com".
                RoutingRule.Kind.DOMAIN -> {
                    val host = s.removePrefix("*.").removeSuffix(".")
                    val valid = host.isNotEmpty() && host.length <= 253 &&
                        host.split('.').all { label ->
                            label.isNotEmpty() && label.length <= 63 &&
                                label.all { it.isLetterOrDigit() || it == '-' } &&
                                !label.startsWith('-') && !label.endsWith('-')
                        }
                    host.takeIf { valid && it.contains('.') }
                }
                // CIDR notation only — a bare IP gets its /32 (v4) or /128
                // (v6) appended so the user can type either.
                RoutingRule.Kind.IP_CIDR -> {
                    val withPrefix =
                        if ('/' in s) {
                            s
                        } else {
                            s + if (':' in s) "/128" else "/32"
                        }
                    val host = withPrefix.substringBefore('/')
                    val bits = withPrefix.substringAfter('/').toIntOrNull() ?: return null
                    val max = if (':' in host) 128 else 32
                    val validIp = runCatching {
                        java.net.InetAddress.getByName(host)
                    }.getOrNull() != null && host.all { it.isDigit() || it == '.' || it == ':' }
                    withPrefix.takeIf { validIp && bits in 0..max }
                }
                // A single port or a range "8000:8080".
                RoutingRule.Kind.PORT -> {
                    val parts = s.split(':')
                    val ok = parts.isNotEmpty() && parts.size <= 2 &&
                        parts.all { it.toIntOrNull() in 1..65535 }
                    s.takeIf { ok }
                }
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
