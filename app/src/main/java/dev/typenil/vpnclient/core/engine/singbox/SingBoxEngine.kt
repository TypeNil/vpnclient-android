package dev.typenil.vpnclient.core.engine.singbox

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import dev.typenil.vpnclient.core.common.log.Redactor
import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.engine.CidrAddress
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private var commandClient: CommandClient? = null
    private var closing = false

    private val _stats = MutableSharedFlow<TrafficStats>(replay = 1)
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    private val _groups = MutableStateFlow<List<OutboundGroupInfo>>(emptyList())

    override val stats: Flow<TrafficStats> = _stats
    override val events: Flow<EngineEvent> = _events
    override val groups: StateFlow<List<OutboundGroupInfo>> = _groups

    override suspend fun validate(config: EngineConfig) = withContext(Dispatchers.IO) {
        try {
            Libbox.checkConfig(config.configJson)
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
            } catch (e: Exception) {
                runCatching { server.close() }
                networkMonitor.stop()
                platform.closeTun()
                commandServer = null
                throw EngineError.StartFailed(e.message ?: "sing-box start failed")
            }
            connectClient()
            _events.emit(EngineEvent.Started)
        }
    }

    private fun connectClient() {
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            addCommand(Libbox.CommandGroup)
            statusInterval = STATUS_INTERVAL_NS
        }
        val client = CommandClient(ClientHandler(), options)
        scope.launch(Dispatchers.IO) {
            runCatching { client.connect() }
                .onFailure { SecureLog.w(TAG, "command client connect failed: ${it.message}") }
        }
        commandClient = client
    }

    override suspend fun stop(): Unit = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            val server = commandServer ?: return@withContext
            // Intentional teardown: serviceStop() callbacks firing while we
            // close the server must not surface as StoppedUnexpectedly.
            closing = true
            commandServer = null
            val client = commandClient
            commandClient = null
            networkMonitor.stop()
            client?.let { runCatching { it.disconnect() } }
            runCatching { server.closeService() }
            runCatching { server.close() }
            platform.closeTun()
            _groups.value = emptyList()
            // No terminal event here — app-requested stops are reported by
            // the service lifecycle (onServiceStopped), not the engine.
        }
    }

    override suspend fun onUnderlyingNetworkChanged(): Unit = withContext(Dispatchers.IO) {
        runCatching { commandServer?.resetNetwork() }
            .onFailure { SecureLog.w(TAG, "resetNetwork failed: ${it.message}") }
        Unit
    }

    override suspend fun selectOutbound(groupTag: String, outboundTag: String): Unit =
        withContext(Dispatchers.IO) {
            runCatching { commandClient?.selectOutbound(groupTag, outboundTag) }
                .onFailure { SecureLog.w(TAG, "selectOutbound failed: ${it.message}") }
            Unit
        }

    override suspend fun urlTest(groupTag: String): Unit = withContext(Dispatchers.IO) {
        runCatching { commandClient?.urlTest(groupTag) }
            .onFailure { SecureLog.w(TAG, "urlTest failed: ${it.message}") }
        Unit
    }

    // region CommandServerHandler

    private inner class ServerHandler : CommandServerHandler {
        override fun serviceStop() {
            if (closing) return
            scope.launch { _events.emit(EngineEvent.StoppedUnexpectedly) }
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
        override fun connected() = Unit

        override fun disconnected(message: String?) {
            SecureLog.d(TAG, "command client disconnected: ${Redactor.redact(message)}")
        }

        override fun writeStatus(message: StatusMessage) {
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
            scope.launch { _events.emit(EngineEvent.GroupsUpdated(groups)) }
        }

        override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit

        override fun setDefaultLogLevel(level: Int) = Unit

        override fun clearLogs() = Unit

        override fun writeLogs(messageList: LogIterator?) = Unit

        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit

        override fun updateClashMode(newMode: String) = Unit

        override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
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
