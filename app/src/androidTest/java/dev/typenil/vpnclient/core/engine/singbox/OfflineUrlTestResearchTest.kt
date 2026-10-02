package dev.typenil.vpnclient.core.engine.singbox

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import dev.typenil.vpnclient.R04ReceiverIsolation
import io.nekohasekai.libbox.WIFIState
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * R-0.4 research gate: can the pinned libbox 1.14.1 control path trigger a
 * credential-free proxy-path URL measurement with NO tun inbound and NO
 * openTun call, entirely on loopback fixtures?
 *
 * Isolation contract (architect gate, supervisor-approved):
 * - Exclusive instrumentation run only, opt-in via `-e r04 1` — without the
 *   argument the whole class is skipped by the Assume gate in
 *   [setupNative] (a plain `connectedDebugAndroidTest` must never share a
 *   process-global `Libbox.setup` with RuleSetCoreValidationTest /
 *   WireGuardEndpointTest / any VPN-path class in the same session).
 *   `Libbox.setup()` is process-global and this class repoints it at unique
 *   dirs under the target app's `cacheDir`.
 * - No SingBoxEngine/ConnectionManager/VpnService/Activity/Room — raw
 *   CommandServer + CommandClient against the pinned AAR only.
 * - Synthetic config: no inbounds, one no-auth SOCKS5 outbound (loopback),
 *   one urltest group with an explicit loopback URL. No credentials, no
 *   real endpoints, nothing imported.
 * - Assertions required for a GO: zero openTun calls; SOCKS5 negotiation +
 *   CONNECT observed at the fixture; HTTP HEAD for the synthetic path AND a
 *   written 204 response observed at the fixture; fresh group-member history
 *   (urlTestTime advancing, positive delay) after BOTH the startup probe and
 *   an explicit urlTest command; SOCKS-reject control produces no endpoint
 *   HEAD and clears member history; closeService while a request is held
 *   terminates the native socket within a short cancellation deadline
 *   measured from close initiation; a fresh second lifecycle repeats the
 *   measurement and tears down.
 * - Cancellation attribution: the held relay must be alive immediately
 *   before closure. A no-op control window of the same length as the
 *   cancellation deadline first proves the oracle does not fire without
 *   closure. Termination inside the 2 s deadline (far below the ~15 s
 *   native probe timeout) is cancellation-attributed; later termination is
 *   reported as inconclusive "eventual" evidence, not a pass.
 * - Every blocking JNI call — including Libbox.setup — runs on an
 *   independent tracked worker bounded by a latch watchdog. Timed-out calls
 *   stay registered in [nativeOps]: a stuck thread is never claimed
 *   terminated, no conflicting JNI cleanup/restart is launched against
 *   possibly-busy native state, and the owned dirs are preserved to process
 *   exit instead of being deleted under a live native handle.
 * - Relay pumps are retained and joined; activeRelays can only reach zero
 *   when both pump threads have actually exited. A latch timeout never
 *   counts as a stop.
 * - Nothing here proves production latency, underlay selection, socket
 *   protection, or live-VPN coexistence. This is a research gate result,
 *   not approval to ship.
 *
 * Only fixed phase labels, counts and durations are logged — never config
 * bodies, URLs beyond the synthetic loopback path, or native payloads.
 */
@RunWith(AndroidJUnit4::class)
class OfflineUrlTestResearchTest {

    companion object {
        private const val TAG = "R04Research"
        private const val SOCKS_TAG = "r04-socks"
        private const val GROUP_TAG = "r04-auto"
        private const val PROBE_PATH = "/r04-probe"
        private const val LOOPBACK = "127.0.0.1"

        /** Keeps a ~100 ms fixture delay above millisecond truncation. */
        private const val RESPONSE_DELAY_MS = 100L

        private const val POLL_SLICE_MS = 100L
        private const val STARTUP_WAIT_MS = 25_000L
        private const val COMMAND_WAIT_MS = 25_000L
        private const val JNI_CALL_BOUND_MS = 15_000L
        private const val START_SERVICE_BOUND_MS = 25_000L

        /** Return bound for the closeService CALL itself — unrelated to the
         *  cancellation attribution deadline below. */
        private const val CLOSE_CALL_BOUND_MS = 15_000L

        /** Cancellation attribution deadline measured from closeService
         *  INITIATION: far above loopback teardown latency (ms), far below
         *  the ~15 s native probe timeout — a termination inside it cannot
         *  be the probe's own timeout. */
        private const val CANCEL_DEADLINE_MS = 2_000L

        /** Negative-control window: same oracle, same duration, no closure
         *  issued — termination here would make the oracle unusable. */
        private const val NOOP_OBSERVE_MS = CANCEL_DEADLINE_MS

        /** Bound for classifying termination as merely *eventual* (covers
         *  the ~15 s native timeout + slack). Passing here is not a pass —
         *  it is the inconclusive-attribution branch. */
        private const val EVENTUAL_GONE_WAIT_MS = 20_000L

        /** Total probe-age budget: (command age at closure) +
         *  [CANCEL_DEADLINE_MS] must stay under this — comfortably below the
         *  ~15 s native probe timeout so a probe already dying of old age
         *  can never satisfy the cancellation predicate. */
        private const val PROBE_AGE_BUDGET_MS = 5_000L

        /** Fixture read timeout — deliberately longer than
         *  [EVENTUAL_GONE_WAIT_MS] so a fixture-side timeout can never be
         *  mistaken for native teardown inside the oracle window. */
        private const val FIXTURE_SO_TIMEOUT_MS = 30_000

        /** A silent pump dies at FIXTURE_SO_TIMEOUT_MS; join bound adds
         *  wakeup slack. Applies per pump, sequential. */
        private const val PUMP_JOIN_BOUND_MS = FIXTURE_SO_TIMEOUT_MS + 10_000L
        private const val PUMP_REJOIN_MS = 2_000L

        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val MAX_CONNECTIONS = 8
        private const val RELAY_BUF = 8 * 1024
        private const val HOLD_LATCH_BOUND_MS = 120_000L
        private const val WORKER_JOIN_MS = 5_000L

        private lateinit var nativeRoot: File

        /** A blocking native-side call still in flight after its watchdog
         *  deadline. Lives in [nativeOps] until the worker actually exits —
         *  registration happens before the worker starts so a fast return
         *  can never leak an entry. */
        private class NativeOp(val name: String, val thread: Thread, val startedAtNs: Long)

        enum class CallEnd { RETURNED, THREW, TIMED_OUT }

        private class BoundedResult<T>(val end: CallEnd, val outcome: Result<T>? = null)

        /** Live registry of in-flight native calls — the single source of
         *  truth for "is anything still inside JNI". */
        private val nativeOps = Collections.synchronizedList(mutableListOf<NativeOp>())

        /** Set whenever a native call's termination is unconfirmed (timeout
         *  or cleanup throw): owned dirs must be preserved to process exit. */
        private val preserveNativeRoot = AtomicBoolean(false)

        private fun pendingNativeOps(): String =
            synchronized(nativeOps) {
                nativeOps.joinToString(",") {
                    "${it.name}(alive=${it.thread.isAlive}," +
                        "ageMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - it.startedAtNs)})"
                }
            }

        /**
         * Pure cancellation-attribution predicate for the held-probe
         * teardown gate. All timestamps are monotonic nanoTime. True only
         * when the probe is young enough that the window can't overlap the
         * ~15 s native timeout, the parked request belongs to this command
         * and predates closure, and BOTH termination stamps fall strictly
         * inside (closeInitiated, closeInitiated + CANCEL_DEADLINE].
         */
        fun cancellationAttributed(
            commandIssuedAtNs: Long,
            holdEnteredAtNs: Long,
            closeInitiatedAtNs: Long,
            nativeGoneAtNs: Long,
            relayEndedAtNs: Long,
        ): Boolean {
            val cancelWindow = TimeUnit.MILLISECONDS.toNanos(CANCEL_DEADLINE_MS)
            val ageBudget = TimeUnit.MILLISECONDS.toNanos(PROBE_AGE_BUDGET_MS)
            // An aged probe could die on its own inside the window.
            if (closeInitiatedAtNs - commandIssuedAtNs + cancelWindow > ageBudget) {
                return false
            }
            // The held request must belong to THIS command and must already
            // be parked when closure is initiated.
            if (holdEnteredAtNs <= commandIssuedAtNs ||
                holdEnteredAtNs > closeInitiatedAtNs
            ) {
                return false
            }
            val deadline = closeInitiatedAtNs + cancelWindow
            // Lower bound: termination cannot predate closure — an EOF that
            // landed before initiation is natural, not cancellation.
            if (nativeGoneAtNs < closeInitiatedAtNs || nativeGoneAtNs > deadline) {
                return false
            }
            if (relayEndedAtNs < closeInitiatedAtNs || relayEndedAtNs > deadline) {
                return false
            }
            return true
        }

        /** Runs [block] on an independent daemon worker tracked in
         *  [nativeOps], bounded by a latch watchdog. A TIMED_OUT result
         *  leaves the entry registered — the worker may still be inside JNI
         *  and that is reported, never interpreted as termination. */
        private fun <T> tryBounded(what: String, timeoutMs: Long, block: () -> T): BoundedResult<T> {
            val outcome = AtomicReference<Result<T>>()
            val done = CountDownLatch(1)
            val opRef = AtomicReference<NativeOp>()
            val worker = thread(start = false, isDaemon = true, name = "r04-$what") {
                try {
                    outcome.set(Result.success(block()))
                } catch (t: Throwable) {
                    outcome.set(Result.failure(t))
                } finally {
                    nativeOps.remove(opRef.get())
                    done.countDown()
                }
            }
            val op = NativeOp(what, worker, System.nanoTime())
            opRef.set(op)
            nativeOps.add(op)
            worker.start()
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return BoundedResult(CallEnd.TIMED_OUT)
            }
            val r = outcome.get()
            return BoundedResult(if (r.isSuccess) CallEnd.RETURNED else CallEnd.THREW, r)
        }

