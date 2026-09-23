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

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("rule_sets").toFile()
        ruleDir = File(dir, "rule_sets").apply { mkdirs() }
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = dir
        }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/")).build())
            }
            .build()
        store = RuleSetStore(context, client)
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
                MockResponse().setBody("srs-bytes")
                    .setBodyDelay(300, TimeUnit.MILLISECONDS),
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
}
