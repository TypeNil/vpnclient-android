package dev.typenil.vpnclient.core.vpn

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import dev.typenil.vpnclient.core.engine.CidrAddress
import dev.typenil.vpnclient.core.engine.ConnectionInfo
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.EngineEvent
import dev.typenil.vpnclient.core.engine.EngineNotificationSink
import dev.typenil.vpnclient.core.engine.EnginePlatform
import dev.typenil.vpnclient.core.engine.OutboundGroupInfo
import dev.typenil.vpnclient.core.engine.TrafficStats
import dev.typenil.vpnclient.core.engine.TunRequest
import dev.typenil.vpnclient.core.engine.VpnEngine
import dev.typenil.vpnclient.core.engine.VpnEngineFactory
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Instrumented-test doubles for the VPN path.
 *
 * - [FakeVpnEngine] implements [VpnEngine] without libbox, a real TUN fd, or
 *   an upstream server. Its [start] drives [EnginePlatform.openTun] through
 *   the real service exactly like [SingBoxEngine] does, then blocks on a
 *   release latch so tests can control when "the tunnel is up" lands.
 * - [FakeVpnEngineFactory] hands out scripted engines and records `create`
 *   calls so tests can assert engine lifecycle ordering.
 * - [FakeTunProvider] replaces the consent-gated `VpnService.prepare` /
 *   `Builder.establish` pair: `prepare` always reports granted and
 *   `establish` returns a real fd from `ParcelFileDescriptor.createPipe()`
 *   (a genuine, closable fd — no VPN permission required).
 */

/** A no-network, controllable [VpnEngine]. */
class FakeVpnEngine(
    /** When set, [start] suspends until [CompletableDeferred.complete] is
     *  called — lets a test hold the engine in "starting" while it inspects
     *  the Connecting state, then release to let Connected publish. */
    var startLatch: CompletableDeferred<Unit>? = null,
    /** When set, [start] throws this instead of opening the TUN — used to
     *  exercise the engine-failure path. */
    var startFailure: EngineError? = null,
) : VpnEngine {
    val statsFlow = MutableSharedFlow<TrafficStats>(replay = 1)
    val eventsFlow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
    val groupsFlow = MutableStateFlow<List<OutboundGroupInfo>>(emptyList())
    val connectionsFlow = MutableStateFlow<List<ConnectionInfo>>(emptyList())

    override val stats: Flow<TrafficStats> get() = statsFlow
    override val events: Flow<EngineEvent> get() = eventsFlow
    override val groups: StateFlow<List<OutboundGroupInfo>> get() = groupsFlow
    override val connections: StateFlow<List<ConnectionInfo>> get() = connectionsFlow

    val startCalls = AtomicInteger(0)
    val stopCalls = AtomicInteger(0)
    val openTunCalls = AtomicInteger(0)
    val networkChangedCalls = AtomicInteger(0)
    val statusEnabledCalls = AtomicInteger(0)
    val deviceIdleCalls = AtomicInteger(0)

    /** The fd the service handed us through openTun (for assertions). */
    @Volatile
    var lastTunFd: Int = -1
        private set

    @Volatile
    var started = false
        private set

    private val stopMutex = Mutex()

    override suspend fun validate(config: EngineConfig) = Unit

    override suspend fun start(config: EngineConfig) {
        startCalls.incrementAndGet()
        startLatch?.await()
        startFailure?.let { throw it }
        // Drive the platform exactly like the real engine: open a TUN fd
        // through the service before declaring start complete.
        val fd = platform!!.openTun(tunRequest())
        openTunCalls.incrementAndGet()
        lastTunFd = fd
        started = true
    }

    override suspend fun stop() {
        stopMutex.withLock {
            stopCalls.incrementAndGet()
            started = false
            platform?.closeTun()
        }
    }

    override suspend fun onUnderlyingNetworkChanged() {
        networkChangedCalls.incrementAndGet()
    }

    override suspend fun onDeviceIdle(idle: Boolean) {
        deviceIdleCalls.incrementAndGet()
    }

    override suspend fun setStatusUpdatesEnabled(enabled: Boolean) {
        statusEnabledCalls.incrementAndGet()
    }

    override suspend fun selectOutbound(
        groupTag: String,
        outboundTag: String,
    ): Boolean = true

    override suspend fun urlTest(groupTag: String) = Unit

    override suspend fun closeConnection(id: String): Boolean = true

    /** Emit an engine lifecycle event to the service's collector. */
    suspend fun emitEvent(event: EngineEvent) {
        eventsFlow.emit(event)
    }

    /** Push a stats tick into a Connected session. */
    suspend fun emitStats(stats: TrafficStats) {
        statsFlow.emit(stats)
    }

    /** Set the outbound groups the manager sees. */
    fun setGroups(groups: List<OutboundGroupInfo>) {
        groupsFlow.value = groups
    }

    /** Bound platform supplied by the factory at create time. */
    @Volatile
    var platform: EnginePlatform? = null

    private fun tunRequest(): TunRequest =
        TunRequest(
            mtu = 9000,
            inet4Addresses = listOf(CidrAddress("10.0.0.2", 32)),
            inet6Addresses = emptyList(),
            dnsServers = listOf("10.0.0.1"),
            inet4Routes = listOf(CidrAddress("0.0.0.0", 0)),
            inet6Routes = emptyList(),
            inet4ExcludedRoutes = emptyList(),
            inet6ExcludedRoutes = emptyList(),
            autoRoute = true,
            includedPackages = emptyList(),
            excludedPackages = emptyList(),
        )
}

