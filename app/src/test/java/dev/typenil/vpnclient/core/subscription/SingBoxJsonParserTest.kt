package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
        val nodes = parser.parse(body, 9).nodes
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
        val n = parser.parse(body, 4).nodes.single()
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
        val n = parser.parse(body, 1).nodes.single()
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
        val nodes = parser.parse(body, 1).nodes
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
        val nodes = parser.parse(body, 1).nodes
        assertEquals(2, nodes.size)
        assertNotEquals(nodes[0].id, nodes[1].id)
    }

    @Test
    fun `outbound key order does not perturb the id`() {
        val a = """{"outbounds":[{"type":"vless","tag":"n","server":"a.example.com",
            |"server_port":443,"uuid":"u","flow":"f"}]}""".trimMargin()
        val b = """{"outbounds":[{"uuid":"u","server_port":443,"server":"a.example.com",
            |"flow":"f","type":"vless","tag":"n"}]}""".trimMargin()
        assertEquals(parser.parse(a, 1).nodes.single().id, parser.parse(b, 1).nodes.single().id)
    }

    @Test
    fun `no node outbounds throws EmptyResult`() {
        try {
            parser.parse("""{"outbounds":[{"type":"direct","tag":"d"}]}""", 1).nodes
            fail("expected EmptyResult")
        } catch (e: SubscriptionError.EmptyResult) {
            // expected
        }
    }

    @Test
    fun `malformed json throws ParseFailed`() {
        try {
            parser.parse("{not json", 1).nodes
            fail("expected ParseFailed")
        } catch (e: SubscriptionError.ParseFailed) {
            // expected
        }
    }

    @Test
    fun `anytls outbound imports as a node`() {
        val body = """{"outbounds":[{"type":"anytls","tag":"at","server":"at.example.com",
            |"server_port":443,"password":"pw","tls":{"enabled":true}}]}""".trimMargin()
        val n = parser.parse(body, 1).nodes.single()
        assertEquals(ProtocolType.ANYTLS, n.protocol)
        assertEquals("at", n.name)
        assertEquals("at.example.com", n.server)
    }

    @Test
    fun `wireguard endpoint imports as a node`() {
        val body = """{"endpoints":[{"type":"wireguard","tag":"wg",
            |"address":["10.0.0.2/32"],"private_key":"priv",
            |"peers":[{"address":"wg.example.com","port":51820,"public_key":"pub",
            |"allowed_ips":["0.0.0.0/0"]}]}]}""".trimMargin()
        val n = parser.parse(body, 1).nodes.single()
        assertEquals(ProtocolType.WIREGUARD, n.protocol)
        assertEquals("wg.example.com", n.server)
        assertEquals(51820, n.port)
        val o = json.parseToJsonElement(n.outboundJson).jsonObject
        assertEquals("wireguard", o["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `wireguard endpoint normalizes peers like the legacy path`() {
        // peers omitting allowed_ips must not produce a node that fails
        // checkConfig at refresh — default to full-tunnel like
        // wireguardToNode; missing private_key is a skip.
        val body = """{"endpoints":[{"type":"wireguard","tag":"wg",
            |"address":["10.0.0.2/32"],"private_key":"priv","reserved":[1,2,3],
            |"peers":[{"address":"wg.example.com","port":51820,"public_key":"pub"}]}]}""".trimMargin()
        val n = parser.parse(body, 1).nodes.single()
        val o = json.parseToJsonElement(n.outboundJson).jsonObject
        val peer = o["peers"]!!.jsonArray.single().jsonObject
        assertEquals("0.0.0.0/0", peer["allowed_ips"]!!.jsonArray[0].jsonPrimitive.content)
        // Top-level reserved is inherited into the peer, not left dangling.
        assertNull(o["reserved"])
        assertEquals(1, peer["reserved"]!!.jsonArray[0].jsonPrimitive.int)
    }

    @Test
    fun `wireguard endpoint without private_key is skipped`() {
        val body = """{"endpoints":[{"type":"wireguard","tag":"wg",
            |"address":["10.0.0.2/32"],
            |"peers":[{"address":"wg.example.com","port":51820,"public_key":"pub"}]}],
            |"outbounds":[{"type":"vless","tag":"ok","server":"a.example.com",
            | "server_port":443,"uuid":"11111111-2222-3333-4444-555555555555"}]}""".trimMargin()
        val result = parser.parse(body, 1)
        assertEquals("malformed", result.skipped.single().reason)
    }

    @Test
    fun `legacy wireguard outbound converts to endpoint shape`() {
        val body = """{"outbounds":[{"type":"wireguard","tag":"wg",
            |"server":"wg.example.com","server_port":51820,
            |"local_address":["10.0.0.2/32"],"private_key":"priv",
            |"peer_public_key":"pub","mtu":1280}]}""".trimMargin()
        val n = parser.parse(body, 1).nodes.single()
        assertEquals(ProtocolType.WIREGUARD, n.protocol)
        val o = json.parseToJsonElement(n.outboundJson).jsonObject
        // Endpoint shape: peers[] + address[], no top-level server_port.
        assertEquals("wireguard", o["type"]!!.jsonPrimitive.content)
        assertNull(o["server_port"])
        val peer = o["peers"]!!.jsonArray.single().jsonObject
        assertEquals("wg.example.com", peer["address"]!!.jsonPrimitive.content)
        assertEquals(51820, peer["port"]!!.jsonPrimitive.int)
        assertEquals("pub", peer["public_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `legacy wireguard outbound with multiple peers keeps all peers`() {
        val body = """{"outbounds":[{"type":"wireguard","tag":"wg",
            |"local_address":["10.0.0.2/32"],"private_key":"priv",
            |"peers":[
            |  {"server":"a.example.com","server_port":51820,"public_key":"pa",
            |   "allowed_ips":["10.1.0.0/16"]},
            |  {"server":"b.example.com","server_port":51821,"public_key":"pb"}
            |]}]}""".trimMargin()
        val n = parser.parse(body, 1).nodes.single()
        assertEquals("a.example.com", n.server)
        assertEquals(51820, n.port)
        val o = json.parseToJsonElement(n.outboundJson).jsonObject
        val peers = o["peers"]!!.jsonArray
        assertEquals(2, peers.size)
        val p0 = peers[0].jsonObject
        assertEquals("a.example.com", p0["address"]!!.jsonPrimitive.content)
        assertEquals(51820, p0["port"]!!.jsonPrimitive.int)
        assertEquals("10.1.0.0/16", p0["allowed_ips"]!!.jsonArray.single().jsonPrimitive.content)
        val p1 = peers[1].jsonObject
        assertEquals("b.example.com", p1["address"]!!.jsonPrimitive.content)
        // Missing allowed_ips defaults to full-tunnel.
        assertEquals("0.0.0.0/0", p1["allowed_ips"]!!.jsonArray[0].jsonPrimitive.content)
        // Endpoint shape: local_address normalized to address.
        assertEquals("10.0.0.2/32", o["address"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `non-primitive tag falls back to server-port name`() {
        val body = """{"outbounds":[
            |{"type":"vless","tag":{"bad":1},"server":"a.example.com",
            | "server_port":443,"uuid":"11111111-2222-3333-4444-555555555555"},
            |{"type":"vless","tag":"ok","server":"b.example.com",
            | "server_port":443,"uuid":"11111111-2222-3333-4444-555555555555"}]}""".trimMargin()
        val result = parser.parse(body, 1)
        // A weird tag is display metadata only — the node still imports.
        assertEquals(2, result.nodes.size)
        assertEquals("a.example.com:443", result.nodes[0].name)
        assertEquals("ok", result.nodes[1].name)
    }

    @Test
    fun `unknown outbound type is reported as skipped`() {
        val body = """{"outbounds":[
            |{"type":"shadowtls","tag":"st"},
            |{"type":"mystery","tag":"m"},
            |{"type":"vless","tag":"ok","server":"a.example.com","server_port":443,
            |"uuid":"11111111-2222-3333-4444-555555555555"}]}""".trimMargin()
        val result = parser.parse(body, 1)
        assertEquals(1, result.nodes.size)
        // shadowtls is a known non-node type — ignored silently, not counted.
        assertEquals(1, result.skipped.size)
        assertEquals("unsupported protocol: mystery", result.skipped[0].reason)
        assertEquals("m", result.skipped[0].name)
    }

    @Test
    fun `unknown endpoint type is reported as skipped`() {
        val body = """{"endpoints":[{"type":"tailscale","tag":"ts"}],
            |"outbounds":[{"type":"vless","tag":"ok","server":"a.example.com",
            |"server_port":443,"uuid":"11111111-2222-3333-4444-555555555555"}]}""".trimMargin()
        val result = parser.parse(body, 1)
        assertEquals(1, result.nodes.size)
        assertEquals("unsupported endpoint: tailscale", result.skipped.single().reason)
    }
}
