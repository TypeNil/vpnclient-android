package dev.typenil.vpnclient.core.engine.singbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import io.nekohasekai.libbox.Libbox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

@RunWith(AndroidJUnit4::class)
class RuleSetCoreValidationTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun initLibbox() {
            LibboxRuntime.init(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
        }

        private fun validSrsBytes(): ByteArray {
            val payload = byteArrayOf(
                0x01, // 1 rule
                0x00, // default rule
                0x03, // ruleItemDomainKeyword
                0x01, // 1 keyword
                0x04, // length 4
                0x74, 0x65, 0x73, 0x74, // "test"
                0xFF.toByte(), // ruleItemFinal
                0x00, // invert = false
            )
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
            deflater.setInput(payload)
            deflater.finish()
            val buf = ByteArray(1024)
            val out = ByteArrayOutputStream()
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            deflater.end()
            return byteArrayOf(0x53, 0x52, 0x53, 0x01) + out.toByteArray()
        }

        private fun pseudoSrsBytes(text: String): ByteArray {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
            deflater.setInput(text.toByteArray())
            deflater.finish()
            val buf = ByteArray(1024)
            val out = ByteArrayOutputStream()
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            deflater.end()
            return byteArrayOf(0x53, 0x52, 0x53, 0x01) + out.toByteArray()
        }
    }

    private lateinit var testDir: File

    @Before
    fun setUp() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        testDir = File(targetContext.cacheDir, "test_rule_sets_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun realLibboxAcceptsValidSrsAndRejectsPseudoSrs() {
        val validFile = File(testDir, "valid.srs").apply { writeBytes(validSrsBytes()) }
        assertTrue("Real libbox should accept valid SRS", RuleSetStore.LibboxCoreValidator.validate(validFile))

        val pseudoFile = File(testDir, "pseudo.srs").apply { writeBytes(pseudoSrsBytes("not-a-ruleset")) }
        assertFalse("Real libbox should reject pseudo SRS", RuleSetStore.LibboxCoreValidator.validate(pseudoFile))
    }

    @Test
    fun realLibboxRejectsRuleWithoutConditions() {
        // SRS payload without conditions (01 00 FF 00) — sing-box 1.14.1 rejects with "missing conditions"
        val emptyConditionsPayload = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0x00)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        deflater.setInput(emptyConditionsPayload)
        deflater.finish()
        val buf = ByteArray(1024)
        val out = ByteArrayOutputStream()
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        val emptyRuleFile = File(testDir, "empty_rule.srs").apply {
            writeBytes(byteArrayOf(0x53, 0x52, 0x53, 0x01) + out.toByteArray())
        }
        assertFalse("Real libbox should reject rule without conditions", RuleSetStore.LibboxCoreValidator.validate(emptyRuleFile))
    }

    @Test
    fun realLibboxAcceptsBundledRuleSets() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val assetNames = targetContext.assets.list("rule_sets").orEmpty()
        assertTrue("Bundled rule sets should exist in assets", assetNames.isNotEmpty())
        for (assetName in assetNames) {
            val file = File(testDir, assetName)
            targetContext.assets.open("rule_sets/$assetName").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            assertTrue("Real libbox should accept bundled $assetName", RuleSetStore.LibboxCoreValidator.validate(file))
        }
    }

    @Test
    fun realLibboxAcceptsProxyBlockedAll11RuleSets() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val mode = RouteMode.PROXY_BLOCKED
        assertEquals("PROXY_BLOCKED should require 11 rule sets", 11, mode.ruleSetTags.size)
        val ruleSetPaths = mutableMapOf<String, String>()
        for (tag in mode.ruleSetTags) {
            val file = File(testDir, "$tag.srs")
            targetContext.assets.open("rule_sets/$tag.srs").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            assertTrue("Rule set $tag must be valid", RuleSetStore.LibboxCoreValidator.validate(file))
            ruleSetPaths[tag] = file.absolutePath
        }

        val testNode = ProxyNode(
            id = "test-node",
            subscriptionId = 1,
            name = "Test",
            protocol = ProtocolType.VLESS,
            server = "127.0.0.1",
            port = 443,
            outboundJson = """{"type":"vless","tag":"test-node","server":"127.0.0.1","server_port":443,"uuid":"00000000-0000-0000-0000-000000000000"}""",
            rawUri = null,
        )
        val config = ConfigCompiler().build(
            nodes = listOf(testNode),
            selectedNodeId = testNode.id,
            ipv6Enabled = false,
            routeMode = mode,
            ruleSetPaths = ruleSetPaths,
        )
        Libbox.checkConfig(config.configJson)
    }
}