        /**
         * Process-global libbox init aimed at unique dirs under the TARGET
         * app's cacheDir — deliberately NOT the production filesDir layout
         * (collide on basePath/command.sock) and NOT the instrumentation
         * context (the .test package's dirs are owned by a different UID
         * than the instrumented app process — mkdirs fails EACCES).
         * Bounded like every other blocking JNI op (phase 0); on timeout the
         * call stays registered and the dirs are preserved, not deleted.
         */
        @BeforeClass
        @JvmStatic
        fun setupNative() {
            // Exclusivity is enforced, not just documented: without the
            // runner arg the class skips before any process-global JNI state
            // is touched, so suite runs stay side-effect free.
            Assume.assumeTrue(
                "R04 gate: exclusive run only — pass -e " +
                    R04ReceiverIsolation.RUN_ARG + " 1",
                "1" == InstrumentationRegistry.getArguments()
                    .getString(R04ReceiverIsolation.RUN_ARG),
            )
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            nativeRoot = File(
                appContext.cacheDir,
                "r04-${System.currentTimeMillis().toString(36)}",
            )
            val base = File(nativeRoot, "b")
            val work = File(nativeRoot, "w")
            val tmp = File(nativeRoot, "t")
            check(base.mkdirs() && work.mkdirs() && tmp.mkdirs()) {
                "R04 phase=0: could not create test-owned native dirs"
            }
            val res = tryBounded("setup", JNI_CALL_BOUND_MS) {
                Libbox.setup(
                    SetupOptions().apply {
                        basePath = base.absolutePath
                        workingPath = work.absolutePath
                        tempPath = tmp.absolutePath
                        // 0 → in-process unix socket under basePath only.
                        commandServerListenPort = 0
                        crashReportSource = "vpnclient-r04"
                        logMaxLines = 300
                        debug = false
                        fixAndroidStack = true
                    },
                )
            }
            when (res.end) {
                CallEnd.RETURNED -> Unit
                CallEnd.THREW -> {
                    // The call returned but native state is partially
                    // initialized — root contents are evidence, keep them.
                    preserveNativeRoot.set(true)
                    fail(
                        "R04 phase=0: Libbox.setup threw " +
                            res.outcome!!.exceptionOrNull()?.javaClass?.simpleName,
                    )
                }

                CallEnd.TIMED_OUT -> {
                    preserveNativeRoot.set(true)
                    fail(
                        "R04 phase=0: Libbox.setup did not return within " +
                            "${JNI_CALL_BOUND_MS}ms — still in flight " +
                            "(tracked); owned dirs preserved to process exit",
                    )
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun removeNativeRoot() {
            // Deletes only when every native op has exited and no cleanup
            // failure was recorded — otherwise the owned dirs stay for the
            // rest of the process lifetime.
            deleteNativeRootIfSafe()
        }

        fun deleteNativeRootIfSafe(): Boolean {
            if (!::nativeRoot.isInitialized) return true
            if (preserveNativeRoot.get() || nativeOps.isNotEmpty()) return false
            return nativeRoot.deleteRecursively()
        }
    }

    private data class GroupItemSnap(
        val tag: String,
        val urlTestTime: Long,
        val urlTestDelay: Int,
    )

    /** Bounded JNI call for the test proper: RETURNED unwraps, THREW and
     *  TIMED_OUT fail with a phase label. TIMED_OUT also preserves the
     *  native root — the call may still be running. */
    private fun <T> boundedCall(phase: Int, what: String, timeoutMs: Long, block: () -> T): T {
        val startedAt = System.nanoTime()
        val res = tryBounded("p$phase-$what", timeoutMs, block)
        // Fixed op label + duration only — feeds the "is urlTest blocking"
        // question with device numbers instead of guesses.
        Log.i(
            TAG,
            "call=p$phase-$what tookMs=" +
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
        )
        when (res.end) {
            CallEnd.RETURNED -> return res.outcome!!.getOrThrow()
            CallEnd.THREW -> {
                // Sanitized: fixed op label + exception class ONLY. The
                // native throwable is never attached as cause/suppressed —
                // its message may carry payload material.
                throw AssertionError(
                    "R04 phase=$phase: '$what' threw " +
                        res.outcome?.exceptionOrNull()?.javaClass?.simpleName,
                )
            }

            CallEnd.TIMED_OUT -> {
                preserveNativeRoot.set(true)
                fail(
                    "R04 phase=$phase: '$what' did not return within " +
                        "${timeoutMs}ms — native call still in flight " +
                        "(tracked); dependent cleanup skipped, dirs preserved",
                )
                throw AssertionError("unreachable")
            }
        }
    }

    /** Bounded cleanup that never masks the primary result — failures and
     *  timeouts are collected; a throw means that handle's termination is
     *  unconfirmed → the native root is preserved. */
    private fun boundedCleanup(
        what: String,
        timeoutMs: Long,
        cleanupErrors: MutableList<String>,
        block: () -> Unit,
    ) {
        when (tryBounded("cleanup-$what", timeoutMs, block).end) {
            CallEnd.RETURNED -> Unit
            CallEnd.THREW -> {
                preserveNativeRoot.set(true)
                cleanupErrors.add("'$what' threw — native termination unconfirmed")
            }

            CallEnd.TIMED_OUT -> {
                preserveNativeRoot.set(true)
                cleanupErrors.add("'$what' did not return within ${timeoutMs}ms — still in flight")
            }
        }
    }

    /** Polls [probe] until non-null or the bound expires → labeled fail. */
    private fun <T> await(phase: Int, what: String, timeoutMs: Long, probe: () -> T?): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            probe()?.let { return it }
            Thread.sleep(POLL_SLICE_MS)
        }
        fail("R04 phase=$phase: timed out after ${timeoutMs}ms waiting for $what")
        throw AssertionError("unreachable")
    }

