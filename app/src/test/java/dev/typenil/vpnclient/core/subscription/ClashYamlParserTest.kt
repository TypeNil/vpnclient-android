package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClashYamlParserTest {

    private val parser = ClashYamlParser()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `vless with reality-opts maps to sing-box vless`() {
        val body = """
        proxies:
          - name: vless-reality
            type: vless
            server: v.example.com
            port: 443
            uuid: 11111111-2222-3333-4444-555555555555
            flow: xtls-rprx-vision
            network: tcp
            tls: true
            servername: www.example.org
            client-fingerprint: chrome
            reality-opts:
              public-key: pubkey123
              short-id: ab01
        """.trimIndent()
        val n = parser.parse(body, 2).single()
        assertEquals(ProtocolType.VLESS, n.protocol)
        assertEquals("vless-reality", n.name)
        assertEquals("v.example.com", n.server)
        assertEquals(443, n.port)
        assertNull(n.rawUri)

        val o = json.parseToJsonElement(n.outboundJson).jsonObject
        assertEquals("vless", o["type"]!!.jsonPrimitive.content)
        assertEquals(n.id, o["tag"]!!.jsonPrimitive.content)
        assertEquals("11111111-2222-3333-4444-555555555555", o["uuid"]!!.jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", o["flow"]!!.jsonPrimitive.content)
        assertEquals("local", o["domain_resolver"]!!.jsonPrimitive.content)
        val tls = o["tls"]!!.jsonObject
        assertEquals("www.example.org", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("pubkey123", tls["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content)
        assertEquals("ab01", tls["reality"]!!.jsonObject["short_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vmess with ws-opts maps to ws transport`() {
        val body = """
        proxies:
          - name: vmess-ws
            type: vmess
            server: vm.example.com
            port: 8443
            uuid: 22222222-3333-4444-5555-666666666666
            alterId: 2
            cipher: auto
            tls: true
            servername: vm.example.com
            network: ws
            ws-opts:
              path: /ws-path
              headers:
                Host: cdn.example.com
        """.trimIndent()
        val n = parser.parse(body, 1).single()
        val o = json.parseToJsonElement(n.outboundJson).jsonObject
        assertEquals("vmess", o["type"]!!.jsonPrimitive.content)
        assertEquals(2, o["alter_id"]!!.jsonPrimitive.int)
        assertEquals("auto", o["security"]!!.jsonPrimitive.content)
        val transport = o["transport"]!!.jsonObject
        assertEquals("ws", transport["type"]!!.jsonPrimitive.content)
        assertEquals("/ws-path", transport["path"]!!.jsonPrimitive.content)
        assertEquals("cdn.example.com", transport["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content)
        assertTrue(o["tls"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `ss proxy maps cipher to method`() {
        val body = """
        proxies:
          - name: ss-node
            type: ss
            server: ss.example.com
            port: 8388
            cipher: chacha20-ietf-poly1305
            password: pw123
        """.trimIndent()
        val n = parser.parse(body, 1).single()
        assertEquals(ProtocolType.SHADOWSOCKS, n.protocol)
        val o = json.parseToJsonElement(n.outboundJson).jsonObject
        assertEquals("shadowsocks", o["type"]!!.jsonPrimitive.content)
        assertEquals("chacha20-ietf-poly1305", o["method"]!!.jsonPrimitive.content)
        assertEquals("pw123", o["password"]!!.jsonPrimitive.content)
        assertEquals(null, o["network"])
    }

    @Test
    fun `unsupported proxy types are skipped`() {
        val body = """
        proxies:
          - name: ssr-node
            type: ssr
            server: ssr.example.com
            port: 1234
            cipher: aes-256-cfb
            password: pw
            protocol: origin
            obfs: plain
          - name: snell
            type: snell
            server: sn.example.com
            port: 443
            psk: psk
          - name: ok
            type: trojan
            server: t.example.com
            port: 443
            password: pw
            sni: t.example.com
        """.trimIndent()
        val nodes = parser.parse(body, 1)
        assertEquals(1, nodes.size)
        assertEquals(ProtocolType.TROJAN, nodes[0].protocol)
    }

    @Test
    fun `trojan always gets tls block`() {
        val body = """
        proxies:
          - name: tj
            type: trojan
            server: t.example.com
            port: 443
            password: pw
            skip-cert-verify: true
        """.trimIndent()
        val n = parser.parse(body, 1).single()
        val tls = json.parseToJsonElement(n.outboundJson).jsonObject["tls"]!!.jsonObject
        assertTrue(tls["enabled"]!!.jsonPrimitive.boolean)
        assertTrue(tls["insecure"]!!.jsonPrimitive.boolean)
        assertEquals("t.example.com", tls["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty proxies throws EmptyResult`() {
        try {
            parser.parse("proxies: []", 1)
            fail("expected EmptyResult")
        } catch (e: SubscriptionError.EmptyResult) {
            // expected
        }
    }

    @Test
    fun `garbage yaml throws ParseFailed or EmptyResult`() {
        try {
            parser.parse("just a scalar", 1)
            fail("expected error")
        } catch (e: SubscriptionError) {
            // ParseFailed ("not a clash config") — either typed error is acceptable
        }
    }
}
