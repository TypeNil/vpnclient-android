package dev.typenil.vpnclient.core.subscription

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SniFallbackTest {
    private data class Case(
        val server: String = "server.example",
        val network: String = "tcp",
        val sni: String? = null,
        val host: String? = null,
        val expected: String? = "server.example",
        val tls: Boolean = true,
    )

    private val cases = listOf(
        Case(network = "ws", sni = "sni.example", host = "host.example", expected = "sni.example"),
        Case(network = "ws", host = "host.example", expected = "host.example"),
        Case(network = "httpupgrade", host = "host.example", expected = "host.example"),
        Case(network = "h2", host = "host.example", expected = "host.example"),
        Case(network = "h2", host = "first.example,second.example", expected = "first.example"),
        Case(network = "http", host = ", first.example, second.example", expected = "first.example"),
        Case(network = "h2", sni = "sni.example", host = "first.example,second.example", expected = "sni.example"),
        Case(network = "h2", host = ", ,"),
        Case(network = "WS", sni = "", host = "host.example", expected = "host.example"),
        Case(host = "ignored.example"),
        Case(server = "192.0.2.1", expected = null),
        Case(server = "2001:db8::1", expected = null),
        Case(server = "::ffff:192.0.2.1", expected = null),
        Case(server = "192.0.2.1", network = "ws", host = "host.example", expected = "host.example"),
        Case(network = "ws", host = "host.example", tls = false, expected = null),
    )

    @Test fun vlessFallbackBranches() {
        cases.forEach { c ->
            val server = if (':' in c.server) "[${c.server}]" else c.server
            val params = listOfNotNull(
                "type=${c.network}", "security=${if (c.tls) "tls" else "none"}",
                c.sni?.let { "sni=$it" }, c.host?.let { "host=$it" },
            ).joinToString("&")
            assertTls(UriListParser().parse(
                "vless://00000000-0000-0000-0000-000000000001@$server:443?$params", 1,
            ).nodes.single().outboundJson, c)
        }
    }

    @Test fun vmessFallbackBranches() {
        cases.forEach { c ->
            val payload = buildJsonObject {
                put("add", c.server); put("port", 443)
                put("id", "00000000-0000-0000-0000-000000000001")
                put("net", c.network); put("tls", if (c.tls) "tls" else "")
                c.sni?.let { put("sni", it) }; c.host?.let { put("host", it) }
            }
            val encoded = Base64.getEncoder().encodeToString(payload.toString().toByteArray())
            assertTls(UriListParser().parse("vmess://$encoded", 1).nodes.single().outboundJson, c)
        }
    }

    @Test fun clashWsFallbackAndExplicitServername() {
        for (type in listOf("vless", "vmess")) {
            for (sni in listOf(null, "sni.example")) {
                val body = """
                    proxies:
                      - name: synthetic
                        type: $type
                        server: server.example
                        port: 443
                        uuid: 00000000-0000-0000-0000-000000000001
                        tls: true
                        network: ws
                        ${sni?.let { "servername: $it" } ?: ""}
                        ws-opts:
                          headers:
                            Host: host.example
                """.trimIndent()
                assertTls(ClashYamlParser().parse(body, 1).nodes.single().outboundJson,
                    Case(expected = sni ?: "host.example"))
            }
        }
    }

    private fun assertTls(outbound: String, c: Case) {
        val obj = Json.parseToJsonElement(outbound).jsonObject
        if (c.network.lowercase() in setOf("h2", "http") && c.host != null) {
            val hosts = c.host.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (hosts.size > 1) {
                assertEquals(hosts, obj["transport"]!!.jsonObject["host"]!!.jsonArray.map { it.jsonPrimitive.content })
            }
        }
        val tls = obj["tls"] as? JsonObject
        if (!c.tls) { assertNull(tls); return }
        assertEquals(c.expected, tls?.get("server_name")?.jsonPrimitive?.content)
        assertNull(tls?.get("insecure"))
    }
}
