package dev.typenil.vpnclient.core.engine.singbox

import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCompilerTest {

    private val compiler = ConfigCompiler()
    private val json = Json { ignoreUnknownKeys = true }

    private fun node(id: String, name: String = id) = ProxyNode(
        id = id,
        subscriptionId = 1,
        name = name,
        protocol = ProtocolType.VLESS,
        server = "example.com",
        port = 443,
        outboundJson = """{"type":"vless","tag":"$id","server":"example.com","server_port":443,"uuid":"u"}""",
        rawUri = null,
    )

    @Test
    fun `config embeds node outbounds plus selector and urltest`() {
        val config = compiler.build(listOf(node("n1"), node("n2")), "n1", true)
        val root = json.parseToJsonElement(config.configJson).jsonObject

        val outbounds = root["outbounds"]!!.jsonArray
        val types = outbounds.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("selector", "urltest", "vless", "vless", "direct"), types)

        val selector = outbounds[0].jsonObject
        assertEquals("proxy", selector["tag"]!!.jsonPrimitive.content)
        assertEquals("n1", selector["default"]!!.jsonPrimitive.content)

        val urltest = outbounds[1].jsonObject
        assertEquals("auto", urltest["tag"]!!.jsonPrimitive.content)
        assertEquals(2, urltest["outbounds"]!!.jsonArray.size)

        // node outbound carried through verbatim
        val vless = outbounds[2].jsonObject
        assertEquals("n1", vless["tag"]!!.jsonPrimitive.content)
        assertEquals(443, vless["server_port"]!!.jsonPrimitive.int)
    }

    @Test
    fun `tun inbound has routes and dns`() {
        val config = compiler.build(listOf(node("n1")), "n1", true)
        val root = json.parseToJsonElement(config.configJson).jsonObject

        val tun = root["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals("tun", tun["type"]!!.jsonPrimitive.content)
        assertEquals(9000, tun["mtu"]!!.jsonPrimitive.int)
        val addrs = tun["address"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(addrs.any { it.startsWith("172.") })
        assertTrue(addrs.any { it.startsWith("fd") })

        val routeRules = root["route"]!!.jsonObject["rules"]!!.jsonArray
        assertTrue(routeRules.any { it.jsonObject["action"]?.jsonPrimitive?.content == "hijack-dns" })
        assertTrue(routeRules.any { it.jsonObject["ip_is_private"]?.jsonPrimitive?.content == "true" })

        val dns = root["dns"]!!.jsonObject
        assertEquals("remote", dns["final"]!!.jsonPrimitive.content)
    }

    @Test
    fun `remote DoH is detoured through the selected proxy`() {
        val config = compiler.build(listOf(node("n1")), "n1", true)
        val servers = json.parseToJsonElement(config.configJson)
            .jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray
        val remote = servers.first {
            it.jsonObject["tag"]!!.jsonPrimitive.content == "remote"
        }.jsonObject
        assertEquals("proxy", remote["detour"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deprecated sing-box fields are absent on pinned core`() {
        val config = compiler.build(listOf(node("n1")), "n1", true)
        val root = json.parseToJsonElement(config.configJson).jsonObject

        val tun = root["inbounds"]!!.jsonArray[0].jsonObject
        assertTrue("endpoint_independent_nat must not be emitted", "endpoint_independent_nat" !in tun)
        // gvisor stays while the pinned core is 1.14.x — removal is gated on a
        // 1.15+ upgrade with device regression (see docs/UPSTREAM_RESEARCH.md).
        assertEquals("gvisor", tun["stack"]!!.jsonPrimitive.content)

        val dns = root["dns"]!!.jsonObject
        assertTrue("independent_cache is deprecated in 1.14", "independent_cache" !in dns)
    }

    @Test
    fun `ipv6 disabled drops v6 address and uses ipv4_only`() {
        val config = compiler.build(listOf(node("n1")), "n1", false)
        val root = json.parseToJsonElement(config.configJson).jsonObject
        val addrs = root["inbounds"]!!.jsonArray[0].jsonObject["address"]!!.jsonArray
        assertEquals(1, addrs.size)
        assertEquals("ipv4_only", root["dns"]!!.jsonObject["strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `null selection falls back to first node`() {
        val config = compiler.build(listOf(node("n1"), node("n2")), null, true)
        val selector = json.parseToJsonElement(config.configJson)
            .jsonObject["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals("n1", selector["default"]!!.jsonPrimitive.content)
        assertNotNull(config.node)
    }

    @Test(expected = dev.typenil.vpnclient.core.engine.EngineError.InvalidConfig::class)
    fun `stale selection rejected instead of silent fallback`() {
        compiler.build(listOf(node("n1"), node("n2")), "gone", true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty nodes rejected`() {
        compiler.build(emptyList(), null, true)
    }
}
