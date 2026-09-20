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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
        /** Quiet window before a per-app change triggers a TUN rebuild —
         *  rapid toggle bursts collapse into a single rebuild. */
        private const val PER_APP_REBUILD_DEBOUNCE_MS = 800L
        /** Bounded re-runs for changes landing mid-rebuild (dirty flag). */
        private const val MAX_TUNNEL_REBUILDS = 4

        fun connectIntent(context: Context): Intent =
            Intent(context, ClientVpnService::class.java).setAction(ACTION_CONNECT)

        fun disconnectIntent(context: Context): Intent =
            Intent(context, ClientVpnService::class.java).setAction(ACTION_DISCONNECT)
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
    /** Coalesced network-change notification to the engine. Main-thread only —
     *  the ConnectivityManager callback is registered on the main Handler. */
    private var networkNotifyJob: Job? = null
    private var networkDirty = false
    /** In-session tunnel rebuild (per-app policy change). Same coalescing
     *  protocol as the network-change path — main-thread confined. */
    private var rebuildJob: Job? = null
    private var rebuildDirty = false

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
                // User asked for the tunnel — remember it across process death.
                scope.launch { settings.setDesiredVpnRunning(true) }
                startTunnel()
                return START_STICKY
            }
            ACTION_DISCONNECT -> {
                scope.launch { settings.setDesiredVpnRunning(false) }
                stopTunnel()
                return START_NOT_STICKY
            }
            else -> {
                // System restart (null intent) or always-on boot: rebuild only
                // if the user previously wanted the tunnel running. A failed
                // flag read stops the service cleanly.
                scope.launch {
                    val wanted = runCatching { settings.desiredVpnRunning.first() }
                        .getOrDefault(false)
                    if (wanted) {
                        SecureLog.i(TAG, "rebuilding tunnel after service restart")
                        startTunnel()
                    } else {
                        stopSelf()
                    }
                }
                return START_STICKY
            }
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
            node = session?.config?.node,
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
                if (session != null) {
                    connectionManager.onServiceFailed(
                        VpnError.NoNodeSelected, session.generation,
                    )
                } else {
                    // Rebuild with nothing usable — don't loop on it.
                    settings.setDesiredVpnRunning(false)
                }
                cleanup()
                stopSelf()
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
            showNotification(
                title = getString(R.string.notification_connecting),
                text = config.node.name,
                node = config.node,
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
            engine = created
            connectionManager.attachEngine(created, generation)
            registerDozeReceiver()
            created.start(config)
            // start() returned → openTun succeeded inside it; tunnel is up.
            if (engine !== created || stopRequested) {
                // A disconnect raced us while start() was suspended.
                runCatching { created.stop() }
                cleanup()
                connectionManager.onServiceStopped(generation)
                stopSelf()
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
            cleanup()
            // A start failure must not become a restart loop: clear the
            // desire flag so a STICKY restart doesn't retry forever.
            settings.setDesiredVpnRunning(false)
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
        if (connectionManager.state.value !is VpnConnectionState.Connected) return
        rebuildJob = scope.launch {
            var runs = 0
            do {
                rebuildDirty = false
                rebuildTunnel()
            } while (rebuildDirty && ++runs < MAX_TUNNEL_REBUILDS)
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
        // A network loss during the rebuild left no further callbacks —
        // re-evaluate so we don't publish Connected while offline.
        if (lastUnderlyingNetwork == null) {
            connectionManager.onUnderlyingNetworkLost()
        }
    }

    private fun stopTunnel() {
        stopRequested = true
        val current = engine
        engine = null
        val generation = activeGeneration
        activeGeneration = -1L
        if (current != null) pendingTeardown.add(current)
        scope.launch {
            try {
                runCatching { current?.stop() }
            } finally {
                pendingTeardown.remove(current)
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
        val current = engine
        engine = null
        val generation = activeGeneration
        activeGeneration = -1L
        if (current != null) pendingTeardown.add(current)
        scope.launch {
            settings.setDesiredVpnRunning(false)
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

    // region EnginePlatform

    override fun openTun(request: TunRequest): Int {
        if (prepare(this) != null) error("android: missing vpn permission")

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(request.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        builder.allowBypass()

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
        node: dev.typenil.vpnclient.core.subscription.model.NodeSummary?,
        showDisconnect: Boolean,
    ) {
        val n = notification.build(title, text, node, showDisconnect)
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

@androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun dev.typenil.vpnclient.core.engine.CidrAddress.toIpPrefix(): android.net.IpPrefix =
    android.net.IpPrefix(java.net.InetAddress.getByName(address), prefix)
