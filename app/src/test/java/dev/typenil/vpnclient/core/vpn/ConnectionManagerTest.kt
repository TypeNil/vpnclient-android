package dev.typenil.vpnclient.core.vpn

import android.content.Intent
import dev.typenil.vpnclient.core.engine.ConnectionInfo
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.EngineEvent
import dev.typenil.vpnclient.core.engine.OutboundGroupInfo
import dev.typenil.vpnclient.core.engine.OutboundItemInfo
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.engine.TrafficStats
import dev.typenil.vpnclient.core.engine.VpnEngine
import dev.typenil.vpnclient.core.engine.singbox.ConfigCompiler
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Lifecycle/state-machine tests for [ConnectionManager] — generation guards,
 * telemetry invariants, and terminal-error convergence. Uses fake
 * [ServiceControl]/[VpnEngine]; no Android runtime needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {
    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private lateinit var serviceControl: FakeServiceControl
    private lateinit var configProvider: FakeNodeConfigProvider
    private lateinit var engine: FakeEngine
    private lateinit var manager: ConnectionManager
    private var probeCalls = 0
    private var probeCheck: suspend () -> IpCheckResult = { IpCheckResult(null, null, "unexpected response") }
    private var dnsCalls = 0
    private var dnsCheck: suspend () -> DnsCheckResult = { DnsCheckResult.NotRun }

    private val node =
        NodeSummary(
            id = "node-1",
            name = "Test Node",
            protocol = ProtocolType.VLESS,
            server = "example.invalid",
        )
    private val config = EngineConfig(configJson = "{}", node = node)

    private class FakeServiceControl(
        var permissionIntent: Intent? = null,
    ) : ServiceControl {
        var connectStarts = 0
        var disconnectStarts = 0

        /** When set, startDisconnectService throws — simulates a service
         *  unreachable under background-start restrictions. */
        var disconnectFailure: RuntimeException? = null
        var stopServiceCalls = 0
        var stopServiceResult = false

        override fun prepareVpn(): Intent? = permissionIntent

        override fun startConnectService() {
            connectStarts++
        }

        override fun startDisconnectService() {
            disconnectStarts++
            disconnectFailure?.let { throw it }
        }

        override fun stopVpnService(): Boolean {
            stopServiceCalls++
            return stopServiceResult
        }
    }

    @Test fun `core diagnostics follow active state screen suppression and engine epoch`() = testScope.runTest {
        val generation = connectToRunning()
        runCurrent()
        assertTrue(engine.coreLogsEnabled)
        engine.emitCoreWarning("first timeout")
        assertEquals(listOf("WARN first timeout"), manager.coreLogSnapshot())
        engine.setStatusUpdatesEnabled(false)
        engine.emitCoreWarning("screen off ignored")
        assertEquals(1, manager.coreLogSnapshot().size)
        engine.setStatusUpdatesEnabled(true)
        manager.onUnderlyingNetworkLost(generation)
        runCurrent()
        assertFalse(engine.coreLogsEnabled)
        engine.emitCoreWarning("reconnecting ignored")
        manager.onUnderlyingNetworkAvailable(generation)
        runCurrent()
        assertTrue(engine.coreLogsEnabled)
        assertEquals(1, manager.coreLogSnapshot().size)
        val oldEngine = engine
        val epoch = manager.engineEpoch.value
        engine = FakeEngine()
        manager.attachEngine(engine, generation)
        runCurrent()
        assertTrue(manager.engineEpoch.value > epoch)
        assertTrue(manager.coreLogSnapshot().isEmpty())
        oldEngine.emitCoreWarning("old engine ignored by export")
        assertTrue(manager.coreLogSnapshot().isEmpty())
        engine.emitCoreWarning("fresh warning")
        assertEquals(listOf("WARN fresh warning"), manager.coreLogSnapshot())
        manager.disconnect()
        runCurrent()
        assertFalse(engine.coreLogsEnabled)
        manager.onServiceStopped(generation)
        assertTrue(manager.coreLogSnapshot().isEmpty())
    }

    private class FakeNodeConfigProvider(
        var config: EngineConfig?,
        var failure: Exception? = null,
    ) : NodeConfigProvider {
        val selected = MutableStateFlow<String?>(null)
        var summaries = mapOf<String, NodeSummary>()
        var blockedIds = emptySet<String>()
        var selectionGate: CompletableDeferred<Unit>? = null
        override suspend fun isSelectionAllowed(id: String): Boolean {
            selectionGate?.await()
            return id !in blockedIds
        }

        /** Fingerprint of the enabled set the live session compares against. */
        val enabledFingerprint = MutableStateFlow("fingerprint-a")

        /** Fingerprint the last compile ran against — the fake compile just
         *  records whatever the test set as [enabledFingerprint]. */
        val compiledFingerprint = MutableStateFlow<String?>(null)

        /** When set, compile suspends until completed — a slow rule-set fetch. */
        var compileGate: CompletableDeferred<Unit>? = null
        var compileCalls = 0

        override suspend fun compileSelected(): EngineConfig? {
            compileCalls++
            compileGate?.await()
            failure?.let { throw it }
            compiledFingerprint.value = enabledFingerprint.value
            return config
        }

        override val selectedNodeId: Flow<String?> get() = selected

        override suspend fun nodeSummary(id: String): NodeSummary? = summaries[id]

        override val enabledNodeSetFingerprint: Flow<String> get() = enabledFingerprint
        override val compiledNodeSetFingerprint: StateFlow<String?> get() = compiledFingerprint

        override var underlayHasIpv6: Boolean = true

        private var underlayEpoch = 0L
        private var underlayReported = false

        override fun acquireUnderlayEpoch(): Long {
            underlayEpoch++
            underlayReported = false
            return underlayEpoch
        }

        override fun releaseUnderlayEpoch(epoch: Long) {
            if (epoch == underlayEpoch) underlayReported = false
        }

        override fun reportUnderlay(hasIpv6: Boolean) {
            underlayHasIpv6 = hasIpv6
            underlayReported = true
        }
    }

    private class FakeEngine : VpnEngine {
        val statsFlow = MutableSharedFlow<TrafficStats>(replay = 1)
        val eventsFlow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
        val groupsFlow = MutableStateFlow<List<OutboundGroupInfo>>(emptyList())
        val connectionsFlow = MutableStateFlow<List<ConnectionInfo>>(emptyList())
        val closedConnectionIds = mutableListOf<String>()
        val selections = mutableListOf<Pair<String, String>>()
        var selectOutboundResult = true
        /** Tests can flip this to emulate screen-off suppression. */
        val statusUpdatesFlow = MutableStateFlow(true)
        override val stats: Flow<TrafficStats> get() = statsFlow
        override val events: Flow<EngineEvent> get() = eventsFlow
        override val groups: StateFlow<List<OutboundGroupInfo>> get() = groupsFlow
        override val connections: StateFlow<List<ConnectionInfo>> get() = connectionsFlow
        override val statusUpdatesEnabled: StateFlow<Boolean> get() = statusUpdatesFlow
        var stopCalls = 0
        var coreLogsEnabled = false
        val coreLogs = dev.typenil.vpnclient.core.engine.CoreLogBuffer().apply { start(emptyList()) }
        private var logToken = -1L
        override fun coreLogSnapshot() = coreLogs.snapshot()
        override suspend fun setCoreLogsEnabled(enabled: Boolean) {
            coreLogsEnabled = enabled
            logToken = if (enabled && statusUpdatesFlow.value) coreLogs.subscribe() else {
                coreLogs.pause()
                -1L
            }
        }
        override suspend fun setStatusUpdatesEnabled(enabled: Boolean) {
            statusUpdatesFlow.value = enabled
            setCoreLogsEnabled(coreLogsEnabled)
        }
        fun emitCoreWarning(text: String) = coreLogs.add(logToken, 3, text)

        override suspend fun validate(config: EngineConfig) = Unit

        override suspend fun start(config: EngineConfig) = Unit

        override suspend fun stop() {
            stopCalls++
        }

        override suspend fun onUnderlyingNetworkChanged() = Unit

        override suspend fun selectOutbound(
            groupTag: String,
            outboundTag: String,
        ): Boolean {
            selections += groupTag to outboundTag
            if (selectOutboundResult) {
                // Mirror the real engine: a successful switch is reflected
                // in the next groups push as the group's selected item.
                groupsFlow.value =
                    groupsFlow.value.map { g ->
                        if (g.tag == groupTag) g.copy(selected = outboundTag) else g
                    }
            }
            return selectOutboundResult
        }

        override suspend fun onDeviceIdle(idle: Boolean) = Unit

        override suspend fun urlTest(groupTag: String) = Unit

        override suspend fun closeConnection(id: String): Boolean {
            closedConnectionIds += id
            return true
        }
    }

    private fun conn(id: String) =
        ConnectionInfo(
            id = id,
            destination = "conn.invalid:443",
            domain = "conn.invalid",
            protocol = "tls",
            network = "tcp",
            outbound = "node-1",
            packages = listOf("dev.test.app"),
            uplinkTotalBytes = 1_000,
            downlinkTotalBytes = 2_000,
            createdAtMs = 1_700_000_000_000,
        )

    private fun stats(speed: Long = 1000L) =
        TrafficStats(
            uplinkBytesPerSec = speed,
            downlinkBytesPerSec = speed,
            uplinkTotalBytes = 10_000,
            downlinkTotalBytes = 20_000,
            connectionsIn = 2,
            connectionsOut = 3,
            goroutines = 42,
            memoryBytes = 1_000_000,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        serviceControl = FakeServiceControl()
        configProvider = FakeNodeConfigProvider(config)
        engine = FakeEngine()
        probeCalls = 0
        dnsCalls = 0
        probeCheck = { IpCheckResult(null, null, "unexpected response") }
        dnsCheck = { DnsCheckResult.NotRun }
        manager = ConnectionManager(serviceControl, configProvider, PostStartHealthProbe({
            dnsCalls++
            dnsCheck()
        }) {
            probeCalls++
            probeCheck()
        })
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** connect() → service "started" → engine attached → service reports up. */
    private fun connectToRunning(): Long {
        manager.connect()
        testScope.advanceUntilIdle()
        assertTrue(manager.state.value is VpnConnectionState.Connecting)
        assertEquals(1, serviceControl.connectStarts)
        val generation = manager.pendingSession!!.generation
        manager.attachEngine(engine, generation)
        // Let the collectors actually subscribe — the fake flows use replay=0,
        // so emitting before the collector starts would drop the event.
        testScope.advanceUntilIdle()
        manager.onServiceStarted(generation)
        assertTrue(manager.state.value is VpnConnectionState.Connected)
        return generation
    }

    private fun readyForProbe(): Long {
        configProvider.selected.value = node.id
        engine.groupsFlow.value = listOf(proxyGroup(node.id))
        val generation = connectToRunning()
        testScope.runCurrent()
        return generation
    }

    @Test
    fun `preparing is published before a slow compile finishes`() = testScope.runTest {
        val gate = CompletableDeferred<Unit>()
        configProvider.compileGate = gate
        manager.connect()
        runCurrent()
        assertTrue(manager.state.value is VpnConnectionState.Preparing)
        assertEquals(1, configProvider.compileCalls)
        assertEquals(0, serviceControl.connectStarts)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(manager.state.value is VpnConnectionState.Connecting)
        assertEquals(1, serviceControl.connectStarts)
    }

    @Test
    fun `disconnect during preparation cancels at once and never starts the tunnel`() = testScope.runTest {
        val gate = CompletableDeferred<Unit>()
        configProvider.compileGate = gate
        manager.connect()
        runCurrent()
        assertTrue(manager.state.value is VpnConnectionState.Preparing)

        manager.disconnect()
        runCurrent()
        // Compile is still hung: the cancel must not have waited for it.
        assertEquals(VpnConnectionState.Idle, manager.state.value)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(VpnConnectionState.Idle, manager.state.value)
        assertEquals(0, serviceControl.connectStarts)
        assertEquals(0, serviceControl.disconnectStarts)
        assertNull(manager.pendingSession)
    }

    @Test
    fun `a cancelled preparation cannot hijack the next connect`() = testScope.runTest {
        val gate = CompletableDeferred<Unit>()
        configProvider.compileGate = gate
        manager.connect()
        runCurrent()
        manager.disconnect()
        runCurrent()

        manager.connect()
        runCurrent()
        assertTrue(manager.state.value is VpnConnectionState.Preparing)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(manager.state.value is VpnConnectionState.Connecting)
        assertEquals(1, serviceControl.connectStarts)
    }

    @Test
    fun `post start HTTP success never verifies selected outbound mixed routing or DNS`() = testScope.runTest {
        configProvider.config = config.copy(routeMode = RouteMode.PROXY_BLOCKED,
            configJson = "{\"route\":{\"rules\":[{\"ip_is_private\":true,\"outbound\":\"direct\"}]}}")
        probeCheck = { IpCheckResult("192.0.2.1", 1, null) }
        readyForProbe()
        assertEquals(1, probeCalls)
        assertTrue(manager.state.value is VpnConnectionState.Connected)
        val traffic = manager.health.value.observations.first { it.level == HealthLevel.TrafficForwarding }
        assertEquals(HealthStatus.Unverified, traffic.status)
        assertEquals(HealthReason.HttpResponseRouteUnverified, traffic.reason)
        assertEquals(HealthScope.AppHttpRouteUnverified, traffic.scope)
        val success = manager.health.value.observations.first { it.level == HealthLevel.LastSuccessfulCheck }
        assertEquals(HealthStatus.Ok, success.status)
        assertEquals(HealthSource.IpEcho, success.source)
        assertTrue(manager.health.value.observations.filter {
            it.level == HealthLevel.OutboundReachable || it.level == HealthLevel.DnsReachable
        }.all { it.status == HealthStatus.Unverified })
    }

    private fun dnsLevel() = manager.health.value.observations.first { it.level == HealthLevel.DnsReachable }

    private fun pathChanged(generation: Long) {
        manager.reportHealthUnderlay(true, generation, pathChanged = true)
        testScope.runCurrent()
    }

    @Test fun `path retry runs both checks after three stable seconds without reconnect`() = testScope.runTest {
        dnsCheck = { DnsCheckResult.Answered }
        probeCheck = { IpCheckResult("192.0.2.1", 1, null) }
        val generation = readyForProbe()
        pathChanged(generation)
        assertEquals(HealthStatus.Unverified, dnsLevel().status)
        advanceTimeBy(2_999); runCurrent()
        assertEquals(1, probeCalls)
        assertEquals(1, dnsCalls)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, probeCalls)
        assertEquals(2, dnsCalls)
        assertEquals(HealthStatus.Ok, dnsLevel().status)
        assertEquals(HealthStatus.Ok, manager.health.value.observations.first { it.level == HealthLevel.LastSuccessfulCheck }.status)
        assertTrue(manager.state.value is VpnConnectionState.Connected)
        assertEquals(1, serviceControl.connectStarts)
        assertEquals(0, serviceControl.disconnectStarts)
    }

    @Test fun `path retry debounces repeated revisions`() = testScope.runTest {
        val generation = readyForProbe()
        repeat(3) { pathChanged(generation); advanceTimeBy(2_000); runCurrent() }
        assertEquals(1, probeCalls)
        advanceTimeBy(999); runCurrent()
        assertEquals(1, dnsCalls)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, probeCalls)
        assertEquals(2, dnsCalls)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, probeCalls) // no periodic polling
    }

    @Test fun `path retry debounces a change during its initialization barrier`() = testScope.runTest {
        val generation = readyForProbe()
        engine.groupsFlow.value = emptyList(); runCurrent()
        advanceTimeBy(3_000); runCurrent() // retry is waiting for groups
        pathChanged(generation)
        engine.groupsFlow.value = listOf(proxyGroup(node.id)); runCurrent()
        advanceTimeBy(2_999); runCurrent()
        assertEquals(1, probeCalls)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, probeCalls)
        assertEquals(2, dnsCalls)
    }

    @Test fun `path retry interval is at least sixty seconds`() = testScope.runTest {
        val generation = readyForProbe()
        pathChanged(generation)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(2, probeCalls)
        pathChanged(generation)
        advanceTimeBy(59_999); runCurrent()
        assertEquals(2, probeCalls)
        assertEquals(2, dnsCalls)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, probeCalls)
        assertEquals(3, dnsCalls)
    }

    @Test fun `path retry budget is five and resets only for a new generation`() = testScope.runTest {
        val generation = readyForProbe()
        repeat(7) { pathChanged(generation); advanceTimeBy(60_000); runCurrent() }
        assertEquals(6, probeCalls)
        assertEquals(6, dnsCalls)
        manager.onServiceStopped(generation)
        val next = manager.adoptSession(node)
        engine = FakeEngine().also { it.groupsFlow.value = listOf(proxyGroup(node.id)) }
        manager.attachEngine(engine, next)
        manager.onServiceStarted(next)
        runCurrent()
        assertEquals(7, probeCalls)
        pathChanged(next)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(8, probeCalls)
        assertEquals(8, dnsCalls)
    }

    @Test fun `path retry cancels at disconnect during debounce or cooldown`() = testScope.runTest {
        val generation = readyForProbe()
        pathChanged(generation)
        advanceTimeBy(3_000); runCurrent()
        pathChanged(generation) // waiting for cooldown
        manager.disconnect(); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, probeCalls)
        assertEquals(2, dnsCalls)
        manager.onServiceStopped(generation)
        pathChanged(generation)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(2, probeCalls)
    }

    @Test fun `path retry never runs while Connecting or Reconnecting`() = testScope.runTest {
        configProvider.selected.value = node.id
        engine.groupsFlow.value = listOf(proxyGroup(node.id))
        manager.connect(); runCurrent()
        val generation = manager.pendingSession!!.generation
        manager.attachEngine(engine, generation); runCurrent()
        manager.reportHealthUnderlay(true, generation, pathChanged = true)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, probeCalls)
        manager.onServiceStarted(generation); runCurrent()
        manager.onUnderlyingNetworkLost(generation); runCurrent()
        pathChanged(generation)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, probeCalls)
        assertEquals(1, dnsCalls)
    }

    @Test fun `path retry waits for cancelled probes cleanup with no parallel requests`() = testScope.runTest {
        var active = 0
        probeCheck = {
            active++
            try { kotlinx.coroutines.awaitCancellation() } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { kotlinx.coroutines.delay(4_000) }
                active--
            }
        }
        val generation = readyForProbe()
        pathChanged(generation)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, probeCalls)
        assertEquals(1, active)
        probeCheck = { assertEquals(0, active); IpCheckResult(null, null, "timeout") }
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, probeCalls)
        assertEquals(2, dnsCalls)
    }

    @Test fun `path retry from stale generation cannot start or publish`() = testScope.runTest {
        val late = CompletableDeferred<Unit>()
        dnsCheck = { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { late.await() }; DnsCheckResult.Answered }
        val old = readyForProbe()
        pathChanged(old)
        manager.onServiceStopped(old)
        val next = manager.adoptSession(node)
        engine = FakeEngine().also { it.groupsFlow.value = listOf(proxyGroup(node.id)) }
        manager.attachEngine(engine, next)
        dnsCheck = { DnsCheckResult.NotRun }
        manager.onServiceStarted(next)
        runCurrent()
        pathChanged(old)
        late.complete(Unit); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, probeCalls)
        assertEquals(2, dnsCalls)
        assertEquals(next, dnsLevel().generation)
        assertEquals(HealthStatus.Unverified, dnsLevel().status)
    }

    @Test
    fun `dns answer is Ok but never substitutes the egress check`() = testScope.runTest {
        dnsCheck = { DnsCheckResult.Answered }
        readyForProbe()
        assertEquals(1, dnsCalls)
        val dns = dnsLevel()
        assertEquals(HealthStatus.Ok, dns.status)
        assertEquals(HealthReason.DnsAnswered, dns.reason)
        assertEquals(HealthSource.DnsQuery, dns.source)
        assertEquals(HealthScope.AppDnsQuery, dns.scope)
        // A-01 failed independently: DNS Ok writes nothing into the egress levels.
        assertEquals(HealthStatus.Degraded, manager.health.value.observations.first { it.level == HealthLevel.TrafficForwarding }.status)
        assertEquals(HealthStatus.Unverified, manager.health.value.observations.first { it.level == HealthLevel.LastSuccessfulCheck }.status)
        assertEquals(HealthStatus.Unverified, manager.health.value.observations.first { it.level == HealthLevel.OutboundReachable }.status)
    }

    @Test
    fun `dns failure is Degraded only and keeps the egress success and session`() = testScope.runTest {
        probeCheck = { IpCheckResult("192.0.2.1", 1, null) }
        dnsCheck = { DnsCheckResult.Failed }
        readyForProbe()
        val dns = dnsLevel()
        assertEquals(HealthStatus.Degraded, dns.status)
        assertEquals(HealthReason.DnsFailed, dns.reason)
        assertEquals(HealthStatus.Ok, manager.health.value.observations.first { it.level == HealthLevel.LastSuccessfulCheck }.status)
        assertTrue(manager.state.value is VpnConnectionState.Connected)
        assertEquals(0, serviceControl.disconnectStarts)
    }

    @Test
    fun `dns four second deadline cancels once with no retry or lifecycle write`() = testScope.runTest {
        var cancelled = false
        dnsCheck = { try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true } }
        readyForProbe()
        advanceTimeBy(3_999)
        runCurrent()
        assertFalse(cancelled)
        assertEquals(HealthStatus.Unverified, dnsLevel().status)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(HealthStatus.Degraded, dnsLevel().status)
        assertEquals(HealthReason.DnsTimeout, dnsLevel().reason)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, dnsCalls)
        assertTrue(manager.state.value is VpnConnectionState.Connected)
        assertEquals(0, serviceControl.disconnectStarts)
    }

    @Test
    fun `dns probe that could not run stays Unverified`() = testScope.runTest {
        dnsCheck = { DnsCheckResult.NotRun }
        readyForProbe()
        assertEquals(1, dnsCalls)
        assertEquals(HealthStatus.Unverified, dnsLevel().status)
        assertNull(dnsLevel().checkedAt)
    }

    @Test
    fun `throwing dns probe stays Unverified and leaves the session alone`() = testScope.runTest {
        dnsCheck = { throw RuntimeException("dns exploded") }
        readyForProbe()
        assertEquals(1, dnsCalls)
        assertEquals(HealthStatus.Unverified, dnsLevel().status)
        assertNull(dnsLevel().checkedAt)
        assertTrue(manager.state.value is VpnConnectionState.Connected)
        assertEquals(0, serviceControl.disconnectStarts)
    }

    @Test
    fun `dns probe waits for Connected`() = testScope.runTest {
        runCurrent()
        assertEquals(0, dnsCalls)
        configProvider.selected.value = node.id
        engine.groupsFlow.value = listOf(proxyGroup(node.id))
        manager.connect()
        runCurrent()
        val generation = manager.pendingSession!!.generation
        manager.attachEngine(engine, generation)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(manager.state.value is VpnConnectionState.Connecting)
        assertEquals(0, dnsCalls)
        manager.onServiceStarted(generation)
        runCurrent()
        assertEquals(1, dnsCalls)
    }

    @Test
    fun `dns probe is not started when the session already left Connected`() = testScope.runTest {
        configProvider.selected.value = node.id
        engine.groupsFlow.value = listOf(proxyGroup(node.id))
        manager.connect()
        runCurrent()
        val generation = manager.pendingSession!!.generation
        manager.attachEngine(engine, generation)
        runCurrent()
        manager.onServiceStarted(generation)
        manager.onUnderlyingNetworkLost(generation) // queued: Reconnecting before the probe barrier opens
        runCurrent()
        assertTrue(manager.state.value is VpnConnectionState.Reconnecting)
        assertEquals(0, dnsCalls)
        assertEquals(0, probeCalls)
    }

    @Test
    fun `duplicate starts run one dns probe`() = testScope.runTest {
        val answer = kotlinx.coroutines.CompletableDeferred<DnsCheckResult>()
        dnsCheck = { answer.await() }
        val generation = readyForProbe()
        manager.onServiceStarted(generation)
        manager.onServiceStarted(generation)
        runCurrent()
        assertEquals(1, dnsCalls)
        answer.complete(DnsCheckResult.Answered)
        runCurrent()
        assertEquals(HealthStatus.Ok, dnsLevel().status)
        assertEquals(1, dnsCalls)
    }

    @Test
    fun `old generation dns completion is not published into a newer session`() = testScope.runTest {
        val oldAnswer = kotlinx.coroutines.CompletableDeferred<DnsCheckResult>()
        dnsCheck = { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { oldAnswer.await() } }
        val oldGeneration = readyForProbe()
        manager.onServiceStopped(oldGeneration)
        val generation = manager.adoptSession(node)
        engine = FakeEngine().also { it.groupsFlow.value = listOf(proxyGroup(node.id)) }
        manager.attachEngine(engine, generation)
        val current = kotlinx.coroutines.CompletableDeferred<DnsCheckResult>()
        dnsCheck = { current.await() }
        manager.onServiceStarted(generation)
        runCurrent()
        oldAnswer.complete(DnsCheckResult.Answered)
        runCurrent()
        assertEquals(generation, manager.health.value.generation)
        assertEquals(HealthStatus.Unverified, dnsLevel().status)
        current.complete(DnsCheckResult.Failed)
        runCurrent()
        assertEquals(HealthReason.DnsFailed, dnsLevel().reason)
    }

    @Test
    fun `leaving Connected during the dns probe publishes nothing`() = testScope.runTest {
        val answer = kotlinx.coroutines.CompletableDeferred<DnsCheckResult>()
        dnsCheck = { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { answer.await() } }
        val generation = readyForProbe()
        manager.onUnderlyingNetworkLost(generation)
        runCurrent()
        assertFalse(manager.state.value is VpnConnectionState.Connected)
        answer.complete(DnsCheckResult.Answered)
        runCurrent()
        assertEquals(HealthStatus.Unverified, dnsLevel().status)
    }

    @Test
    fun `same generation rebuild rejects the old dns answer and probes the new runtime once`() = testScope.runTest {
        val old = kotlinx.coroutines.CompletableDeferred<DnsCheckResult>()
        dnsCheck = { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { old.await() } }
        val generation = readyForProbe()
        manager.onTunnelRebuildStarted(generation)
        runCurrent()
        engine = FakeEngine().also { it.groupsFlow.value = listOf(proxyGroup(node.id)) }
        manager.attachEngine(engine, generation)
        val fresh = kotlinx.coroutines.CompletableDeferred<DnsCheckResult>()
        dnsCheck = { fresh.await() }
        manager.onTunnelRebuilt(generation)
        runCurrent()
        assertEquals(1, dnsCalls) // replacement waits for old request cleanup
        old.complete(DnsCheckResult.Answered)
        runCurrent()
        assertEquals(2, dnsCalls)
        assertEquals(HealthStatus.Unverified, dnsLevel().status)
        fresh.complete(DnsCheckResult.Timeout)
        runCurrent()
        assertEquals(HealthReason.DnsTimeout, dnsLevel().reason)
    }

    @Test
    fun `failed post start check degrades only evidence and never reconnects`() = testScope.runTest {
        probeCheck = { IpCheckResult(null, null, "http 503") }
        readyForProbe()
        val traffic = manager.health.value.observations.first { it.level == HealthLevel.TrafficForwarding }
        assertEquals(HealthStatus.Degraded, traffic.status)
        assertEquals(HealthReason.HttpError, traffic.reason)
        assertEquals(HealthStatus.Unverified, manager.health.value.observations.first { it.level == HealthLevel.LastSuccessfulCheck }.status)
        assertTrue(manager.state.value is VpnConnectionState.Connected)
        assertEquals(0, serviceControl.disconnectStarts)
    }

    @Test
    fun `a throwing post start probe degrades evidence without touching the session`() = testScope.runTest {
        probeCheck = { throw RuntimeException("probe exploded") }
        readyForProbe()
        assertEquals(1, probeCalls)
        val traffic = manager.health.value.observations.first { it.level == HealthLevel.TrafficForwarding }
        assertEquals(HealthStatus.Degraded, traffic.status)
        assertEquals(HealthReason.HttpNetworkError, traffic.reason)
        assertEquals(HealthStatus.Unverified, manager.health.value.observations.first { it.level == HealthLevel.LastSuccessfulCheck }.status)
        assertTrue(manager.state.value is VpnConnectionState.Connected)
        assertEquals(0, serviceControl.disconnectStarts)
    }

    @Test
    fun `eight second deadline cancels fake request with no retries or lifecycle writes`() = testScope.runTest {
        var cancelled = false
        probeCheck = {
            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true }
        }
        readyForProbe()
        advanceTimeBy(8_001)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(HealthReason.HttpTimeout, manager.health.value.observations.first { it.level == HealthLevel.TrafficForwarding }.reason)
        assertTrue(manager.state.value is VpnConnectionState.Connected)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, probeCalls)
        assertEquals(0, serviceControl.disconnectStarts)
    }

    @Test
    fun `idle and duplicate starts do not stack checks`() = testScope.runTest {
        runCurrent()
        assertEquals(0, probeCalls)
        val result = kotlinx.coroutines.CompletableDeferred<IpCheckResult>()
        probeCheck = { result.await() }
        val generation = readyForProbe()
        manager.onServiceStarted(generation)
        manager.onServiceStarted(generation)
        runCurrent()
        assertEquals(1, probeCalls)
        result.complete(IpCheckResult("192.0.2.1", 1, null))
        runCurrent()
        assertEquals(HealthStatus.Ok, manager.health.value.observations.first { it.level == HealthLevel.LastSuccessfulCheck }.status)
    }

    @Test
    fun `stop cancels an active check and keeps ended evidence unverified`() = testScope.runTest {
        var cancelled = false
        probeCheck = { try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true } }
        val generation = readyForProbe()
        manager.onHealthStopping(generation)
        runCurrent()
        assertTrue(cancelled)
        manager.onServiceStopped(generation)
        assertEquals(VpnConnectionState.Idle, manager.state.value)
        assertTrue(manager.health.value.observations.all { it.status == HealthStatus.Unverified })
    }

    @Test
    fun `underlay change cancels but unchanged reevaluation does not cancel active request`() = testScope.runTest {
        var cancelled = false
        probeCheck = { try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true } }
        val generation = readyForProbe()
        manager.reportHealthUnderlay(true, generation, pathChanged = false)
        runCurrent()
        assertFalse(cancelled)
        manager.reportHealthUnderlay(true, generation, pathChanged = true)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(1, probeCalls)
        assertTrue(manager.state.value is VpnConnectionState.Connected)
    }

    @Test
    fun `start failure and consent revoke each cancel their active request`() = testScope.runTest {
        var cancelled = false
        probeCheck = { try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true } }
        val failedGeneration = readyForProbe()
        manager.onServiceFailed(VpnError.EngineFailed("test failure"), failedGeneration)
        runCurrent()
        assertTrue(cancelled)
        assertTrue(manager.state.value is VpnConnectionState.Error)
        assertTrue(manager.health.value.observations.all { it.status == HealthStatus.Unverified })
        cancelled = false
        val generation = manager.adoptSession(node)
        engine = FakeEngine().also { it.groupsFlow.value = listOf(proxyGroup(node.id)) }
        manager.attachEngine(engine, generation)
        manager.onServiceStarted(generation)
        runCurrent()
        assertEquals(2, probeCalls)
        manager.onServiceRevoked(generation)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(VpnError.PermissionRevoked, (manager.state.value as VpnConnectionState.Error).error)
        assertEquals(HealthStatus.Unverified, manager.health.value.observations.first { it.level == HealthLevel.LastSuccessfulCheck }.status)
    }

    @Test
    fun `selection change cancels the check without restarting its HTTP request`() = testScope.runTest {
        var cancelled = false
        probeCheck = { try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true } }
        readyForProbe()
        configProvider.selected.value = "node-2"
        runCurrent()
        assertTrue(cancelled)
        assertEquals(1, probeCalls)
        assertTrue(manager.state.value is VpnConnectionState.Connected)
        assertEquals(0, serviceControl.disconnectStarts)
    }

    @Test
    fun `same generation rebuilt runtime rejects old result and gets one new check`() = testScope.runTest {
        val oldResult = kotlinx.coroutines.CompletableDeferred<IpCheckResult>()
        probeCheck = { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { oldResult.await() } }
        val generation = readyForProbe()
        manager.onTunnelRebuildStarted(generation)
        runCurrent()
        engine = FakeEngine().also { it.groupsFlow.value = listOf(proxyGroup(node.id)) }
        manager.attachEngine(engine, generation)
        val newResult = kotlinx.coroutines.CompletableDeferred<IpCheckResult>()
        probeCheck = { newResult.await() }
        manager.onTunnelRebuilt(generation)
        runCurrent()
        assertEquals(1, probeCalls) // replacement waits for old request cleanup
        oldResult.complete(IpCheckResult("192.0.2.1", 1, null))
        runCurrent()
        assertEquals(2, probeCalls)
        assertEquals(HealthStatus.Unverified, manager.health.value.observations.first { it.level == HealthLevel.LastSuccessfulCheck }.status)
        newResult.complete(IpCheckResult("192.0.2.2", 1, null))
        runCurrent()
        assertEquals(HealthStatus.Ok, manager.health.value.observations.first { it.level == HealthLevel.LastSuccessfulCheck }.status)
        assertTrue(manager.state.value is VpnConnectionState.Connected)
    }

    @Test
    fun `new generation rejects old non cancellable probe completion`() = testScope.runTest {
        val oldResult = kotlinx.coroutines.CompletableDeferred<IpCheckResult>()
        probeCheck = { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { oldResult.await() } }
        val oldGeneration = readyForProbe()
        manager.onServiceStopped(oldGeneration)
        val generation = manager.adoptSession(node)
        engine = FakeEngine().also { it.groupsFlow.value = listOf(proxyGroup(node.id)) }
        manager.attachEngine(engine, generation)
        val currentResult = kotlinx.coroutines.CompletableDeferred<IpCheckResult>()
        probeCheck = { currentResult.await() }
        manager.onServiceStarted(generation)
        runCurrent()
        oldResult.complete(IpCheckResult("192.0.2.1", 1, null))
        runCurrent()
        assertEquals(generation, manager.health.value.generation)
        assertEquals(HealthStatus.Unverified, manager.health.value.observations.first { it.level == HealthLevel.LastSuccessfulCheck }.status)
        currentResult.complete(IpCheckResult(null, null, "network"))
        runCurrent()
        assertTrue(manager.state.value is VpnConnectionState.Connected)
    }

    @Test
    fun `initial groups and underlay reconcile before tokens are captured`() = testScope.runTest {
        configProvider.selected.value = node.id
        val result = kotlinx.coroutines.CompletableDeferred<IpCheckResult>()
        probeCheck = { result.await() }
        val generation = connectToRunning()
        runCurrent()
        assertEquals(0, probeCalls)
        manager.onUnderlyingNetworkAvailable(generation)
        engine.groupsFlow.value = listOf(proxyGroup(node.id))
        runCurrent()
        assertEquals(1, probeCalls)
        result.complete(IpCheckResult("192.0.2.1", 1, null))
        runCurrent()
        assertEquals(HealthStatus.Ok, manager.health.value.observations.first { it.level == HealthLevel.LastSuccessfulCheck }.status)
    }

    @Test
    fun `silent groups do not indefinitely postpone the single check`() = testScope.runTest {
        probeCheck = { IpCheckResult("192.0.2.1", 1, null) }
        connectToRunning()
        runCurrent()
        assertEquals(0, probeCalls)
        advanceTimeBy(ConnectionManager.GROUPS_WAIT_MS + 101)
        runCurrent()
        assertEquals(1, probeCalls)
        assertEquals(HealthStatus.Ok, manager.health.value.observations.first { it.level == HealthLevel.LastSuccessfulCheck }.status)
    }

    @Test
    fun `late rebuild completion after core failure cannot restore runtime health`() = testScope.runTest {
        val generation = connectToRunning()
        manager.onTunnelRebuildStarted(generation)
        runCurrent()
        engine.eventsFlow.emit(EngineEvent.Failed(EngineError.CoreError("test failure")))
        runCurrent()
        val failedState = manager.state.value as VpnConnectionState.Reconnecting
        assertEquals(VpnConnectionState.Reconnecting.Reason.CoreFailure, failedState.reason)
        manager.onTunnelRebuilt(generation)
        runCurrent()
        assertEquals(failedState, manager.state.value)
        assertTrue(manager.health.value.observations.filter { it.level in ConnectionHealthStore.RUNTIME_LEVELS }
            .all { it.status == HealthStatus.Unverified })
        manager.onServiceStopped(generation)
        manager.disconnect()
        runCurrent()
    }

    @Test
    fun `accepted sessionless revoke clears pending attempt health`() = testScope.runTest {
        manager.connect()
        runCurrent()
        assertEquals(HealthStatus.Ok, manager.health.value.observations.first { it.level == HealthLevel.VpnConsent }.status)
        manager.onServiceRevoked(-1)
        assertEquals(VpnError.PermissionRevoked, (manager.state.value as VpnConnectionState.Error).error)
        val consent = manager.health.value.observations.first { it.level == HealthLevel.VpnConsent }
        assertEquals(HealthStatus.Failed, consent.status)
        assertEquals(HealthReason.Revoked, consent.reason)
        assertTrue(manager.health.value.observations.filter { it.level in ConnectionHealthStore.RUNTIME_LEVELS }
            .all { it.status == HealthStatus.Unverified })
    }

    @Test
    fun `accepted sessionless stop clears pending attempt health`() = testScope.runTest {
        manager.connect()
        runCurrent()
        manager.onServiceStopped(-1)
        assertEquals(VpnConnectionState.Idle, manager.state.value)
        assertTrue(manager.health.value.observations.all { it.status == HealthStatus.Unverified })
    }

    @Test
    fun `health does not imply egress or change Connected and Idle`() = testScope.runTest {
        val generation = connectToRunning()
        manager.reportUnderlyingTransport(UnderlyingTransport.UNKNOWN)
        manager.onUnderlyingNetworkAvailable(generation)
        runCurrent()
        assertTrue(manager.state.value is VpnConnectionState.Connected)
        val observations = manager.health.value.observations
        assertEquals(HealthStatus.Ok, observations.first { it.level == HealthLevel.EngineRunning }.status)
        assertEquals(HealthStatus.Ok, observations.first { it.level == HealthLevel.UnderlyingNetwork }.status)
        assertTrue(observations.filter { it.level in ConnectionHealthStore.PATH_LEVELS }.all { it.status == HealthStatus.Unverified })
        manager.onServiceStopped(generation)
        assertEquals(VpnConnectionState.Idle, manager.state.value)
        manager.reportHealthUnderlay(true, generation)
        assertTrue(manager.health.value.observations.all { it.status == HealthStatus.Unverified })
    }

    @Test
    fun `stale queued network and rebuild callbacks cannot alter a newer session`() = testScope.runTest {
        configProvider.selected.value = node.id
        val oldGeneration = connectToRunning()
        manager.onUnderlyingNetworkLost(oldGeneration) // queued before the new session exists
        manager.onTunnelRebuildStarted(oldGeneration)
        manager.onServiceStopped(oldGeneration)
        val generation = manager.adoptSession(node)
        engine = FakeEngine()
        manager.attachEngine(engine, generation)
        manager.onServiceStarted(generation)
        manager.reportUnderlyingTransport(UnderlyingTransport.WIFI)
        val connected = manager.state.value
        val health = manager.health.value
        runCurrent()
        assertEquals(connected, manager.state.value)
        assertEquals(health, manager.health.value)
        assertEquals(UnderlyingTransport.WIFI, manager.underlyingTransport.value)

        manager.onUnderlyingNetworkLost(generation)
        runCurrent()
        val reconnecting = manager.state.value
        val lostHealth = manager.health.value
        assertTrue(reconnecting is VpnConnectionState.Reconnecting)
        manager.onUnderlyingNetworkAvailable(oldGeneration)
        manager.onUnderlyingNetworkAvailable(-1)
        runCurrent()
        assertEquals(reconnecting, manager.state.value)
        assertEquals(lostHealth, manager.health.value)
        manager.onUnderlyingNetworkAvailable(generation)
        runCurrent()
        assertTrue(manager.state.value is VpnConnectionState.Connected)
    }

    @Test
    fun `underlay reevaluation without path changes leaves path invalidation untouched`() = testScope.runTest {
        configProvider.selected.value = node.id
        val generation = connectToRunning()
        val path = manager.health.value.observations.filter { it.level in ConnectionHealthStore.PATH_LEVELS }
        assertTrue(path.all { it.reason == HealthReason.RuntimeInvalidated })
        manager.reportHealthUnderlay(true, generation, pathChanged = false)
        assertEquals(path, manager.health.value.observations.filter { it.level in ConnectionHealthStore.PATH_LEVELS })
        manager.reportHealthUnderlay(true, generation, pathChanged = true)
        assertTrue(manager.health.value.observations.filter { it.level in ConnectionHealthStore.PATH_LEVELS }.all {
            it.reason == HealthReason.PathChanged && it.status == HealthStatus.Unverified
        })
        assertTrue(manager.state.value is VpnConnectionState.Connected)
    }

    @Test
    fun `consent denial and revoke keep safe failed consent while clearing runtime`() = testScope.runTest {
        serviceControl.permissionIntent = Intent()
        manager.connect()
        runCurrent()
        manager.onPermissionResult(false)
        runCurrent()
        assertEquals(HealthReason.ConsentDenied, manager.health.value.observations.first { it.level == HealthLevel.VpnConsent }.reason)
        assertEquals(HealthStatus.Failed, manager.health.value.observations.first { it.level == HealthLevel.VpnConsent }.status)
        serviceControl.permissionIntent = null
        manager.connect()
        runCurrent()
        val generation = manager.pendingSession!!.generation
        manager.attachEngine(engine, generation)
        manager.onServiceStarted(generation)
        manager.onServiceRevoked(generation)
        assertTrue(manager.state.value is VpnConnectionState.Error)
        assertEquals(HealthReason.Revoked, manager.health.value.observations.first { it.level == HealthLevel.VpnConsent }.reason)
        assertTrue(manager.health.value.observations.filter { it.level in ConnectionHealthStore.RUNTIME_LEVELS }.all { it.status == HealthStatus.Unverified })
    }

    @Test
    fun `adopt and start failure clear evidence without guessing egress`() = testScope.runTest {
        val generation = manager.adoptSession(node)
        assertTrue(manager.health.value.observations.all { it.status == HealthStatus.Unverified })
        manager.attachEngine(engine, generation)
        manager.onServiceStarted(generation)
        manager.reportHealthUnderlay(true, generation, pathChanged = true)
        manager.onServiceFailed(VpnError.EngineFailed("test failure"), generation)
        assertTrue(manager.state.value is VpnConnectionState.Error)
        assertTrue(manager.health.value.observations.all { it.status == HealthStatus.Unverified && it.checkedAt == null })
        manager.reportHealthUnderlay(true, generation)
        assertTrue(manager.health.value.observations.all { it.status == HealthStatus.Unverified })
    }

    @Test
    fun `same generation rebuild clears runtime and stale health callbacks cannot repopulate`() = testScope.runTest {
        val generation = connectToRunning()
        manager.onTunnelRebuildStarted(generation)
        runCurrent()
        assertEquals(HealthStatus.Unverified, manager.health.value.observations.first { it.level == HealthLevel.EngineRunning }.status)
        manager.onTunnelRebuilt(generation)
        runCurrent()
        assertTrue(manager.state.value is VpnConnectionState.Connected)
        manager.reportHealthUnderlay(true, generation - 1)
        assertEquals(HealthStatus.Unverified, manager.health.value.observations.first { it.level == HealthLevel.UnderlyingNetwork }.status)
        assertTrue(manager.health.value.observations.filter { it.level in ConnectionHealthStore.PATH_LEVELS }.all { it.status == HealthStatus.Unverified })
    }

    @Test
    fun `connect reaches Connected via service callbacks`() =
        testScope.runTest {
            val generation = connectToRunning()
            advanceUntilIdle()
            val state = manager.state.value
            assertTrue(state is VpnConnectionState.Connected)
            assertEquals(node, (state as VpnConnectionState.Connected).node)
            assertTrue(manager.pendingSession == null)
        }

    @Test
    fun `stats mutate Connected payload but never create lifecycle state`() =
        testScope.runTest {
            manager.connect()
            advanceUntilIdle()
            val generation = manager.pendingSession!!.generation
            manager.attachEngine(engine, generation)
            advanceUntilIdle()

            // Stats arriving while still Connecting must not promote state.
            engine.statsFlow.emit(stats(111))
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.Connecting)

            manager.onServiceStarted(generation)
            engine.statsFlow.emit(stats(222))
            advanceUntilIdle()
            val state = manager.state.value
            assertTrue(state is VpnConnectionState.Connected)
            assertEquals(222L, (state as VpnConnectionState.Connected).stats?.uplinkBytesPerSec)
        }

    @Test
    fun `identical status arrivals refresh receipt time`() =
        testScope.runTest {
            connectToRunning()
            val idle = stats(0)
            engine.statsFlow.emit(idle)
            runCurrent()
            val first = manager.state.value as VpnConnectionState.Connected
            assertTrue(first.statsReceivedAtNanos > 0L)
            engine.statsFlow.emit(idle)
            runCurrent()
            val second = manager.state.value as VpnConnectionState.Connected
            assertEquals(first.stats, second.stats)
            assertTrue(second.statsReceivedAtNanos > first.statsReceivedAtNanos)
        }

    @Test
    fun `late stats after Error do not resurrect Connected`() =
        testScope.runTest {
            val generation = connectToRunning()
            engine.eventsFlow.emit(EngineEvent.Failed(EngineError.CoreError("boom")))
            advanceUntilIdle()
            // Terminal events request teardown through the service.
            assertEquals(1, serviceControl.disconnectStarts)
            manager.onServiceStopped(generation)
            assertTrue(manager.state.value is VpnConnectionState.Error)

            engine.statsFlow.emit(stats(333))
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.Error)
        }

    @Test
    fun `unexpected core stop auto-reconnects after teardown`() =
        testScope.runTest {
            val generation = connectToRunning()
            engine.eventsFlow.emit(EngineEvent.StoppedUnexpectedly)
            runCurrent()
            // The failure parks as Reconnecting and requests teardown — the
            // retry fires only after the service reports stopped.
            val reconnecting = manager.state.value
            assertTrue(reconnecting is VpnConnectionState.Reconnecting)
            assertEquals(1, (reconnecting as VpnConnectionState.Reconnecting).attempt)
            assertEquals(1, serviceControl.disconnectStarts)

            manager.onServiceStopped(generation)
            advanceTimeBy(1_100) // first backoff step
            runCurrent()
            assertEquals(2, serviceControl.connectStarts)
            assertTrue(manager.state.value is VpnConnectionState.Connecting)
        }

    @Test
    fun `failure reconnect budget exhausts into Error`() =
        testScope.runTest {
            var generation = connectToRunning()
            // Each retry brings a fresh session up; each fresh session dies.
            repeat(ConnectionManager.MAX_FAILURE_RECONNECTS) {
                engine.eventsFlow.emit(EngineEvent.StoppedUnexpectedly)
                runCurrent()
                assertTrue(manager.state.value is VpnConnectionState.Reconnecting)
                manager.onServiceStopped(generation)
                advanceTimeBy(17_000) // covers the largest backoff step (16s)
                runCurrent()
                val gen = manager.pendingSession!!.generation
                engine = FakeEngine()
                manager.attachEngine(engine, gen)
                runCurrent()
                manager.onServiceStarted(gen)
                generation = gen
            }
            // One failure past the budget — terminal teardown, no retry.
            engine.eventsFlow.emit(EngineEvent.StoppedUnexpectedly)
            runCurrent()
            manager.onServiceStopped(generation)
            assertTrue(manager.state.value is VpnConnectionState.Error)
        }

    @Test
    fun `undeliverable disconnect intent converges to Error without wedging`() =
        testScope.runTest {
            connectToRunning()
            serviceControl.disconnectFailure = RuntimeException("background start blocked")

            engine.eventsFlow.emit(EngineEvent.StoppedUnexpectedly)
            runCurrent()

            // One retry, then the machine converges: the service is
            // unreachable, so no onServiceStopped will ever arrive — Error
            // is published immediately instead of waiting out the settle
            // window with a live tunnel.
            assertEquals(2, serviceControl.disconnectStarts)
            assertEquals(1, serviceControl.stopServiceCalls)
            val state = manager.state.value
            assertTrue(state is VpnConnectionState.Error)
            assertTrue((state as VpnConnectionState.Error).error is VpnError.EngineFailed)

            // teardownRequested was cleared — a later connect isn't blocked
            // by a teardown that never happened.
            serviceControl.disconnectFailure = null
            manager.connect()
            advanceUntilIdle()
            assertEquals(2, serviceControl.connectStarts)
            assertTrue(manager.state.value is VpnConnectionState.Connecting)
        }

    @Test
    fun `exhausted budget settle timeout publishes the parked error`() =
        testScope.runTest {
            var generation = connectToRunning()
            repeat(ConnectionManager.MAX_FAILURE_RECONNECTS) {
                engine.eventsFlow.emit(EngineEvent.StoppedUnexpectedly)
                runCurrent()
                assertTrue(manager.state.value is VpnConnectionState.Reconnecting)
                manager.onServiceStopped(generation)
                advanceTimeBy(17_000) // covers the largest backoff step (16s)
                runCurrent()
                val gen = manager.pendingSession!!.generation
                engine = FakeEngine()
                manager.attachEngine(engine, gen)
                runCurrent()
                manager.onServiceStarted(gen)
                generation = gen
            }
            // One failure past the budget — teardown requested, but the
            // service never reports stopped. The watchdog must publish the
            // parked terminal error instead of sitting in Reconnecting.
            engine.eventsFlow.emit(EngineEvent.StoppedUnexpectedly)
            runCurrent()
            assertEquals(6, serviceControl.disconnectStarts)
            assertFalse(manager.state.value is VpnConnectionState.Error)

            advanceTimeBy(ConnectionManager.RECONNECT_SETTLE_MS + 1)
            runCurrent()
            val state = manager.state.value
            assertTrue(state is VpnConnectionState.Error)
            assertTrue((state as VpnConnectionState.Error).error is VpnError.EngineFailed)
        }

    @Test
    fun `network recovery during failure reconnect does not fake Connected`() =
        testScope.runTest {
            val generation = connectToRunning()
            engine.eventsFlow.emit(EngineEvent.StoppedUnexpectedly)
            runCurrent()
            assertTrue(manager.state.value is VpnConnectionState.Reconnecting)

            // The underlay callback must not resurrect Connected over a dead
            // engine — the retry owns this Reconnecting.
            manager.onUnderlyingNetworkAvailable()
            runCurrent()
            assertTrue(manager.state.value is VpnConnectionState.Reconnecting)

            manager.onServiceStopped(generation)
            advanceTimeBy(1_100)
            runCurrent()
            assertTrue(manager.state.value is VpnConnectionState.Connecting)
        }

    @Test
    fun `user disconnect during pending failure lands on Idle`() =
        testScope.runTest {
            val generation = connectToRunning()
            engine.eventsFlow.emit(EngineEvent.StoppedUnexpectedly)
            runCurrent()

            manager.disconnect()
            advanceUntilIdle()
            manager.onServiceStopped(generation)
            assertTrue(manager.state.value is VpnConnectionState.Idle)
            // The cancelled retry must not fire after the user's disconnect.
            assertEquals(1, serviceControl.connectStarts)
        }

    @Test
    fun `disconnect produces Stopping then Idle`() =
        testScope.runTest {
            val generation = connectToRunning()
            manager.disconnect()
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.Stopping)
            manager.onServiceStopped(generation)
            assertTrue(manager.state.value is VpnConnectionState.Idle)
        }

    @Test
    fun `stale generation callbacks are ignored`() =
        testScope.runTest {
            val generation = connectToRunning()
            val stale = generation - 1
            manager.onServiceStopped(stale)
            assertTrue(manager.state.value is VpnConnectionState.Connected)
            manager.onServiceFailed(VpnError.Unexpected("stale"), stale)
            assertTrue(manager.state.value is VpnConnectionState.Connected)
            manager.onServiceStarted(stale)
            assertTrue(manager.state.value is VpnConnectionState.Connected)
        }

    @Test
    fun `emissions from previous session are dropped`() =
        testScope.runTest {
            val oldEngine = engine
            val genA = connectToRunning()
            manager.disconnect()
            advanceUntilIdle()
            manager.onServiceStopped(genA)
            assertTrue(manager.state.value is VpnConnectionState.Idle)

            // New session, new engine.
            engine = FakeEngine()
            manager.connect()
            advanceUntilIdle()
            val genB = manager.pendingSession!!.generation
            manager.attachEngine(engine, genB)
            advanceUntilIdle()
            manager.onServiceStarted(genB)
            assertTrue(manager.state.value is VpnConnectionState.Connected)

            // A stale event from session A must not tear down session B.
            oldEngine.eventsFlow.emit(EngineEvent.Failed(EngineError.CoreError("old")))
            advanceUntilIdle()
            assertEquals(1, serviceControl.disconnectStarts)
            assertTrue(manager.state.value is VpnConnectionState.Connected)
        }

    @Test
    fun `duplicate connect and disconnect are no-ops`() =
        testScope.runTest {
            connectToRunning()
            manager.connect()
            advanceUntilIdle()
            assertEquals(1, serviceControl.connectStarts)

            manager.disconnect()
            manager.disconnect()
            advanceUntilIdle()
            assertEquals(1, serviceControl.disconnectStarts)
        }

    @Test
    fun `connect without selected node reports NoNodeSelected`() =
        testScope.runTest {
            configProvider.config = null
            manager.connect()
            advanceUntilIdle()
            val state = manager.state.value
            assertTrue(state is VpnConnectionState.Error)
            assertTrue((state as VpnConnectionState.Error).error is VpnError.NoNodeSelected)
            assertEquals(0, serviceControl.connectStarts)
        }

    @Test
    fun `permission flow requires consent before starting service`() =
        testScope.runTest {
            serviceControl.permissionIntent = Intent()
            manager.connect()
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.PermissionRequired)
            assertEquals(0, serviceControl.connectStarts)

            manager.onPermissionResult(true)
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.Connecting)
            assertEquals(1, serviceControl.connectStarts)
        }

    @Test
    fun `network loss transitions Connected to Reconnecting and back`() =
        testScope.runTest {
            connectToRunning()

            manager.onUnderlyingNetworkLost()
            advanceUntilIdle()
            val reconnecting = manager.state.value
            assertTrue(reconnecting is VpnConnectionState.Reconnecting)
            assertEquals(node, (reconnecting as VpnConnectionState.Reconnecting).node)

            // Stats during Reconnecting don't leak into the payload.
            engine.statsFlow.emit(stats(555))
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.Reconnecting)

            manager.onUnderlyingNetworkAvailable()
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.Connected)
        }

    @Test
    fun `underlying transport label tracks service reports without state transitions`() =
        testScope.runTest {
            // Unknown before the service's underlay tracker reports.
            assertEquals(UnderlyingTransport.UNKNOWN, manager.underlyingTransport.value)

            connectToRunning()
            // A fresh session must not inherit the previous one's label.
            assertEquals(UnderlyingTransport.UNKNOWN, manager.underlyingTransport.value)

            manager.reportUnderlyingTransport(UnderlyingTransport.WIFI)
            assertEquals(UnderlyingTransport.WIFI, manager.underlyingTransport.value)

            // A cell hand-off relabels without touching the state machine.
            manager.reportUnderlyingTransport(UnderlyingTransport.CELLULAR)
            assertEquals(UnderlyingTransport.CELLULAR, manager.underlyingTransport.value)
            assertTrue(manager.state.value is VpnConnectionState.Connected)

            // Underlay lost: label goes unknown alongside the Reconnecting.
            manager.onUnderlyingNetworkLost()
            advanceUntilIdle()
            assertEquals(UnderlyingTransport.UNKNOWN, manager.underlyingTransport.value)
        }

    @Test
    fun `underlying transport resets when the session ends`() =
        testScope.runTest {
            val generation = connectToRunning()
            manager.reportUnderlyingTransport(UnderlyingTransport.WIFI)

            manager.disconnect()
            advanceUntilIdle()
            manager.onServiceStopped(generation)
            assertEquals(UnderlyingTransport.UNKNOWN, manager.underlyingTransport.value)
        }

    @Test
    fun `network callbacks are no-ops outside the relevant states`() =
        testScope.runTest {
            manager.onUnderlyingNetworkLost()
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.Idle)
            manager.onUnderlyingNetworkAvailable()
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.Idle)

            connectToRunning()
            manager.onUnderlyingNetworkAvailable()
            advanceUntilIdle()
            // Already Connected — no spurious transition.
            assertTrue(manager.state.value is VpnConnectionState.Connected)
        }

    @Test
    fun `tunnel rebuild transitions Connected to Reconnecting and back`() =
        testScope.runTest {
            val generation = connectToRunning()

            manager.onTunnelRebuildStarted()
            advanceUntilIdle()
            val rebuilding = manager.state.value
            assertTrue(rebuilding is VpnConnectionState.Reconnecting)
            assertEquals(node, (rebuilding as VpnConnectionState.Reconnecting).node)

            manager.onTunnelRebuilt(generation)
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.Connected)
        }

    @Test
    fun `a rebuilt tunnel with a recompiled node relabels the session`() =
        testScope.runTest {
            // The node-set change removed the session's node: the rebuilt
            // engine runs a different one, and the label must follow it
            // instead of describing the previous session.
            val generation = connectToRunning()
            manager.onTunnelRebuildStarted()
            advanceUntilIdle()

            manager.onTunnelRebuilt(generation, node2)
            advanceUntilIdle()

            val state = manager.state.value
            assertTrue(state is VpnConnectionState.Connected)
            assertEquals(node2, (state as VpnConnectionState.Connected).node)
        }

    @Test
    fun `applied session config is reported and cleared with the session`() =
        testScope.runTest {
            val generation = connectToRunning()
            val applied =
                AppliedSessionConfig(
                    routeMode = RouteMode.BYPASS_RU,
                    perAppMode = PerAppMode.EXCLUDE,
                    perAppPackages = setOf("com.a", "com.b", "com.c"),
                )

            manager.reportAppliedSessionConfig(applied)
            assertEquals(applied, manager.appliedSessionConfig.value)

            // The teardown must not leave a stale plan describing a session
            // that no longer exists.
            manager.onServiceStopped(generation)
            advanceUntilIdle()
            assertNull(manager.appliedSessionConfig.value)
        }

    @Test
    fun `a sessionless start failure is published when nothing owns the session`() =
        testScope.runTest {
            assertTrue(manager.state.value is VpnConnectionState.Idle)

            manager.onSessionlessStartFailed(VpnError.NoNodeSelected)
            advanceUntilIdle()

            val state = manager.state.value
            assertTrue(state is VpnConnectionState.Error)
            assertEquals(VpnError.NoNodeSelected, (state as VpnConnectionState.Error).error)
        }

    @Test
    fun `a sessionless start failure cannot clear a newer connect`() =
        testScope.runTest {
            // The user tapped Connect while a restore was still compiling: that
            // session owns the outcome, so the stale failure must neither clear
            // its pending session nor publish over its state.
            manager.connect()
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.Connecting)
            val pending = manager.pendingSession
            assertTrue(pending != null)

            manager.onSessionlessStartFailed(VpnError.NoNodeSelected)
            advanceUntilIdle()

            assertTrue(manager.state.value is VpnConnectionState.Connecting)
            assertEquals(pending, manager.pendingSession)
        }

    @Test
    fun `a sessionless start failure cannot clobber a live session`() =
        testScope.runTest {
            connectToRunning()

            manager.onSessionlessStartFailed(VpnError.NoNodeSelected)
            advanceUntilIdle()

            assertTrue(manager.state.value is VpnConnectionState.Connected)
        }

    @Test
    fun `rebuild callbacks are generation-guarded`() =
        testScope.runTest {
            val generation = connectToRunning()
            manager.onTunnelRebuildStarted()
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.Reconnecting)

            // A stale generation must not resolve the live session's state.
            manager.onTunnelRebuilt(generation - 1)
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.Reconnecting)

            manager.onTunnelRebuilt(generation)
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.Connected)
        }

    @Test
    fun `rebuild start is a no-op outside Connected`() =
        testScope.runTest {
            // Idle: nothing to rebuild.
            manager.onTunnelRebuildStarted()
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.Idle)

            // Already Reconnecting (e.g. network lost mid-request): the rebuild
            // must not overwrite the existing reason/attempt payload.
            connectToRunning()
            manager.onUnderlyingNetworkLost()
            advanceUntilIdle()
            val before = manager.state.value as VpnConnectionState.Reconnecting
            manager.onTunnelRebuildStarted()
            advanceUntilIdle()
            assertEquals(before, manager.state.value)
        }

    @Test
    fun `engine failure during Reconnecting converges to Error`() =
        testScope.runTest {
            val generation = connectToRunning()
            manager.onUnderlyingNetworkLost()
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.Reconnecting)

            engine.eventsFlow.emit(EngineEvent.Failed(EngineError.CoreError("boom")))
            advanceUntilIdle()
            assertEquals(1, serviceControl.disconnectStarts)
            manager.onServiceStopped(generation)
            assertTrue(manager.state.value is VpnConnectionState.Error)
        }

    @Test
    fun `adopted session gets its own generation and reports Connected`() =
        testScope.runTest {
            // Service-driven restart (process death / always-on): no
            // connect() call, generation comes from adoptSession.
            val generation = manager.adoptSession(node)
            manager.attachEngine(engine, generation)
            advanceUntilIdle()
            manager.onServiceStarted(generation)
            assertTrue(manager.state.value is VpnConnectionState.Connected)

            // The adopted generation guards telemetry like a normal one.
            engine.statsFlow.emit(stats(777))
            advanceUntilIdle()
            val state = manager.state.value as VpnConnectionState.Connected
            assertEquals(777L, state.stats?.uplinkBytesPerSec)

            // And a stale pre-adoption generation is still rejected.
            manager.onServiceStopped(generation - 1)
            assertTrue(manager.state.value is VpnConnectionState.Connected)
        }

    @Test
    fun `activeConnections mirrors engine while attached and clears on detach`() =
        testScope.runTest {
            val generation = connectToRunning()
            engine.connectionsFlow.value = listOf(conn("c1"), conn("c2"))
            advanceUntilIdle()
            assertEquals(
                listOf("c1", "c2"),
                manager.activeConnections.value.map { it.id },
            )

            manager.disconnect()
            advanceUntilIdle()
            manager.onServiceStopped(generation)
            assertTrue(manager.activeConnections.value.isEmpty())
        }

    @Test
    fun `connection snapshots from a previous session are dropped`() =
        testScope.runTest {
            val oldEngine = engine
            val genA = connectToRunning()
            oldEngine.connectionsFlow.value = listOf(conn("old"))
            advanceUntilIdle()
            assertEquals(listOf("old"), manager.activeConnections.value.map { it.id })

            manager.disconnect()
            advanceUntilIdle()
            manager.onServiceStopped(genA)
            assertTrue(manager.activeConnections.value.isEmpty())

            engine = FakeEngine()
            manager.connect()
            advanceUntilIdle()
            val genB = manager.pendingSession!!.generation
            manager.attachEngine(engine, genB)
            advanceUntilIdle()
            manager.onServiceStarted(genB)

            // Session A's tracker keeps emitting — its snapshots must not leak
            // into session B's surface.
            oldEngine.connectionsFlow.value = listOf(conn("stale"))
            advanceUntilIdle()
            assertTrue(manager.activeConnections.value.isEmpty())
        }

    @Test
    fun `closeConnection delegates to the attached engine`() =
        testScope.runTest {
            connectToRunning()
            assertTrue(manager.closeConnection("c1"))
            assertEquals(listOf("c1"), engine.closedConnectionIds)
        }

    @Test
    fun `closeConnection returns false while detached`() =
        testScope.runTest {
            assertTrue(!manager.closeConnection("c1"))
            assertTrue(engine.closedConnectionIds.isEmpty())
        }

    @Test
    fun `permission denied reports PermissionDenied`() =
        testScope.runTest {
            serviceControl.permissionIntent = Intent()
            manager.connect()
            advanceUntilIdle()
            manager.onPermissionResult(false)
            advanceUntilIdle()
            val state = manager.state.value
            assertTrue(state is VpnConnectionState.Error)
            assertTrue(
                (state as VpnConnectionState.Error).error is VpnError.PermissionDenied,
            )
            assertEquals(0, serviceControl.connectStarts)
        }

    private val node2 =
        NodeSummary(
            id = "node-2",
            name = "Second Node",
            protocol = ProtocolType.VLESS,
            server = "second.invalid",
        )

    private fun proxyGroup(selected: String? = null) =
        OutboundGroupInfo(
            tag = "proxy",
            type = "selector",
            selectable = true,
            selected = selected,
            items =
                listOf(
                    OutboundItemInfo("node-1", "vless", null),
                    OutboundItemInfo("node-2", "vless", null),
                ),
        )

    /** Mirrors the compiled layout: "auto" is the urltest group and the
     *  selector's first item. */
    private fun autoProxyGroup(selected: String? = null) =
        OutboundGroupInfo(
            tag = "proxy",
            type = "selector",
            selectable = true,
            selected = selected,
            items =
                listOf(
                    OutboundItemInfo("auto", "urltest", null),
                    OutboundItemInfo("node-1", "vless", null),
                    OutboundItemInfo("node-2", "vless", null),
                ),
        )

    private fun urltestGroup(selected: String? = null) =
        OutboundGroupInfo(
            tag = "auto",
            type = "urltest",
            selectable = false,
            selected = selected,
            items =
                listOf(
                    OutboundItemInfo("node-1", "vless", null),
                    OutboundItemInfo("node-2", "vless", null),
                ),
        )

    private fun summariesWithAuto(vararg nodes: NodeSummary): Map<String, NodeSummary> =
        nodes.associateBy { it.id } + (NodeSelection.AUTO_ID to ConfigCompiler.AUTO_NODE_SUMMARY)

    @Test
    fun `auto pick live-switches the selector to the urltest group`() =
        testScope.runTest {
            connectToRunning()
            engine.groupsFlow.value =
                listOf(
                    autoProxyGroup(selected = "node-1"),
                    urltestGroup(selected = null),
                )
            configProvider.summaries = summariesWithAuto(node2)

            configProvider.selected.value = NodeSelection.AUTO_ID
            advanceUntilIdle()

            assertEquals(listOf("proxy" to "auto"), engine.selections)
            val state = manager.state.value as VpnConnectionState.Connected
            assertEquals(NodeSelection.AUTO_ID, state.node.id)
            // No winner reported yet — the label stays generic.
            assertEquals("Auto", state.node.name)
            assertEquals(0, serviceControl.disconnectStarts)

            // The urltest group's measured winner is surfaced once reported —
            // named, never impersonated as the session node.
            engine.groupsFlow.value =
                listOf(
                    autoProxyGroup(selected = "auto"),
                    urltestGroup(selected = "node-2"),
                )
            advanceUntilIdle()

            val updated = manager.state.value as VpnConnectionState.Connected
            assertEquals(NodeSelection.AUTO_ID, updated.node.id)
            assertEquals("Auto → Second Node", updated.node.name)
            assertEquals(node2.server, updated.node.server)
        }

    @Test
    fun `auto label without a reported winner stays generic`() =
        testScope.runTest {
            connectToRunning()
            engine.groupsFlow.value =
                listOf(
                    autoProxyGroup(selected = "node-1"),
                    urltestGroup(selected = null),
                )
            configProvider.summaries = summariesWithAuto()

            configProvider.selected.value = NodeSelection.AUTO_ID
            advanceUntilIdle()

            assertEquals(listOf("proxy" to "auto"), engine.selections)
            val state = manager.state.value as VpnConnectionState.Connected
            assertEquals(NodeSelection.AUTO_ID, state.node.id)
            assertEquals("Auto", state.node.name)
        }

    @Test
    fun `node pick after auto restores the node label`() =
        testScope.runTest {
            connectToRunning()
            // Selector not yet on "auto" — the reconcile path issues the live
            // switch rather than short-circuiting on the compiled default.
            engine.groupsFlow.value =
                listOf(
                    autoProxyGroup(selected = "node-1"),
                    urltestGroup(selected = "node-1"),
                )
            configProvider.summaries = summariesWithAuto(node2)
            configProvider.selected.value = NodeSelection.AUTO_ID
            advanceUntilIdle()
            assertEquals(
                NodeSelection.AUTO_ID,
                (manager.state.value as VpnConnectionState.Connected).node.id,
            )

            configProvider.selected.value = "node-2"
            advanceUntilIdle()

            assertEquals(listOf("proxy" to "auto", "proxy" to "node-2"), engine.selections)
            assertEquals(node2, (manager.state.value as VpnConnectionState.Connected).node)
            assertEquals(0, serviceControl.disconnectStarts)
        }

    @Test
    fun `policy query completing after stop cannot resurrect a selection error`() = testScope.runTest {
        val generation = connectToRunning()
        configProvider.blockedIds = setOf("node-2")
        val gate = CompletableDeferred<Unit>()
        configProvider.selectionGate = gate
        configProvider.selected.value = "node-2"
        runCurrent()
        manager.disconnect()
        runCurrent()
        manager.onServiceStopped(generation)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(VpnConnectionState.Idle, manager.state.value)
        assertNull(manager.selectionError.value)
        assertTrue(engine.selections.isEmpty())
    }

    @Test
    fun `unsafe live selection never switches or tears down a working tunnel`() = testScope.runTest {
        connectToRunning()
        engine.groupsFlow.value = listOf(proxyGroup(selected = "node-1"))
        configProvider.blockedIds = setOf("node-2")
        configProvider.selected.value = "node-2"
        advanceUntilIdle()
        assertTrue(engine.selections.isEmpty())
        assertEquals(0, serviceControl.disconnectStarts)
        assertEquals("node-1", (manager.state.value as VpnConnectionState.Connected).node.id)
        assertEquals(VpnError.UnencryptedTransport, manager.selectionError.value)
        configProvider.selected.value = "node-1"
        advanceUntilIdle()
        assertNull(manager.selectionError.value)
    }

    @Test
    fun `selection change live-switches the engine and updates the shown node`() =
        testScope.runTest {
            val generation = connectToRunning()
            engine.groupsFlow.value = listOf(proxyGroup(selected = "node-1"))
            configProvider.summaries = mapOf("node-2" to node2)

            configProvider.selected.value = "node-2"
            advanceUntilIdle()

            assertEquals(listOf("proxy" to "node-2"), engine.selections)
            val state = manager.state.value
            assertTrue(state is VpnConnectionState.Connected)
            assertEquals(node2, (state as VpnConnectionState.Connected).node)
            // No teardown — the switch happened inside the live session.
            assertEquals(0, serviceControl.disconnectStarts)
        }

    @Test
    fun `a rebuilt engine re-applies the persisted selection`() =
        testScope.runTest {
            val generation = connectToRunning()
            engine.groupsFlow.value = listOf(proxyGroup(selected = "node-1"))
            configProvider.summaries = mapOf("node-2" to node2)
            configProvider.selected.value = "node-2"
            advanceUntilIdle()
            assertEquals(node2, (manager.state.value as VpnConnectionState.Connected).node)

            // In-session rebuild: the fresh engine starts on its compiled
            // default ("node-1") — attach must push the pick back to node-2.
            val rebuilt = FakeEngine()
            rebuilt.groupsFlow.value = listOf(proxyGroup(selected = "node-1"))
            manager.attachEngine(rebuilt, generation)
            advanceUntilIdle()

            assertEquals(listOf("proxy" to "node-2"), rebuilt.selections)
            assertEquals(node2, (manager.state.value as VpnConnectionState.Connected).node)
        }

    @Test
    fun `a rejected live switch falls back to reconnect`() =
        testScope.runTest {
            val generation = connectToRunning()
            engine.groupsFlow.value = listOf(proxyGroup(selected = "node-1"))
            engine.selectOutboundResult = false

            configProvider.selected.value = "node-2"
            runCurrent()
            // Live switch failed → teardown requested; the session waits for
            // the service to finish stopping before connecting again.
            assertEquals(1, serviceControl.disconnectStarts)
            assertTrue(manager.state.value is VpnConnectionState.Stopping)

            manager.onServiceStopped(generation)
            advanceUntilIdle()
            assertEquals(2, serviceControl.connectStarts)
            assertTrue(manager.state.value is VpnConnectionState.Connecting)
        }

    @Test
    fun `engine with no groups falls back to reconnect when the pick moved`() =
        testScope.runTest {
            val generation = connectToRunning()
            // Control channel never reports groups — the live path is dead.
            configProvider.selected.value = "node-2"
            advanceTimeBy(6_000)
            runCurrent()
            assertEquals(1, serviceControl.disconnectStarts)
            assertTrue(manager.state.value is VpnConnectionState.Stopping)

            manager.onServiceStopped(generation)
            advanceUntilIdle()
            assertEquals(2, serviceControl.connectStarts)
        }

    @Test
    fun `engine already on the pick does not switch or reconnect`() =
        testScope.runTest {
            connectToRunning()
            engine.groupsFlow.value = listOf(proxyGroup(selected = "node-1"))
            configProvider.selected.value = "node-1"
            advanceUntilIdle()
            assertTrue(engine.selections.isEmpty())
            assertEquals(0, serviceControl.disconnectStarts)
        }

    @Test
    fun `selection while idle does not touch the engine`() =
        testScope.runTest {
            configProvider.selected.value = "node-2"
            advanceUntilIdle()
            assertTrue(engine.selections.isEmpty())
            assertEquals(0, serviceControl.disconnectStarts)
            assertTrue(manager.state.value is VpnConnectionState.Idle)
        }

    @Test
    fun `reconnect stops the session and connects again`() =
        testScope.runTest {
            val generation = connectToRunning()
            val job = async { manager.reconnect() }
            runCurrent()
            assertTrue(manager.state.value is VpnConnectionState.Stopping)
            assertEquals(1, serviceControl.disconnectStarts)

            manager.onServiceStopped(generation)
            advanceUntilIdle()
            assertTrue(job.await())
            assertEquals(2, serviceControl.connectStarts)
            assertTrue(manager.state.value is VpnConnectionState.Connecting)
        }

    @Test
    fun `reconnect is a no-op while idle`() =
        testScope.runTest {
            assertFalse(manager.reconnect())
            assertEquals(0, serviceControl.disconnectStarts)
            assertEquals(0, serviceControl.connectStarts)
        }

    @Test
    fun `reconnect during consent re-runs connect with fresh settings`() =
        testScope.runTest {
            serviceControl.permissionIntent = Intent()
            manager.connect()
            advanceUntilIdle()
            assertTrue(manager.state.value is VpnConnectionState.PermissionRequired)

            assertTrue(manager.reconnect())
            advanceUntilIdle()
            // Recompiled and re-prepared — consent is asked again for the
            // new session (the fake always returns an intent).
            assertTrue(manager.state.value is VpnConnectionState.PermissionRequired)
            assertTrue(manager.pendingSession != null)
        }

    @Test
    fun `unused auto winner changes and group reorder neither cancel nor erase path evidence`() =
        testScope.runTest {
            var cancelled = false
            val result = kotlinx.coroutines.CompletableDeferred<IpCheckResult>()
            probeCheck = {
                try {
                    result.await()
                } finally {
                    cancelled = true
                }
            }
            configProvider.selected.value = node.id
            // The compiled layout: a manual pick rides the "proxy" selector
            // while the urltest group keeps re-measuring in the background.
            engine.groupsFlow.value =
                listOf(autoProxyGroup(selected = node.id), urltestGroup(selected = "node-2"))
            connectToRunning()
            runCurrent()
            assertEquals(1, probeCalls)

            // A new unused urltest winner carries no traffic — the live check
            // must not be cancelled.
            engine.groupsFlow.value =
                listOf(autoProxyGroup(selected = node.id), urltestGroup(selected = "node-1"))
            runCurrent()
            assertFalse(cancelled)

            // Same selections, different report order — still no path change.
            engine.groupsFlow.value =
                listOf(urltestGroup(selected = "node-1"), autoProxyGroup(selected = node.id))
            runCurrent()
            assertFalse(cancelled)

            result.complete(IpCheckResult("192.0.2.1", 1, null))
            runCurrent()
            val success = manager.health.value.observations.first { it.level == HealthLevel.LastSuccessfulCheck }
            assertEquals(HealthStatus.Ok, success.status)

            // Unused churn after evidence landed must not erase it either.
            engine.groupsFlow.value =
                listOf(autoProxyGroup(selected = node.id), urltestGroup(selected = "node-2"))
            runCurrent()
            val after = manager.health.value.observations.first { it.level == HealthLevel.LastSuccessfulCheck }
            assertEquals(HealthStatus.Ok, after.status)
            assertEquals(HealthReason.HttpResponseRouteUnverified, after.reason)
            assertTrue(manager.state.value is VpnConnectionState.Connected)
        }

    @Test
    fun `an active auto winner change invalidates path evidence and cancels the live check`() =
        testScope.runTest {
            var cancelled = false
            probeCheck = {
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            configProvider.config = config.copy(node = ConfigCompiler.AUTO_NODE_SUMMARY)
            configProvider.selected.value = NodeSelection.AUTO_ID
            configProvider.summaries = summariesWithAuto(node2)
            engine.groupsFlow.value =
                listOf(autoProxyGroup(selected = NodeSelection.AUTO_ID), urltestGroup(selected = "node-1"))
            connectToRunning()
            runCurrent()
            assertEquals(1, probeCalls)

            // The selector actually rides the urltest group — a new measured
            // winner IS a different outbound.
            engine.groupsFlow.value =
                listOf(autoProxyGroup(selected = NodeSelection.AUTO_ID), urltestGroup(selected = "node-2"))
            runCurrent()

            assertTrue(cancelled)
            assertTrue(
                manager.health.value.observations
                    .filter { it.level in ConnectionHealthStore.PATH_LEVELS }
                    .all { it.status == HealthStatus.Unverified && it.reason == HealthReason.PathChanged },
            )
            // Path invalidation is evidence-only — the session stays up and
            // the single-shot check is not restarted.
            assertTrue(manager.state.value is VpnConnectionState.Connected)
            assertEquals(0, serviceControl.disconnectStarts)
            assertEquals(1, probeCalls)
        }
}
