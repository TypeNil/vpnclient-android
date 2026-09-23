package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `outbounds differing only in tag share an id`() {
        val body = """{"outbounds":[
            |{"type":"vless","tag":"one","server":"a.example.com","server_port":443,
            |"uuid":"11111111-2222-3333-4444-555555555555"},
            |{"type":"vless","tag":"two","server":"a.example.com","server_port":443,
            |"uuid":"11111111-2222-3333-4444-555555555555"}
            |]}""".trimMargin()
        val nodes = parser.parse(body, 1)
        assertEquals(2, nodes.size)
        assertEquals(nodes[0].id, nodes[1].id)
    }

    @Test
    fun `outbounds differing in transport get distinct ids`() {
        val body = """{"outbounds":[
            |{"type":"vless","tag":"a","server":"a.example.com","server_port":443,
            |"uuid":"11111111-2222-3333-4444-555555555555"},
            |{"type":"vless","tag":"b","server":"a.example.com","server_port":443,
            |"uuid":"11111111-2222-3333-4444-555555555555",
            |"transport":{"type":"ws","path":"/ws"}}
            |]}""".trimMargin()
        val nodes = parser.parse(body, 1)
        assertEquals(2, nodes.size)
        assertNotEquals(nodes[0].id, nodes[1].id)
    }

    @Test
    fun `outbound key order does not perturb the id`() {
        val a = """{"outbounds":[{"type":"vless","tag":"n","server":"a.example.com",
            |"server_port":443,"uuid":"u","flow":"f"}]}""".trimMargin()
        val b = """{"outbounds":[{"uuid":"u","server_port":443,"server":"a.example.com",
            |"flow":"f","type":"vless","tag":"n"}]}""".trimMargin()
        assertEquals(parser.parse(a, 1).single().id, parser.parse(b, 1).single().id)
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