    private fun researchConfig(socksPort: Int, httpPort: Int): String = buildJsonObject {
        putJsonObject("log") {
            put("level", "warn")
            put("timestamp", false)
        }
        putJsonArray("outbounds") {
            addJsonObject {
                put("type", "socks")
                put("tag", SOCKS_TAG)
                put("server", LOOPBACK)
                put("server_port", socksPort)
                put("version", "5")
                put("network", "tcp")
            }
            addJsonObject {
                put("type", "urltest")
                put("tag", GROUP_TAG)
                putJsonArray("outbounds") { add(SOCKS_TAG) }
                put("url", "http://$LOOPBACK:$httpPort$PROBE_PATH")
                // interval <= idle_timeout keeps the native validator happy and
                // suppresses automatic re-probes for the duration of the test —
                // every check after startup is attributable to an explicit RPC.
                put("interval", "5m")
                put("idle_timeout", "30m")
                put("tolerance", 50)
                put("interrupt_exist_connections", false)
            }
        }
    }.toString()

    /**
     * Synthetic, native-free check of the watchdog machinery itself: an
     * over-running op must classify TIMED_OUT and stay tracked while its
     * worker is still alive; after the worker really exits the registry
     * must clear. Run-independent — its ops are confirmed gone before it
     * ends, so it cannot contaminate the gate's pending-op checks.
     */
    @Test
    fun watchdogTrackingSelfcheck() {
        val release = CountDownLatch(1)
        val res = tryBounded("selfcheck-blocked", 200L) {
            release.await(HOLD_LATCH_BOUND_MS, TimeUnit.MILLISECONDS)
        }
        try {
            assertEquals("watchdog must classify an over-running op", CallEnd.TIMED_OUT, res.end)
            assertTrue(
                "timed-out op must remain tracked while its worker is still alive",
                synchronized(nativeOps) { nativeOps.any { it.name == "selfcheck-blocked" } },
            )
        } finally {
            // Always free the sleeper — a failed assert must not leave a
            // tracked op pending into the gate test's lifecycle.
            release.countDown()
        }
        await(0, "selfcheck worker exit clears tracking", 10_000L) {
            if (synchronized(nativeOps) { nativeOps.none { it.name == "selfcheck-blocked" } }) {
                Unit
            } else {
                null
            }
        }
        val ok = tryBounded("selfcheck-quick", 5_000L) { 41 }
        assertEquals(CallEnd.RETURNED, ok.end)
        assertEquals(41, ok.outcome!!.getOrThrow())
        assertFalse(
            "selfcheck must not leave tracked ops behind",
            synchronized(nativeOps) { nativeOps.any { it.name.startsWith("selfcheck") } },
        )
    }

    /**
     * Pure boundary checks for [cancellationAttributed] — the phase-4 oracle
     * must reject aged probes, terminations predating closure, terminations
     * past the deadline, and stale/post-closure holds. (Body is native-free;
     * class @BeforeClass still runs Libbox.setup when this method is
     * selected — exclusive-process requirement unchanged.)
     */
    @Test
    fun cancellationOracleSelfcheck() {
        val base = System.nanoTime()
        fun at(deltaMs: Long) = base + TimeUnit.MILLISECONDS.toNanos(deltaMs)
        // Happy path: 2 s command age → 2+2=4 s ≤ 5 s budget; hold parked
        // after command; EOF+relay-end strictly inside the 2 s window.
        assertTrue(
            "in-window termination must attribute",
            cancellationAttributed(at(0), at(1_000), at(2_000), at(2_100), at(2_200)),
        )
        // Aged probe: 4 s old at close → 4+2=6 s > 5 s budget → natural
        // timeout could overlap the window; must reject.
        assertFalse(
            "aged probe must not attribute",
            cancellationAttributed(at(0), at(3_500), at(4_000), at(4_100), at(4_200)),
        )
        // EOF strictly before closure initiation → natural, not cancel.
        assertFalse(
            "EOF before closure must not attribute",
            cancellationAttributed(at(0), at(1_000), at(2_000), at(1_999), at(2_100)),
        )
        // Relay end strictly before closure initiation → likewise.
        assertFalse(
            "relay end before closure must not attribute",
            cancellationAttributed(at(0), at(1_000), at(2_000), at(2_100), at(1_999)),
        )
        // Termination after the 2 s deadline is merely eventual.
        assertFalse(
            "past-deadline termination must not attribute",
            cancellationAttributed(at(0), at(1_000), at(2_000), at(4_100), at(4_200)),
        )
        // Stale hold parked before this command → wrong probe.
        assertFalse(
            "pre-command hold must not attribute",
            cancellationAttributed(at(1_000), at(0), at(2_000), at(2_100), at(2_200)),
        )
        // Hold parked only after closure initiation → not held-before-close.
        assertFalse(
            "post-closure hold must not attribute",
            cancellationAttributed(at(0), at(2_100), at(2_000), at(2_200), at(2_300)),
        )
    }

    /**
     * Sanitization selfcheck: a native-call failure must surface as an
     * AssertionError carrying ONLY the fixed op label and exception class —
     * never the throwable's message/cause/suppressed payload. (Body is
     * native-free; @BeforeClass still runs — see class KDoc.)
     */
    @Test
    fun sanitizedThrowableSelfcheck() {
        val sentinel = "SENTINEL-R04-7f3a"
        val nested = "NESTED-R04-9c2b"
        val thrown = try {
            boundedCall(9, "sentinel-op", 5_000L) {
                throw IOException(
                    "payload=$sentinel",
                    IllegalStateException("payload=$nested"),
                )
            }
            null
        } catch (e: AssertionError) {
            e
        }
        assertNotNull("boundedCall must surface the throw as a failure", thrown)
        val trace = thrown!!.stackTraceToString()
        assertFalse(
            "failure must not contain the sentinel payload",
            trace.contains(sentinel),
        )
        assertFalse(
            "failure must not contain the nested-cause payload",
            trace.contains(nested),
        )
        assertTrue(
            "failure must keep the fixed op label",
            trace.contains("sentinel-op"),
        )
    }

