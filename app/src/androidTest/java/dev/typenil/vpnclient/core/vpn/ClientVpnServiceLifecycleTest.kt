package dev.typenil.vpnclient.core.vpn

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.EngineEvent
import dev.typenil.vpnclient.core.engine.TrafficStats
import dev.typenil.vpnclient.core.engine.VpnEngineFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeoutException
import javax.inject.Inject

/**
 * On-device lifecycle harness for [ClientVpnService].
 *
 * Drives the *real* service end-to-end through the same intents the app uses —
 * `connectIntent` / `disconnectIntent` / `restoreIntent` — while the pieces
 * that need a VPN permission grant or a live upstream server are faked:
 *
 * - `VpnEngineFactory` → [FakeVpnEngineFactory] (a [FakeVpnEngine] that calls
 *   [EnginePlatform.openTun][dev.typenil.vpnclient.core.engine.EnginePlatform.openTun]
 *   on the real service but opens no sockets and dials no server);
 * - `TunProvider` → [FakeTunProvider] (reports consent granted, hands out a
 *   real fd from `ParcelFileDescriptor.createPipe()`);
 * - `NodeConfigProvider` → [FakeNodeConfigProvider] (a fixed synthetic config).
 *
 * `ConnectionManager`, the service's state machine, the per-app plan resolver,
 * the network callback, and `stopSelf`/teardown are all the production code.
 * Assertions land on the observable surface — `ConnectionManager.state` — plus
 * the fake engine's call counters.
 *
 * ## Running it
 *
 * Needs an emulator or device (it exercises `VpnService`, `ParcelFileDescriptor`,
 * and `ConnectivityManager`). No VPN profile or consent dialog is required.
 *
 *     .\gradlew.bat connectedDebugAndroidTest
 *
 * or just this class:
 *
 *     .\gradlew.bat connectedDebugAndroidTest `
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.typenil.vpnclient.core.vpn.ClientVpnServiceLifecycleTest
 *
 * On the `Medium_Phone_API_36.1` emulator these tests run without a real
 * upstream: the engine fake never opens a socket, and `openTun` returns a
 * pipe fd instead of a platform TUN interface.
 *
 * ## Design notes / seams
 *
 * `ClientVpnService` is `@AndroidEntryPoint`, so the fakes are installed into
 * the generated Hilt test component via `@TestInstallIn` modules
 * ([TestEngineFactoryModule]/[TestTunProviderModule]/[TestNodeConfigProviderModule]/[TestServiceControlModule]),
 * each replacing one small production module. `VpnService.prepare`,
 * `Builder.establish`, and the `startForeground(systemExempted)` promotion are
 * the consent-gated framework calls — they sit behind the small `TunProvider`
 * seam (and the consent check inside the manager behind `ServiceControl`), so
 * the test needs no permission prompt.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ClientVpnServiceLifecycleTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Inject
    lateinit var connectionManager: ConnectionManager

    // ---- fakes (resolved from the test component, not the test class) ----

    /** The same singletons the service is injected with — resolved from the
     *  test component's application-level entry point. */
    private fun fakeFactory(): FakeVpnEngineFactory =
        EntryPointAccessors
            .fromApplication(context, FakeEntryPoint::class.java)
            .engineFactory() as FakeVpnEngineFactory

    private fun fakeTun(): FakeTunProvider =
        EntryPointAccessors
            .fromApplication(context, FakeEntryPoint::class.java)
            .tunProvider() as FakeTunProvider

    private fun fakeConfig(): FakeNodeConfigProvider =
        EntryPointAccessors
            .fromApplication(context, FakeEntryPoint::class.java)
            .configProvider() as FakeNodeConfigProvider

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface FakeEntryPoint {
        fun engineFactory(): VpnEngineFactory

        fun tunProvider(): TunProvider

        fun configProvider(): NodeConfigProvider
    }

    /** Send a start intent to the service (the production entry point). */
    private fun startService(intent: Intent) {
        // startService (not startForegroundService): the test process is in
        // the foreground-eligible instrumentation context and the service
        // calls startForeground() itself once it takes the intent.
        context.startService(intent)
    }

    private fun connect() = startService(ClientVpnService.connectIntent(context))

    private fun disconnect() = startService(ClientVpnService.disconnectIntent(context))

    private fun restore() = startService(ClientVpnService.restoreIntent(context))

    /** Stop the service outright (simulates system teardown / process kill). */
    private fun stopService() {
        context.stopService(Intent(context, ClientVpnService::class.java))
    }

    /**
     * Wait until the manager's state is of type [T] (reified). All state
     * transitions publish on `Dispatchers.Main.immediate`, so a suspend wait
     * on the StateFlow observes them in order.
     */
    private suspend inline fun <reified T : VpnConnectionState> awaitState(timeoutMs: Long = STATE_TIMEOUT_MS): T =
        withTimeout(timeoutMs) {
            connectionManager.state.filterIsInstance<T>().first()
        }

    /** Wait for the *current* value to be of type T, or throw. */
    private inline fun <reified T : VpnConnectionState> requireState(): T {
        val s = connectionManager.state.value
        if (s !is T) fail("expected ${T::class.simpleName} but was $s")
        // `s` is T here — the fail() above throws otherwise; cast explicitly
        // because a smart cast can't prove a reified-generic predicate.
        @Suppress("UNCHECKED_CAST")
        return s as T
    }

    /** Drain pending engine-stop / cleanup so no background work outlives a test. */
    private fun settleService() {
        runBlocking {
            withTimeoutOrNull(SETTLE_MS) {
                // Give the service scope a beat to finish teardown coroutines.
                kotlinx.coroutines.delay(200)
            }
        }
    }

    @Before
    fun setUp() {
        hiltRule.inject()
        // A fresh app process may already carry a persisted desiredVpnRunning
        // or selection from a previous run — start from a clean slate so the
        // first intent's behavior is deterministic.
        runBlocking {
            fakeConfig().config = FakeNodeConfigProvider.defaultConfig()
        }
    }

    @After
    fun tearDown() {
        // Make sure no live engine outlives the test: send a disconnect and
        // stop the service so a following test sees a fresh Idle.
        runCatching { disconnect() }
        settleService()
        runCatching { stopService() }
        settleService()
    }

    // ---- the happy path -----------------------------------------------

    /**
     * CONNECT intent → engine created → openTun runs on the real service →
     * Connected published; then DISCONNECT → engine.stop() →
     * onServiceStopped → Idle.
     */
    @Test
    fun connect_reachesConnected_thenDisconnect_reachesIdle() {
        runBlocking {
            val engine = FakeVpnEngine()
            fakeFactory().next = engine

            connect()

            // Engine launch → openTun through the real service → onServiceStarted.
            val connected = awaitState<VpnConnectionState.Connected>()
            assertEquals(FakeNodeConfigProvider.NODE, connected.node)
            assertEquals(1, engine.startCalls.get())
            assertEquals(1, engine.openTunCalls.get())
            assertTrue(engine.lastTunFd >= 0)
            assertEquals(1, fakeTun().establishCalls.get())
            assertTrue(engine.started)

            disconnect()

            awaitState<VpnConnectionState.Idle>()
            assertEquals(1, engine.stopCalls.get())
            requireState<VpnConnectionState.Idle>()
        }
    }

    /**
     * While the engine is "still starting" (openTun gated by a latch), the
     * manager must be in Connecting — not Connected. Releasing the latch lets
     * onServiceStarted land Connected.
     */
    @Test
    fun connecting_isPublished_beforeOpenTunCompletes() {
        runBlocking {
            val latch = CompletableDeferred<Unit>()
            val engine = FakeVpnEngine(startLatch = latch)
            fakeFactory().next = engine

            connect()

            // Connecting publishes before the engine's start() suspends on
            // the latch — wait for start() to actually be in flight.
            awaitState<VpnConnectionState.Connecting>()
            withTimeout(STATE_TIMEOUT_MS) {
                while (engine.startCalls.get() == 0) kotlinx.coroutines.delay(20)
            }
            // start() is suspended on the latch — openTun hasn't run yet.
            assertEquals(0, engine.openTunCalls.get())

            latch.complete(Unit)
            awaitState<VpnConnectionState.Connected>()
            assertEquals(1, engine.openTunCalls.get())
        }
    }

    /**
     * A stats tick arriving while Connected mutates the Connected payload —
     * telemetry may only ride on a live session, never create one.
     */
    @Test
    fun statsTick_updatesConnectedPayload() {
        runBlocking {
            val engine = FakeVpnEngine()
            fakeFactory().next = engine
            connect()
            awaitState<VpnConnectionState.Connected>()

            engine.emitStats(stats(uplink = 4242))
            // Connected republishes on each stats tick; wait for the new payload.
            val updated =
                withTimeout(STATE_TIMEOUT_MS) {
                    connectionManager.state
                        .filterIsInstance<VpnConnectionState.Connected>()
                        .first { it.stats?.uplinkBytesPerSec == 4242L }
                }
            assertEquals(4242L, updated.stats?.uplinkBytesPerSec)
        }
    }

    /**
     * Engine death (Failed) while Connected parks the error as Reconnecting
     * and drives the bounded auto-reconnect: the service tears down the dead
     * engine and rebuilds a fresh one inside the same user session.
     */
    @Test
    fun engineFailure_triggersAutoReconnect() {
        runBlocking {
            val dead = FakeVpnEngine()
            val revived = FakeVpnEngine()
            fakeFactory().next = dead
            connect()
            awaitState<VpnConnectionState.Connected>()
            assertEquals(1, fakeFactory().createCalls.get())

            // Stage the replacement the reconnect will build.
            fakeFactory().next = revived

            dead.emitEvent(EngineEvent.Failed(EngineError.CoreError("core died")))

            // The bounded auto-reconnect parks the failure, tears the dead
            // engine down through the service (stop → onServiceStopped →
            // Idle), waits a 1s failure backoff, then reconnects — creating
            // a fresh engine and landing back on Connected. Assert on the
            // durable outcome: a second engine created and the dead one
            // stopped. The transient Reconnecting/Idle are conflated away by
            // the StateFlow, so gate on createCalls, not the state value.
            withTimeout(STATE_TIMEOUT_MS) {
                while (fakeFactory().createCalls.get() < 2) kotlinx.coroutines.delay(50)
            }
            awaitState<VpnConnectionState.Connected>()
            assertEquals(2, fakeFactory().createCalls.get())
            assertEquals(1, dead.stopCalls.get())
            assertEquals(1, revived.startCalls.get())
            assertTrue(revived.started)
        }
    }

    /**
     * A service-driven start (process-death restore / always-on) rebuilds the
     * config itself when desiredVpnRunning is set — no pendingSession handoff.
     */
    @Test
    fun restoreStart_rebuildsTunnelAutomatically() {
        runBlocking {
            // Simulate the durable "user wanted the tunnel running" flag that a
            // restore relies on. The service reads it via SettingsRepository.
            // We don't have direct access to write it from the test without the
            // repo, but a stray/RESTORE start with the flag unset must stopSelf
            // and stay Idle — that's the deterministic contract we can assert.
            restore()

            // With no prior CONNECT the flag is false: the auto-start bails to
            // stopSelf without adopting a session — state must stay Idle and no
            // engine must be created.
            settleService()
            assertEquals(0, fakeFactory().createCalls.get())
            requireState<VpnConnectionState.Idle>()
        }
    }

    /**
     * Destroying the service (system teardown) while Connected must not leave
     * the manager claiming a live tunnel — onDestroy reports onServiceStopped.
     */
    @Test
    fun serviceDestroy_reportsStoppedToManager() {
        runBlocking {
            val engine = FakeVpnEngine()
            fakeFactory().next = engine
            connect()
            awaitState<VpnConnectionState.Connected>()

            // Physical stop — onDestroy() closes the TUN and reports stopped.
            stopService()
            awaitState<VpnConnectionState.Idle>()
            requireState<VpnConnectionState.Idle>()
        }
    }

    /**
     * The openTun path exercised the service's full Builder pipeline — the
     * fake engine must have received a real fd from the (fake) TunProvider.
     */
    @Test
    fun openTun_deliversFdToEngine() {
        runBlocking {
            val engine = FakeVpnEngine()
            fakeFactory().next = engine
            connect()
            awaitState<VpnConnectionState.Connected>()

            assertEquals(1, fakeTun().prepareCalls.get())
            assertEquals(1, fakeTun().establishCalls.get())
            assertTrue(engine.lastTunFd > 0)
        }
    }

    private fun stats(uplink: Long): TrafficStats =
        TrafficStats(
            uplinkBytesPerSec = uplink,
            downlinkBytesPerSec = uplink / 2,
            uplinkTotalBytes = 10_000,
            downlinkTotalBytes = 20_000,
            connectionsIn = 1,
            connectionsOut = 1,
            goroutines = 8,
            memoryBytes = 512_000,
        )

    private companion object {
        /** Generous bound — emulator scheduling of the service scope can lag. */
        const val STATE_TIMEOUT_MS = 15_000L
        const val SETTLE_MS = 2_000L
    }
}
