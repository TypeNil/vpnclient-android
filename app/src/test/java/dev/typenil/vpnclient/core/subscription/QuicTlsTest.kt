package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.parse.hysteria2Outbound
import dev.typenil.vpnclient.core.subscription.parse.tlsBlock
import dev.typenil.vpnclient.core.subscription.parse.tuicOutbound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QuicTlsTest {
    private fun tls(outbound: String) = Json.parseToJsonElement(outbound).jsonObject["tls"]!!.jsonObject

    @Test
    fun `URI QUIC nodes omit uTLS and retain TLS settings`() {
        for (link in listOf(
            "hy2://test-password@quic.example:443?sni=tls.example&insecure=1&ech=test-ech",
            "tuic://test-user:test-password@quic.example:443?sni=tls.example&allow_insecure=1&alpn=h3&ech=test-ech",
        )) {
            val block = tls(UriListParser().parse(link, 1).nodes.single().outboundJson)
            assertFalse(block.containsKey("utls"))
            assertEquals("true", block["enabled"]!!.jsonPrimitive.content)
            assertEquals("tls.example", block["server_name"]!!.jsonPrimitive.content)
            assertEquals("true", block["insecure"]!!.jsonPrimitive.content)
            assertEquals("test-ech", block["ech"]!!.jsonObject["config"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `Clash QUIC nodes omit uTLS even with a fingerprint`() {
        for (type in listOf("hysteria2", "tuic")) {
            val body = """
                proxies:
                  - name: test-node
                    type: $type
                    server: quic.example
                    port: 443
                    uuid: test-user
                    password: test-password
                    sni: tls.example
                    skip-cert-verify: true
                    alpn: [h3]
                    client-fingerprint: firefox
            """.trimIndent()
            val block = tls(ClashYamlParser().parse(body, 1).nodes.single().outboundJson)
            assertFalse(block.containsKey("utls"))
            assertEquals("true", block["enabled"]!!.jsonPrimitive.content)
            assertEquals("tls.example", block["server_name"]!!.jsonPrimitive.content)
            assertEquals("true", block["insecure"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `shared QUIC builders remove only uTLS`() {
        val original = tlsBlock("tls.example", insecure = true, alpn = listOf("h3"), ech = "test-ech")
        val expected = JsonObject(original - "utls")
        val hy2 = hysteria2Outbound("test", "quic.example", 443, "test-password", original)
        val tuic = tuicOutbound("test", "quic.example", 443, "test-user", "test-password", tls = original)
        assertEquals(expected, hy2["tls"])
        assertEquals(expected, tuic["tls"])
        assertEquals("chrome", original["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
    }

    @Test
    fun `TCP URI TLS retains uTLS including ECH`() {
        for (link in listOf(
            "vless://test-user@tcp.example:443?security=tls&fp=firefox&ech=test-ech",
            "trojan://test-password@tcp.example:443?fp=firefox&ech=test-ech",
            "anytls://test-password@tcp.example:443?fp=firefox&ech=test-ech",
        )) {
            val block = tls(UriListParser().parse(link, 1).nodes.single().outboundJson)
            assertEquals("firefox", block["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
            assertEquals("test-ech", block["ech"]!!.jsonObject["config"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `TCP Clash TLS retains uTLS`() {
        for (type in listOf("vless", "vmess", "trojan")) {
            val body = """
                proxies:
                  - name: test-node
                    type: $type
                    server: tcp.example
                    port: 443
                    uuid: test-user
                    password: test-password
                    tls: true
                    client-fingerprint: firefox
            """.trimIndent()
            val block = tls(ClashYamlParser().parse(body, 1).nodes.single().outboundJson)
            assertEquals("firefox", block["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `explicit sing-box QUIC TLS remains user input without parser failure`() {
        // Policy: don't silently rewrite user-authored native TLS. Runtime may still reject uTLS.
        for (type in listOf("hysteria2", "tuic")) {
            val body = """{"outbounds":[{"type":"$type","server":"quic.example","server_port":443,
                "uuid":"test-user","password":"test-password","tls":{"enabled":true,
                "utls":{"enabled":true,"fingerprint":"firefox"}}}]}"""
            val result = SingBoxJsonParser().parse(body, 1)
            assertEquals(0, result.skipped.size)
            val block = tls(result.nodes.single().outboundJson)
            assertEquals("true", block["utls"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
            assertEquals("firefox", block["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        }
    }
}
