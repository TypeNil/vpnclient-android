package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UriListParserTest {

    private val parser = UriListParser()
    private val json = Json { ignoreUnknownKeys = true }

    private fun outbound(node: ProxyNode): JsonObject =
        json.parseToJsonElement(node.outboundJson).jsonObject

    private fun b64(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    @Test
    fun `vless reality node builds reality tls block`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?security=reality&sni=www.example.org&fp=chrome&pbk=abc123pubkey&sid=01ab" +
            "&type=tcp&flow=xtls-rprx-vision#Reality%20Node"
        val nodes = parser.parse(uri, 7)
        assertEquals(1, nodes.size)
        val n = nodes[0]
        assertEquals(ProtocolType.VLESS, n.protocol)
        assertEquals("example.com", n.server)
        assertEquals(443, n.port)
        assertEquals("Reality Node", n.name)
        assertEquals(uri, n.rawUri)

        val o = outbound(n)
        assertEquals("vless", o["type"]!!.jsonPrimitive.content)
        assertEquals(n.id, o["tag"]!!.jsonPrimitive.content)
        assertEquals("11111111-2222-3333-4444-555555555555", o["uuid"]!!.jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", o["flow"]!!.jsonPrimitive.content)
        assertEquals("local", o["domain_resolver"]!!.jsonPrimitive.content)
        assertNull(o["transport"])
        val tls = o["tls"]!!.jsonObject
        assertTrue(tls["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("www.example.org", tls["server_name"]!!.jsonPrimitive.content)
        val reality = tls["reality"]!!.jsonObject
        assertEquals("abc123pubkey", reality["public_key"]!!.jsonPrimitive.content)
        assertEquals("01ab", reality["short_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vless ws tls node builds ws transport`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@ws.example.com:8443" +
            "?security=tls&sni=ws.example.com&type=ws&host=cdn.example.com" +
            "&path=%2Fapi%2Fv1&alpn=h2,http/1.1#wsnode"
        val n = parser.parse(uri, 1).single()
        val o = outbound(n)
        assertEquals("vless", o["type"]!!.jsonPrimitive.content)
        val transport = o["transport"]!!.jsonObject
        assertEquals("ws", transport["type"]!!.jsonPrimitive.content)
        assertEquals("/api/v1", transport["path"]!!.jsonPrimitive.content)
        assertEquals("cdn.example.com", transport["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content)
        val tls = o["tls"]!!.jsonObject
        assertEquals("ws.example.com", tls["server_name"]!!.jsonPrimitive.content)
        val alpn = tls["alpn"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("h2", "http/1.1"), alpn)
        assertNull(tls["reality"])
    }

    @Test
    fun `vless xhttp transport is skipped`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@x.example.com:443" +
            "?security=reality&pbk=k&type=xhttp&mode=auto#xhttp"
        try {
            parser.parse(uri, 1)
            fail("xhttp-only list should be empty")
        } catch (e: SubscriptionError.EmptyResult) {
            // expected
        }
    }

    @Test
    fun `vmess base64 node parses fields and ws transport`() {
        val payload = b64(
            """{"v":"2","ps":"Vmess Node","add":"vm.example.com","port":"8443",""" +
                """"id":"22222222-3333-4444-5555-666666666666","aid":"4","scy":"auto",""" +
                """"net":"ws","type":"none","host":"cdn.example.com","path":"/ws","tls":"tls","sni":"vm.example.com"}""",
        )
        val n = parser.parse("vmess://$payload", 3).single()
        assertEquals(ProtocolType.VMESS, n.protocol)
        assertEquals("vm.example.com", n.server)
        assertEquals(8443, n.port)
        assertEquals("Vmess Node", n.name)

        val o = outbound(n)
        assertEquals("vmess", o["type"]!!.jsonPrimitive.content)
        assertEquals("22222222-3333-4444-5555-666666666666", o["uuid"]!!.jsonPrimitive.content)
        assertEquals("auto", o["security"]!!.jsonPrimitive.content)
        assertEquals(4, o["alter_id"]!!.jsonPrimitive.int)
        assertTrue(o["authenticated_length"]!!.jsonPrimitive.boolean)
        val transport = o["transport"]!!.jsonObject
        assertEquals("ws", transport["type"]!!.jsonPrimitive.content)
        assertEquals("/ws", transport["path"]!!.jsonPrimitive.content)
        assertEquals("vm.example.com", o["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `trojan node always gets tls and ws transport`() {
        val uri = "trojan://t0psecret@tr.example.com:443?sni=tr.example.com" +
            "&type=ws&host=h.example.com&path=%2Ftj#Trojan"
        val n = parser.parse(uri, 2).single()
        assertEquals(ProtocolType.TROJAN, n.protocol)
        assertEquals("Trojan", n.name)
        val o = outbound(n)
        assertEquals("trojan", o["type"]!!.jsonPrimitive.content)
        assertEquals("t0psecret", o["password"]!!.jsonPrimitive.content)
        assertEquals("tr.example.com", o["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
        val transport = o["transport"]!!.jsonObject
        assertEquals("ws", transport["type"]!!.jsonPrimitive.content)
        assertEquals("/tj", transport["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ss userinfo base64 form`() {
        val uri = "ss://${b64("aes-256-gcm:pw123")}@ss.example.com:8388#SS-A"
        val n = parser.parse(uri, 1).single()
        assertEquals(ProtocolType.SHADOWSOCKS, n.protocol)
        assertEquals("ss.example.com", n.server)
        assertEquals(8388, n.port)
        assertEquals("SS-A", n.name)
        val o = outbound(n)
        assertEquals("shadowsocks", o["type"]!!.jsonPrimitive.content)
        assertEquals("aes-256-gcm", o["method"]!!.jsonPrimitive.content)
        assertEquals("pw123", o["password"]!!.jsonPrimitive.content)
        assertEquals(null, o["network"])
    }

    @Test
    fun `ss plaintext and per-part base64 userinfo forms`() {
        val plain = "ss://aes-256-gcm:pw123@ss.example.com:8388"
        val perPart = "ss://${b64("aes-256-gcm")}:${b64("pw123")}@ss.example.com:8388"
        val nodes = parser.parse("$plain\n$perPart", 1)
        assertEquals(2, nodes.size)
        nodes.forEach { n ->
            val o = outbound(n)
            assertEquals("aes-256-gcm", o["method"]!!.jsonPrimitive.content)
            assertEquals("pw123", o["password"]!!.jsonPrimitive.content)
            assertEquals("ss.example.com", o["server"]!!.jsonPrimitive.content)
            assertEquals(8388, o["server_port"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `ss sip002 whole-thing base64 form`() {
        val uri = "ss://${b64("chacha20-ietf-poly1305:pw456@ss2.example.com:8389")}#SS-C"
        val n = parser.parse(uri, 1).single()
        val o = outbound(n)
        assertEquals("chacha20-ietf-poly1305", o["method"]!!.jsonPrimitive.content)
        assertEquals("pw456", o["password"]!!.jsonPrimitive.content)
        assertEquals("ss2.example.com", o["server"]!!.jsonPrimitive.content)
        assertEquals(8389, o["server_port"]!!.jsonPrimitive.int)
        assertEquals("SS-C", n.name)
    }

    @Test
    fun `hysteria2 node with obfs`() {
        val uri = "hysteria2://hy2pass@hy.example.com:443?sni=hy.example.com" +
            "&insecure=1&obfs=salamander&obfs-password=obfspw#Hy2"
        val n = parser.parse(uri, 1).single()
        assertEquals(ProtocolType.HYSTERIA2, n.protocol)
        val o = outbound(n)
        assertEquals("hysteria2", o["type"]!!.jsonPrimitive.content)
        assertEquals("hy2pass", o["password"]!!.jsonPrimitive.content)
        val tls = o["tls"]!!.jsonObject
        assertTrue(tls["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("hy.example.com", tls["server_name"]!!.jsonPrimitive.content)
        assertTrue(tls["insecure"]!!.jsonPrimitive.boolean)
        val obfs = o["obfs"]!!.jsonObject
        assertEquals("salamander", obfs["type"]!!.jsonPrimitive.content)
        assertEquals("obfspw", obfs["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hy2 scheme alias parses`() {
        val uri = "hy2://pw@hy.example.com:1443?sni=hy.example.com"
        val n = parser.parse(uri, 1).single()
        assertEquals(ProtocolType.HYSTERIA2, n.protocol)
        assertEquals(1443, n.port)
    }

    @Test
    fun `tuic node with congestion control and alpn`() {
        val uri = "tuic://33333333-4444-5555-6666-777777777777:tuicpw@tuic.example.com:443" +
            "?congestion_control=bbr&udp_relay_mode=native&alpn=h3&sni=t.example.com#Tuic"
        val n = parser.parse(uri, 1).single()
        assertEquals(ProtocolType.TUIC, n.protocol)
        val o = outbound(n)
        assertEquals("tuic", o["type"]!!.jsonPrimitive.content)
        assertEquals("33333333-4444-5555-6666-777777777777", o["uuid"]!!.jsonPrimitive.content)
        assertEquals("tuicpw", o["password"]!!.jsonPrimitive.content)
        assertEquals("bbr", o["congestion_control"]!!.jsonPrimitive.content)
        assertEquals("native", o["udp_relay_mode"]!!.jsonPrimitive.content)
        val tls = o["tls"]!!.jsonObject
        assertEquals("t.example.com", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("h3", tls["alpn"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `unknown and unsupported schemes are skipped`() {
        val body = """
            |hysteria://pw@h1.example.com:443
            |socks5://u:p@s.example.com:1080
            |wireguard://wg.example.com:51820
            |ss://aes-256-gcm:pw123@ss.example.com:8388
            |not-a-uri-at-all
        """.trimMargin()
        val nodes = parser.parse(body, 1)
        assertEquals(1, nodes.size)
        assertEquals(ProtocolType.SHADOWSOCKS, nodes[0].protocol)
    }

    @Test
    fun `comment lines are skipped but fragment-only hash inside uri is not`() {
        val body = "# comment\n// another comment\nss://aes-256-gcm:pw123@ss.example.com:8388#Hashtag"
        val nodes = parser.parse(body, 1)
        assertEquals(1, nodes.size)
        assertEquals("Hashtag", nodes[0].name)
    }

    @Test
    fun `malformed line does not fail sibling lines`() {
        val body = "vless://not-a-uuid\nss://aes-256-gcm:pw123@ss.example.com:8388#OK"
        val nodes = parser.parse(body, 1)
        assertEquals(1, nodes.size)
        assertEquals("OK", nodes[0].name)
    }

    @Test
    fun `all bad input throws EmptyResult`() {
        try {
            parser.parse("garbage\nhysteria://x@y:1\nss://broken", 1)
            fail("expected EmptyResult")
        } catch (e: SubscriptionError.EmptyResult) {
            // expected
        }
    }

    @Test
    fun `node ids are deterministic and scoped to subscription`() {
        val uri = "trojan://pw@tr.example.com:443#N"
        val first = parser.parse(uri, 5).single()
        val second = parser.parse(uri, 5).single()
        val otherSub = parser.parse(uri, 6).single()
        assertEquals(first.id, second.id)
        assertNotEquals(first.id, otherSub.id)
        assertTrue(first.id.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `vless links sharing uuid host port but different network get distinct ids`() {
        // Regression: identity used to hash only scheme|server:port|credential,
        // so transport/TLS differences collided and distinctBy dropped a node.
        val uuid = "11111111-2222-3333-4444-555555555555"
        val tcp = "vless://$uuid@shared.example.com:443?security=tls&sni=a.example.com#TCP"
        val ws = "vless://$uuid@shared.example.com:443?security=tls&sni=a.example.com" +
            "&type=ws&host=cdn.example.com&path=%2Fws#WS"
        val nodes = parser.parse("$tcp\n$ws", 1)
        assertEquals(2, nodes.size)
        assertNotEquals(nodes[0].id, nodes[1].id)
        // Both survive the repository's distinctBy { it.id } dedupe.
        assertEquals(2, nodes.distinctBy { it.id }.size)
    }

    @Test
    fun `links differing only in display name share an id`() {
        val a = "trojan://pw@tr.example.com:443#Alpha"
        val b = "trojan://pw@tr.example.com:443#Beta"
        val nodes = parser.parse("$a\n$b", 1)
        assertEquals(2, nodes.size)
        assertEquals(nodes[0].id, nodes[1].id)
        assertEquals(1, nodes.distinctBy { it.id }.size)
    }

    @Test
    fun `fragment name is percent decoded including unicode`() {
        val uri = "ss://aes-256-gcm:pw123@ss.example.com:8388#" +
            "%D0%9C%D0%BE%D1%81%D0%BA%D0%B2%D0%B0%201"
        val n = parser.parse(uri, 1).single()
        assertEquals("Москва 1", n.name)
    }

    @Test
    fun `missing fragment falls back to server-port name`() {
        val n = parser.parse("ss://aes-256-gcm:pw123@ss.example.com:8388", 1).single()
        assertEquals("ss.example.com:8388", n.name)
    }

    @Test
    fun `crlf line endings parse`() {
        val body = "ss://aes-256-gcm:pw@a.example.com:1\r\nss://aes-256-gcm:pw@b.example.com:2\r\n"
        assertEquals(2, parser.parse(body, 1).size)
    }
}
