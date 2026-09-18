package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SingBoxJsonParserTest {

    private val parser = SingBoxJsonParser()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `keeps node outbounds and skips non-node types`() {
        val body = """
        {
          "log": {"level": "warn"},
          "outbounds": [
            {"type": "selector", "tag": "select", "outbounds": ["a"]},
            {"type": "urltest", "tag": "auto", "outbounds": ["a"]},
            {"type": "direct", "tag": "direct"},
            {"type": "block", "tag": "block"},
            {"type": "dns", "tag": "dns"},
            {"type": "vless", "tag": "node-a", "server": "a.example.com", "server_port": 443,
             "uuid": "11111111-2222-3333-4444-555555555555"},
            {"type": "shadowsocks", "tag": "node-b", "server": "b.example.com", "server_port": 8388,
             "method": "aes-256-gcm", "password": "pw"}
          ]
        }
        """.trimIndent()
        val nodes = parser.parse(body, 9)
        assertEquals(2, nodes.size)
        assertEquals(ProtocolType.VLESS, nodes[0].protocol)
        assertEquals("node-a", nodes[0].name)
        assertEquals("a.example.com", nodes[0].server)
        assertEquals(443, nodes[0].port)
        assertEquals(ProtocolType.SHADOWSOCKS, nodes[1].protocol)
    }

    @Test
    fun `tag is overwritten to node id in outboundJson`() {
        val body = """{"outbounds":[{"type":"trojan","tag":"orig","server":"t.example.com",
            |"server_port":443,"password":"pw"}]}""".trimMargin()
        val n = parser.parse(body, 4).single()
        assertEquals("orig", n.name)
        val o = json.parseToJsonElement(n.outboundJson).jsonObject
        assertEquals(n.id, o["tag"]!!.jsonPrimitive.content)
        assertEquals("trojan", o["type"]!!.jsonPrimitive.content)
        assertNull(n.rawUri)
    }

    @Test
    fun `missing tag falls back to server-port name`() {
        val body = """{"outbounds":[{"type":"tuic","server":"t.example.com","server_port":10443,
            |"uuid":"u","password":"p"}]}""".trimMargin()
        val n = parser.parse(body, 1).single()
        assertEquals("t.example.com:10443", n.name)
    }

    @Test
    fun `no node outbounds throws EmptyResult`() {
        try {
            parser.parse("""{"outbounds":[{"type":"direct","tag":"d"}]}""", 1)
            fail("expected EmptyResult")
        } catch (e: SubscriptionError.EmptyResult) {
            // expected
        }
    }

    @Test
    fun `malformed json throws ParseFailed`() {
        try {
            parser.parse("{not json", 1)
            fail("expected ParseFailed")
        } catch (e: SubscriptionError.ParseFailed) {
            // expected
        }
    }
}
