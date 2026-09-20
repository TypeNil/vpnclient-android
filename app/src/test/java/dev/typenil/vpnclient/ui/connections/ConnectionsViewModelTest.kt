package dev.typenil.vpnclient.ui.connections

import android.content.Intent
import dev.typenil.vpnclient.core.engine.ConnectionInfo
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.EngineEvent
import dev.typenil.vpnclient.core.engine.OutboundGroupInfo
import dev.typenil.vpnclient.core.engine.TrafficStats
import dev.typenil.vpnclient.core.engine.VpnEngine
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.NodeConfigProvider
import dev.typenil.vpnclient.core.vpn.ServiceControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UI-state tests for [ConnectionsViewModel] — the screen may only render
 * connections while the state machine says Connected. Uses a real
 * [ConnectionManager] over fake service/engine, same as the manager tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private lateinit var engine: FakeEngine
    private lateinit var manager: ConnectionManager
    private lateinit var viewModel: ConnectionsViewModel

    private val node = NodeSummary(
        id = "node-1",
        name = "Test Node",
        protocol = ProtocolType.VLESS,
        server = "example.invalid",
    )

    private class FakeServiceControl : ServiceControl {
        override fun prepareVpn(): Intent? = null
        override fun startConnectService() = Unit
        override fun startDisconnectService() = Unit
    }

    private class FakeNodeConfigProvider(
        private val config: EngineConfig,
    ) : NodeConfigProvider {
        override suspend fun compileSelected(): EngineConfig = config
    }

    private class FakeEngine : VpnEngine {
        val connectionsFlow = MutableStateFlow<List<ConnectionInfo>>(emptyList())
        val closedConnectionIds = mutableListOf<String>()
        override val stats: Flow<TrafficStats> = MutableSharedFlow()
        override val events: Flow<EngineEvent> = MutableSharedFlow()
        override val groups: StateFlow<List<OutboundGroupInfo>> =
            MutableStateFlow(emptyList())
        override val connections: StateFlow<List<ConnectionInfo>> get() = connectionsFlow
        override suspend fun validate(config: EngineConfig) = Unit
        override suspend fun start(config: EngineConfig) = Unit
        override suspend fun stop() = Unit
        override suspend fun onUnderlyingNetworkChanged() = Unit
        override suspend fun selectOutbound(groupTag: String, outboundTag: String) = true
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
        createdAtMs = 0,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        engine = FakeEngine()
        manager = ConnectionManager(
            FakeServiceControl(),
            FakeNodeConfigProvider(EngineConfig(configJson = "{}", node = node)),
        )
        viewModel = ConnectionsViewModel(manager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun connectToRunning(): Long {
        manager.connect()
        testScope.advanceUntilIdle()
        val generation = manager.pendingSession!!.generation
        manager.attachEngine(engine, generation)
        testScope.advanceUntilIdle()
        manager.onServiceStarted(generation)
        return generation
    }

    @Test
    fun `uiState gates the list on Connected`() = testScope.runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        // Nothing connected — the screen must not show a list.
        assertFalse(viewModel.uiState.value.connected)

        connectToRunning()
        engine.connectionsFlow.value = listOf(conn("c1"))
        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertTrue(ui.connected)
        assertEquals(listOf("c1"), ui.connections.map { it.id })
    }

    @Test
    fun `closeConnection forwards the id to the engine`() = testScope.runTest {
        connectToRunning()
        viewModel.closeConnection("c1")
        advanceUntilIdle()
        assertEquals(listOf("c1"), engine.closedConnectionIds)
    }

    @Test
    fun `metaLine prefers the resolved app label`() {
        assertEquals(
            "Test App · tcp · tls",
            metaLine(conn("c1"), appLabel = "Test App"),
        )
    }

    @Test
    fun `metaLine falls back to the package name and omits empty parts`() {
        assertEquals(
            "dev.test.app · tcp · tls",
            metaLine(conn("c1"), appLabel = null),
        )
        assertEquals(
            "udp",
            metaLine(
                conn("c2").copy(
                    packages = emptyList(),
                    protocol = "",
                    network = "udp",
                ),
                appLabel = null,
            ),
        )
    }
}
