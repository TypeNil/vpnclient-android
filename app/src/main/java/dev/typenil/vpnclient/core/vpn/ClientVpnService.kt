package dev.typenil.vpnclient.core.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.EngineNotification
import dev.typenil.vpnclient.core.engine.EngineNotificationSink
import dev.typenil.vpnclient.core.engine.EnginePlatform
import dev.typenil.vpnclient.core.engine.TunRequest
import dev.typenil.vpnclient.core.engine.VpnEngine
import dev.typenil.vpnclient.core.engine.VpnEngineFactory
import dev.typenil.vpnclient.data.settings.SettingsRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's [VpnService]. Owns the TUN fd lifecycle and the foreground
 * notification; drives the engine through [VpnEngine].
 *
 * Commands arrive as start-intents; state is reported upward through the
 * injected [ConnectionManager] (same-process singleton).
 *
 * The service is self-sufficient: `START_STICKY` restarts and system
 * (always-on) starts rebuild the config from Room/DataStore via
 * `NodeConfigProvider` — no in-memory handoff required. The `desiredRunning`
 * flag is persisted: set on CONNECT, cleared on DISCONNECT/revoke/start
 * failure, so a restart loop can't form and a crashed session doesn't
 * silently come back.
 */
@AndroidEntryPoint
class ClientVpnService : VpnService(), EnginePlatform {

    companion object {
        private const val TAG = "ClientVpnService"
        private const val ACTION_CONNECT = "dev.typenil.vpnclient.action.CONNECT"
        private const val ACTION_DISCONNECT = "dev.typenil.vpnclient.action.DISCONNECT"
        /** Boot/update restore — an *automatic* start, counted by the
         *  restart guard (unlike the user's explicit CONNECT). */
        private const val ACTION_RESTORE = "dev.typenil.vpnclient.action.RESTORE"
        /** Quiet window before a per-app change triggers a TUN rebuild —
         *  rapid toggle bursts collapse into a single rebuild. */
        private const val PER_APP_REBUILD_DEBOUNCE_MS = 800L
        /** Bounded re-runs for changes landing mid-rebuild (dirty flag). */
        private const val MAX_TUNNEL_REBUILDS = 4
        /** A Connected session older than this proves the start wasn't a
         *  crash — resets the sticky-restart crash guard. Known hole: a
         *  core that reliably crashes *after* this uptime loops forever
         *  (each cycle self-resets the counter); acceptable because that
         *  scenario degrades to a flaky-but-working tunnel, not a dead loop. */
        private const val RESTART_GUARD_STABLE_MS = 60_000L
        /** Bound on a graceful engine stop during teardown — a hung core
         *  must not wedge the state machine; on expiry the service forces
         *  cleanup and reports stopped itself. */
        private const val ENGINE_STOP_TIMEOUT_MS = 10_000L

        /**
         * Whether an automatic/system start must be ignored because this
         * instance already owns a session lifecycle. `engine != null` is
         * deliberately absent — during a rebuild the engine slot is null
         * while the generation is still owned, and during teardown
         * [stopRequested] covers the gap before `engine` is cleared.
         * `autoStartActive` covers the window where an earlier automatic
         * start is still suspended on its DataStore reads — a second
         * automatic start must coalesce into it, not consume another
         * restart-guard attempt.
         */
        internal fun sessionOwned(
            activeGeneration: Long,
            startActive: Boolean,
            rebuildActive: Boolean,
            stopRequested: Boolean,
            autoStartActive: Boolean,
        ): Boolean =
            activeGeneration >= 0 || startActive || rebuildActive ||
                stopRequested || autoStartActive

        fun connectIntent(context: Context): Intent =
            Intent(context, ClientVpnService::class.java).setAction(ACTION_CONNECT)

        fun disconnectIntent(context: Context): Intent =
            Intent(context, ClientVpnService::class.java).setAction(ACTION_DISCONNECT)

        fun restoreIntent(context: Context): Intent =
            Intent(context, ClientVpnService::class.java).setAction(ACTION_RESTORE)
    }

    @Inject
    lateinit var connectionManager: ConnectionManager

    @Inject
    lateinit var engineFactory: VpnEngineFactory

    @Inject
    lateinit var configProvider: NodeConfigProvider

    @Inject
    lateinit var settings: SettingsRepository