    @Test
    fun offlineUrlTestGate() {
        val cleanupErrors = mutableListOf<String>()
        var platform = ResearchPlatform()
        var serverHandler = RecordingServerHandler()
        var clientHandler = RecordingClientHandler()
        // Handles are published into these vars BEFORE any blocking native
        // call on them — a start()/connect() timeout can never strand an
        // un-closeable native object.
        var server: CommandServer? = null
        var client: CommandClient? = null
        var http = LoopbackHttpFixture()
        var socks = LoopbackSocks5Fixture(http.port)
        try {
            // ---------------- phase 1: bootstrap + startup probe ----------------
            http.start()
            socks.start()
            val config = researchConfig(socks.port, http.port)
            boundedCall(1, "checkConfig", JNI_CALL_BOUND_MS) { Libbox.checkConfig(config) }
            server = CommandServer(serverHandler, platform)
            val srv = server
            boundedCall(1, "server.start", JNI_CALL_BOUND_MS) { srv.start() }
            boundedCall(1, "startOrReloadService", START_SERVICE_BOUND_MS) {
                srv.startOrReloadService(config, OverrideOptions())
            }
            val clientOptions = CommandClientOptions().apply {
                addCommand(Libbox.CommandGroup)
                statusInterval = 1_000_000_000L
            }
            client = CommandClient(clientHandler, clientOptions)
            val cl = client
            boundedCall(1, "client.connect", JNI_CALL_BOUND_MS) { cl.connect() }
            val startupSnap = await(1, "startup-probe proxied exchange", STARTUP_WAIT_MS) {
                val item = clientHandler.item(GROUP_TAG, SOCKS_TAG)
                // connected() fires on a binder thread — wait for it here
                // instead of asserting it right after connect() returns.
                if (clientHandler.connected.get() &&
                    socks.connectAllowed.get() >= 1 &&
                    http.headOk.get() >= 1 &&
                    http.responsesWritten.get() >= 1 &&
                    item != null && item.urlTestTime > 0 && item.urlTestDelay > 0
                ) {
                    item
                } else {
                    null
                }
            }
            assertEquals(
                "R04 phase=1: openTun must never be invoked",
                0, platform.openTunCalls.get(),
            )
            Log.i(
                TAG,
                "phase=1 ok: startupDelayMs=${startupSnap.urlTestDelay} " +
                    "socksConnects=${socks.connectAllowed.get()} " +
                    "heads=${http.headOk.get()} tunCalls=${platform.openTunCalls.get()}",
            )

            // ---------------- phase 2: explicit-command attribution ----------------
            // Settle past the seconds-level urlTestTime granularity so a fresh
            // check MUST produce a strictly newer timestamp, and past the
            // startup check so the command can't coalesce with it.
            Thread.sleep(1_100)
            val t0 = clientHandler.item(GROUP_TAG, SOCKS_TAG)?.urlTestTime ?: 0L
            val s0 = socks.connectAllowed.get()
            val h0 = http.headOk.get()
            val r0 = http.responsesWritten.get()
            boundedCall(2, "urlTest", JNI_CALL_BOUND_MS) { cl.urlTest(GROUP_TAG) }
            await(2, "fresh history + fresh proxied exchange after urlTest", COMMAND_WAIT_MS) {
                val item = clientHandler.item(GROUP_TAG, SOCKS_TAG)
                if (item != null && item.urlTestTime > t0 && item.urlTestDelay > 0 &&
                    socks.connectAllowed.get() > s0 && http.headOk.get() > h0 &&
                    http.responsesWritten.get() > r0
                ) {
                    Unit
                } else {
                    null
                }
            }
            Log.i(
                TAG,
                "phase=2 ok: urlTestTime advanced past baseline; " +
                    "socks+http+response counters advanced",
            )

            // ---------------- phase 3: SOCKS-reject negative control ----------------
            val rejected0 = socks.connectRejected.get()
            val h1 = http.headOk.get()
            socks.rejectMode.set(true)
            boundedCall(3, "urlTest", JNI_CALL_BOUND_MS) { cl.urlTest(GROUP_TAG) }
            await(3, "rejected SOCKS CONNECT attempt", COMMAND_WAIT_MS) {
                if (socks.connectRejected.get() > rejected0) Unit else null
            }
            await(3, "member history cleared after proxy failure", COMMAND_WAIT_MS) {
                val item = clientHandler.item(GROUP_TAG, SOCKS_TAG)
                if (item == null || item.urlTestTime == 0L) Unit else null
            }
            // The endpoint must never be reached directly — quiet window, then check.
            Thread.sleep(1_500)
            assertEquals(
                "R04 phase=3: no endpoint HEAD may bypass the SOCKS reject",
                h1, http.headOk.get(),
            )
            Log.i(
                TAG,
                "phase=3 ok: rejected=${socks.connectRejected.get() - rejected0} " +
                    "headsDelta=${http.headOk.get() - h1}",
            )

            // ---------------- phase 4: teardown while a request is held ----------------
            socks.rejectMode.set(false)
            http.holdMode.set(true)
            val holds0 = http.holds.get()
            val allowed0 = socks.connectAllowed.get()
            val nativeGoneAt0 = socks.lastNativeGoneAt.get()
            val relayEndedAt0 = socks.lastRelayEndedAt.get()
            // Probe-age baseline: stamped BEFORE issuing the command so the
            // oracle can reject a probe already near its native timeout.
            val commandIssuedAt = System.nanoTime()
            boundedCall(4, "urlTest", JNI_CALL_BOUND_MS) { cl.urlTest(GROUP_TAG) }
            await(4, "request parked in HTTP hold with live relay", COMMAND_WAIT_MS) {
                if (http.holds.get() > holds0 &&
                    socks.connectAllowed.get() > allowed0 &&
                    socks.activeRelays.get() >= 1
                ) {
                    Unit
                } else {
                    null
                }
            }
            val holdEnteredAt = http.lastHoldEnteredAt.get()
            // Negative control: no closure issued — the same oracle inputs
            // watched for one cancellation-deadline window must NOT satisfy.
            // If the probe dies on its own here, closeService attribution is
            // impossible and the phase reports inconclusive.
            val noopDeadline = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(NOOP_OBSERVE_MS)
            while (System.nanoTime() < noopDeadline) {
                if (socks.lastNativeGoneAt.get() != nativeGoneAt0 ||
                    socks.lastRelayEndedAt.get() != relayEndedAt0 ||
                    socks.activeRelays.get() < 1
                ) {
                    fail(
                        "R04 phase=4 inconclusive: held probe terminated " +
                            "WITHOUT closeService during the no-op control " +
                            "window — cancellation oracle unusable",
                    )
                }
                Thread.sleep(POLL_SLICE_MS)
            }
            assertTrue(
                "R04 phase=4: held relay must be live immediately before closure",
                socks.activeRelays.get() >= 1,
            )
            // Real closure — the cancellation deadline is measured from the
            // INITIATION of the native call, not from its return.
            val closeInitiatedAt = System.nanoTime()
            boundedCall(4, "closeService", CLOSE_CALL_BOUND_MS) { srv.closeService() }
            val cancelDeadline = closeInitiatedAt +
                TimeUnit.MILLISECONDS.toNanos(CANCEL_DEADLINE_MS)
            val eventualDeadline = closeInitiatedAt +
                TimeUnit.MILLISECONDS.toNanos(EVENTUAL_GONE_WAIT_MS)
            // Termination observation is separate from the cancellation
            // oracle: keep watching until the eventual bound either way.
            var terminated = false
            while (System.nanoTime() < eventualDeadline) {
                if (socks.lastNativeGoneAt.get() > nativeGoneAt0 &&
                    socks.activeRelays.get() == 0
                ) {
                    terminated = true
                    break
                }
                Thread.sleep(POLL_SLICE_MS)
            }
            val goneAt = socks.lastNativeGoneAt.get()
            val endedAt = socks.lastRelayEndedAt.get()
            val goneDeltaMs = TimeUnit.NANOSECONDS.toMillis(goneAt - closeInitiatedAt)
            val endedDeltaMs = TimeUnit.NANOSECONDS.toMillis(endedAt - closeInitiatedAt)
            if (!terminated) {
                fail(
                    "R04 phase=4 inconclusive: held probe never terminated " +
                        "within ${EVENTUAL_GONE_WAIT_MS}ms of close initiation",
                )
            }
            val commandAgeAtCloseMs =
                TimeUnit.NANOSECONDS.toMillis(closeInitiatedAt - commandIssuedAt)
            if (socks.pumpStuck.get() > 0 ||
                goneAt <= nativeGoneAt0 || endedAt <= relayEndedAt0 ||
                !cancellationAttributed(
                    commandIssuedAt, holdEnteredAt, closeInitiatedAt, goneAt, endedAt,
                )
            ) {
                fail(
                    "R04 phase=4 inconclusive: termination not attributable " +
                        "to closeService cancellation (cmdAgeAtClose=" +
                        "${commandAgeAtCloseMs}ms, EOF +${goneDeltaMs}ms, " +
                        "relayEnd +${endedDeltaMs}ms, cancelWindow=" +
                        "${CANCEL_DEADLINE_MS}ms, ageBudget=${PROBE_AGE_BUDGET_MS}ms, " +
                        "pumpStuck=${socks.pumpStuck.get()})",
                )
            }
            Log.i(
                TAG,
                "phase=4 ok: cancellation attributed — EOF +${goneDeltaMs}ms, " +
                    "relayEnd +${endedDeltaMs}ms after close initiation " +
                    "(holdEnteredAt=${TimeUnit.NANOSECONDS.toMillis(closeInitiatedAt - holdEnteredAt)}ms " +
                    "before close); serviceStopCalls=${serverHandler.serviceStopCalls.get()}",
            )
            http.releaseHold()
            boundedCall(4, "client.disconnect", JNI_CALL_BOUND_MS) { cl.disconnect() }
            boundedCall(4, "server.close", JNI_CALL_BOUND_MS) { srv.close() }
            client = null
            server = null

            // ---------------- phase 5: sequential restart ----------------
            val socks1 = socks
            val http1 = http
            socks1.close(WORKER_JOIN_MS, cleanupErrors)
            http1.close(WORKER_JOIN_MS, cleanupErrors)
            if (cleanupErrors.isNotEmpty() || nativeOps.isNotEmpty() ||
                socks1.pumpStuck.get() > 0 || socks1.hasLivePumps()
            ) {
                fail(
                    "R04 phase=5 refused: prior lifecycle not fully stopped " +
                        "(cleanupErrors=$cleanupErrors, pendingOps=" +
                        "${pendingNativeOps()}, pumpStuck=${socks1.pumpStuck.get()}, " +
                        "livePumps=${socks1.hasLivePumps()})",
                )
            }
            platform = ResearchPlatform()
            serverHandler = RecordingServerHandler()
            clientHandler = RecordingClientHandler()
            http = LoopbackHttpFixture().also { it.start() }
            socks = LoopbackSocks5Fixture(http.port).also { it.start() }
            val config2 = researchConfig(socks.port, http.port)
            server = CommandServer(serverHandler, platform)
            val srv2 = server
            boundedCall(5, "server2.start", JNI_CALL_BOUND_MS) { srv2.start() }
            boundedCall(5, "startOrReloadService", START_SERVICE_BOUND_MS) {
                srv2.startOrReloadService(config2, OverrideOptions())
            }
            val clientOptions2 = CommandClientOptions().apply {
                addCommand(Libbox.CommandGroup)
                statusInterval = 1_000_000_000L
            }
            client = CommandClient(clientHandler, clientOptions2)
            val cl2 = client
            boundedCall(5, "client2.connect", JNI_CALL_BOUND_MS) { cl2.connect() }
            await(5, "second-lifecycle startup probe", STARTUP_WAIT_MS) {
                val item = clientHandler.item(GROUP_TAG, SOCKS_TAG)
                if (socks.connectAllowed.get() >= 1 &&
                    http.headOk.get() >= 1 &&
                    http.responsesWritten.get() >= 1 &&
                    item != null && item.urlTestTime > 0 && item.urlTestDelay > 0
                ) {
                    Unit
                } else {
                    null
                }
            }
            boundedCall(5, "client2.disconnect", JNI_CALL_BOUND_MS) { cl2.disconnect() }
            boundedCall(5, "closeService2", CLOSE_CALL_BOUND_MS) { srv2.closeService() }
            boundedCall(5, "server2.close", JNI_CALL_BOUND_MS) { srv2.close() }
            client = null
            server = null
            assertEquals(
                "R04 phase=5: openTun must never be invoked (2nd lifecycle)",
                0, platform.openTunCalls.get(),
            )
            assertEquals(
                "R04: relay pumps must all have terminated, not timed out",
                0, socks.pumpStuck.get() + socks1.pumpStuck.get(),
            )
            Log.i(TAG, "phase=5 ok: sequential restart measured and tore down")

            // Whole-run fixture integrity across BOTH lifecycles: nothing
            // but the allowlisted destination/path may ever be touched.
            assertEquals(
                "R04: SOCKS CONNECT requests to non-allowlisted destinations",
                0, socks1.disallowedConnects.get() + socks.disallowedConnects.get(),
            )
            assertEquals(
                "R04: HTTP requests to non-probe paths",
                0, http1.foreignRequests.get() + http.foreignRequests.get(),
            )
        } catch (t: Throwable) {
            cleanup(server, client, socks, http, cleanupErrors)
            cleanupErrors.forEach { t.addSuppressed(AssertionError("cleanup: $it")) }
            throw t
        }
        cleanup(server, client, socks, http, cleanupErrors)
        assertTrue(
            "R04 cleanup failures: ${cleanupErrors.joinToString("; ")}",
            cleanupErrors.isEmpty(),
        )
    }

