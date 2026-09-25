package dev.typenil.vpnclient.core.vpn

import android.content.Intent
import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.engine.ConnectionInfo
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.EngineEvent
import dev.typenil.vpnclient.core.engine.OutboundGroupInfo
import dev.typenil.vpnclient.core.engine.VpnEngine
import dev.typenil.vpnclient.core.engine.resolveSelectionTarget
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Provides the engine config for the currently selected node.
 * Implemented by the subscription/config layer — keeps the VPN layer free
 * of subscription and core-format details.
 */
interface NodeConfigProvider {
    /** Compile the engine config for the selected node, or null if none. */
    suspend fun compileSelected(): EngineConfig?

    /** Persisted server pick — the desired outbound for any live session. */
    val selectedNodeId: Flow<String?>

    /** Display summary for a node id, or null when the node is gone. */
    suspend fun nodeSummary(id: String): NodeSummary?
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
    /** The outbound the current engine's config was compiled with — its
     *  selector default; the urltest group tag for an Auto compile.
     *  Diverges from [sessionNode] after a live outbound switch. */
    private var compiledNodeId: String? = null

    /** Serializes selection reconciliation — every run re-reads the latest
     *  persisted pick, so queued runs converge instead of racing. */
    private val selectionMutex = Mutex()

    /** Bounded auto-reconnect bookkeeping: consecutive engine failures
     *  retry with backoff; a stable session or a user action resets it. */
    private var failureReconnectAttempts = 0
    private var failureReconnectJob: Job? = null
    private var failureResetJob: Job? = null
    private var lastFailureError: VpnError? = null

    init {
        // The persisted pick is the desired outbound for any live session:
        // a tap in Servers lands here and is applied to the running engine.
        scope.launch {
            configProvider.selectedNodeId
                .distinctUntilChanged()
                .collect { reconcileSelection() }
        }
    }

    /**
     * Terminal error observed while a session was running; published by
     * [onServiceStopped] once the service finished teardown. A VPN that is
     * "failing but still up" is reported as failed only after TUN/core are
     * actually cleaned.
     */
    private var pendingTerminalError: VpnError? = null

    /** A teardown intent was already sent for this session — don't resend. */
    private var teardownRequested = false

    private var engine: VpnEngine? = null
    private var statsJob: Job? = null
    private var eventsJob: Job? = null
    private var groupsJob: Job? = null
    private var connectionsJob: Job? = null

    /** Live outbound groups from the running engine; empty while detached. */
    private val _groups = MutableStateFlow<List<OutboundGroupInfo>>(emptyList())
    val groups: StateFlow<List<OutboundGroupInfo>> = _groups

    /** Live connections through the tunnel; empty while detached. */
    private val _activeConnections = MutableStateFlow<List<ConnectionInfo>>(emptyList())
    val activeConnections: StateFlow<List<ConnectionInfo>> = _activeConnections

    /** Coarse transport of the physical underlay carrying the tunnel —
     *  reported by the service's network observer; UNKNOWN until classified
     *  or when there is no usable underlay. */
    private val _underlyingTransport = MutableStateFlow(UnderlyingTransport.UNKNOWN)
    val underlyingTransport: StateFlow<UnderlyingTransport> = _underlyingTransport

    /** User pressed Connect. */
    fun connect() {
        scope.launch { mutex.withLock { startConnect(resetFailureBudget = true) } }
    }

