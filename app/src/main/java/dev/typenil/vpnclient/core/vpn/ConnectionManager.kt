package dev.typenil.vpnclient.core.vpn

import android.content.Intent
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
import kotlinx.coroutines.Job
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
 *
 * Invariants:
 * - every connect attempt gets a [sessionGeneration]; service callbacks and
 *   collector emissions tagged with an older generation are ignored, so a
 *   stale session can never corrupt current state;
 * - telemetry (stats/groups) may only mutate an existing `Connected` payload —
 *   it never creates or resurrects lifecycle state;
 * - engine terminal events are recorded and teardown converges through the
 *   service; the terminal error is published by [onServiceStopped];
 * - `pendingSession` is an in-memory handoff only (durable recovery is
 *   handled at the service level in a later slice).
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val serviceControl: ServiceControl,
    private val configProvider: NodeConfigProvider,
) {

    /** Engine config + session generation handed to the service (too big for extras). */
    class PendingSession(val config: EngineConfig, val generation: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Idle)
    val state: StateFlow<VpnConnectionState> = _state

    /** Consent intent the UI must launch. */
    private val _prepareIntent = MutableStateFlow<Intent?>(null)
    val prepareIntent: StateFlow<Intent?> = _prepareIntent

    @Volatile
    var pendingSession: PendingSession? = null
        private set

    /** Monotonic in-process session id; incremented once per connect attempt. */
    private var sessionGeneration = 0L
    private var sessionNode: NodeSummary? = null

    /**
     * Terminal error observed while a session was running; published by
     * [onServiceStopped] once the service finished teardown. A VPN that is
     * "failing but still up" is reported as failed only after TUN/core are
     * actually cleaned.
     */
    private var pendingTerminalError: VpnError? = null

    private var engine: VpnEngine? = null
    private var statsJob: Job? = null
    private var eventsJob: Job? = null

    /** User pressed Connect. */
    fun connect() {
        scope.launch {
            mutex.withLock {
                when (_state.value) {
                    is VpnConnectionState.Connected,
                    is VpnConnectionState.Connecting,
                    is VpnConnectionState.Reconnecting,
                    is VpnConnectionState.Stopping,
                    -> return@withLock
                    else -> Unit
                }
                val config = try {
                    configProvider.compileSelected()
                } catch (e: EngineError) {
                    publish(VpnConnectionState.Error(VpnError.fromEngine(e), null))
                    return@withLock
                } catch (e: Exception) {
                    publish(
                        VpnConnectionState.Error(
                            VpnError.Unexpected(e.message ?: "config build failed"), null,
                        ),
                    )
                    return@withLock
                }
                if (config == null) {
                    publish(VpnConnectionState.Error(VpnError.NoNodeSelected, null))
                    return@withLock
                }
                val generation = ++sessionGeneration
                pendingSession = PendingSession(config, generation)
                sessionNode = config.node
                publish(VpnConnectionState.Preparing(config.node))

                val prepare = serviceControl.prepareVpn()
                if (prepare != null) {
                    _prepareIntent.value = prepare
                    publish(VpnConnectionState.PermissionRequired)
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
                // Only a consent we actually requested counts — a duplicate or
                // stale result must not launch a second service.
                if (_state.value !is VpnConnectionState.PermissionRequired) {
                    return@withLock
                }
                val session = pendingSession
                if (!granted) {
                    pendingSession = null
                    publish(
                        VpnConnectionState.Error(
                            VpnError.PermissionDenied, session?.config?.node,
                        ),
                    )
                    return@withLock
                }
                if (session == null) {
                    publish(VpnConnectionState.Error(VpnError.NoNodeSelected, null))
                    return@withLock
                }
                launchService(session.config)
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
                // User intent supersedes any pending terminal error — a normal
                // disconnect must land on Idle, not on a stale failure.
                pendingTerminalError = null
                publish(VpnConnectionState.Stopping)
                serviceControl.startDisconnectService()
            }
        }
    }

    private fun launchService(config: EngineConfig) {
        publish(VpnConnectionState.Connecting(config.node))
        serviceControl.startConnectService()
    }

    // region service callbacks (same process)

    /**
     * Service created the engine — collect its outputs into state.
     * [generation] tags the session the engine belongs to; emissions tagged
     * with a stale generation are dropped.
     */
    fun attachEngine(engine: VpnEngine, generation: Long) {
        this.engine = engine
        statsJob?.cancel()
        eventsJob?.cancel()
        statsJob = scope.launch {
            engine.stats.collect { stats ->
                if (generation != sessionGeneration) return@collect
                // Telemetry mutates a Connected payload only — it is never
                // evidence that a session is alive.
                val current = _state.value
                if (current is VpnConnectionState.Connected) {
                    _state.value = current.copy(stats = stats)
                }
            }
        }
        eventsJob = scope.launch {
            engine.events.collect { event ->
                if (generation != sessionGeneration) return@collect
                when (event) {
                    is EngineEvent.Failed ->
                        onEngineTerminated(VpnError.fromEngine(event.error))
                    is EngineEvent.StoppedUnexpectedly ->
                        onEngineTerminated(VpnError.EngineFailed("core terminated unexpectedly"))
                    else -> Unit
                }
            }
        }
    }

    fun detachEngine() {
        statsJob?.cancel()
        statsJob = null
        eventsJob?.cancel()
        eventsJob = null
        engine = null
    }

    private fun onEngineTerminated(error: VpnError) {
        // Only meaningful while a session is alive; teardown converges through
        // the service so TUN/collectors are cleaned before the error is shown.
        when (_state.value) {
            is VpnConnectionState.Connecting,
            is VpnConnectionState.Connected,
            is VpnConnectionState.Reconnecting,
            -> {
                if (pendingTerminalError == null) pendingTerminalError = error
                serviceControl.startDisconnectService()
            }
            else -> Unit
        }
    }

    /** Engine + tunnel up (openTun succeeded during start). */
    fun onServiceStarted(generation: Long) {
        if (!isCurrent(generation)) return
        val node = sessionNode ?: pendingSession?.config?.node ?: return
        pendingSession = null
        publish(VpnConnectionState.Connected(node, Instant.now(), null))
    }

    fun onServiceFailed(error: VpnError, generation: Long) {
        if (!isCurrent(generation)) return
        pendingSession = null
        publish(VpnConnectionState.Error(error, sessionNode))
    }

    fun onServiceStopped(generation: Long) {
        if (!isCurrent(generation)) return
        detachEngine()
        pendingSession = null
        val error = pendingTerminalError
        pendingTerminalError = null
        val current = _state.value
        val next = when {
            error != null -> VpnConnectionState.Error(error, sessionNode)
            // A failure was already published (onServiceFailed / onRevoke) —
            // teardown completion must not regress it to Idle.
            current is VpnConnectionState.Error -> return
            else -> VpnConnectionState.Idle
        }
        publish(next)
    }

    /** onRevoke — the tunnel is already gone. */
    fun onServiceRevoked() {
        pendingSession = null
        pendingTerminalError = null
        detachEngine()
        publish(VpnConnectionState.Error(VpnError.PermissionRevoked, sessionNode))
    }

    // endregion

    /**
     * A callback applies only to the session that produced it. [generation]
     * `-1` means the service had no session to tag (e.g. stray start) — those
     * are lifecycle events worth reporting regardless.
     */
    private fun isCurrent(generation: Long): Boolean =
        generation < 0 || generation == sessionGeneration

    private fun publish(next: VpnConnectionState) {
        SecureLog.d(TAG, "state ${state.value.javaClass.simpleName} -> ${next.javaClass.simpleName}")
        _state.value = next
    }

    private companion object {
        const val TAG = "ConnectionManager"
    }
}
