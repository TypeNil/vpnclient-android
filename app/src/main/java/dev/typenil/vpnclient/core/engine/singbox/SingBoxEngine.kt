package dev.typenil.vpnclient.core.engine.singbox

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import dev.typenil.vpnclient.core.common.log.Redactor
import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.engine.CidrAddress
import dev.typenil.vpnclient.core.engine.ConnectionInfo
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.EngineEvent
import dev.typenil.vpnclient.core.engine.EngineNotification
import dev.typenil.vpnclient.core.engine.EngineNotificationSink
import dev.typenil.vpnclient.core.engine.EnginePlatform
import dev.typenil.vpnclient.core.engine.OutboundGroupInfo
import dev.typenil.vpnclient.core.engine.OutboundItemInfo
import dev.typenil.vpnclient.core.engine.TrafficStats
import dev.typenil.vpnclient.core.engine.TunRequest
import dev.typenil.vpnclient.core.engine.VpnEngine
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.Connections
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InetSocketAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * sing-box (libbox) adapter behind [VpnEngine].
 *
 * Uses the CommandServer + in-process CommandClient model (same as
 * sing-box-for-android): nothing listens on TCP, control stays in-process.
 *
 * TUN fd provisioning flows back through [EnginePlatform] (implemented by the
 * VpnService) when the core calls [PlatformInterface.openTun].
 */
