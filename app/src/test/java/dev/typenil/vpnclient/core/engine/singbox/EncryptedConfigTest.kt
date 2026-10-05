package dev.typenil.vpnclient.core.engine.singbox

import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedConfigTest {
    private val compiler = ConfigCompiler()
    private fun node(id: String, host: String = "192.0.2.1", encrypted: Boolean = false) = ProxyNode(
        id, id, ProtocolType.HTTP, host, 443,
        """{"type":"http","tag":"$id","server":"$host","server_port":443,"tls":{"enabled":$encrypted}}""",
        null, 1,
    )
    private fun tags(config: String, group: String): List<String> =
        Json.parseToJsonElement(config).jsonObject["outbounds"]!!.jsonArray
            .first { it.jsonObject["tag"]!!.jsonPrimitive.content == group }
            .jsonObject["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test fun `runtime excludes unsafe nodes from every outbound and group`() {
        val nodes = listOf(node("unsafe"), node("tls", encrypted = true), node("local", "127.0.0.1"))
        val config = compiler.build(nodes, null, true)
        assertEquals("tls", config.node.id)
        assertEquals(listOf("tls", "local"), tags(config.configJson, "auto"))
        assertEquals(listOf("auto", "tls", "local"), tags(config.configJson, "proxy"))
        assertFalse(config.configJson.contains("unsafe"))
        assertEquals("auto", compiler.build(nodes, null, true, selectAuto = true).node.id)
    }

    @Test fun `selected plaintext is typed rejection not stale node or fallback`() {
        try {
            compiler.build(listOf(node("unsafe"), node("tls", encrypted = true)), "unsafe", true)
            throw AssertionError("unsafe selection compiled")
        } catch (e: EngineError) {
            assertEquals(EngineError.UnencryptedTransport, e)
        }
    }

    @Test fun `no allowed node is a typed safety failure including Auto`() {
        for (auto in listOf(false, true)) {
            try {
                compiler.build(listOf(node("unsafe")), null, true, selectAuto = auto)
                throw AssertionError("unsafe config compiled")
            } catch (e: EngineError) {
                assertEquals(EngineError.UnencryptedTransport, e)
            }
        }
    }

    @Test fun `candidate native validation retains all nodes including unsafe only import`() {
        val candidate = compiler.buildCandidate(listOf(node("unsafe")))
        assertTrue(candidate.configJson.contains("unsafe"))
        assertEquals(listOf("unsafe"), tags(candidate.configJson, "auto"))
    }

    @Test fun `localhost is pinned so sidecar never uses hostile network DNS`() {
        val config = compiler.build(listOf(node("local", "LOCALHOST")), "local", true)
        val outbound = Json.parseToJsonElement(config.configJson).jsonObject["outbounds"]!!.jsonArray
            .first { it.jsonObject["tag"]!!.jsonPrimitive.content == "local" }.jsonObject
        assertEquals("127.0.0.1", outbound["server"]!!.jsonPrimitive.content)
    }
}
