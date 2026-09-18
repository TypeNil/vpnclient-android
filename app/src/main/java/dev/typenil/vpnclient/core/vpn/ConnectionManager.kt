package dev.typenil.vpnclient.core.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.EngineEvent
import dev.typenil.vpnclient.core.engine.VpnEngine
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Provides the engine config for the currently selected node.
 * Implemented by the subscription/config layer — keeps the VPN layer free
 * of subscription and core-format details.
 */
interface NodeConfigProvider {
    /** Compile the engine config for the selected node, or null if none. */
    suspend fun compileSelected(): EngineConfig?
}

/**
 * Process-wide connection orchestrator: owns the [VpnConnectionState] state
 * machine and coordinates UI intents ↔ [ClientVpnService] ↔ [VpnEngine].
 *
 * Same-process wiring: the service injects this singleton and reports engine
 * lifecycle; the UI calls [connect]/[disconnect].
 */
@Singleton
class ConnectionManager @Inject constructor(
    @ApplicationContext private val app: Context,
    private val configProvider: NodeConfigProvider,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Idle)
    val state: StateFlow<VpnConnectionState> = _state

    /** Consent intent from [VpnService.prepare] the UI must launch. */
    private val _prepareIntent = MutableStateFlow<Intent?>(null)
    val prepareIntent: StateFlow<Intent?> = _prepareIntent

    /** Config handed to the service via the pending field (too big for extras). */
    @Volatile
    var pendingConfig: EngineConfig? = null
        private set

    private var engine: VpnEngine? = null
    private var statsJob: kotlinx.coroutines.Job? = null

    /** User pressed Connect. */
    fun connect() {
        scope.launch {
            mutex.withLock {
                when (_state.value) {
                    is VpnConnectionState.Connected,
                    is VpnConnectionState.Connecting,
                    is VpnConnectionState.Reconnecting,
                    -> return@withLock
                    else -> Unit
                }
                val config = try {
                    configProvider.compileSelected()
                } catch (e: EngineError) {
                    _state.value = VpnConnectionState.Error(VpnError.fromEngine(e), null)
                    return@withLock
                } catch (e: Exception) {
                    _state.value = VpnConnectionState.Error(
                        VpnError.Unexpected(e.message ?: "config build failed"), null,
                    )
                    return@withLock
                }
                if (config == null) {
                    _state.value = VpnConnectionState.Error(VpnError.NoNodeSelected, null)
                    return@withLock
                }
                _state.value = VpnConnectionState.Preparing(config.node)

                pendingConfig = config
                val prepare = VpnService.prepare(app)
                if (prepare != null) {
                    _prepareIntent.value = prepare
                    _state.value = VpnConnectionState.PermissionRequired
                    return@withLock
                }
                launchService(config)
            }
        }
    }

    /** System VPN-consent dialog result. */
    fun onPermissionResult(granted: Boolean) {
        _prepareIntent.value = null
        scope.launch {
            mutex.withLock {
                val config = pendingConfig
                if (!granted) {
                    pendingConfig = null
                    _state.value = VpnConnectionState.Error(
                        VpnError.PermissionDenied, config?.node,
                    )
                    return@withLock
                }
                if (config == null) {
                    _state.value = VpnConnectionState.Error(VpnError.NoNodeSelected, null)
                    return@withLock
                }
                launchService(config)
            }
        }
    }

    fun disconnect() {
        scope.launch {
            mutex.withLock {
                if (_state.value is VpnConnectionState.Idle ||
                    _state.value is VpnConnectionState.Stopping
                ) {
                    return@withLock
                }
                _state.value = VpnConnectionState.Stopping
                app.startService(ClientVpnService.disconnectIntent(app))
            }
        }
    }

    private fun launchService(config: EngineConfig) {
        _state.value = VpnConnectionState.Connecting(config.node)
        ContextCompat.startForegroundService(app, ClientVpnService.connectIntent(app))
    }

    // region service callbacks (same process)

    /** Service created the engine — collect its outputs into state. */
    fun attachEngine(engine: VpnEngine) {
        this.engine = engine
        statsJob?.cancel()
        val node = (pendingConfig?.node)
        statsJob = scope.launch {
            engine.stats.collect { stats ->
                val current = _state.value
                if (current is VpnConnectionState.Connected) {
                    _state.value = current.copy(stats = stats)
                } else {
                    _state.value = VpnConnectionState.Connected(
                        node = node ?: return@collect,
                        since = Instant.now(),
                        stats = stats,
                    )
                }
            }
        }
        scope.launch {
            engine.events.collect { event ->
                when (event) {
                    is EngineEvent.Failed -> _state.value = VpnConnectionState.Error(
                        VpnError.fromEngine(event.error), node,
                    )
                    else -> Unit
                }
            }
        }
    }

    fun detachEngine() {
        statsJob?.cancel()
        statsJob = null
        engine = null
    }

    /** Engine + tunnel up (openTun succeeded during start). */
    fun onServiceStarted() {
        val config = pendingConfig ?: return
        _state.value = VpnConnectionState.Connected(config.node, Instant.now(), null)
    }

    fun onServiceFailed(error: VpnError) {
        val node = pendingConfig?.node
        pendingConfig = null
        _state.value = VpnConnectionState.Error(error, node)
    }

    fun onServiceStopped() {
        pendingConfig = null
        _state.value = VpnConnectionState.Idle
    }

    /** onRevoke — the tunnel is already gone. */
    fun onServiceRevoked() {
        pendingConfig = null
        detachEngine()
        _state.value = VpnConnectionState.Error(VpnError.PermissionRevoked, null)
    }

    // endregion
}