class SingBoxEngine(
    private val context: Context,
    private val platform: EnginePlatform,
    private val scope: CoroutineScope,
    private val notifications: EngineNotificationSink,
) : VpnEngine {

    private val lifecycleMutex = Mutex()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networkMonitor = NetworkMonitor(connectivity, scope)
    private val localDnsResolver = LocalDnsResolver(networkMonitor)

    private var commandServer: CommandServer? = null

    /** Published under [clientMutex]; read lock-free on IO threads by the
     *  control calls (selectOutbound/urlTest/closeConnection). */
    @Volatile
    private var commandClient: CommandClient? = null

    /**
     * Client-side connection tracker fed by CommandConnections events.
     * Not thread-safe on the Go side — every access goes through the
     * `synchronized` block in `writeConnectionEvents`. Recreated per start()
     * so a session never sees a previous tunnel's connections.
     * Written under lifecycleMutex on IO, read by libbox's callback on a
     * binder thread — volatile for the same reason as [closing].
     */
    @Volatile
    private var connectionsTracker: Connections? = null

    /** Written on IO under lifecycleMutex, read by libbox's serviceStop()
     *  callback on a binder thread — must be volatile to be visible. */
    @Volatile
    private var closing = false

    private val _stats = MutableSharedFlow<TrafficStats>(replay = 1)
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    private val _groups = MutableStateFlow<List<OutboundGroupInfo>>(emptyList())
    private val _connections = MutableStateFlow<List<ConnectionInfo>>(emptyList())

    override val stats: Flow<TrafficStats> = _stats
    override val events: Flow<EngineEvent> = _events
    override val groups: StateFlow<List<OutboundGroupInfo>> = _groups
    override val connections: StateFlow<List<ConnectionInfo>> = _connections

    override suspend fun validate(config: EngineConfig) = withContext(Dispatchers.IO) {
        try {
            Libbox.checkConfig(config.configJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EngineError.InvalidConfig(e.message ?: "invalid config")
        }
    }

    override suspend fun start(config: EngineConfig): Unit = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            val server = try {
                CommandServer(ServerHandler(), PlatformBridge()).also { it.start() }
            } catch (e: Exception) {
                throw EngineError.StartFailed(e.message ?: "command server start failed")
            }
            commandServer = server
            try {
                closing = false
                networkMonitor.start()
                server.startOrReloadService(config.configJson, OverrideOptions())
            } catch (e: CancellationException) {
                // Still tear down what we built — cancellation is not an
                // excuse to leak a half-started CommandServer.
                runCatching { server.close() }
                networkMonitor.stop()
                platform.closeTun()
                commandServer = null
                throw e
            } catch (e: Exception) {
                runCatching { server.close() }
                networkMonitor.stop()
                platform.closeTun()
                commandServer = null
                throw EngineError.StartFailed(e.message ?: "sing-box start failed")
            }
            // Fresh tracker + cleared surface per session — a new tunnel
            // must never replay the previous one's connections.
            connectionsTracker = Connections().apply {
                filterState(Libbox.ConnectionStateActive.toInt())
            }
            _connections.value = emptyList()
            // Invalidate anything a previous session left in flight, then
            // start a fresh client — a restart must not inherit a channel
            // bound to the old CommandServer.
            val staleClient = clientMutex.withLock {
                updatesWanted = true
                val stale = invalidateClientLocked()
                connectClientLocked()
                stale
            }
            staleClient?.let { runCatching { it.disconnect() } }
            _events.emit(EngineEvent.Started)
        }
    }

    /**
     * CommandClient lifecycle is serialized on [clientMutex]: [clientEpoch]
     * is bumped on every disable/stop so a connect() that returns after its
     * job was cancelled can never publish — the stale client is disconnected
     * instead. [updatesWanted] is the screen-off suppression flag.
     */
    private val clientMutex = Mutex()
    private var clientEpoch = 0L
    private var updatesWanted = false
    private var clientJob: Job? = null

    /** Caller must hold [clientMutex]. Returns the dropped client so the
     *  caller can disconnect it outside the lock. */
    private fun invalidateClientLocked(): CommandClient? {
        clientEpoch++
        clientJob?.cancel()
        clientJob = null
        val client = commandClient
        commandClient = null
        return client
    }

    /** Caller must hold [clientMutex]. Idempotent: a live client or an
     *  in-flight connect loop is left alone. */
    private fun connectClientLocked() {
        if (!updatesWanted || closing || commandServer == null) return
        // A live client or an in-flight retry loop — don't stack a second.
        if (commandClient != null || clientJob?.isActive == true) return
        val epoch = clientEpoch
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            addCommand(Libbox.CommandGroup)
            addCommand(Libbox.CommandConnections)
            statusInterval = STATUS_INTERVAL_NS
        }
        clientJob = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive && !closing) {
                attempt++
                // Fresh client per attempt — a failed connect() can leave the
                // instance in a state libbox won't recover.
                val handler = ClientHandler()
                val client = CommandClient(handler, options)
                handler.client = client
                try {
                    client.connect()
                    // connect() may outlive a cancel/disable — publish only
                    // if this epoch is still current, updates are still
                    // wanted, the server is alive, and the channel didn't
                    // die while we were blocked in connect().
                    val publish = try {
                        clientMutex.withLock {
                            if (epoch == clientEpoch && updatesWanted && !closing &&
                                commandServer != null && !handler.dropped
                            ) {
                                commandClient = client
                                true
                            } else {
                                // The channel died before it could publish —
                                // reschedule instead of dropping the event.
                                if (handler.dropped && epoch == clientEpoch &&
                                    updatesWanted && !closing && commandServer != null
                                ) {
                                    clientJob = null
                                    connectClientLocked()
                                }
                                false
                            }
                        }
                    } catch (e: CancellationException) {
                        runCatching { client.disconnect() }
                        throw e
                    }
                    if (!publish) runCatching { client.disconnect() }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (attempt >= COMMAND_CONNECT_MAX_ATTEMPTS) {
                        // Bounded retry: the tunnel works without the control
                        // channel (stats/selection degrade), so this stays
                        // non-fatal — but it must be observable.
                        SecureLog.w(
                            TAG,
                            "command client connect failed after " +
                                "$attempt attempts: ${e.message}",
                        )
                        return@launch
                    }
                    SecureLog.d(TAG, "command client connect retry $attempt")
                    delay(COMMAND_CONNECT_DELAY_MS * attempt)
                }
            }
        }
    }

    /**
     * Screen-off suppression (SFA pattern): disconnecting the client stops
     * the core's status pushes at the source — no 1 Hz stats churn while
     * nobody is looking. Control calls (selectOutbound/urlTest) fail while
     * the channel is down — fine, they're UI-driven and the screen is off.
     */
    override suspend fun setStatusUpdatesEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            SecureLog.d(TAG, "status updates enabled=$enabled")
            val stale = clientMutex.withLock {
                updatesWanted = enabled
                if (enabled) {
                    connectClientLocked()
                    null
                } else {
                    invalidateClientLocked()
                }
            }
            stale?.let { runCatching { it.disconnect() } }
        }
    }

    override suspend fun stop(): Unit = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            val server = commandServer ?: return@withContext
            // Intentional teardown: serviceStop() callbacks firing while we
            // close the server must not surface as StoppedUnexpectedly.
            closing = true
            commandServer = null
            val client = clientMutex.withLock {
                updatesWanted = false
                invalidateClientLocked()
            }
            networkMonitor.stop()
            client?.let { runCatching { it.disconnect() } }
            runCatching { server.closeService() }
            runCatching { server.close() }
            platform.closeTun()
            connectionsTracker = null
            _groups.value = emptyList()
            _connections.value = emptyList()
            // No terminal event here — app-requested stops are reported by
            // the service lifecycle (onServiceStopped), not the engine.
        }
    }

    override suspend fun onUnderlyingNetworkChanged(): Unit = withContext(Dispatchers.IO) {
        runCatching { commandServer?.resetNetwork() }
            .onFailure { SecureLog.w(TAG, "resetNetwork failed: ${it.message}") }
        Unit
    }

    /**
     * Doze: `pause()` stops the core's background timers — it also drops open
     * TCP connections, so callers only opt in via the Doze power-save setting.
     */
    override suspend fun onDeviceIdle(idle: Boolean): Unit = withContext(Dispatchers.IO) {
        runCatching { if (idle) commandServer?.pause() else commandServer?.wake() }
            .onFailure { SecureLog.w(TAG, "pause/wake failed: ${it.message}") }
        Unit
    }

    override suspend fun selectOutbound(groupTag: String, outboundTag: String): Boolean =
        withContext(Dispatchers.IO) {
            val client = commandClient ?: return@withContext false
            runCatching { client.selectOutbound(groupTag, outboundTag); true }
                .onFailure { SecureLog.w(TAG, "selectOutbound failed: ${it.message}") }
                .getOrDefault(false)
        }

    override suspend fun urlTest(groupTag: String): Unit = withContext(Dispatchers.IO) {
        runCatching { commandClient?.urlTest(groupTag) }
            .onFailure { SecureLog.w(TAG, "urlTest failed: ${it.message}") }
        Unit
    }

    override suspend fun closeConnection(id: String): Boolean =
        withContext(Dispatchers.IO) {
            val client = commandClient ?: return@withContext false
            // The id is an opaque tracker key — never log connection payloads.
            runCatching { client.closeConnection(id); true }
                .onFailure { SecureLog.w(TAG, "closeConnection failed: ${it.message}") }
                .getOrDefault(false)
        }

    // region CommandServerHandler

    private inner class ServerHandler : CommandServerHandler {
        override fun serviceStop() {
            // closing is @Volatile — read on a binder thread, written under
            // lifecycleMutex. Re-check inside the coroutine to close the
            // check-then-launch gap.
            if (closing) return
            scope.launch {
                if (!closing) _events.emit(EngineEvent.StoppedUnexpectedly)
            }
        }

        override fun serviceReload() {
            // Not supported in this app: profile content is owned by repositories.
        }

        override fun getSystemProxyStatus(): SystemProxyStatus? = null

        override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

        override fun triggerNativeCrash() = Unit

        override fun writeDebugMessage(message: String?) {
            SecureLog.d(TAG, Redactor.redact(message ?: ""))
        }

        override fun connectSSHAgent(): Int = -1
    }

    // endregion

    // region CommandClientHandler

    private inner class ClientHandler : CommandClientHandler {
        /** The client this handler is bound to — set before connect() so
         *  callbacks can be matched against the published client. Read on
         *  libbox binder threads. */
        @Volatile
        var client: CommandClient? = null

        /** Set when the channel drops — a client that ever disconnected is
         *  dead to us even if connect() returns afterwards. */
        @Volatile
        var dropped = false

        override fun connected() = Unit

        override fun disconnected(message: String?) {
            SecureLog.d(TAG, "command client disconnected: ${Redactor.redact(message)}")
            dropped = true
            val dead = client ?: return
            // Binder thread — the client state is mutex-guarded, so the
            // identity check and the reconnect scheduling happen in a
            // coroutine. Callbacks from stale clients (already replaced or
            // deliberately dropped) are ignored.
            scope.launch {
                clientMutex.withLock {
                    if (commandClient !== dead) return@withLock
                    commandClient = null
                    connectClientLocked()
                }
            }
        }

        override fun writeStatus(message: StatusMessage) {
            // Counters are meaningless until the core says they're
            // available — emitting them anyway shows fake "0 B/s" on Home.
            if (!message.trafficAvailable) return
            _stats.tryEmit(
                TrafficStats(
                    uplinkBytesPerSec = message.uplink,
                    downlinkBytesPerSec = message.downlink,
                    uplinkTotalBytes = message.uplinkTotal,
                    downlinkTotalBytes = message.downlinkTotal,
                    connectionsIn = message.connectionsIn,
                    connectionsOut = message.connectionsOut,
                    goroutines = message.goroutines,
                    memoryBytes = message.memory,
                ),
            )
        }

        override fun writeGroups(message: OutboundGroupIterator?) {
            if (message == null) return
            val groups = mutableListOf<OutboundGroupInfo>()
            while (message.hasNext()) {
                val group = message.next()
                val items = mutableListOf<OutboundItemInfo>()
                val iter = group.items
                while (iter != null && iter.hasNext()) {
                    val item = iter.next()
                    items.add(
                        OutboundItemInfo(
                            tag = item.tag,
                            type = item.type,
                            urlTestDelayMs = item.urlTestDelay.takeIf { it > 0 },
                        ),
                    )
                }
                groups.add(
                    OutboundGroupInfo(
                        tag = group.tag,
                        type = group.type,
                        selectable = group.selectable,
                        selected = group.selected,
                        items = items,
                    ),
                )
            }
            _groups.value = groups
        }

        override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit

        override fun setDefaultLogLevel(level: Int) = Unit

        override fun clearLogs() = Unit

        override fun writeLogs(messageList: LogIterator?) = Unit

        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit

        override fun updateClashMode(newMode: String) = Unit

        /**
         * Pushed ~1/s by the CommandConnections subscription on a binder
         * thread. The libbox tracker is not internally synchronized, so all
         * tracker work stays inside one monitor; the iterator's Go proxies
         * are mapped to plain [ConnectionInfo] before they leave it.
         *
         * Nothing here is logged — destinations/domains are user traffic.
         */
        override fun writeConnectionEvents(events: ConnectionEvents?) {
            val tracker = connectionsTracker ?: return
            if (events == null) return
            val snapshot = synchronized(tracker) {
                tracker.applyEvents(events)
                // sortByDate re-sorts `filtered` in place — ApplyEvents
                // rebuilds it, so the sort must be re-applied every push.
                tracker.sortByDate()
                val list = mutableListOf<ConnectionInfo>()
                val iter = tracker.iterator()
                // A busy tunnel can hold thousands of connections — cap the
                // snapshot (newest first after sortByDate) so per-push
                // mapping work stays bounded.
                while (iter.hasNext() && list.size < MAX_CONNECTION_SNAPSHOT) {
                    list.add(iter.next().toConnectionInfo())
                }
                list
            }
            // stop() may have nulled the tracker while the snapshot was
            // being built — publishing now would leak it past the clear.
            if (tracker === connectionsTracker) {
                _connections.value = snapshot
            }
        }
    }

    // endregion

    // region PlatformInterface

    private inner class PlatformBridge : PlatformInterface {

        override fun openTun(options: TunOptions): Int {
            val request = TunRequest(
                mtu = options.mtu,
                inet4Addresses = options.inet4Address.toCidrList(),
                inet6Addresses = options.inet6Address.toCidrList(),
                dnsServers = options.dnsServerAddress.toStringList(),
                inet4Routes = options.inet4RouteAddress.toCidrList()
                    .ifEmpty { options.inet4RouteRange.toCidrList() },
                inet6Routes = options.inet6RouteAddress.toCidrList()
                    .ifEmpty { options.inet6RouteRange.toCidrList() },
                inet4ExcludedRoutes = options.inet4RouteExcludeAddress.toCidrList(),
                inet6ExcludedRoutes = options.inet6RouteExcludeAddress.toCidrList(),
                autoRoute = options.autoRoute,
                includedPackages = options.includePackage.toStringList(),
                excludedPackages = options.excludePackage.toStringList(),
            )
            return platform.openTun(request)
        }

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

        override fun autoDetectInterfaceControl(fd: Int) {
            platform.protectSocket(fd)
        }

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            networkMonitor.setListener(listener)
        }

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            networkMonitor.setListener(null)
        }

        override fun getInterfaces() = networkMonitor.getInterfaces()

        override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String,
            sourcePort: Int,
            destinationAddress: String,
            destinationPort: Int,
        ): ConnectionOwner {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                error("connection owner lookup requires API 29+")
            }
            val uid = connectivity.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort),
            )
            if (uid == Process.INVALID_UID) error("connection owner not found")
            val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
            return ConnectionOwner().apply {
                userId = uid
                userName = packages.firstOrNull() ?: ""
                setAndroidPackageNames(NetworkMonitor.StringArray(packages.iterator()))
            }
        }

        override fun localDNSTransport(): LocalDNSTransport = localDnsResolver

        override fun underNetworkExtension(): Boolean = false

        override fun includeAllNetworks(): Boolean = false

        override fun clearDNSCache() = Unit

        override fun readWIFIState(): WIFIState? = null

        override fun sendNotification(notification: Notification) {
            notifications.send(
                EngineNotification(
                    identifier = notification.identifier.orEmpty(),
                    typeName = notification.typeName.orEmpty(),
                    typeId = notification.typeID,
                    title = notification.title.orEmpty(),
                    subtitle = notification.subtitle.orEmpty(),
                    body = notification.body.orEmpty(),
                    openUrl = notification.openURL,
                ),
            )
        }

        override fun cancelNotification(identifier: String, typeID: Int) {
            notifications.cancel(identifier, typeID)
        }

        override fun registerMyInterface(name: String?) = Unit

        // Features we do not implement (root/shell/bridge/tailscale/sftp).
        override fun usePlatformBridge(): Boolean = false
        override fun createBridge(options: BridgeOptions?): BridgeSession = error("not supported")
        override fun usePlatformShell(): Boolean = false
        override fun checkPlatformShell() = error("not supported")
        override fun openShellSession(
            user: PlatformUser?,
            command: String?,
            environ: StringIterator?,
            term: String?,
            rows: Int,
            cols: Int,
        ): ShellSession = error("not supported")

        override fun lookupUser(username: String?): PlatformUser = error("not supported")
        override fun lookupSFTPServer(): String = error("not supported")
        override fun readSystemSSHHostKey(): String = error("not supported")
        override fun tailscaleHostname(): String = error("not supported")
        override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
        override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    }

    // endregion

    companion object {
        private const val TAG = "SingBoxEngine"
        private const val STATUS_INTERVAL_NS = 1_000_000_000L
        private const val MAX_CONNECTION_SNAPSHOT = 1_000
        private const val COMMAND_CONNECT_MAX_ATTEMPTS = 3
        private const val COMMAND_CONNECT_DELAY_MS = 300L
    }
}

private fun io.nekohasekai.libbox.RoutePrefixIterator?.toCidrList(): List<CidrAddress> {
    if (this == null) return emptyList()
    val out = mutableListOf<CidrAddress>()
    while (hasNext()) {
        val prefix = next()
        out.add(CidrAddress(prefix.address(), prefix.prefix()))
    }
    return out
}

private fun StringIterator?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = mutableListOf<String>()
    while (hasNext()) out.add(next())
    return out
}

/** Copy every field out of the Go proxy — libbox `Connection` objects must
 *  not escape the tracker monitor or the engine package. */
private fun io.nekohasekai.libbox.Connection.toConnectionInfo() = ConnectionInfo(
    id = id,
    destination = displayDestination().orEmpty(),
    domain = domain.orEmpty(),
    protocol = protocol.orEmpty(),
    network = network.orEmpty(),
    outbound = outbound.orEmpty(),
    packages = processInfo?.packageNames().toStringList(),
    uplinkTotalBytes = uplinkTotal,
    downlinkTotalBytes = downlinkTotal,
    createdAtMs = createdAt,
)