    private fun cleanup(
        server: CommandServer?,
        client: CommandClient?,
        socks: LoopbackSocks5Fixture,
        http: LoopbackHttpFixture,
        cleanupErrors: MutableList<String>,
    ) {
        if (nativeOps.isNotEmpty()) {
            // A timed-out call may still be inside JNI — launching more JNI
            // against the same native state could conflict. Report, don't
            // pretend cleanup happened.
            preserveNativeRoot.set(true)
            cleanupErrors.add(
                "incomplete cleanup: native calls still in flight " +
                    "(${pendingNativeOps()}); owned dirs preserved to process exit",
            )
        } else {
            client?.let { c ->
                boundedCleanup("client.disconnect", JNI_CALL_BOUND_MS, cleanupErrors) {
                    c.disconnect()
                }
            }
            // Re-check between steps: a cleanup call that got stuck must
            // stop us from piling more JNI onto uncertain native state.
            if (nativeOps.isEmpty()) {
                server?.let { s ->
                    boundedCleanup("closeService", CLOSE_CALL_BOUND_MS, cleanupErrors) {
                        s.closeService()
                    }
                }
            }
            if (nativeOps.isEmpty()) {
                server?.let { s ->
                    boundedCleanup("server.close", JNI_CALL_BOUND_MS, cleanupErrors) {
                        s.close()
                    }
                }
            }
            if (nativeOps.isNotEmpty()) {
                preserveNativeRoot.set(true)
                cleanupErrors.add(
                    "incomplete cleanup: a native call became stuck mid-cleanup " +
                        "(${pendingNativeOps()})",
                )
            }
        }
        // Fixture teardown is pure JVM — always safe regardless of native state.
        http.releaseHold()
        socks.close(WORKER_JOIN_MS, cleanupErrors)
        http.close(WORKER_JOIN_MS, cleanupErrors)
        // Owned dirs go last — only when native termination is confirmed.
        if (!deleteNativeRootIfSafe()) {
            cleanupErrors.add(
                if (preserveNativeRoot.get() || nativeOps.isNotEmpty()) {
                    "native root preserved until process exit " +
                        "(native termination unconfirmed): ops=${pendingNativeOps()}"
                } else {
                    "native root deleteRecursively returned false"
                },
            )
        }
    }

