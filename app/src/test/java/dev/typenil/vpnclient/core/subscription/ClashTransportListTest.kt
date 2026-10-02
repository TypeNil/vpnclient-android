package dev.typenil.vpnclient.core.subscription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClashTransportListTest {
    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/clash/$name.yaml")!!.bufferedReader().use { it.readText() }

    @Test fun h2HostListPreservedInJson() {
        val node = ClashYamlParser().parse(fixture("h2-host-list"), 1).nodes.single()
        val outbound = Json.parseToJsonElement(node.outboundJson).jsonObject
        val transport = outbound["transport"]!!.jsonObject
        assertEquals("http", transport["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("first.example", "second.example"),
            transport["host"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("/tunnel", transport["path"]!!.jsonPrimitive.content)
        assertEquals("first.example", outbound["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
    }

    @Test fun h2ScalarHostAlsoAccepted() {
        val yaml = fixture("h2-host-list").replace("[first.example, second.example]", "first.example")
        val node = ClashYamlParser().parse(yaml, 1).nodes.single()
        val transport = Json.parseToJsonElement(node.outboundJson).jsonObject["transport"]!!.jsonObject
        assertEquals(listOf("first.example"), transport["host"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun httpPathListAndHeadersNotApproximated() {
        val result = ClashYamlParser().parse(fixture("http-path-list"), 1)
        assertEquals(listOf("ordinary"), result.nodes.map { it.name })
        assertEquals("unsupported transport: http obfuscation", result.skipped.single().reason)
        val outbound = Json.parseToJsonElement(result.nodes.single().outboundJson).jsonObject
        assertFalse(outbound.containsKey("transport"))
    }

    @Test fun httpSinglePathStillNotSilentlyConvertedToH2() {
        val yaml = fixture("http-path-list").replace("[/first, /second]", "/first")
        val result = ClashYamlParser().parse(yaml, 1)
        assertEquals(1, result.nodes.size)
        assertEquals("unsupported transport: http obfuscation", result.skipped.single().reason)
    }

    @Test fun structuredHostRejectedInsteadOfStringified() {
        val bad = fixture("h2-host-list").replace("[first.example, second.example]", "[{bad: value}]")
        val good = fixture("http-path-list").substringAfter("  - name: ordinary")
        val result = ClashYamlParser().parse(bad + "\n  - name: ordinary" + good, 1)
        assertEquals(1, result.nodes.size)
        assertEquals("malformed h2-opts.host", result.skipped.single().reason)
    }
}
