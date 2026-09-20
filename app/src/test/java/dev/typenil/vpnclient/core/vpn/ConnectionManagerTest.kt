package dev.typenil.vpnclient.core.vpn

import android.content.Intent
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.EngineEvent
import dev.typenil.vpnclient.core.engine.OutboundGroupInfo
import dev.typenil.vpnclient.core.engine.TrafficStats
import dev.typenil.vpnclient.core.engine.VpnEngine
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
        override fun prepareVpn(): Intent? = permissionIntent
        override fun startConnectService() { connectStarts++ }
        override fun startDisconnectService() { disconnectStarts++ }
    }

    private class FakeNodeConfigProvider(
        var config: EngineConfig?,
        var failure: Exception? = null,
    ) : NodeConfigProvider {
        override suspend fun compileSelected(): EngineConfig? {
            failure?.let { throw it }
            return config
        }
    }

    private class FakeEngine : VpnEngine {
        val statsFlow = MutableSharedFlow<TrafficStats>(replay = 1)
        val eventsFlow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
        override val stats: Flow<TrafficStats> get() = statsFlow
        override val events: Flow<EngineEvent> get() = eventsFlow
        override val groups: StateFlow<List<OutboundGroupInfo>> =
            MutableStateFlow(emptyList())
        var stopCalls = 0
        override suspend fun validate(config: EngineConfig) = Unit
        override suspend fun start(config: EngineConfig) = Unit
        override suspend fun stop() { stopCalls++ }
        override suspend fun onUnderlyingNetworkChanged() = Unit
        override suspend fun onDeviceIdle(idle: Boolean) = Unit
        override suspend fun selectOutbound(groupTag: String, outboundTag: String) = true
        override suspend fun urlTest(groupTag: String) = Unit
    }

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
    fun `unexpected core stop converges to Error after teardown`() = testScope.runTest {
        val generation = connectToRunning()
        engine.eventsFlow.emit(EngineEvent.StoppedUnexpectedly)
        advanceUntilIdle()
        assertEquals(1, serviceControl.disconnectStarts)
        // Error is published only once teardown completes.
        manager.onServiceStopped(generation)
        val state = manager.state.value
        assertTrue(state is VpnConnectionState.Error)
        assertTrue(
            (state as VpnConnectionState.Error).error is VpnError.EngineFailed,
        )
    }

    @Test
    fun `user disconnect during pending failure lands on Idle`() = testScope.runTest {
        val generation = connectToRunning()
        engine.eventsFlow.emit(EngineEvent.StoppedUnexpectedly)
        advanceUntilIdle()

        manager.disconnect()
        advanceUntilIdle()
        manager.onServiceStopped(generation)
        assertTrue(manager.state.value is VpnConnectionState.Idle)
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
}