    /**
     * Shared connect body. [resetFailureBudget] distinguishes a user-initiated
     * connect (fresh auto-reconnect budget) from a retry driven by
     * reconnect() — resetting there would make the budget never exhaust.
     */
    private suspend fun startConnect(resetFailureBudget: Boolean) {
        when (_state.value) {
            is VpnConnectionState.Connected,
            is VpnConnectionState.Connecting,
            is VpnConnectionState.Reconnecting,
            is VpnConnectionState.Stopping,
            // A consent intent may already be outstanding — a second
            // connect() would burn a generation and recompile.
            is VpnConnectionState.Preparing,
            is VpnConnectionState.PermissionRequired,
            -> return
            else -> Unit
        }
        if (resetFailureBudget) {
            failureReconnectJob?.cancel()
            failureReconnectAttempts = 0
            lastFailureError = null
        }
        val config = try {
            configProvider.compileSelected()
        } catch (e: EngineError) {
            publish(VpnConnectionState.Error(VpnError.fromEngine(e), null))
            return
        } catch (e: CancellationException) {
            // Cancelled work is not a user-visible failure.
            throw e
        } catch (e: Exception) {
            publish(
                VpnConnectionState.Error(
                    VpnError.Unexpected(e.message ?: "config build failed"), null,
                ),
            )
            return
        }
        if (config == null) {
            publish(VpnConnectionState.Error(VpnError.NoNodeSelected, null))
            return
        }
        val generation = ++sessionGeneration
        pendingSession = PendingSession(config, generation)
        sessionNode = config.node
        compiledNodeId = config.node.id
        publish(VpnConnectionState.Preparing(config.node))

        val prepare = serviceControl.prepareVpn()
        if (prepare != null) {
            _prepareIntent.value = prepare
            publish(VpnConnectionState.PermissionRequired)
            return
        }
        launchService(config)
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
                // A user disconnect ends the retry chain — a pending
                // auto-reconnect must not fire after it.
                failureReconnectJob?.cancel()
                failureReconnectAttempts = 0
                lastFailureError = null
                // User intent supersedes any pending terminal error — a normal
                // disconnect must land on Idle, not on a stale failure.
                pendingTerminalError = null
                publish(VpnConnectionState.Stopping)
                // startService can throw under background-start restrictions —
                // report instead of leaving the machine stuck in Stopping.
                runCatching { serviceControl.startDisconnectService() }
                    .onFailure {
                        SecureLog.w(TAG, "disconnect intent failed")
                        publish(
                            VpnConnectionState.Error(
                                VpnError.Unexpected("failed to stop service"),
                                sessionNode,
                            ),
                        )
                    }
            }
        }
    }

    /**
     * Stop the live session and connect again with freshly compiled
     * settings — the apply path for changes baked into the config at
     * compile time (route mode, IPv6) and the fallback when a live
     * outbound switch can't be honored. Returns false when there is no
     * session to restart (Idle/Error already pick up new settings on the
     * next connect) or while a connect is still in its pre-service phase.
     */
    suspend fun reconnect(): Boolean = mutex.withLock {
        when (_state.value) {
            is VpnConnectionState.Connected,
            is VpnConnectionState.Connecting,
            is VpnConnectionState.Reconnecting,
            -> Unit
            // Consent pending: drop the stale pending session and re-run
            // connect() — it recompiles with the new settings and the
            // already-granted consent skips the dialog.
            is VpnConnectionState.PermissionRequired -> {
                pendingSession = null
                publish(VpnConnectionState.Idle)
                startConnect(resetFailureBudget = false)
                return@withLock true
            }
            else -> return@withLock false
        }
        pendingTerminalError = null
        publish(VpnConnectionState.Stopping)
        val sent = runCatching { serviceControl.startDisconnectService() }
            .onFailure { SecureLog.w(TAG, "disconnect intent failed") }
            .isSuccess
        if (!sent) {
            publish(
                VpnConnectionState.Error(
                    VpnError.Unexpected("failed to stop service"), sessionNode,
                ),
            )
            return@withLock false
        }
        // Teardown converges through the service → onServiceStopped → Idle.
        // The bound keeps a wedged teardown from hanging the caller.
        val settled = withTimeoutOrNull(RECONNECT_SETTLE_MS) {
            _state.first {
                it is VpnConnectionState.Idle || it is VpnConnectionState.Error
            }
        }
        if (settled == null) {
            SecureLog.w(TAG, "reconnect: teardown did not settle")
            return@withLock false
        }
        if (_state.value is VpnConnectionState.Error) return@withLock false
        startConnect(resetFailureBudget = false)
        true
    }

    private fun launchService(config: EngineConfig) {
        publish(VpnConnectionState.Connecting(config.node))
        // startForegroundService can throw under background-start
        // restrictions — a stuck "Connecting" would be a fake state.
        runCatching { serviceControl.startConnectService() }
            .onFailure {
                SecureLog.w(TAG, "connect intent failed")
                pendingSession = null
                publish(
                    VpnConnectionState.Error(
                        VpnError.Unexpected("failed to start service"),
                        sessionNode,
                    ),
                )
            }
    }

    // region service callbacks (same process)

    /**
     * Service created the engine — collect its outputs into state.
     * [generation] tags the session the engine belongs to; emissions tagged
     * with a stale generation are dropped.
     */
    fun attachEngine(engine: VpnEngine, generation: Long) {
        if (generation != sessionGeneration) {
            SecureLog.w(TAG, "attachEngine with stale generation — ignored")
            return
        }
        this.engine = engine
        statsJob?.cancel()
        eventsJob?.cancel()
        groupsJob?.cancel()
        connectionsJob?.cancel()
        groupsJob = scope.launch {
            engine.groups.collect { groups ->
                if (generation != sessionGeneration) return@collect
                _groups.value = groups
                // While Auto is selected, the urltest group's measured
                // winner is what Home should name — refresh the label as
                // the group reports it.
                val current = _state.value
                if ((current is VpnConnectionState.Connected ||
                        current is VpnConnectionState.Reconnecting) &&
                    sessionNode?.id == NodeSelection.AUTO_ID
                ) {
                    updateSessionNode(
                        NodeSelection.AUTO_ID,
                        resolvedTag = groups.firstOrNull {
                            it.tag == NodeSelection.AUTO_ID
                        }?.selected,
                    )
                }
            }
        }
        connectionsJob = scope.launch {
            engine.connections.collect { connections ->
                if (generation == sessionGeneration) {
                    _activeConnections.value = connections
                }
            }
        }
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
        // A fresh engine starts on its compiled selector default — re-apply
        // the persisted pick so a rebuilt tunnel doesn't silently revert to
        // the node the session originally connected with.
        reconcileSelection()
    }

    /**
     * Detach collectors from the current engine. [generation] `-1` forces the
     * detach; a tagged call only acts when it still owns the session — a stale
     * teardown must not strip a newer session's collectors.
     */
    fun detachEngine(generation: Long = -1L) {
        if (generation >= 0 && generation != sessionGeneration) return
        statsJob?.cancel()
        statsJob = null
        eventsJob?.cancel()
        eventsJob = null
        groupsJob?.cancel()
        groupsJob = null
        connectionsJob?.cancel()
        connectionsJob = null
        _groups.value = emptyList()
        _activeConnections.value = emptyList()
        engine = null
    }

    /**
     * Re-apply the persisted server pick to the live engine. Triggered by
     * selection changes and by every engine attach (connect, in-session
     * rebuild): the selector's compiled default is only right when the pick
     * hasn't moved since compile time.
     */
    private fun reconcileSelection() {
        scope.launch { applyDesiredSelection() }
    }

    /**
     * Serialized under [selectionMutex]: each run re-reads the latest pick,
     * so a burst of taps converges on the final one. A live switch updates
     * the session node so Home/notification show what the tunnel actually
     * uses; when the engine can't honor it (control channel down, group
     * missing) the pick is applied by a full reconnect instead.
     */
    private suspend fun applyDesiredSelection() = selectionMutex.withLock {
        val eng = engine ?: return@withLock
        val desired = configProvider.selectedNodeId.first()
        val selection = NodeSelection.fromId(desired) ?: return@withLock
        if (_state.value !is VpnConnectionState.Connected &&
            _state.value !is VpnConnectionState.Reconnecting
        ) {
            return@withLock
        }
        // The outbound the pick resolves to: a node id, or the urltest
        // group's tag when the user picked Auto.
        val desiredTag = when (selection) {
            NodeSelection.Auto -> NodeSelection.AUTO_ID
            is NodeSelection.Node -> selection.id
        }
        // Groups arrive after the command client connects — a fresh engine
        // reports none yet, so wait briefly before falling back.
        val groups = withTimeoutOrNull(GROUPS_WAIT_MS) {
            eng.groups.first { it.isNotEmpty() }
        }
        if (engine !== eng) return@withLock // detached/replaced while waiting
        if (groups == null) {
            // Control channel never came up: a live switch is impossible.
            // Reconnect only when the compiled default isn't already the
            // pick — otherwise the tunnel is on the right outbound anyway.
            if (compiledNodeId != desiredTag) reconnect()
            return@withLock
        }
        // "auto" sits inside the "proxy" selector's items, so the first
        // selectable group containing the tag is still the right target.
        val target = resolveSelectionTarget(groups, desiredTag)
        if (target == null) {
            // The pick isn't in any selectable group (stale id, group
            // vanished) — a reconnect compiles with it as the default.
            if (compiledNodeId != desiredTag) reconnect()
            return@withLock
        }
        if (groups.first { it.tag == target }.selected == desiredTag) {
            // Engine already on the pick — just fix a stale label.
            updateSessionLabel(selection, groups)
            return@withLock
        }
        if (eng.selectOutbound(target, desiredTag)) {
            updateSessionLabel(selection, groups)
        } else {
            reconnect()
        }
    }

    /** Display label for the applied pick: [NodeSelection.Auto] resolves
     *  through the urltest group's measured winner; a node resolves its own
     *  summary. */
    private suspend fun updateSessionLabel(
        selection: NodeSelection,
        groups: List<OutboundGroupInfo>,
    ) {
        when (selection) {
            NodeSelection.Auto -> updateSessionNode(
                NodeSelection.AUTO_ID,
                resolvedTag = groups.firstOrNull { it.tag == NodeSelection.AUTO_ID }?.selected,
            )
            is NodeSelection.Node -> updateSessionNode(selection.id)
        }
    }

    /** Point the session's displayed node at [id] — the engine's
     *  selector is the source of truth, so the label follows the switch.
     *  [resolvedTag] is the node the urltest group currently prefers; when
     *  present it is mapped back through the provider and shown as
     *  "Auto → <name>". */
    private suspend fun updateSessionNode(id: String, resolvedTag: String? = null) {
        var summary = configProvider.nodeSummary(id) ?: return
        if (summary.id == NodeSelection.AUTO_ID) {
            // No member is impersonated: until the urltest group reports a
            // pick the label stays the generic "Auto · Fastest".
            val resolved = resolvedTag?.let { configProvider.nodeSummary(it) }
            if (resolved != null) {
                summary = summary.copy(
                    name = "${summary.name} → ${resolved.name}",
                    server = resolved.server,
                )
            }
        }
        sessionNode = summary
        when (val current = _state.value) {
            is VpnConnectionState.Connected ->
                publish(current.copy(node = summary))
            is VpnConnectionState.Reconnecting ->
                publish(current.copy(node = summary))
            else -> Unit
        }
    }

    /** Ask the engine to run urltest on [groupTag]; results arrive via [groups]. */
    suspend fun urlTest(groupTag: String) {
        engine?.urlTest(groupTag)
    }

    /**
     * Close one live connection by tracker id; the removal arrives through
     * [activeConnections] on the next core event. Returns false while
     * detached or when the engine rejected the close.
     */
    suspend fun closeConnection(id: String): Boolean =
        engine?.closeConnection(id) ?: false

    /**
     * Allocates the session generation for a service-driven start (process
     * restart, always-on) that has no `pendingSession`. Returns -1 when a
     * session is already live — the caller must bail instead of competing
     * with it. On success the state moves to Connecting so the rebuild is
     * visible and cancellable like a user-initiated connect.
     */
    fun adoptSession(node: NodeSummary): Long {
        when (_state.value) {
            is VpnConnectionState.Connected,
            is VpnConnectionState.Connecting,
            is VpnConnectionState.Reconnecting,
            is VpnConnectionState.Stopping,
            is VpnConnectionState.Preparing,
            is VpnConnectionState.PermissionRequired,
            -> return -1L
            else -> Unit
        }
        val generation = ++sessionGeneration
        sessionNode = node
        compiledNodeId = node.id
        teardownRequested = false
        publish(VpnConnectionState.Connecting(node))
        return generation
    }

    private fun onEngineTerminated(error: VpnError) {
        // Serialized through the same launch+mutex path as the other state
        // transitions — a terminal event must not race a user disconnect.
        scope.launch {
            mutex.withLock {
                // Only meaningful while a session is alive; teardown converges
                // through the service so TUN/collectors are cleaned first.
                when (_state.value) {
                    is VpnConnectionState.Connecting,
                    is VpnConnectionState.Connected,
                    is VpnConnectionState.Reconnecting,
                    -> {
                        failureResetJob?.cancel()
                        val attempt = ++failureReconnectAttempts
                        if (attempt <= MAX_FAILURE_RECONNECTS) {
                            // Bounded auto-reconnect: the error is parked and
                            // the session restarts once teardown settles — a
                            // transient core/network failure shouldn't drop
                            // the user to a dead Error state. Reconnecting
                            // stays published through the whole backoff +
                            // teardown window (no Stopping/Idle flicker).
                            lastFailureError = error
                            val current = _state.value
                            val node = when (current) {
                                is VpnConnectionState.Connecting -> current.node
                                is VpnConnectionState.Connected -> current.node
                                is VpnConnectionState.Reconnecting -> current.node
                                else -> sessionNode
                            } ?: return@withLock
                            publish(
                                VpnConnectionState.Reconnecting(
                                    node, VpnConnectionState.Reconnecting.Reason.CoreFailure, attempt,
                                ),
                            )
                            if (!requestServiceTeardown()) {
                                // The service is unreachable — no
                                // onServiceStopped will ever arrive, so the
                                // settle wait would publish Error while the
                                // tunnel is still up. Error now is honest;
                                // teardownRequested is cleared so a later
                                // connect isn't blocked.
                                detachEngine()
                                publish(VpnConnectionState.Error(error, sessionNode))
                                return@withLock
                            }
                            scheduleFailureReconnect(attempt)
                        } else {
                            // Budget exhausted — converge to a terminal error.
                            if (pendingTerminalError == null) pendingTerminalError = error
                            if (!requestServiceTeardown()) {
                                // Unreachable service: no onServiceStopped
                                // will arrive — publish the parked error now.
                                detachEngine()
                                val terminal = pendingTerminalError ?: error
                                pendingTerminalError = null
                                publish(VpnConnectionState.Error(terminal, sessionNode))
                                return@withLock
                            }
                            // Watchdog: if the service never reports
                            // onServiceStopped (wedged teardown, unreachable
                            // service) the state must not sit in
                            // Reconnecting forever — the service-side stop
                            // deadline guarantees physical convergence.
                            failureReconnectJob?.cancel()
                            failureReconnectJob = scope.launch {
                                val settled = withTimeoutOrNull(RECONNECT_SETTLE_MS) {
                                    _state.first {
                                        it is VpnConnectionState.Idle ||
                                            it is VpnConnectionState.Error
                                    }
                                }
                                if (settled == null) {
                                    // A user disconnect cancels this job,
                                    // but the timeout can win the race —
                                    // never clobber Stopping/Idle/Error.
                                    val s = _state.value
                                    if (s !is VpnConnectionState.Connected &&
                                        s !is VpnConnectionState.Connecting &&
                                        s !is VpnConnectionState.Reconnecting
                                    ) {
                                        return@launch
                                    }
                                    SecureLog.w(
                                        TAG,
                                        "terminal teardown did not settle",
                                    )
                                    detachEngine()
                                    val terminal = pendingTerminalError
                                        ?: lastFailureError
                                        ?: VpnError.EngineFailed("reconnect failed")
                                    pendingTerminalError = null
                                    publish(
                                        VpnConnectionState.Error(terminal, sessionNode),
                                    )
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Ask the service to tear down the live session. Returns false when the
     * disconnect intent can't be delivered even after one retry — the
     * service is unreachable and no onServiceStopped will arrive, so the
     * caller must converge the state machine itself. [teardownRequested] is
     * cleared on failure so a later connect isn't blocked by a teardown
     * that never happened.
     */
    private fun requestServiceTeardown(): Boolean {
        if (teardownRequested) return true
        teardownRequested = true
        // startService can throw under background-start restrictions —
        // the state machine must survive it.
        repeat(2) { attempt ->
            try {
                serviceControl.startDisconnectService()
                return true
            } catch (e: Exception) {
                SecureLog.w(TAG, "disconnect intent failed (attempt ${attempt + 1})")
            }
        }
        // A background-start restriction can reject startService even though
        // the service is alive. Context.stopService is the physical fallback:
        // onDestroy closes the TUN and reports onServiceStopped(generation).
        val stopping = runCatching { serviceControl.stopVpnService() }
            .onFailure { SecureLog.w(TAG, "stopService fallback failed") }
            .getOrDefault(false)
        if (stopping) return true
        teardownRequested = false
        return false
    }

    /**
     * One scheduled auto-reconnect at a time — a new failure replaces the
     * pending retry. Waits out the backoff, then waits for the teardown to
     * converge (onServiceStopped → Idle) before connecting again. A user
     * disconnect cancels the job, so a retry can never fire after it; a
     * wedged teardown surfaces the parked failure instead of hanging.
     */
    private fun scheduleFailureReconnect(attempt: Int) {
        failureReconnectJob?.cancel()
        failureReconnectJob = scope.launch {
            delay(failureBackoffMs(attempt))
            val settled = withTimeoutOrNull(RECONNECT_SETTLE_MS) {
                _state.first {
                    it is VpnConnectionState.Idle || it is VpnConnectionState.Error
                }
            }
            when {
                settled == null -> {
                    if (_state.value is VpnConnectionState.Reconnecting) {
                        publish(
                            VpnConnectionState.Error(
                                lastFailureError
                                    ?: VpnError.EngineFailed("reconnect failed"),
                                sessionNode,
                            ),
                        )
                    }
                }
                settled is VpnConnectionState.Error -> Unit
                else -> mutex.withLock {
                    if (_state.value is VpnConnectionState.Idle) {
                        startConnect(resetFailureBudget = false)
                    }
                }
            }
        }
    }

    /** Engine + tunnel up (openTun succeeded during start). */
    fun onServiceStarted(generation: Long) {
        if (!isCurrent(generation)) return
        val node = sessionNode ?: pendingSession?.config?.node ?: return
        pendingSession = null
        teardownRequested = false
        publish(VpnConnectionState.Connected(node, Instant.now(), null))
        // Fresh session — the label below is re-published by the next
        // underlay evaluation; reset so a previous session's transport
        // doesn't linger as fake state.
        _underlyingTransport.value = UnderlyingTransport.UNKNOWN
        // A session that stays up past the stability window proves the
        // failure was transient — the next failure gets a fresh budget.
        failureResetJob?.cancel()
        failureResetJob = scope.launch {
            delay(FAILURE_STABLE_MS)
            failureReconnectAttempts = 0
            lastFailureError = null
        }
        // A pick made while Connecting missed the reconcile gate — apply it
        // now that the session is live.
        reconcileSelection()
    }

    fun onServiceFailed(error: VpnError, generation: Long) {
        if (!isCurrent(generation)) return
        failureResetJob?.cancel()
        detachEngine()
        pendingSession = null
        _underlyingTransport.value = UnderlyingTransport.UNKNOWN
        publish(VpnConnectionState.Error(error, sessionNode))
    }

    fun onServiceStopped(generation: Long) {
        if (!isCurrent(generation)) return
        failureResetJob?.cancel()
        detachEngine()
        pendingSession = null
        teardownRequested = false
        _underlyingTransport.value = UnderlyingTransport.UNKNOWN
        val error = pendingTerminalError
        pendingTerminalError = null
        val current = _state.value
        val next = when {
            error != null -> VpnConnectionState.Error(error, sessionNode)
            // A failure was already published (onServiceFailed / onRevoke) —
            // teardown completion must not regress it to Idle.
            current is VpnConnectionState.Error -> return
            // A pending auto-reconnect waits on Idle here — publishing it is
            // what resumes reconnect(); the brief Idle is invisible in
            // practice because the retry republishes immediately.
            else -> VpnConnectionState.Idle
        }
        publish(next)
    }

    /**
     * Physical network lost while Connected → Reconnecting (the tunnel is up
     * but starved). Called by the service's single network observer.
     */
    fun onUnderlyingNetworkLost() {
        scope.launch {
            mutex.withLock {
                _underlyingTransport.value = UnderlyingTransport.UNKNOWN
                val current = _state.value
                if (current is VpnConnectionState.Connected) {
                    publish(
                        VpnConnectionState.Reconnecting(
                            current.node, VpnConnectionState.Reconnecting.Reason.NetworkUnavailable, attempt = 1,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Service reports the current physical underlay's transport — updates
     * the label without implying any state transition (a usable underlay can
     * exist while Connecting or Reconnecting for a dead engine).
     */
    fun reportUnderlyingTransport(transport: UnderlyingTransport) {
        _underlyingTransport.value = transport
    }

    /** A usable underlying network is back → resume Connected — but only
     *  when the Reconnecting was caused by the network loss itself. A
     *  rebuild or core-failure reconnect isn't resolved by the underlay
     *  coming back. */
    fun onUnderlyingNetworkAvailable() {
        scope.launch {
            mutex.withLock {
                val current = _state.value
                // teardownRequested marks a failure-reconnect — the engine is
                // dead and teardown is in flight, so Connected would be fake.
                if (current is VpnConnectionState.Reconnecting &&
                    current.reason == VpnConnectionState.Reconnecting.Reason.NetworkUnavailable &&
                    !teardownRequested
                ) {
                    publish(VpnConnectionState.Connected(current.node, Instant.now(), null))
                }
            }
        }
    }

    /**
     * The service is rebuilding the TUN inside the live session (the per-app
     * package lists are baked into the fd at establish() time, so a policy
     * change needs a fresh establish). Surface it as a brief Reconnecting —
     * [onTunnelRebuilt] flips back once the new engine is up.
     */
    fun onTunnelRebuildStarted() {
        scope.launch {
            mutex.withLock {
                val current = _state.value
                if (current is VpnConnectionState.Connected) {
                    publish(
                        VpnConnectionState.Reconnecting(
                            current.node, VpnConnectionState.Reconnecting.Reason.ApplyingChanges, attempt = 1,
                        ),
                    )
                }
            }
        }
    }

    /**
     * The session's tunnel is back up after an in-session rebuild. Only
     * resolves a Reconnecting state — anything else (Stopping, an early
     * Connected from a network callback) is left alone. Goes through the
     * same launch+mutex path as [onTunnelRebuildStarted]: the mutex grants
     * in FIFO order, so "rebuilt" can never publish before "started".
     */
    fun onTunnelRebuilt(generation: Long) {
        scope.launch {
            mutex.withLock {
                if (!isCurrent(generation)) return@withLock
                val current = _state.value
                // teardownRequested marks a failure-reconnect — the rebuilt
                // engine is about to be torn down by the pending disconnect
                // anyway, so Connected would be fake.
                if (current is VpnConnectionState.Reconnecting && !teardownRequested) {
                    publish(VpnConnectionState.Connected(current.node, Instant.now(), null))
                }
            }
        }
    }

    /** onRevoke — the tunnel is already gone. */
    fun onServiceRevoked(generation: Long) {
        if (!isCurrent(generation)) return
        // Revoke is user/system intent — no auto-reconnect after it.
        failureReconnectJob?.cancel()
        failureResetJob?.cancel()
        failureReconnectAttempts = 0
        lastFailureError = null
        pendingSession = null
        pendingTerminalError = null
        detachEngine()
        _underlyingTransport.value = UnderlyingTransport.UNKNOWN
        publish(VpnConnectionState.Error(VpnError.PermissionRevoked, sessionNode))
    }

    // endregion

    /**
     * A callback applies only to the session that produced it. [generation]
     * `-1` means the service had no session to tag (stray start, post-teardown
     * onDestroy) — those reports apply only while no session is attached, so
     * a stale teardown can't clobber a live one.
     */
    private fun isCurrent(generation: Long): Boolean =
        if (generation < 0) engine == null else generation == sessionGeneration

    private fun publish(next: VpnConnectionState) {
        val reason = (next as? VpnConnectionState.Reconnecting)?.reason?.name?.let { "($it)" } ?: ""
        SecureLog.d(TAG, "state ${state.value.javaClass.simpleName} -> ${next.javaClass.simpleName}$reason")
        _state.value = next
    }

    companion object {
        const val TAG = "ConnectionManager"
        /** Consecutive engine failures retried before giving up to Error. */
        internal const val MAX_FAILURE_RECONNECTS = 5

        /** A session stable this long proves the failure was transient. */
        internal const val FAILURE_STABLE_MS = 60_000L

        /** Exponential backoff: 1s, 2s, 4s, 8s, 16s. */
        internal fun failureBackoffMs(attempt: Int): Long =
            (1_000L shl (attempt - 1).coerceIn(0, 4))

        /** Bound on waiting for a fresh engine's outbound groups before
         *  falling back to a reconnect. */
        const val GROUPS_WAIT_MS = 5_000L
        /** Bound on waiting for teardown to settle inside [reconnect]. */
        const val RECONNECT_SETTLE_MS = 10_000L
    }
}
