package dev.typenil.vpnclient.core.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
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
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    /** Session generation this service instance is serving; -1 = none yet. */
    private var activeGeneration: Long = -1L
    /** A teardown was requested while a start was still in flight. */
    private var stopRequested = false
    /** Coalesced network-change notification to the engine. */
    private var networkNotifyJob: kotlinx.coroutines.Job? = null
    private var networkDirty = false

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
                // if the user previously wanted the tunnel running.
                scope.launch {
                    if (settings.desiredVpnRunning.first()) {
                        SecureLog.i(TAG, "rebuilding tunnel after service restart")
                        startTunnel()
                    } else {
                        stopSelf()
                    }
                }
                // Worst case the flag read fails and we stop cleanly.
                return START_STICKY
            }
        }
    }

    private fun startTunnel() {
        if (engine != null) {
            SecureLog.w(TAG, "start requested while engine already running")
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
        registerUnderlyingNetworkCallback()
        scope.launch {
            // Same-process fast path uses the handed-off session; after a
            // process death the service rebuilds from persisted state itself.
            val config = session?.config
                ?: runCatching { configProvider.compileSelected() }
                    .onFailure { SecureLog.w(TAG, "config rebuild failed: ${it.message}") }
                    .getOrNull()
            if (config == null) {
                connectionManager.onServiceFailed(VpnError.NoNodeSelected, -1L)
                cleanup()
                stopSelf()
                return@launch
            }
            if (stopRequested) {
                connectionManager.onServiceStopped(-1L)
                cleanup()
                stopSelf()
                return@launch
            }
            val generation = session?.generation
                ?: connectionManager.adoptSession(config.node)
            activeGeneration = generation
            showNotification(
                title = getString(R.string.notification_connecting),
                text = config.node.name,
                node = config.node,
                showDisconnect = true,
            )
            try {
                val created = engineFactory.create(
                    context = this@ClientVpnService,
                    platform = this@ClientVpnService,
                    scope = scope,
                    notifications = notificationSink,
                )
                engine = created
                connectionManager.attachEngine(created, generation)
                created.start(config)
                // start() returned → openTun succeeded inside it; tunnel is up.
                if (engine !== created || stopRequested) {
                    // A disconnect raced us while start() was suspended.
                    runCatching { created.stop() }
                    cleanup()
                    connectionManager.onServiceStopped(generation)
                    stopSelf()
                    return@launch
                }
                connectionManager.onServiceStarted(generation)
            } catch (e: Exception) {
                SecureLog.e(TAG, "engine start failed", e)
                engine = null
                cleanup()
                // A start failure must not become a restart loop: clear the
                // desire flag so a STICKY restart doesn't retry forever.
                settings.setDesiredVpnRunning(false)
                connectionManager.onServiceFailed(
                    if (e is dev.typenil.vpnclient.core.engine.EngineError) {
                        VpnError.fromEngine(e)
                    } else {
                        VpnError.Unexpected(e.message ?: "engine start failed")
                    },
                    generation,
                )
                stopSelf()
            }
        }
    }

    private fun stopTunnel() {
        stopRequested = true
        val current = engine
        engine = null
        val generation = activeGeneration
        activeGeneration = -1L
        scope.launch {
            runCatching { current?.stop() }
            connectionManager.detachEngine()
            cleanup()
            connectionManager.onServiceStopped(generation)
            stopSelf()
        }
    }

    private fun cleanup() {
        runCatching { closeTun() }
        unregisterUnderlyingNetworkCallback()
    }

    override fun onDestroy() {
        stopRequested = true
        val current = engine
        engine = null
        // Close the fd synchronously (cheap). The Go teardown is handed to the
        // application scope — the service scope is cancelled below and
        // blocking the main thread here risks an ANR on system teardown.
        runCatching { closeTun() }
        if (current != null) {
            applicationScope.launch(Dispatchers.IO) {
                runCatching { current.stop() }
            }
        }
        cleanup()
        // Safety net: if the service dies without a disconnect intent (system
        // kill, always-on teardown), the state machine must not keep claiming
        // a live tunnel. Idempotent with the normal stopTunnel report.
        connectionManager.onServiceStopped(activeGeneration)
        activeGeneration = -1L
        scope.cancel()
        super.onDestroy()
    }

    /** VPN permission revoked while running — tunnel is already dead. */
    override fun onRevoke() {
        SecureLog.w(TAG, "vpn permission revoked")
        stopRequested = true
        val current = engine
        engine = null
        scope.launch {
            settings.setDesiredVpnRunning(false)
            runCatching { current?.stop() }
            connectionManager.onServiceRevoked()
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

            request.includedPackages.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    SecureLog.w(TAG, "addAllowedApplication failed for package")
                }
            }
            request.excludedPackages.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    SecureLog.w(TAG, "addDisallowedApplication failed for package")
                }
            }

            // Our own process hosts the proxy core: without this, its outbound
            // sockets would route back into the TUN and loop into themselves
            // (same as sing-box-for-android excluding its own package).
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                SecureLog.w(TAG, "addDisallowedApplication failed for own package")
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
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            cb,
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
        // a new network while Reconnecting → Connected.
        if (active == null) {
            connectionManager.onUnderlyingNetworkLost()
        } else {
            connectionManager.onUnderlyingNetworkAvailable()
        }
        notifyEngineNetworkChanged()
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
