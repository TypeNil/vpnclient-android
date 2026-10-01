package dev.typenil.vpnclient.core.engine.singbox

import dev.typenil.vpnclient.core.subscription.ClashYamlParser
import dev.typenil.vpnclient.core.subscription.SingBoxJsonParser
import dev.typenil.vpnclient.core.subscription.UriListParser
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.toDomain
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read-only TLS posture surfaced on the Servers card — the summary is the
 * only view of stored `outboundJson` the UI is allowed to have, so the
 * parsing honesty (and secrecy) contract is pinned here. Fixtures use
 * reserved domains and fake credentials only.
 */
class NodeTlsSummaryTest {

    private val uriParser = UriListParser()
    private val yamlParser = ClashYamlParser()
    private val jsonParser = SingBoxJsonParser()
    private val compiler = ConfigCompiler()
    private val json = Json { ignoreUnknownKeys = true }

    private fun summaryOf(node: ProxyNode): NodeTlsSummary =
        NodeTlsSummary.fromOutboundJson(node.outboundJson)

    private fun uriNode(line: String): ProxyNode = uriParser.parse(line, 7).nodes.single()

    private fun b64(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    private fun jsonNode(outbound: String): ProxyNode =
        jsonParser.parse("""{"outbounds":[$outbound]}""", 7).nodes.single()

    // ---------- URI share links ----------

    @Test
    fun `trojan uri defaults to certificate verification`() {
        val node = uriNode("trojan://pw@example.com:443#t")
        val s = summaryOf(node)
        assertEquals(NodeTlsSummary.Mode.CERTIFICATE, s.mode)
        assertFalse(s.insecure)
        assertEquals("example.com", s.serverName)
    }

    @Test
    fun `vless tls uri with allowInsecure reports insecure`() {
        val node =
            uriNode(
                "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
                    "?security=tls&sni=cdn.example.org&allowInsecure=1&alpn=h2,http/1.1#v",
            )
        val s = summaryOf(node)
        assertEquals(NodeTlsSummary.Mode.CERTIFICATE, s.mode)
        assertTrue(s.insecure)
        assertEquals("cdn.example.org", s.serverName)
        assertEquals(listOf("h2", "http/1.1"), s.alpn)
    }

    @Test
    fun `vless reality uri reports reality and leaks no key material`() {
        val node =
            uriNode(
                "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
                    "?security=reality&sni=www.example.org&pbk=fakepubkey123&sid=01ab#r",
            )
        val s = summaryOf(node)
        assertEquals(NodeTlsSummary.Mode.REALITY, s.mode)
        assertFalse(s.insecure)
        assertEquals("www.example.org", s.serverName)
        // Secrecy: public key / short id are engine material, never summary data.
        val rendered = s.toString()
        assertFalse(rendered.contains("fakepubkey123"))
        assertFalse(rendered.contains("01ab"))
    }

    @Test
    fun `reality uri with insecure keeps the insecure flag visible`() {
        val node =
            uriNode(
                "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
                    "?security=reality&sni=www.example.org&pbk=fakepubkey123&allowInsecure=1#r",
            )
        val s = summaryOf(node)
        assertEquals(NodeTlsSummary.Mode.REALITY, s.mode)
        assertTrue(s.insecure)
    }

    @Test
    fun `vmess uri tls reports certificate`() {
        val payload =
            b64(
                """{"add":"example.com","port":"443",""" +
                    """"id":"11111111-2222-3333-4444-555555555555","tls":"tls",""" +
                    """"sni":"cdn.example.org","alpn":"h2,http/1.1"}""",
            )
        val s = summaryOf(uriNode("vmess://$payload#v"))
        assertEquals(NodeTlsSummary.Mode.CERTIFICATE, s.mode)
        assertFalse(s.insecure)
        assertEquals("cdn.example.org", s.serverName)
        assertEquals(listOf("h2", "http/1.1"), s.alpn)
    }

    @Test
    fun `vmess uri allowInsecure reports insecure`() {
        val payload =
            b64(
                """{"add":"example.com","port":"443",""" +
                    """"id":"11111111-2222-3333-4444-555555555555","tls":"tls",""" +
                    """"allowInsecure":true}""",
            )
        val s = summaryOf(uriNode("vmess://$payload#v"))
        assertEquals(NodeTlsSummary.Mode.CERTIFICATE, s.mode)
        assertTrue(s.insecure)
    }

    @Test
    fun `tuic uri reports certificate with default alpn`() {
        val node =
            uriNode("tuic://11111111-2222-3333-4444-555555555555:pw@example.com:443#t")
        val s = summaryOf(node)
        assertEquals(NodeTlsSummary.Mode.CERTIFICATE, s.mode)
        assertFalse(s.insecure)
        // Parser defaults: SNI = host, ALPN = h3.
        assertEquals("example.com", s.serverName)
        assertEquals(listOf("h3"), s.alpn)
    }

    @Test
    fun `anytls uri insecure reports insecure`() {
        val node = uriNode("anytls://pw@example.com:443?insecure=true#a")
        val s = summaryOf(node)
        assertEquals(NodeTlsSummary.Mode.CERTIFICATE, s.mode)
        assertTrue(s.insecure)
    }

    @Test
    fun `shadowsocks uri has no tls`() {
        val node = uriNode("ss://YWVzLTI1Ni1nY206cHc=@example.com:8388#s")
        assertEquals(NodeTlsSummary.Mode.NONE, summaryOf(node).mode)
    }

    @Test
    fun `hysteria2 uri insecure reports insecure`() {
        val node = uriNode("hysteria2://pw@example.com:443?insecure=1#h")
        val s = summaryOf(node)
        assertEquals(NodeTlsSummary.Mode.CERTIFICATE, s.mode)
        assertTrue(s.insecure)
    }

    // ---------- Clash YAML ----------

    @Test
    fun `clash trojan skip-cert-verify reports insecure`() {
        val yaml =
            """
            proxies:
              - name: t
                type: trojan
                server: example.com
                port: 443
                password: pw
                sni: cdn.example.org
                skip-cert-verify: true
            """.trimIndent()
        val s = summaryOf(yamlParser.parse(yaml, 7).nodes.single())
        assertEquals(NodeTlsSummary.Mode.CERTIFICATE, s.mode)
        assertTrue(s.insecure)
        assertEquals("cdn.example.org", s.serverName)
    }

    @Test
    fun `clash vless reality-opts reports reality`() {
        val yaml =
            """
            proxies:
              - name: r
                type: vless
                server: example.com
                port: 443
                uuid: 11111111-2222-3333-4444-555555555555
                tls: true
                reality-opts:
                  public-key: fakepubkey123
                  short-id: 01ab
            """.trimIndent()
        val s = summaryOf(yamlParser.parse(yaml, 7).nodes.single())
        assertEquals(NodeTlsSummary.Mode.REALITY, s.mode)
        assertFalse(s.toString().contains("fakepubkey123"))
        assertFalse(s.toString().contains("01ab"))
    }

    @Test
    fun `clash shadowsocks has no tls`() {
        val yaml =
            """
            proxies:
              - name: s
                type: ss
                server: example.com
                port: 8388
                cipher: aes-256-gcm
                password: pw
            """.trimIndent()
        assertEquals(
            NodeTlsSummary.Mode.NONE,
            summaryOf(yamlParser.parse(yaml, 7).nodes.single()).mode,
        )
    }

    // ---------- sing-box subscription JSON ----------

    @Test
    fun `sing-box json strict tls reports certificate`() {
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"trojan","tag":"t","server":"example.com","server_port":443,""" +
                        """"password":"pw","tls":{"enabled":true,"server_name":"cdn.example.org",""" +
                        """"alpn":["h2"]}}""",
                ),
            )
        assertEquals(NodeTlsSummary.Mode.CERTIFICATE, s.mode)
        assertFalse(s.insecure)
        assertEquals("cdn.example.org", s.serverName)
        assertEquals(listOf("h2"), s.alpn)
    }

    @Test
    fun `sing-box json insecure roundtrips as insecure`() {
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"trojan","tag":"t","server":"example.com","server_port":443,""" +
                        """"password":"pw","tls":{"enabled":true,"insecure":true,""" +
                        """"server_name":"cdn.example.org"}}""",
                ),
            )
        assertEquals(NodeTlsSummary.Mode.CERTIFICATE, s.mode)
        assertTrue(s.insecure)
        assertEquals("cdn.example.org", s.serverName)
    }

    @Test
    fun `sing-box json tls disabled reports none`() {
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"trojan","tag":"t","server":"example.com","server_port":443,""" +
                        """"password":"pw","tls":{"enabled":false}}""",
                ),
            )
        assertEquals(NodeTlsSummary.Mode.NONE, s.mode)
    }

    @Test
    fun `sing-box json reality reports reality`() {
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"vless","tag":"r","server":"example.com","server_port":443,""" +
                        """"uuid":"11111111-2222-3333-4444-555555555555","tls":{"enabled":true,""" +
                        """"reality":{"enabled":true,"public_key":"fakepubkey123","short_id":"01ab"}}}""",
                ),
            )
        assertEquals(NodeTlsSummary.Mode.REALITY, s.mode)
        assertFalse(s.toString().contains("fakepubkey123"))
        assertFalse(s.toString().contains("01ab"))
    }

    // ---------- malformed / wrong types ----------

    @Test
    fun `non-object tls block reports unknown`() {
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"trojan","tag":"t","server":"example.com","server_port":443,""" +
                        """"password":"pw","tls":true}""",
                ),
            )
        assertEquals(NodeTlsSummary.Mode.UNKNOWN, s.mode)
        assertFalse(s.insecure)
    }

    @Test
    fun `string enabled reports unknown not verified`() {
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"trojan","tag":"t","server":"example.com","server_port":443,""" +
                        """"password":"pw","tls":{"enabled":"true"}}""",
                ),
            )
        assertEquals(NodeTlsSummary.Mode.UNKNOWN, s.mode)
    }

    @Test
    fun `string insecure reports unknown and never claims strict or off`() {
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"trojan","tag":"t","server":"example.com","server_port":443,""" +
                        """"password":"pw","tls":{"enabled":true,"insecure":"true"}}""",
                ),
            )
        assertEquals(NodeTlsSummary.Mode.UNKNOWN, s.mode)
        assertFalse(s.insecure)
    }

    @Test
    fun `reality wrong type reports unknown`() {
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"vless","tag":"r","server":"example.com","server_port":443,""" +
                        """"uuid":"u","tls":{"enabled":true,"reality":"yes"}}""",
                ),
            )
        assertEquals(NodeTlsSummary.Mode.UNKNOWN, s.mode)
    }

    @Test
    fun `non-array alpn reports unknown`() {
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"trojan","tag":"t","server":"example.com","server_port":443,""" +
                        """"password":"pw","tls":{"enabled":true,"alpn":"h2"}}""",
                ),
            )
        assertEquals(NodeTlsSummary.Mode.UNKNOWN, s.mode)
    }

    // ---------- insecure preserved on NONE/UNKNOWN paths ----------

    @Test
    fun `invalid enabled still preserves literal insecure true`() {
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"trojan","tag":"t","server":"example.com","server_port":443,""" +
                        """"password":"pw","tls":{"enabled":"x","insecure":true}}""",
                ),
            )
        assertEquals(NodeTlsSummary.Mode.UNKNOWN, s.mode)
        assertTrue(s.insecure)
        assertNull(s.serverName)
        assertTrue(s.alpn.isEmpty())
    }

    @Test
    fun `invalid reality still preserves literal insecure true`() {
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"vless","tag":"r","server":"example.com","server_port":443,""" +
                        """"uuid":"u","tls":{"enabled":true,"reality":"yes","insecure":true}}""",
                ),
            )
        assertEquals(NodeTlsSummary.Mode.UNKNOWN, s.mode)
        assertTrue(s.insecure)
    }

    @Test
    fun `invalid server_name still preserves literal insecure true`() {
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"trojan","tag":"t","server":"example.com","server_port":443,""" +
                        """"password":"pw","tls":{"enabled":true,"insecure":true,""" +
                        """"server_name":5}}""",
                ),
            )
        assertEquals(NodeTlsSummary.Mode.UNKNOWN, s.mode)
        assertTrue(s.insecure)
        assertNull(s.serverName)
    }

    @Test
    fun `invalid alpn still preserves literal insecure true`() {
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"trojan","tag":"t","server":"example.com","server_port":443,""" +
                        """"password":"pw","tls":{"enabled":true,"insecure":true,"alpn":5}}""",
                ),
            )
        assertEquals(NodeTlsSummary.Mode.UNKNOWN, s.mode)
        assertTrue(s.insecure)
        assertTrue(s.alpn.isEmpty())
    }

    @Test
    fun `disabled tls with insecure keeps the flag on NONE`() {
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"trojan","tag":"t","server":"example.com","server_port":443,""" +
                        """"password":"pw","tls":{"enabled":false,"insecure":true}}""",
                ),
            )
        assertEquals(NodeTlsSummary.Mode.NONE, s.mode)
        assertTrue(s.insecure)
    }

    @Test
    fun `missing enabled with insecure keeps the flag on NONE`() {
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"trojan","tag":"t","server":"example.com","server_port":443,""" +
                        """"password":"pw","tls":{"insecure":true}}""",
                ),
            )
        assertEquals(NodeTlsSummary.Mode.NONE, s.mode)
        assertTrue(s.insecure)
    }

    @Test
    fun `unparseable outbound json reports unknown`() {
        val s = NodeTlsSummary.fromOutboundJson("not json at all")
        assertEquals(NodeTlsSummary.Mode.UNKNOWN, s.mode)
        assertFalse(s.insecure)
    }

    // ---------- bounds / sanitization ----------

    @Test
    fun `hostile server_name is capped and stripped of control chars`() {
        val longName = "a".repeat(500)
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"trojan","tag":"t","server":"example.com","server_port":443,""" +
                        """"password":"pw","tls":{"enabled":true,"server_name":"$longName"}}""",
                ),
            )
        assertEquals(253, s.serverName!!.length)

        val dirty =
            NodeTlsSummary.fromOutboundJson(
                """{"type":"x","tls":{"enabled":true,"server_name":"ev\nilad.example.com"}}""",
            )
        assertEquals("evilad.example.com", dirty.serverName)
    }

    @Test
    fun `alpn list is entry capped`() {
        val alpn = (1..20).joinToString(",") { "\"t$it\"" }
        val s =
            summaryOf(
                jsonNode(
                    """{"type":"trojan","tag":"t","server":"example.com","server_port":443,""" +
                        """"password":"pw","tls":{"enabled":true,"alpn":[$alpn]}}""",
                ),
            )
        assertEquals(8, s.alpn.size)
    }

    // ---------- extraction / compile pipeline ----------

    @Test
    fun `entity to domain preserves outbound and id so summary is identical`() {
        val node =
            uriNode(
                "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
                    "?security=tls&allowInsecure=1#v",
            )
        val entity =
            NodeEntity(
                id = node.id,
                subscriptionId = node.subscriptionId,
                name = node.name,
                protocol = node.protocol.name,
                server = node.server,
                port = node.port,
                outboundJson = node.outboundJson,
                rawUri = node.rawUri,
                position = 0,
            )
        val domain = entity.toDomain()
        assertEquals(node.id, domain.id)
        assertEquals(node.outboundJson, domain.outboundJson)
        assertEquals(summaryOf(node), NodeTlsSummary.fromOutboundJson(domain.outboundJson))
    }

    @Test
    fun `compiled config carries a yaml-derived tls block verbatim`() {
        val yaml =
            """
            proxies:
              - name: t
                type: trojan
                server: example.com
                port: 443
                password: pw
                sni: cdn.example.org
                skip-cert-verify: true
            """.trimIndent()
        val node = yamlParser.parse(yaml, 7).nodes.single()
        val config = compiler.build(listOf(node), node.id, ipv6Enabled = true)
        val outbound =
            json.parseToJsonElement(config.configJson).jsonObject["outbounds"]!!.jsonArray
                .map { it.jsonObject }
                .single { it["tag"]?.jsonPrimitive?.content == node.id }
        val originalTls =
            json.parseToJsonElement(node.outboundJson).jsonObject["tls"]!!.jsonObject
        assertEquals(originalTls, outbound["tls"])
        // …and the summary still reports what the engine will run.
        assertTrue(summaryOf(node).insecure)
    }

    @Test
    fun `mode labels stay honest in resources`() {
        // Unit-test working dir is the module dir — paths resolve under app/.
        fun resValue(path: String, name: String): String =
            Regex("""name="$name">([^<]*)<""")
                .find(File(path).readText())!!.groupValues[1]
        val en = "src/main/res/values/strings.xml"
        val ru = "src/main/res/values-ru/strings.xml"
        // NONE must not call the protocol "plaintext" — just no TLS configured.
        assertEquals("No TLS configured", resValue(en, "servers_tls_mode_none"))
        assertEquals("TLS не настроен", resValue(ru, "servers_tls_mode_none"))
        // CERTIFICATE names the auth category; it must not promise CA
        // verification — insecure/custom certs/pinning still decide that.
        assertEquals("Certificate-based TLS", resValue(en, "servers_tls_mode_certificate"))
        assertFalse(resValue(en, "servers_tls_mode_none").contains("plaintext", ignoreCase = true))
    }

    @Test
    fun `compiled config carries the node tls block verbatim`() {
        val node =
            uriNode(
                "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
                    "?security=reality&sni=www.example.org&pbk=fakepubkey123&sid=01ab#r",
            )
        val config = compiler.build(listOf(node), node.id, ipv6Enabled = true)
        val outbound =
            json.parseToJsonElement(config.configJson).jsonObject["outbounds"]!!.jsonArray
                .map { it.jsonObject }
                .single { it["tag"]?.jsonPrimitive?.content == node.id }
        val originalTls =
            json.parseToJsonElement(node.outboundJson).jsonObject["tls"]!!.jsonObject
        assertEquals(originalTls, outbound["tls"])
    }
}
