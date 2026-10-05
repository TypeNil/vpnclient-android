package dev.typenil.vpnclient.core.engine.singbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** Native validation only: synthetic configs, no dials or owner DB changes. */
@RunWith(AndroidJUnit4::class)
class EncryptedPolicyCoreTest {
    companion object {
        @BeforeClass @JvmStatic fun initCore() {
            LibboxRuntime.init(InstrumentationRegistry.getInstrumentation().targetContext)
        }
    }
    private val compiler = ConfigCompiler()
    private fun node(id: String, server: String) = ProxyNode(
        id, id, ProtocolType.SOCKS, server, 1080,
        """{"type":"socks","tag":"$id","server":"$server","server_port":1080,"version":"5"}""",
        null, 1,
    )

    @Test fun plaintextCandidateStillReceivesNativeValidation() = runBlocking {
        val remote = node("synthetic-remote", "192.0.2.1")
        compiler.validateCandidates(listOf(remote), RouteMode.ALL, emptyMap())
        val malformed = remote.copy(outboundJson = remote.outboundJson.replace("1080", "true"))
        try {
            compiler.validateCandidates(listOf(malformed), RouteMode.ALL, emptyMap())
            throw AssertionError("native-invalid candidate accepted")
        } catch (e: EngineError.InvalidConfig) {
            assertEquals("config rejected", e.message)
        }
    }

    @Test fun nativeRuntimeConfigExcludesRemotePlaintext() = runBlocking {
        val config = compiler.compile(listOf(node("synthetic-remote", "192.0.2.1"),
            node("synthetic-local", "127.0.0.1")), null, true, selectAuto = true)
        assertEquals("auto", config.node.id)
        assertFalse(config.configJson.contains("synthetic-remote"))
    }

    @Test fun nativeManualRemoteSelectionIsBlocked() = runBlocking {
        try {
            compiler.compile(listOf(node("synthetic-remote", "192.0.2.1")), "synthetic-remote", true)
            throw AssertionError("remote plaintext compiled")
        } catch (e: EngineError) {
            assertEquals(EngineError.UnencryptedTransport, e)
        }
    }

    @Test fun nativeLocalhostSidecarIsPinned() = runBlocking {
        val config = compiler.compile(listOf(node("synthetic-local", "LOCALHOST")), "synthetic-local", true)
        assertFalse(config.configJson.contains("LOCALHOST"))
    }
}
