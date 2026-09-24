package dev.typenil.vpnclient.core.engine.singbox

import android.content.ContextWrapper
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.RouteMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * JVM coverage for the store's freshness/serialization contract. The
 * hardcoded GitHub URLs are intercepted and pointed at MockWebServer;
 * `unitTests.isReturnDefaultValues` stubs the Context base so only
 * `filesDir` is real.
 */
class RuleSetStoreTest {

    private val server = MockWebServer()
    private lateinit var dir: File
    private lateinit var ruleDir: File
    private lateinit var store: RuleSetStore
    /** tag → bundled bytes; absent key = not shipped. */
    private val bundledBytes = mutableMapOf<String, ByteArray>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("rule_sets").toFile()
        ruleDir = File(dir, "rule_sets").apply { mkdirs() }
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = dir
        }
        val bundled = object : BundledRuleSets(context) {
            override fun open(tag: String) =
                bundledBytes[tag]?.inputStream()
        }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/")).build())
            }
            .build()
        store = RuleSetStore(context, client, bundled)
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun `concurrent refreshes of the same tag download once`() = runTest {
        // Slow bodies keep both downloads in flight long enough to overlap;
        // the per-tag lock must still serialize them into one fetch each.
        repeat(2 * RouteMode.BYPASS_RU.ruleSetTags.size) {
            server.enqueue(
                MockResponse().setBody(
                    okio.Buffer().write(byteArrayOf(0x53, 0x53, 0x52, 0x01))
                        .writeUtf8("srs-bytes"),
                ).setBodyDelay(300, TimeUnit.MILLISECONDS),
            )
        }
        val first = async { store.ensureReady(RouteMode.BYPASS_RU) }
        val second = async { store.ensureReady(RouteMode.BYPASS_RU) }
        val results = awaitAll(first, second)

        assertEquals(RouteMode.BYPASS_RU.ruleSetTags.size, server.requestCount)
        results.forEach { paths ->
            assertEquals(RouteMode.BYPASS_RU.ruleSetTags.toSet(), paths.keys)
            paths.values.forEach { assertTrue(File(it).isFile) }
        }
        // No temp debris left behind.
        assertTrue(ruleDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `fresh files skip the network entirely`() = runTest {
        RouteMode.BYPASS_RU.ruleSetTags.forEach {
            File(ruleDir, "$it.srs").writeBytes(byteArrayOf(1))
        }
        val paths = store.ensureReady(RouteMode.BYPASS_RU)
        assertEquals(0, server.requestCount)
        assertEquals(RouteMode.BYPASS_RU.ruleSetTags.size, paths.size)
    }

    @Test
    fun `failed refresh keeps the stale copy`() = runTest {
        RouteMode.BYPASS_RU.ruleSetTags.forEach {
            File(ruleDir, "$it.srs").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(0L)
            }
        }
        repeat(RouteMode.BYPASS_RU.ruleSetTags.size) {
            server.enqueue(MockResponse().setResponseCode(500))
        }
        val paths = store.ensureReady(RouteMode.BYPASS_RU)
        assertEquals(RouteMode.BYPASS_RU.ruleSetTags.size, paths.size)
    }

    @Test
    fun `failed download without a stale copy fails the connect`() = runTest {
        repeat(RouteMode.BYPASS_RU.ruleSetTags.size) {
            server.enqueue(MockResponse().setResponseCode(500))
        }
        try {
            store.ensureReady(RouteMode.BYPASS_RU)
            fail("expected StartFailed")
        } catch (e: EngineError.StartFailed) {
            // expected
        }
    }

    @Test
    fun `bundled seed is used without a download attempt`() = runTest {
        RouteMode.BYPASS_RU.ruleSetTags.forEach {
            bundledBytes[it] = byteArrayOf(7, 7, 7)
        }
        val paths = store.ensureReady(RouteMode.BYPASS_RU)
        assertEquals(RouteMode.BYPASS_RU.ruleSetTags.size, paths.size)
        paths.values.forEach {
            assertEquals(3L, File(it).length())
        }
        // The seed is marked fresh — no network call on the connect path.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `non-srs 2xx body keeps the stale copy`() = runTest {
        RouteMode.BYPASS_RU.ruleSetTags.forEach {
            File(ruleDir, "$it.srs").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(0L)
            }
        }
        // A captive-portal HTML page is a 200 — it must not replace the
        // working copy.
        repeat(RouteMode.BYPASS_RU.ruleSetTags.size) {
            server.enqueue(MockResponse().setBody("<html>portal</html>"))
        }
        val paths = store.ensureReady(RouteMode.BYPASS_RU)
        assertEquals(RouteMode.BYPASS_RU.ruleSetTags.size, paths.size)
        paths.values.forEach {
            assertEquals(1L, File(it).length())
        }
    }

    @Test
    fun `empty 2xx body is rejected`() = runTest {
        repeat(RouteMode.BYPASS_RU.ruleSetTags.size) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        }
        try {
            store.ensureReady(RouteMode.BYPASS_RU)
            fail("expected StartFailed")
        } catch (e: EngineError.StartFailed) {
            // expected — empty body must not seed a zero-byte file
        }
    }

    @Test
    fun `oversized body is rejected`() = runTest {
        val huge = ByteArray(33 * 1024 * 1024)
        repeat(RouteMode.BYPASS_RU.ruleSetTags.size) {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(okio.Buffer().write(huge)),
            )
        }
        try {
            store.ensureReady(RouteMode.BYPASS_RU)
            fail("expected StartFailed")
        } catch (e: EngineError.StartFailed) {
            // expected — over the 32 MB cap
        }
    }
}