/**
 * Hands out scripted [FakeVpnEngine]s in order; records each `create`. The
 * [next] engine is consumed by the next `create` call — set it before the
 * service asks for an engine. If unset, a plain [FakeVpnEngine] is used.
 */
class FakeVpnEngineFactory : VpnEngineFactory {
    val createCalls = AtomicInteger(0)
    val createdEngines = mutableListOf<FakeVpnEngine>()

    /** Engine returned by the *next* create; cleared after use. */
    @Volatile
    var next: FakeVpnEngine? = null

    /** Engines created so far, in order. */
    fun lastCreated(): FakeVpnEngine? = createdEngines.lastOrNull()

    override fun create(
        context: Context,
        platform: EnginePlatform,
        scope: CoroutineScope,
        notifications: EngineNotificationSink,
    ): VpnEngine {
        createCalls.incrementAndGet()
        val engine =
            (next ?: FakeVpnEngine()).also {
                it.platform = platform
                createdEngines.add(it)
                next = null
            }
        return engine
    }
}

/**
 * A [NodeConfigProvider] serving a single fixed [EngineConfig] — the fake
 * engine ignores the JSON anyway. Mirrors the JVM-test provider but lives
 * in androidTest so the harness carries no test-source-set dependency.
 */
class FakeNodeConfigProvider(
    config: EngineConfig? = defaultConfig(),
    var failure: Exception? = null,
) : NodeConfigProvider {
    var config = config

    /** Selected-node pick the manager reconciles into the live engine. */
    val selected = MutableStateFlow<String?>(null)
    var summaries = mapOf<String, NodeSummary>()

    /** Fingerprint of the enabled set the live session compares against. */
    val enabledFingerprint = MutableStateFlow("fp-a")
    val compiledFingerprint = MutableStateFlow<String?>(null)

    /** Underlay IPv6 posture — the service pushes it; tests set it directly. */
    override var underlayHasIpv6: Boolean = true

    override suspend fun compileSelected(): EngineConfig? {
        failure?.let { throw it }
        val c = config
        // Only a built config becomes the baseline — mirrors the real
        // provider so a live session doesn't see a phantom set change.
        compiledFingerprint.value = if (c != null) enabledFingerprint.value else null
        return c
    }

    override val selectedNodeId: Flow<String?> get() = selected
    override suspend fun nodeSummary(id: String): NodeSummary? =
        summaries[id] ?: defaultConfig().node.takeIf { it.id == id }
    override val enabledNodeSetFingerprint: Flow<String> get() = enabledFingerprint
    override val compiledNodeSetFingerprint: StateFlow<String?> get() = compiledFingerprint

    companion object {
        /** Synthetic node — loopback placeholder, never a real endpoint. */
        val NODE = NodeSummary(
            id = "harness-node-1",
            name = "Harness Node",
            protocol = ProtocolType.VLESS,
            server = "127.0.0.1",
        )

        fun defaultConfig(): EngineConfig =
            EngineConfig(configJson = "{}", node = NODE)
    }
}

/**
 * [ServiceControl] that reports VPN consent already granted (`prepareVpn`
 * → null, so `ConnectionManager.startConnect` never parks on
 * `PermissionRequired`) and forwards the connect/disconnect start-intents
 * to the real [Context], so the service is genuinely started/stopped. The
 * auto-reconnect path calls `prepareVpn` on every retry — without this the
 * reconnect stalls at `PermissionRequired` instead of building engine #2.
 */
class FakeServiceControl(
    private val context: Context,
) : ServiceControl {
    val connectStarts = AtomicInteger(0)
    val disconnectStarts = AtomicInteger(0)
    val stopCalls = AtomicInteger(0)

    override fun prepareVpn(): Intent? = null

    override fun startConnectService() {
        connectStarts.incrementAndGet()
        context.startService(ClientVpnService.connectIntent(context))
    }

    override fun startDisconnectService() {
        disconnectStarts.incrementAndGet()
        context.startService(ClientVpnService.disconnectIntent(context))
    }

    override fun stopVpnService(): Boolean {
        stopCalls.incrementAndGet()
        return context.stopService(Intent(context, ClientVpnService::class.java))
    }
}

/**
 * Reports VPN consent as already granted and supplies a real, closable fd
 * from `ParcelFileDescriptor.createPipe()` — no platform VPN permission
 * needed, so the service's `openTun` runs on an emulator without a consent
 * dialog.
 */
class FakeTunProvider : TunProvider {
    val prepareCalls = AtomicInteger(0)
    val establishCalls = AtomicInteger(0)

    /** When set, establish() returns this pre-made fd once, then null. */
    @Volatile
    var establishedFd: ParcelFileDescriptor? = null

    override fun prepare(context: Context): Intent? {
        prepareCalls.incrementAndGet()
        return null // consent already granted
    }

    override fun establish(builder: VpnService.Builder): ParcelFileDescriptor? {
        establishCalls.incrementAndGet()
        establishedFd?.let { return it }
        // A real, closable fd pair — survives close() by the service without
        // a live VPN interface or permission.
        val pair = ParcelFileDescriptor.createPipe()
        pair[0].close() // keep the write end as the "tun" fd
        return pair[1]
    }

    /**
     * No-op: the instrumentation process doesn't need real FGS elevation,
     * and the real `startForeground(systemExempted)` would require the
     * `activate_vpn` appop that only a platform-granted TUN produces.
     */
    override fun startForeground(service: Service, id: Int, notification: Notification) = Unit
}
