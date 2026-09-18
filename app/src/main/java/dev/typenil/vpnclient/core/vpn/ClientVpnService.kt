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
import dev.typenil.vpnclient.core.engine.EnginePlatform
import dev.typenil.vpnclient.core.engine.TunRequest
import dev.typenil.vpnclient.core.engine.VpnEngine
import dev.typenil.vpnclient.core.engine.singbox.SingBoxEngine
import io.nekohasekai.libbox.Notification
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The app's [VpnService]. Owns the TUN fd lifecycle and the foreground
 * notification; drives the engine through [VpnEngine].
 *
 * Commands arrive as start-intents; state is reported upward through the
 * injected [ConnectionManager] (same-process singleton).
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notification = VpnNotification(this)
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    private var engine: VpnEngine? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var underlyingCallback: ConnectivityManager.NetworkCallback? = null

    private val notificationSink = object : SingBoxEngine.NotificationSink {
        override fun send(notification: Notification) = Unit
        override fun cancel(identifier: String, typeId: Int) = Unit
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> startTunnel()
            ACTION_DISCONNECT -> stopTunnel()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        val config = connectionManager.pendingConfig
        if (config == null) {
            SecureLog.e(TAG, "start requested with no pending config")
            connectionManager.onServiceFailed(VpnError.NoNodeSelected)
            stopSelf()
            return
        }
        showNotification(
            title = getString(R.string.notification_connecting),
            text = config.node.name,
            node = config.node,
            showDisconnect = true,
        )
        registerUnderlyingNetworkCallback()
        scope.launch {
            try {
                val created = SingBoxEngine(
                    context = this@ClientVpnService,
                    platform = this@ClientVpnService,
                    scope = scope,
                    notificationSink = notificationSink,
                )
                engine = created
                connectionManager.attachEngine(created)
                created.start(config)
                // start() returned → openTun succeeded inside it; tunnel is up.
                connectionManager.onServiceStarted()
            } catch (e: Exception) {
                SecureLog.e(TAG, "engine start failed", e)
                cleanup()
                connectionManager.onServiceFailed(
                    if (e is dev.typenil.vpnclient.core.engine.EngineError) {
                        VpnError.fromEngine(e)
                    } else {
                        VpnError.Unexpected(e.message ?: "engine start failed")
                    },
                )
                stopSelf()
            }
        }
    }

    private fun stopTunnel() {
        val current = engine
        engine = null
        scope.launch {
            runCatching { current?.stop() }
            connectionManager.detachEngine()
            cleanup()
            connectionManager.onServiceStopped()
            stopSelf()
        }
    }

    private fun cleanup() {
        runCatching { closeTun() }
        unregisterUnderlyingNetworkCallback()
    }

    override fun onDestroy() {
        if (engine != null) {
            val current = engine
            engine = null
            // Engine stop is suspend-based; fire and rely on closeTun for fd.
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                runCatching { current?.stop() }
            }
        }
        cleanup()
        scope.cancel()
        super.onDestroy()
    }

    /** VPN permission revoked while running — tunnel is already dead. */
    override fun onRevoke() {
        SecureLog.w(TAG, "vpn permission revoked")
        val current = engine
        engine = null
        scope.launch {
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
                request.inet6Routes.ifEmpty { listOf(dev.typenil.vpnclient.core.engine.CidrAddress("::", 0)) }
                    .forEach { builder.addRoute(it.address, it.prefix) }
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
    }

    private fun updateUnderlyingNetworks() {
        val active = connectivity.activeNetwork ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { setUnderlyingNetworks(arrayOf(active)) }
        }
        scope.launch { engine?.onUnderlyingNetworkChanged() }
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