    // region fixtures

    /**
     * Loopback-only HTTP endpoint. Accepts only `HEAD /r04-probe` as a
     * "good" request (counted separately), replies 204 after ~100 ms so
     * millisecond truncation cannot yield a zero delay. In hold mode the
     * response is gated on [releaseHold] — used to observe native teardown
     * of an in-flight probe. [lastHoldEnteredAt] records the monotonic
     * instant the most recent request parked.
     */
    private class LoopbackHttpFixture {
        private val server = ServerSocket()
        private val open = AtomicBoolean(true)
        private val workers = Collections.synchronizedList(mutableListOf<Thread>())
        private val activeSockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        private val holdLatch = CountDownLatch(1)
        private lateinit var acceptThread: Thread

        val holdMode = AtomicBoolean(false)
        val port: Int
        val requests = AtomicInteger()
        val headOk = AtomicInteger()
        val foreignRequests = AtomicInteger()
        val responsesWritten = AtomicInteger()
        val holds = AtomicInteger()
        val lastHoldEnteredAt = AtomicLong(0)
        val errors = AtomicInteger()

        init {
            server.bind(InetSocketAddress(LOOPBACK, 0))
            port = server.localPort
        }

        fun start() {
            acceptThread = thread(isDaemon = true, name = "r04-http-accept") {
                while (open.get()) {
                    val socket = try {
                        server.accept()
                    } catch (e: IOException) {
                        if (open.get()) errors.incrementAndGet()
                        break
                    }
                    if (workers.size >= MAX_CONNECTIONS) {
                        runCatching { socket.close() }
                        continue
                    }
                    activeSockets.add(socket)
                    val w = thread(isDaemon = true, name = "r04-http-conn") {
                        try {
                            handle(socket)
                        } finally {
                            activeSockets.remove(socket)
                            runCatching { socket.close() }
                        }
                    }
                    workers.add(w)
                }
            }
        }

        private fun handle(socket: Socket) {
            socket.soTimeout = FIXTURE_SO_TIMEOUT_MS
            val head = readHead(socket.getInputStream())
            requests.incrementAndGet()
            val firstLine = head?.lineSequence()?.firstOrNull().orEmpty()
            val parts = firstLine.split(' ')
            if (parts.size >= 2 && parts[0] == "HEAD" && parts[1] == PROBE_PATH) {
                headOk.incrementAndGet()
            } else {
                foreignRequests.incrementAndGet()
            }
            if (holdMode.get()) {
                holds.incrementAndGet()
                lastHoldEnteredAt.set(System.nanoTime())
                holdLatch.await(HOLD_LATCH_BOUND_MS, TimeUnit.MILLISECONDS)
            } else {
                Thread.sleep(RESPONSE_DELAY_MS)
            }
            runCatching {
                socket.getOutputStream().write(
                    "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII),
                )
                socket.getOutputStream().flush()
                responsesWritten.incrementAndGet()
            }
        }

        /** Bounded read of request head: stops at CRLFCRLF, cap MAX_HEAD_BYTES. */
        private fun readHead(input: InputStream): String? {
            val buf = ByteArray(MAX_HEAD_BYTES)
            var n = 0
            while (n < buf.size) {
                val b = try {
                    input.read()
                } catch (e: IOException) {
                    return if (n == 0) null else String(buf, 0, n, Charsets.ISO_8859_1)
                }
                if (b < 0) break
                buf[n++] = b.toByte()
                if (n >= 4 &&
                    buf[n - 4] == '\r'.code.toByte() && buf[n - 3] == '\n'.code.toByte() &&
                    buf[n - 2] == '\r'.code.toByte() && buf[n - 1] == '\n'.code.toByte()
                ) {
                    break
                }
            }
            return if (n == 0) null else String(buf, 0, n, Charsets.ISO_8859_1)
        }

        fun releaseHold() = holdLatch.countDown()

        fun close(joinMs: Long, cleanupErrors: MutableList<String>) {
            open.set(false)
            runCatching { server.close() }
            synchronized(activeSockets) {
                activeSockets.toList()
            }.forEach { runCatching { it.close() } }
            if (::acceptThread.isInitialized) {
                acceptThread.join(joinMs)
                if (acceptThread.isAlive) cleanupErrors.add("http accept thread did not exit")
            }
            workers.toList().forEach { w ->
                w.join(joinMs)
                if (w.isAlive) cleanupErrors.add("http worker ${w.name} did not exit")
            }
        }
    }

    /**
     * Loopback-only SOCKS5 server: no-auth greeting, CONNECT command only,
     * and only to the single allowlisted destination (127.0.0.1:httpPort).
     * Everything else is rejected and counted. Each allowed connection gets
     * a [ConnState] with two retained pump threads; [activeRelays] only
     * reaches zero when both pumps have actually exited — a join timeout
     * counts [pumpStuck] instead of faking a stop. [lastNativeGoneAt] is the
     * monotonic instant of the most recent client-side termination that did
     * NOT originate from this fixture.
     */
    private class LoopbackSocks5Fixture(private val allowedPort: Int) {
        private val server = ServerSocket()
        private val open = AtomicBoolean(true)
        private val workers = Collections.synchronizedList(mutableListOf<Thread>())
        private val activeSockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        private val liveConns = Collections.synchronizedSet(mutableSetOf<ConnState>())
        private lateinit var acceptThread: Thread

        val rejectMode = AtomicBoolean(false)
        val port: Int
        val negotiations = AtomicInteger()
        val connectRequests = AtomicInteger()
        val connectAllowed = AtomicInteger()
        val connectRejected = AtomicInteger()
        val disallowedConnects = AtomicInteger()
        val activeRelays = AtomicInteger()
        val nativeGone = AtomicInteger()
        val lastNativeGoneAt = AtomicLong(0)
        val lastRelayEndedAt = AtomicLong(0)
        val pumpStuck = AtomicInteger()
        val workerTimeouts = AtomicInteger()
        val errors = AtomicInteger()