    /** Process-wide scope for fire-and-forget teardown in onDestroy —
     *  the service's own scope is cancelled on destroy. */
    @Inject
    lateinit var applicationScope: CoroutineScope

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notification = VpnNotification(this)
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    private var engine: VpnEngine? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var underlyingCallback: ConnectivityManager.NetworkCallback? = null
    private var lastUnderlyingNetwork: Network? = null
    private var dozeReceiverRegistered = false
    /** Cached settings flags — network callbacks can't suspend to read them. */
    private var reconnectOnChange = true
    private var dozePowerSave = false
    /** Engines captured by in-flight teardown coroutines — drained in onDestroy
     *  so a cancelled service scope can't leak a CommandServer. */
    private val pendingTeardown = ConcurrentHashMap.newKeySet<VpnEngine>()

    /** Forwards Doze transitions to the engine (opt-in — pause drops TCP). */
    private val dozeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!dozePowerSave) return
            val pm = context.getSystemService(PowerManager::class.java)
            val idle = pm.isDeviceIdleMode
            val activeEngine = engine ?: return
            scope.launch { activeEngine.onDeviceIdle(idle) }
        }
    }

    /**
     * Screen-off suppression (SFA pattern): while the screen is off the
     * engine's status channel is disconnected — no 1 Hz stats/groups pushes
     * nobody can see. Forwarded unconditionally; the engine no-ops when it
     * has no client yet.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.action == Intent.ACTION_SCREEN_ON
            val activeEngine = engine ?: return
            scope.launch { activeEngine.setStatusUpdatesEnabled(on) }
        }
    }
    private var screenReceiverRegistered = false
    /** Session generation this service instance is serving; -1 = none yet. */
    private var activeGeneration: Long = -1L
    /** Config the live session was started with — reused by in-session
     *  rebuilds (per-app policy changes re-establish the TUN, they don't
     *  recompile the profile). */
    private var activeConfig: EngineConfig? = null
    /** A teardown was requested while a start was still in flight. */
    private var stopRequested = false
    /** onDestroy ran — reject new starts on this doomed instance. */
    private var destroyed = false
    /** Single-flight guard: only one start coroutine may be in flight. */
    private var startJob: Job? = null
    /** Single-flight guard for the automatic (system/boot/restore) start
     *  coroutine — tracked so a newer command can cancel it and a duplicate
     *  automatic start coalesces instead of burning a restart-guard slot. */
    private var autoStartJob: Job? = null
    /** Coalesced network-change notification to the engine. Main-thread only —
     *  the ConnectivityManager callback is registered on the main Handler. */
    private var networkNotifyJob: Job? = null
    private var networkDirty = false
    /** In-session tunnel rebuild (per-app policy change). Same coalescing
     *  protocol as the network-change path — main-thread confined. */
    private var rebuildJob: Job? = null
    private var rebuildDirty = false
    /** A policy change landed while the session wasn't Connected (e.g.
     *  network-loss Reconnecting — the live TUN would keep the stale plan).
     *  Drained when the session returns to Connected. */
    private var rebuildPending = false

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        // Cache settings the callback paths need synchronously; also push the
        // doze state when the user flips the toggle while an engine is alive.
        scope.launch {
            settings.reconnectOnNetworkChange.collect { reconnectOnChange = it }
        }
        scope.launch {
            settings.dozePowerSave.collect { enabled ->
                dozePowerSave = enabled
                val pm = getSystemService(PowerManager::class.java)
                engine?.onDeviceIdle(enabled && pm.isDeviceIdleMode)
            }
        }
        // Per-app policy changes while Connected must rebuild the TUN — the
        // allowed/disallowed package lists are baked into the fd at
        // establish() time and can't be hot-swapped.
        // drop(1): the initial snapshot isn't a change. distinctUntilChanged:
        // unrelated settings writes re-emit the DataStore flow with equal
        // values. debounce: a toggle burst collapses into one rebuild.
        scope.launch {
            settings.perAppPolicy
                .drop(1)
                .distinctUntilChanged()
                .debounce(PER_APP_REBUILD_DEBOUNCE_MS)
                .collect { requestTunnelRebuild() }
        }
        // A change parked by rebuildPending (network-loss Reconnecting)
        // applies the moment the session is Connected again.
        scope.launch {
            connectionManager.state.collect { state ->
                if (rebuildPending && state is VpnConnectionState.Connected) {
                    rebuildPending = false
                    requestTunnelRebuild()
                }
            }
        }
        // Restart-guard reset: a session that survives Connected this long
        // isn't crash-looping — wipe the window so routine LMK kills are
        // counted fresh. Connected republishes every ~1s on stats ticks, so
        // dedupe on the boolean to arm the timer only on real transitions.
        var stabilityJob: Job? = null
        scope.launch {
            connectionManager.state
                .map { it is VpnConnectionState.Connected }
                .distinctUntilChanged()
                .collect { connected ->
                    stabilityJob?.cancel()
                    stabilityJob = if (connected) {
                        scope.launch {
                            delay(RESTART_GUARD_STABLE_MS)
                            try {
                                settings.resetVpnRestartAttempts()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            }
                        }
                    } else {
                        null
                    }
                }
        }
        // The foreground notification mirrors the live state machine —
        // without this it would stay on "Connecting…" forever. Rendered
        // content is deduped: Connected republishes on every ~1s stats tick.
        scope.launch {
            connectionManager.state
                .map { state ->
                    // Only update while this service instance owns a
                    // session — Idle/Error teardown paths post nothing.
                    if (activeGeneration < 0) return@map null
                    when (state) {
                        is VpnConnectionState.Connected -> NotificationSpec(
                            title = getString(R.string.notification_connected),
                            text = state.node.name,
                            showDisconnect = true,
                        )
                        is VpnConnectionState.Reconnecting -> NotificationSpec(
                            title = getString(R.string.notification_reconnecting),
                            text = state.node.name,
                            showDisconnect = true,
                        )
                        is VpnConnectionState.Connecting -> NotificationSpec(
                            title = getString(R.string.notification_connecting),
                            text = state.node.name,
                            showDisconnect = true,
                        )
                        VpnConnectionState.Stopping -> NotificationSpec(
                            title = getString(R.string.notification_disconnecting),
                            text = activeConfig?.node?.name ?: getString(R.string.app_name),
                            showDisconnect = false,
                        )
                        // e.g. disconnect delivery failed after Stopping —
                        // show the error instead of a stuck "Disconnecting…".
                        is VpnConnectionState.Error -> NotificationSpec(
                            title = getString(R.string.notification_error),
                            text = state.error.message ?: getString(R.string.app_name),
                            showDisconnect = false,
                        )
                        VpnConnectionState.Idle,
                        is VpnConnectionState.Preparing,
                        VpnConnectionState.PermissionRequired -> null
                    }
                }
                .distinctUntilChanged()
                .collect { spec ->
                    spec?.let { showNotification(it.title, it.text, it.showDisconnect) }
                }
        }

        // Screen on/off drives the engine's status channel — registered for
        // the service lifetime, forwarded only while an engine is alive.
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenReceiverRegistered = true
    }

    // The core rarely raises user-facing notifications; forwarded for
    // observability, not rendered (we own the single VPN notification).
    private val notificationSink = object : EngineNotificationSink {
        override fun send(notification: EngineNotification) {
            SecureLog.d(TAG, "core notification: ${notification.typeName}")
        }

        override fun cancel(identifier: String, typeId: Int) = Unit
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                // An explicit command supersedes any in-flight automatic
                // start — kill it so it can't stopSelf() the new session or
                // resurrect a tunnel after it.
                autoStartJob?.cancel()
                // User asked for the tunnel — remember it across process
                // death. The explicit tap also clears the restart guard: a
                // tripped guard is about *automatic* starts, not the user
                // asking again.
                scope.launch {
                    // Flag first — a failed guard reset must not drop it.
                    persistSetting { settings.setDesiredVpnRunning(true) }
                    runCatching { settings.resetVpnRestartAttempts() }
                }
                startTunnel()
                return START_STICKY
            }
            ACTION_DISCONNECT -> {
                autoStartJob?.cancel()
                scope.launch { persistSetting { settings.setDesiredVpnRunning(false) } }
                stopTunnel()
                return START_NOT_STICKY
            }
            else -> {
                // A stray system start (always-on re-start, duplicate
                // package-replaced restore) must not clobber the Connected
                // notification, consume a restart slot, or trip the guard
                // into killing a healthy tunnel. Check session ownership, not
                // just the engine: during rebuildTunnel the engine is null
                // while activeGeneration is still valid, and during teardown
                // stopRequested is set — a stray start in either window would
                // otherwise race startTunnel into cleanup()/stopSelf() or
                // resurrect the tunnel mid-stop. autoStartJob covers the
                // DataStore-read window: a second automatic start while one
                // is in flight coalesces into it instead of consuming
                // another restart-guard attempt.
                if (
                    sessionOwned(
                        activeGeneration = activeGeneration,
                        startActive = startJob?.isActive == true,
                        rebuildActive = rebuildJob?.isActive == true,
                        stopRequested = stopRequested,
                        autoStartActive = autoStartJob?.isActive == true,
                    )
                ) {
                    return START_STICKY
                }
                // FGS contract: startForegroundService gives us ~5s to call
                // startForeground — promote *before* the DataStore reads
                // below, not inside startTunnel after them.
                showNotification(
                    title = getString(R.string.notification_connecting),
                    text = getString(R.string.app_name),
                    showDisconnect = false,
                )
                // System restart (null intent), boot/update restore, or
                // always-on start: rebuild only if the user previously wanted
                // the tunnel running. A failed flag read stops the service
                // cleanly. The restart attempt is bounded — a hard/native
                // crash never reaches a catch block, so without the guard a
                // crash-on-start would loop forever:
                // crash → START_STICKY restart → startTunnel → crash → …
                //
                // The coroutine suspends on DataStore reads, so ownership is
                // re-validated after every suspension point and before
                // startTunnel()/stopSelf() — a CONNECT/DISCONNECT arriving
                // mid-flight must never see this stale work stop or
                // resurrect its session.
                autoStartJob = scope.launch {
                    val wanted = runCatching { settings.desiredVpnRunning.first() }
                        .getOrDefault(false)
                    if (!wanted) {
                        if (!autoStartLostOwnership()) stopSelf()
                        return@launch
                    }
                    // Fail-open on a DataStore read error: the guard is a
                    // safety net, a broken read must not block reconnects.
                    val allowed = runCatching { settings.registerVpnRestartAttempt() }
                        .getOrDefault(true)
                    if (autoStartLostOwnership()) return@launch
                    if (allowed) {
                        SecureLog.i(TAG, "rebuilding tunnel after service restart")
                        startTunnel()
                    } else {
                        SecureLog.w(
                            TAG,
                            "restart guard tripped — clearing desiredVpnRunning",
                        )
                        runCatching { settings.setDesiredVpnRunning(false) }
                        // Never leave a silently-unprotected device: the
                        // persisted flag shows a Home warning even when
                        // notifications are denied; the alert is the fast path.
                        runCatching { settings.setRestartGuardTripped(true) }
                        runCatching {
                            notification.postAlert(
                                title = getString(R.string.notification_restart_guard_title),
                                text = getString(R.string.notification_restart_guard_text),
                            )
                        }
                        if (!autoStartLostOwnership()) stopSelf()
                    }
                }
                return START_STICKY
            }
        }
    }

    /**
     * Re-validation for the automatic-start coroutine after a suspension
     * point: true when a newer command took ownership while it was suspended
     * (a session appeared, a start/rebuild is in flight, teardown was
     * requested, or the instance is being destroyed). The coroutine must
     * bail without calling startTunnel()/stopSelf() — those belong to the
     * new owner. `engine` is checked here (unlike [sessionOwned]) because
     * no rebuild can be in flight before a session exists.
     */
    private fun autoStartLostOwnership(): Boolean =
        destroyed || stopRequested || activeGeneration >= 0 ||
            engine != null || startJob?.isActive == true ||
            rebuildJob?.isActive == true

    /**
     * Best-effort settings write: a DataStore failure is logged, never
     * allowed to skip mandatory lifecycle callbacks or teardown.
     * Cancellation still propagates.
     */
    private suspend fun persistSetting(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SecureLog.w(TAG, "settings write failed", e)
        }
    }

    private fun startTunnel() {
        if (destroyed || engine != null || startJob?.isActive == true) {
            // Single-flight: a second start would tear down whatever the
            // in-flight coroutine just built (shared tunFd/engine fields).
            SecureLog.w(TAG, "start requested while another start is in flight")
            return
        }
        stopRequested = false
        val session = connectionManager.pendingSession
        // FGS contract: foreground notification before any suspend work.
        showNotification(
            title = getString(R.string.notification_connecting),
            text = session?.config?.node?.name ?: getString(R.string.app_name),
            showDisconnect = true,
        )
        runCatching { registerUnderlyingNetworkCallback() }
            .onFailure { SecureLog.w(TAG, "network callback registration failed") }
        startJob = scope.launch {
            // Same-process fast path uses the handed-off session; after a
            // process death the service rebuilds from persisted state itself.
            val config = session?.config
                ?: try {
                    configProvider.compileSelected()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SecureLog.w(TAG, "config rebuild failed: ${e.message}")
                    null
                }
            if (config == null) {
                try {
                    if (session != null) {
                        connectionManager.onServiceFailed(
                            VpnError.NoNodeSelected, session.generation,
                        )
                    } else {
                        // Rebuild with nothing usable — don't loop on it.
                        persistSetting { settings.setDesiredVpnRunning(false) }
                    }
                } finally {
                    cleanup()
                    stopSelf()
                }
                return@launch
            }
            if (stopRequested || destroyed) {
                connectionManager.onServiceStopped(-1L)
                cleanup()
                stopSelf()
                return@launch
            }
            val generation = session?.generation
                ?: connectionManager.adoptSession(config.node)
            if (generation < 0) {
                // A live session owns the state machine — bail quietly.
                cleanup()
                stopSelf()
                return@launch
            }
            activeGeneration = generation
            activeConfig = config
            // On adopted sessions adoptSession published Connecting before
            // the generation was set — that emission was gated out, and the
            // early post above had no session to read the node name from.
            // Re-post with the compiled config so the text is right.
            showNotification(
                title = getString(R.string.notification_connecting),
                text = config.node.name,
                showDisconnect = true,
            )
            if (!launchEngine(config, generation)) return@launch
            connectionManager.onServiceStarted(generation)
            // A network loss during start() left no further callbacks —
            // re-evaluate so we don't publish Connected while offline.
            if (lastUnderlyingNetwork == null) {
                connectionManager.onUnderlyingNetworkLost()
            }
        }
    }

    /**
     * Create + start an engine for [generation] and attach its collectors.
     * Returns true once the tunnel is up (openTun succeeded inside start());
     * false when a teardown raced us or the start failed — both already
     * converged to stopped/error here, so the caller just aborts.
     */
    private suspend fun launchEngine(config: EngineConfig, generation: Long): Boolean {
        var created: VpnEngine? = null
        try {
            created = engineFactory.create(
                context = this@ClientVpnService,
                platform = this@ClientVpnService,
                scope = scope,
                notifications = notificationSink,
            )
            if (activeGeneration != generation) {
                // A disconnect→reconnect swapped the session while the
                // factory ran — a stale engine must stop only itself and
                // never touch the new session's fields or state.
                runCatching { created.stop() }
                return false
            }
            engine = created
            connectionManager.attachEngine(created, generation)
            registerDozeReceiver()
            created.start(config)
            // A fresh engine always connects its status channel — apply the
            // current screen state so a start while the screen is off
            // doesn't churn stats nobody sees.
            if (!getSystemService(PowerManager::class.java).isInteractive) {
                created.setStatusUpdatesEnabled(false)
            }
            // start() returned → openTun succeeded inside it; tunnel is up.
            if (engine !== created) {
                // A disconnect raced us while start() was suspended. If a
                // new session owns the service now, we converge only our own
                // engine — its fields are no longer ours to clear.
                runCatching { created.stop() }
                if (activeGeneration == generation) {
                    cleanup()
                    connectionManager.onServiceStopped(generation)
                    stopSelf()
                }
                return false
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SecureLog.e(TAG, "engine start failed", e)
            if (engine === created) engine = null
            // start() may have failed after the core came up — make sure
            // the partially-started engine is actually torn down.
            if (created != null) {
                pendingTeardown.add(created)
                try {
                    runCatching { created.stop() }
                } finally {
                    pendingTeardown.remove(created)
                }
            }
            if (activeGeneration != generation) {
                // A newer session owns the service — converge only our own
                // engine; its cleanup/notification/state belong to the owner.
                return false
            }
            try {
                cleanup()
                if (stopRequested) {
                    // A disconnect raced the failure — converge to stopped,
                    // not a spurious error over the user's Idle.
                    connectionManager.onServiceStopped(generation)
                } else {
                    connectionManager.onServiceFailed(
                        if (e is dev.typenil.vpnclient.core.engine.EngineError) {
                            VpnError.fromEngine(e)
                        } else {
                            VpnError.Unexpected(e.message ?: "engine start failed")
                        },
                        generation,
                    )
                }
                stopSelf()
            } finally {
                // A start failure must not become a restart loop: clear the
                // desire flag so a STICKY restart doesn't retry forever.
                // Best-effort — a DataStore failure must not skip teardown.
                persistSetting { settings.setDesiredVpnRunning(false) }
            }
            return false
        }
    }

    /**
     * Per-app policy changed: request an in-session rebuild. Only a live
     * session rebuilds — during Connecting/Reconnecting/Stopping the change
     * is left alone because the next openTun reads the current snapshot
     * anyway. A change landing mid-rebuild (already Reconnecting) sets a
     * dirty flag for one bounded re-run — same coalescing as
     * [notifyEngineNetworkChanged].
     */
    private fun requestTunnelRebuild() {
        if (rebuildJob?.isActive == true) {
            rebuildDirty = true
            return
        }
        if (connectionManager.state.value !is VpnConnectionState.Connected) {
            // Session alive but not Connected (network-loss Reconnecting):
            // the live TUN would keep the stale plan — park the change.
            if (activeGeneration >= 0) rebuildPending = true
            return
        }
        rebuildJob = scope.launch {
            var runs = 0
            do {
                rebuildDirty = false
                rebuildTunnel()
            } while (
                rebuildDirty && ++runs < MAX_TUNNEL_REBUILDS &&
                    connectionManager.state.value is VpnConnectionState.Connected
            )
            // Bound hit or state flipped with a change still queued — hand
            // off to a fresh request so the newest policy isn't stranded.
            if (rebuildDirty) {
                rebuildJob = null
                requestTunnelRebuild()
            }
        }
    }

    /**
     * Rebuild engine + TUN inside the live session. The Builder's
     * allowed/disallowed lists are baked into the fd, so a fresh establish()
     * is the only way to apply a new plan — the generation, notification,
     * and service all survive; the UI sees a brief Reconnecting.
     */
    private suspend fun rebuildTunnel() {
        val generation = activeGeneration
        val config = activeConfig
        if (destroyed || generation < 0 || config == null) return
        connectionManager.onTunnelRebuildStarted()
        val old = engine
        engine = null
        if (old != null) {
            pendingTeardown.add(old)
            try {
                runCatching { old.stop() }
            } finally {
                pendingTeardown.remove(old)
            }
        }
        runCatching { closeTun() }
        if (activeGeneration != generation) {
            // A disconnect→reconnect swapped the session while the engine
            // was stopping — the new owner handles state/notification now;
            // converging here would tear down its live session.
            return
        }
        if (stopRequested || destroyed) {
            // A disconnect raced the rebuild — converge to stopped.
            activeGeneration = -1L
            connectionManager.onServiceStopped(generation)
            cleanup()
            stopSelf()
            return
        }
        if (!launchEngine(config, generation)) return
        connectionManager.onTunnelRebuilt(generation)
        // A rebuilt engine never saw the current Doze state — the receiver
        // only forwards transitions.
        if (dozePowerSave) {
            val pm = getSystemService(PowerManager::class.java)
            engine?.onDeviceIdle(pm.isDeviceIdleMode)
        }
        // A network loss during the rebuild left no further callbacks —
        // re-evaluate so we don't publish Connected while offline.
        if (lastUnderlyingNetwork == null) {
            connectionManager.onUnderlyingNetworkLost()
        }
    }

    private fun stopTunnel() {
        stopRequested = true
        // A suspended automatic start must not resurrect the tunnel or
        // stopSelf() past this teardown.
        autoStartJob?.cancel()
        // A queued/in-flight rebuild would converge via the generation
        // guards anyway — cancelling just skips a pointless stop→start.
        rebuildJob?.cancel()
        rebuildJob = null
        rebuildDirty = false
        rebuildPending = false
        val current = engine
        engine = null
        val generation = activeGeneration
        activeGeneration = -1L
        if (generation >= 0) {
            // The notification Disconnect action reaches here directly,
            // bypassing the Stopping state — post it ourselves so the
            // shade doesn't keep showing "Connected" through teardown.
            showNotification(
                title = getString(R.string.notification_disconnecting),
                text = activeConfig?.node?.name ?: getString(R.string.app_name),
                showDisconnect = false,
            )
        }
        if (current != null) pendingTeardown.add(current)
        scope.launch {
            // The engine leaves pendingTeardown only when stop() actually
            // returns — even past the timeout below — so onDestroy's drain
            // can't miss an engine whose stop is still in flight.
            val stopping = current?.let { eng ->
                scope.launch {
                    try {
                        runCatching { eng.stop() }
                    } finally {
                        pendingTeardown.remove(eng)
                    }
                }
            }
            // Bounded: a hung engine stop must not wedge the state machine —
            // on timeout we force cleanup and report stopped ourselves.
            val settled = withTimeoutOrNull(ENGINE_STOP_TIMEOUT_MS) {
                stopping?.join()
                true
            }
            if (settled == null) {
                SecureLog.w(TAG, "engine stop timed out — forcing teardown")
            }
            cleanup()
            // Generation-guarded: a stale teardown can't detach a newer
            // session's collectors (onServiceStopped owns the detach).
            connectionManager.onServiceStopped(generation)
            stopSelf()
        }
    }

    private fun cleanup() {
        activeConfig = null
        runCatching { closeTun() }
        unregisterUnderlyingNetworkCallback()
        unregisterDozeReceiver()
    }

    private fun registerDozeReceiver() {
        if (dozeReceiverRegistered) return
        ContextCompat.registerReceiver(
            this, dozeReceiver,
            IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        dozeReceiverRegistered = true
    }

    private fun unregisterDozeReceiver() {
        if (!dozeReceiverRegistered) return
        runCatching { unregisterReceiver(dozeReceiver) }
        dozeReceiverRegistered = false
    }

    override fun onDestroy() {
        destroyed = true
        stopRequested = true
        val current = engine
        engine = null
        // Close the fd synchronously (cheap). The Go teardown is handed to the
        // application scope — the service scope is cancelled below and
        // blocking the main thread here risks an ANR on system teardown.
        runCatching { closeTun() }
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        // Drain engines captured by teardown coroutines (stopTunnel/onRevoke)
        // that the cancelled scope can no longer finish.
        val toStop = pendingTeardown.toList() + listOfNotNull(current)
        pendingTeardown.clear()
        if (toStop.isNotEmpty()) {
            applicationScope.launch(Dispatchers.IO) {
                toStop.forEach { runCatching { it.stop() } }
            }
        }
        cleanup()
        // Safety net: if the service dies without a disconnect intent (system
        // kill, always-on teardown), the state machine must not keep claiming
        // a live tunnel. Only report a session we actually owned — a -1 report
        // here would clobber a session another instance is building.
        val generation = activeGeneration
        activeGeneration = -1L
        if (generation >= 0) connectionManager.onServiceStopped(generation)
        scope.cancel()
        super.onDestroy()
    }

    /** VPN permission revoked while running — tunnel is already dead. */
    override fun onRevoke() {
        SecureLog.w(TAG, "vpn permission revoked")
        stopRequested = true
        autoStartJob?.cancel()
        val current = engine
        engine = null
        val generation = activeGeneration
        activeGeneration = -1L
        if (current != null) pendingTeardown.add(current)
        scope.launch {
            try {
                persistSetting { settings.setDesiredVpnRunning(false) }
            } finally {
                // The manager callback, engine drain, and service stop are
                // mandatory — a failed flag write must not skip them.
                try {
                    runCatching { current?.stop() }
                } finally {
                    pendingTeardown.remove(current)
                }
                connectionManager.onServiceRevoked(generation)
                cleanup()
                stopSelf()
            }
        }
    }

    // region EnginePlatform

    override fun openTun(request: TunRequest): Int {
        if (prepare(this) != null) error("android: missing vpn permission")

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(request.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        // No allowBypass(): without it apps cannot sidestep the tunnel via
        // bindProcessToNetwork — "Proxy everything" means it. Controlled
        // split tunneling is covered by the per-app include/exclude plan
        // below, and our own core sockets use protect() regardless.

        request.inet4Addresses.forEach { builder.addAddress(it.address, it.prefix) }
        request.inet6Addresses.forEach { builder.addAddress(it.address, it.prefix) }

        if (request.autoRoute) {
            request.dnsServers.forEach { builder.addDnsServer(it) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val v4 = request.inet4Routes.ifEmpty {
                    request.inet4Addresses.map { dev.typenil.vpnclient.core.engine.CidrAddress("0.0.0.0", 0) }
                }
                v4.forEach { builder.addRoute(it.toIpPrefix()) }
                val v6 = request.inet6Routes.ifEmpty {
                    request.inet6Addresses.map { dev.typenil.vpnclient.core.engine.CidrAddress("::", 0) }
                }
                v6.forEach { builder.addRoute(it.toIpPrefix()) }
                request.inet4ExcludedRoutes.forEach { builder.excludeRoute(it.toIpPrefix()) }
                request.inet6ExcludedRoutes.forEach { builder.excludeRoute(it.toIpPrefix()) }
            } else {
                request.inet4Routes.ifEmpty { listOf(dev.typenil.vpnclient.core.engine.CidrAddress("0.0.0.0", 0)) }
                    .forEach { builder.addRoute(it.address, it.prefix) }
                // Only route v6 when the tunnel actually has a v6 address,
                // otherwise we'd blackhole IPv6 into an IPv4-only interface.
                if (request.inet6Addresses.isNotEmpty()) {
                    request.inet6Routes.ifEmpty {
                        listOf(dev.typenil.vpnclient.core.engine.CidrAddress("::", 0))
                    }.forEach { builder.addRoute(it.address, it.prefix) }
                }
                // excludeRoute() doesn't exist below API 33 — excluded routes
                // are silently ignored there.
            }

            // Builder rejects mixing allowed+disallowed calls; the resolver
            // guarantees exactly one side is populated. Our own package is
            // never allowed — its core sockets would loop back into the TUN.
            // Blocking read is fine: openTun already runs on an engine thread
            // doing binder calls.
            val (mode, packages) = runBlocking { settings.perAppPolicySnapshot() }
            val plan = resolvePerAppPlan(
                mode = mode,
                selected = packages,
                selfPackage = packageName,
                coreInclude = request.includedPackages,
                coreExclude = request.excludedPackages,
            )
            var allowedAdded = 0
            plan.allowed.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                    allowedAdded++
                } catch (e: PackageManager.NameNotFoundException) {
                    SecureLog.w(TAG, "addAllowedApplication failed for package")
                }
            }
            if (plan.allowed.isNotEmpty() && allowedAdded == 0) {
                // Every selected app failed to resolve (all uninstalled): an
                // empty allowed set means allow-all, which would route our own
                // traffic into the TUN. Disallowing self is still legal since
                // no allowed app was actually registered.
                runCatching { builder.addDisallowedApplication(packageName) }
            }
            plan.disallowed.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    SecureLog.w(TAG, "addDisallowedApplication failed for package")
                }
            }
        }

        val pfd = builder.establish()
            ?: error("android: vpn interface not established (not prepared or revoked)")
        tunFd = pfd
        return pfd.fd
    }

    override fun protectSocket(fd: Int): Boolean = protect(fd)

    override fun closeTun() {
        runCatching { tunFd?.close() }
        tunFd = null
    }

    // endregion

    private fun registerUnderlyingNetworkCallback() {
        if (underlyingCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateUnderlyingNetworks()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                updateUnderlyingNetworks()
            }

            override fun onLost(network: Network) {
                updateUnderlyingNetworks()
            }
        }
        underlyingCallback = cb
        // Deliver callbacks on the main thread: the coalescing state
        // (networkDirty/networkNotifyJob/lastUnderlyingNetwork) is touched by
        // both the callback and the main-immediate scope — confining both to
        // one thread makes the dirty-flag protocol race-free.
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                // The tunnel's own network must never fire this callback —
                // it's the underlay tracker, not a default-network probe.
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            cb,
            Handler(Looper.getMainLooper()),
        )
        updateUnderlyingNetworks()
    }

    private fun unregisterUnderlyingNetworkCallback() {
        underlyingCallback?.let {
            runCatching { connectivity.unregisterNetworkCallback(it) }
        }
        underlyingCallback = null
        lastUnderlyingNetwork = null
    }

    private fun updateUnderlyingNetworks() {
        val active = connectivity.activeNetwork
            ?.takeIf { network ->
                // Never feed the tunnel its own interface as "underlying".
                val caps = connectivity.getNetworkCapabilities(network)
                caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        if (active == lastUnderlyingNetwork) return
        lastUnderlyingNetwork = active
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                setUnderlyingNetworks(if (active != null) arrayOf(active) else null)
            }
        }
        // Feed the state machine: loss while Connected → Reconnecting;
        // a new network while Reconnecting → Connected. The UI transition is
        // always honest; the engine reset honors the user's toggle.
        if (active == null) {
            connectionManager.onUnderlyingNetworkLost()
        } else {
            connectionManager.onUnderlyingNetworkAvailable()
        }
        if (reconnectOnChange) notifyEngineNetworkChanged()
    }

    /**
     * resetNetwork() re-reads platform state at call time, so coalescing to a
     * single in-flight call is enough — changes that arrive during it are
     * covered by one bounded re-run.
     */
    private fun notifyEngineNetworkChanged() {
        if (networkNotifyJob?.isActive == true) {
            networkDirty = true
            return
        }
        networkNotifyJob = scope.launch {
            var runs = 0
            do {
                networkDirty = false
                engine?.onUnderlyingNetworkChanged()
            } while (networkDirty && ++runs < 4)
        }
    }

    private fun showNotification(
        title: String,
        text: String,
        showDisconnect: Boolean,
    ) {
        val n = notification.build(title, text, showDisconnect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                VpnNotification.NOTIFICATION_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            )
        } else {
            startForeground(VpnNotification.NOTIFICATION_ID, n)
        }
    }
}

/** What the status notification should render — dedupe key for the ~1 Hz stats republish. */
private data class NotificationSpec(
    val title: String,
    val text: String,
    val showDisconnect: Boolean,
)

@androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun dev.typenil.vpnclient.core.engine.CidrAddress.toIpPrefix(): android.net.IpPrefix =
    android.net.IpPrefix(java.net.InetAddress.getByName(address), prefix)
