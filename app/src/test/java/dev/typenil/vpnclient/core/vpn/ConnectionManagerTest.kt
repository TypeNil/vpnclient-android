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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
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

    private val node = NodeSummary(
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
        override fun startConnectService() { connectStarts++ }
        override fun startDisconnectService() {
            disconnectStarts++
            disconnectFailure?.let { throw it }
        }
        override fun stopVpnService(): Boolean {
            stopServiceCalls++
            return stopServiceResult
        }
    }

    private class FakeNodeConfigProvider(
        var config: EngineConfig?,
        var failure: Exception? = null,
    ) : NodeConfigProvider {
        val selected = MutableStateFlow<String?>(null)
        var summaries = mapOf<String, NodeSummary>()

        /** Fingerprint of the enabled set the live session compares against. */
        val enabledFingerprint = MutableStateFlow("fingerprint-a")

        /** Fingerprint the last compile ran against — the fake compile just
         *  records whatever the test set as [enabledFingerprint]. */
        val compiledFingerprint = MutableStateFlow<String?>(null)

        override suspend fun compileSelected(): EngineConfig? {
            failure?.let { throw it }
            compiledFingerprint.value = enabledFingerprint.value
            return config
        }

        override val selectedNodeId: Flow<String?> get() = selected
        override suspend fun nodeSummary(id: String): NodeSummary? = summaries[id]
        override val enabledNodeSetFingerprint: Flow<String> get() = enabledFingerprint
        override val compiledNodeSetFingerprint: StateFlow<String?> get() = compiledFingerprint
    }

    private class FakeEngine : VpnEngine {
        val statsFlow = MutableSharedFlow<TrafficStats>(replay = 1)
        val eventsFlow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
        val groupsFlow = MutableStateFlow<List<OutboundGroupInfo>>(emptyList())
        val connectionsFlow = MutableStateFlow<List<ConnectionInfo>>(emptyList())
        val closedConnectionIds = mutableListOf<String>()
        val selections = mutableListOf<Pair<String, String>>()
        var selectOutboundResult = true
        override val stats: Flow<TrafficStats> get() = statsFlow
        override val events: Flow<EngineEvent> get() = eventsFlow
        override val groups: StateFlow<List<OutboundGroupInfo>> get() = groupsFlow
        override val connections: StateFlow<List<ConnectionInfo>> get() = connectionsFlow
        var stopCalls = 0
        override suspend fun validate(config: EngineConfig) = Unit
        override suspend fun start(config: EngineConfig) = Unit
        override suspend fun stop() { stopCalls++ }
        override suspend fun onUnderlyingNetworkChanged() = Unit
        override suspend fun selectOutbound(groupTag: String, outboundTag: String): Boolean {
            selections += groupTag to outboundTag
            if (selectOutboundResult) {
                // Mirror the real engine: a successful switch is reflected
                // in the next groups push as the group's selected item.
                groupsFlow.value = groupsFlow.value.map { g ->
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

    private fun conn(id: String) = ConnectionInfo(
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

    private fun stats(speed: Long = 1000L) = TrafficStats(
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
        manager = ConnectionManager(serviceControl, configProvider)
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

    @Test
    fun `connect reaches Connected via service callbacks`() = testScope.runTest {
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
    fun `late stats after Error do not resurrect Connected`() = testScope.runTest {
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
    fun `unexpected core stop auto-reconnects after teardown`() = testScope.runTest {
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
    fun `failure reconnect budget exhausts into Error`() = testScope.runTest {
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
    fun `user disconnect during pending failure lands on Idle`() = testScope.runTest {
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
    fun `disconnect produces Stopping then Idle`() = testScope.runTest {
        val generation = connectToRunning()
        manager.disconnect()
        advanceUntilIdle()
        assertTrue(manager.state.value is VpnConnectionState.Stopping)
        manager.onServiceStopped(generation)
        assertTrue(manager.state.value is VpnConnectionState.Idle)
    }

    @Test
    fun `stale generation callbacks are ignored`() = testScope.runTest {
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
    fun `emissions from previous session are dropped`() = testScope.runTest {
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
    fun `duplicate connect and disconnect are no-ops`() = testScope.runTest {
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
    fun `connect without selected node reports NoNodeSelected`() = testScope.runTest {
        configProvider.config = null
        manager.connect()
        advanceUntilIdle()
        val state = manager.state.value
        assertTrue(state is VpnConnectionState.Error)
        assertTrue((state as VpnConnectionState.Error).error is VpnError.NoNodeSelected)
        assertEquals(0, serviceControl.connectStarts)
    }

    @Test
    fun `permission flow requires consent before starting service`() = testScope.runTest {
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
    fun `underlying transport resets when the session ends`() = testScope.runTest {
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
    fun `a sessionless start failure cannot clear a newer connect`() = testScope.runTest {
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
    fun `a sessionless start failure cannot clobber a live session`() = testScope.runTest {
        connectToRunning()

        manager.onSessionlessStartFailed(VpnError.NoNodeSelected)
        advanceUntilIdle()

        assertTrue(manager.state.value is VpnConnectionState.Connected)
    }

    @Test
    fun `rebuild callbacks are generation-guarded`() = testScope.runTest {
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
    fun `rebuild start is a no-op outside Connected`() = testScope.runTest {
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
    fun `engine failure during Reconnecting converges to Error`() = testScope.runTest {
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
    fun `connection snapshots from a previous session are dropped`() = testScope.runTest {
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
    fun `closeConnection delegates to the attached engine`() = testScope.runTest {
        connectToRunning()
        assertTrue(manager.closeConnection("c1"))
        assertEquals(listOf("c1"), engine.closedConnectionIds)
    }

    @Test
    fun `closeConnection returns false while detached`() = testScope.runTest {
        assertTrue(!manager.closeConnection("c1"))
        assertTrue(engine.closedConnectionIds.isEmpty())
    }

    @Test
    fun `permission denied reports PermissionDenied`() = testScope.runTest {
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

    private val node2 = NodeSummary(
        id = "node-2",
        name = "Second Node",
        protocol = ProtocolType.VLESS,
        server = "second.invalid",
    )

    private fun proxyGroup(selected: String? = null) = OutboundGroupInfo(
        tag = "proxy",
        type = "selector",
        selectable = true,
        selected = selected,
        items = listOf(
            OutboundItemInfo("node-1", "vless", null),
            OutboundItemInfo("node-2", "vless", null),
        ),
    )

    /** Mirrors the compiled layout: "auto" is the urltest group and the
     *  selector's first item. */
    private fun autoProxyGroup(selected: String? = null) = OutboundGroupInfo(
        tag = "proxy",
        type = "selector",
        selectable = true,
        selected = selected,
        items = listOf(
            OutboundItemInfo("auto", "urltest", null),
            OutboundItemInfo("node-1", "vless", null),
            OutboundItemInfo("node-2", "vless", null),
        ),
    )

    private fun urltestGroup(selected: String? = null) = OutboundGroupInfo(
        tag = "auto",
        type = "urltest",
        selectable = false,
        selected = selected,
        items = listOf(
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
            engine.groupsFlow.value = listOf(
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
            assertEquals("Auto · Fastest", state.node.name)
            assertEquals(0, serviceControl.disconnectStarts)

            // The urltest group's measured winner is surfaced once reported —
            // named, never impersonated as the session node.
            engine.groupsFlow.value = listOf(
                autoProxyGroup(selected = "auto"),
                urltestGroup(selected = "node-2"),
            )
            advanceUntilIdle()

            val updated = manager.state.value as VpnConnectionState.Connected
            assertEquals(NodeSelection.AUTO_ID, updated.node.id)
            assertEquals("Auto · Fastest → Second Node", updated.node.name)
            assertEquals(node2.server, updated.node.server)
        }

    @Test
    fun `auto label without a reported winner stays generic`() =
        testScope.runTest {
            connectToRunning()
            engine.groupsFlow.value = listOf(
                autoProxyGroup(selected = "node-1"),
                urltestGroup(selected = null),
            )
            configProvider.summaries = summariesWithAuto()

            configProvider.selected.value = NodeSelection.AUTO_ID
            advanceUntilIdle()

            assertEquals(listOf("proxy" to "auto"), engine.selections)
            val state = manager.state.value as VpnConnectionState.Connected
            assertEquals(NodeSelection.AUTO_ID, state.node.id)
            assertEquals("Auto · Fastest", state.node.name)
        }

    @Test
    fun `node pick after auto restores the node label`() = testScope.runTest {
        connectToRunning()
        // Selector not yet on "auto" — the reconcile path issues the live
        // switch rather than short-circuiting on the compiled default.
        engine.groupsFlow.value = listOf(
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
    fun `a rejected live switch falls back to reconnect`() = testScope.runTest {
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
    fun `selection while idle does not touch the engine`() = testScope.runTest {
        configProvider.selected.value = "node-2"
        advanceUntilIdle()
        assertTrue(engine.selections.isEmpty())
        assertEquals(0, serviceControl.disconnectStarts)
        assertTrue(manager.state.value is VpnConnectionState.Idle)
    }

    @Test
    fun `reconnect stops the session and connects again`() = testScope.runTest {
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
    fun `reconnect is a no-op while idle`() = testScope.runTest {
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
}