        /** Per-connection relay state. [closedByUs] is set by ANY
         *  fixture-initiated close so a dying pump can never mistake our
         *  teardown for a native-side termination. [completed] is the
         *  one-shot completion marker — relay end is published exactly once,
         *  only when both pumps have really exited (see [finishConn]). */
        private class ConnState(
            val client: Socket,
            val upstream: Socket,
        ) {
            val closedByUs = AtomicBoolean(false)
            val completed = AtomicBoolean(false)
            @Volatile var inPump: Thread? = null
            @Volatile var outPump: Thread? = null
            fun pumpsAlive(): Boolean =
                inPump?.isAlive == true || outPump?.isAlive == true
            fun closeBoth() {
                closedByUs.set(true)
                runCatching { client.close() }
                runCatching { upstream.close() }
            }
        }

        init {
            server.bind(InetSocketAddress(LOOPBACK, 0))
            port = server.localPort
        }

        fun start() {
            acceptThread = thread(isDaemon = true, name = "r04-socks-accept") {
                while (open.get()) {
                    val socket = try {
                        server.accept()
                    } catch (e: IOException) {
                        if (open.get()) errors.incrementAndGet()
                        break
                    }
                    if (workers.size >= MAX_CONNECTIONS) {
                        runCatching { socket.close() }
                        continue
                    }
                    activeSockets.add(socket)
                    val w = thread(isDaemon = true, name = "r04-socks-conn") {
                        try {
                            handle(socket)
                        } catch (e: SocketTimeoutException) {
                            workerTimeouts.incrementAndGet()
                        } catch (e: IOException) {
                            errors.incrementAndGet()
                        } finally {
                            activeSockets.remove(socket)
                            runCatching { socket.close() }
                        }
                    }
                    workers.add(w)
                }
            }
        }

        private fun markNativeGone() {
            nativeGone.incrementAndGet()
            lastNativeGoneAt.set(System.nanoTime())
        }

        private fun pump(state: ConnState, src: Socket, dst: Socket, nativeSide: Boolean): Thread =
            thread(isDaemon = true, name = if (nativeSide) "r04-pump-in" else "r04-pump-out") {
                try {
                    val buf = ByteArray(RELAY_BUF)
                    while (true) {
                        val n = src.getInputStream().read(buf)
                        if (n < 0) break
                        dst.getOutputStream().write(buf, 0, n)
                        dst.getOutputStream().flush()
                    }
                    // EOF on the native side before any fixture-initiated
                    // close = the native dialer tore its socket down.
                    if (nativeSide && !state.closedByUs.get()) markNativeGone()
                } catch (e: SocketTimeoutException) {
                    workerTimeouts.incrementAndGet()
                } catch (e: IOException) {
                    // Reset/closed on the native side likewise — unless we
                    // closed it ourselves.
                    if (nativeSide && !state.closedByUs.get()) markNativeGone()
                } finally {
                    state.closeBoth()
                }
            }

        private fun readByte(input: InputStream): Int {
            val v = input.read()
            if (v < 0) throw EOFException("socks stream closed")
            return v
        }

        private fun reply(out: java.io.OutputStream, rep: Int) {
            out.write(
                byteArrayOf(0x05, rep.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0),
            )
            out.flush()
        }

        private fun handle(client: Socket) {
            client.soTimeout = FIXTURE_SO_TIMEOUT_MS
            val input = client.getInputStream()
            val out = client.getOutputStream()

            // Greeting: VER=5, NMETHODS, methods — answer "no auth required".
            if (readByte(input) != 0x05) throw IOException("socks: bad version")
            val nMethods = readByte(input)
            repeat(nMethods) { readByte(input) }
            negotiations.incrementAndGet()
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()

            // Request: VER=5, CMD, RSV, ATYP, ADDR, PORT.
            readByte(input) // ver
            val cmd = readByte(input)
            readByte(input) // rsv
            val atyp = readByte(input)
            val host = when (atyp) {
                0x01 -> InetAddress.getByAddress(ByteArray(4) { readByte(input).toByte() }).hostAddress
                0x03 -> {
                    val len = readByte(input)
                    String(ByteArray(len) { readByte(input).toByte() }, Charsets.ISO_8859_1)
                }

                0x04 -> InetAddress.getByAddress(ByteArray(16) { readByte(input).toByte() }).hostAddress
                else -> {
                    connectRejected.incrementAndGet()
                    disallowedConnects.incrementAndGet()
                    reply(out, 0x08) // address type not supported
                    throw IOException("socks: bad atyp")
                }
            }
            val port = (readByte(input) shl 8) or readByte(input)
            connectRequests.incrementAndGet()

            // Foreign destinations are counted independently of rejectMode —
            // the allowlist bookkeeping must stay honest in every phase.
            val foreign = cmd != 0x01 || atyp != 0x01 ||
                host != LOOPBACK || port != allowedPort
            if (foreign) disallowedConnects.incrementAndGet()
            if (foreign || rejectMode.get()) {
                connectRejected.incrementAndGet()
                reply(out, 0x02) // connection not allowed by ruleset
                return
            }
            val upstream = Socket()
            try {
                upstream.connect(InetSocketAddress(LOOPBACK, allowedPort), 5_000)
            } catch (e: IOException) {
                connectRejected.incrementAndGet()
                reply(out, 0x05) // connection refused
                runCatching { upstream.close() }
                return
            }
            upstream.soTimeout = FIXTURE_SO_TIMEOUT_MS
            reply(out, 0x00)
            connectAllowed.incrementAndGet()

            val state = ConnState(client, upstream)
            synchronized(liveConns) { liveConns.add(state) }
            activeRelays.incrementAndGet()
            state.inPump = pump(state, client, upstream, nativeSide = true)
            state.outPump = pump(state, upstream, client, nativeSide = false)
            try {
                // Pumps are joined, never latch-faked: activeRelays only
                // drops once both have actually exited. A pump that outlives
                // the join bound is force-closed (fixture-initiated, never
                // counts as nativeGone) and counted in pumpStuck.
                val deadline = System.nanoTime() +
                    TimeUnit.MILLISECONDS.toNanos(PUMP_JOIN_BOUND_MS)
                for (p in listOfNotNull(state.inPump, state.outPump)) {
                    val rem = deadline - System.nanoTime()
                    if (rem > 0) {
                        p.join(TimeUnit.NANOSECONDS.toMillis(rem).coerceAtLeast(1))
                    }
                }
                if (state.pumpsAlive()) {
                    pumpStuck.incrementAndGet()
                    state.closeBoth()
                    state.inPump?.join(PUMP_REJOIN_MS)
                    state.outPump?.join(PUMP_REJOIN_MS)
                }
            } finally {
                // Publishes relay end ONLY when both pumps are really dead —
                // a still-live pump leaves the state retained in liveConns
                // and activeRelays elevated; fixture close() owns it then.
                finishConn(state)
            }
        }

        /** Completes a conn exactly once, and only when both pumps have
         *  exited: [activeRelays] drops, the ended timestamp is published,
         *  and the state leaves [liveConns]. Anything else is a lie — an
         *  unfinished pump keeps the conn visibly unterminated. */
        private fun finishConn(state: ConnState) {
            if (state.pumpsAlive()) return
            if (state.completed.compareAndSet(false, true)) {
                // Timestamp BEFORE the decrement: an observer seeing
                // activeRelays==0 must already see a fresh ended stamp.
                lastRelayEndedAt.set(System.nanoTime())
                activeRelays.decrementAndGet()
                synchronized(liveConns) { liveConns.remove(state) }
            }
        }

        /** Any retained conn with a pump still running — used by close()
         *  and by the phase-5 restart guard. */
        fun hasLivePumps(): Boolean = synchronized(liveConns) {
            liveConns.any { it.pumpsAlive() }
        }

        fun close(joinMs: Long, cleanupErrors: MutableList<String>) {
            open.set(false)
            runCatching { server.close() }
            // Mark live conns fixture-closed BEFORE closing their sockets —
            // a pump dying of our teardown must never count as nativeGone.
            synchronized(liveConns) { liveConns.toList() }
                .forEach { it.closeBoth() }
            synchronized(activeSockets) { activeSockets.toList() }
                .forEach { runCatching { it.close() } }
            // Retained pumps are this fixture's workers — join them
            // explicitly and report any that refuse to die. Closing the
            // sockets above unblocks their reads.
            val pumps = synchronized(liveConns) {
                liveConns.flatMap { listOfNotNull(it.inPump, it.outPump) }
            }
            pumps.forEach { it.join(joinMs) }
            if (::acceptThread.isInitialized) {
                acceptThread.join(joinMs)
                if (acceptThread.isAlive) cleanupErrors.add("socks accept thread did not exit")
            }
            workers.toList().forEach { w ->
                w.join(joinMs)
                if (w.isAlive) cleanupErrors.add("socks worker ${w.name} did not exit")
            }
            // Conn workers finish their own finishConn bookkeeping once
            // their pumps die — anything still retained/alive is a failure.
            pumps.filter { it.isAlive }.forEach {
                pumpStuck.incrementAndGet()
                cleanupErrors.add("socks relay pump ${it.name} did not exit")
            }
            synchronized(liveConns) {
                if (liveConns.isNotEmpty()) {
                    cleanupErrors.add(
                        "socks: ${liveConns.size} relay conn(s) never completed",
                    )
                }
            }
        }
    }

    // endregion

    // region libbox stubs/handlers

    /**
     * Minimal PlatformInterface for a no-inbounds service. openTun is counted
     * and always rejected — with zero inbounds the core must never call it.
     * Everything the experiment does not exercise fails explicitly instead of
     * manufacturing fake state.
     */
    private class ResearchPlatform : PlatformInterface {
        val openTunCalls = AtomicInteger()
        val autoDetectCalls = AtomicInteger()
        val notifications = AtomicInteger()

        override fun openTun(options: TunOptions): Int {
            openTunCalls.incrementAndGet()
            throw IOException("r04: openTun is not allowed in the no-TUN gate")
        }

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = false

        override fun autoDetectInterfaceControl(fd: Int) {
            autoDetectCalls.incrementAndGet()
        }

        // Retained so the native-registered listener proxy stays alive.
        @Suppress("unused")
        private var interfaceListener: InterfaceUpdateListener? = null

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            interfaceListener = listener
        }

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            interfaceListener = null
        }

        override fun getInterfaces(): NetworkInterfaceIterator =
            object : NetworkInterfaceIterator {
                override fun hasNext(): Boolean = false
                override fun next(): io.nekohasekai.libbox.NetworkInterface =
                    throw NoSuchElementException()
            }

        override fun useProcFS(): Boolean = false

        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String,
            sourcePort: Int,
            destinationAddress: String,
            destinationPort: Int,
        ): ConnectionOwner = throw IOException("r04: connection owner lookup unsupported")

        override fun localDNSTransport(): LocalDNSTransport = object : LocalDNSTransport {
            override fun raw(): Boolean = false
            override fun exchange(context: ExchangeContext, message: ByteArray) {
                throw IOException("r04: local DNS exchange unsupported")
            }

            override fun lookup(context: ExchangeContext, network: String, domain: String) {
                throw IOException("r04: local DNS lookup unsupported")
            }
        }

        override fun underNetworkExtension(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun clearDNSCache() = Unit
        override fun readWIFIState(): WIFIState? = null

        override fun sendNotification(notification: Notification) {
            notifications.incrementAndGet()
        }

        override fun cancelNotification(identifier: String, typeID: Int) {
            notifications.incrementAndGet()
        }

        override fun registerMyInterface(name: String?) = Unit
        override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
        override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit

        override fun usePlatformBridge(): Boolean = false
        override fun createBridge(options: BridgeOptions?): BridgeSession =
            throw IOException("r04: platform bridge unsupported")

        override fun usePlatformShell(): Boolean = false
        override fun checkPlatformShell(): Unit =
            throw IOException("r04: platform shell unsupported")

        override fun openShellSession(
            user: PlatformUser?,
            command: String?,
            environ: StringIterator?,
            term: String?,
            rows: Int,
            cols: Int,
        ): ShellSession = throw IOException("r04: shell session unsupported")

        override fun lookupUser(username: String?): PlatformUser =
            throw IOException("r04: user lookup unsupported")

        override fun lookupSFTPServer(): String =
            throw IOException("r04: SFTP lookup unsupported")

        override fun readSystemSSHHostKey(): String =
            throw IOException("r04: SSH host key unsupported")

        override fun tailscaleHostname(): String =
            throw IOException("r04: tailscale unsupported")
    }

    private class RecordingServerHandler : CommandServerHandler {
        val serviceStopCalls = AtomicInteger()
        val serviceReloadCalls = AtomicInteger()
        val debugMessages = AtomicInteger()

        override fun serviceStop() {
            serviceStopCalls.incrementAndGet()
        }

        override fun serviceReload() {
            serviceReloadCalls.incrementAndGet()
        }

        override fun getSystemProxyStatus(): SystemProxyStatus? = null
        override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
        override fun triggerNativeCrash() = Unit

        override fun writeDebugMessage(message: String?) {
            // Count only — native payloads are never logged or retained.
            debugMessages.incrementAndGet()
        }

        override fun connectSSHAgent(): Int = -1
    }

    private class RecordingClientHandler : CommandClientHandler {
        val connected = AtomicBoolean(false)
        val disconnects = AtomicInteger()
        val groupPushes = AtomicInteger()
        private val groups = AtomicReference<Map<String, List<GroupItemSnap>>>(emptyMap())

        /** Latest snapshot of [groupTag]'s member [itemTag], if present. */
        fun item(groupTag: String, itemTag: String): GroupItemSnap? =
            groups.get()[groupTag]?.firstOrNull { it.tag == itemTag }

        override fun connected() {
            connected.set(true)
        }

        override fun disconnected(message: String?) {
            disconnects.incrementAndGet()
        }

        override fun writeGroups(message: OutboundGroupIterator?) {
            if (message == null) return
            val map = mutableMapOf<String, MutableList<GroupItemSnap>>()
            while (message.hasNext()) {
                val group = message.next()
                val items = mutableListOf<GroupItemSnap>()
                val iter = group.items
                while (iter != null && iter.hasNext()) {
                    val it = iter.next()
                    items.add(
                        GroupItemSnap(
                            tag = it.tag,
                            urlTestTime = it.urlTestTime,
                            urlTestDelay = it.urlTestDelay,
                        ),
                    )
                }
                map[group.tag] = items
            }
            groups.set(map)
            groupPushes.incrementAndGet()
        }

        override fun writeStatus(message: StatusMessage) = Unit
        override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit
        override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
        override fun writeLogs(messageList: LogIterator?) = Unit
        override fun clearLogs() = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
        override fun updateClashMode(newMode: String) = Unit
    }

    // endregion
}
