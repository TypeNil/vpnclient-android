package dev.typenil.vpnclient.core.engine.singbox

import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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

    /** Paths the store would hand the compiler — tag → local .srs file. */
    private fun rsPaths(mode: RouteMode): Map<String, String> =
        mode.ruleSetTags.associateWith { "/data/rule_sets/$it.srs" }

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

    @Test
    fun `ALL mode emits no rule sets and defaults to proxy`() {
        val config = compiler.build(listOf(node("n1")), "n1", true, RouteMode.ALL)
        val root = json.parseToJsonElement(config.configJson).jsonObject

        val route = root["route"]!!.jsonObject
        assertTrue("route.rule_set must not be emitted", "rule_set" !in route)
        assertEquals("proxy", route["final"]!!.jsonPrimitive.content)
        assertEquals(3, route["rules"]!!.jsonArray.size)

        val dns = root["dns"]!!.jsonObject
        assertTrue("dns.rules must not be emitted", "rules" !in dns)
        assertEquals("remote", dns["final"]!!.jsonPrimitive.content)

        assertTrue("cache_file only matters with rule sets", "experimental" !in root)
    }

    @Test
    fun `BYPASS_RU sends russian rule sets direct and keeps proxy final`() {
        val config = compiler.build(
            listOf(node("n1")), "n1", true, RouteMode.BYPASS_RU,
            ruleSetPaths = rsPaths(RouteMode.BYPASS_RU),
        )
        val root = json.parseToJsonElement(config.configJson).jsonObject

        val route = root["route"]!!.jsonObject
        val ruleSets = route["rule_set"]!!.jsonArray
        assertEquals(
            listOf("geoip-ru", "geosite-category-ru"),
            ruleSets.map { it.jsonObject["tag"]!!.jsonPrimitive.content },
        )
        ruleSets.forEach { rs ->
            val obj = rs.jsonObject
            // Local files fetched app-side — no in-engine remote download.
            assertEquals("local", obj["type"]!!.jsonPrimitive.content)
            assertEquals("binary", obj["format"]!!.jsonPrimitive.content)
            assertTrue(obj["path"]!!.jsonPrimitive.content.endsWith(".srs"))
            assertTrue("url" !in obj)
        }

        // Order matters: sniff → hijack-dns → private → mode rule.
        val rules = route["rules"]!!.jsonArray
        assertEquals(4, rules.size)
        assertEquals("sniff", rules[0].jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals("hijack-dns", rules[1].jsonObject["action"]!!.jsonPrimitive.content)
        assertTrue(rules[2].jsonObject["ip_is_private"]!!.jsonPrimitive.boolean)
        val ruRule = rules[3].jsonObject
        assertEquals(
            setOf("geoip-ru", "geosite-category-ru"),
            ruRule["rule_set"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals("direct", ruRule["outbound"]!!.jsonPrimitive.content)
        assertEquals("proxy", route["final"]!!.jsonPrimitive.content)

        // RU domains resolve via ISP DNS; the rest keeps the proxied DoH.
        val dns = root["dns"]!!.jsonObject
        val dnsRule = dns["rules"]!!.jsonArray.single().jsonObject
        assertEquals(
            listOf("geosite-category-ru"),
            dnsRule["rule_set"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("local", dnsRule["server"]!!.jsonPrimitive.content)
        assertEquals("remote", dns["final"]!!.jsonPrimitive.content)

        // Bootstrap invariant: proxy server names resolve locally.
        assertEquals(
            "local",
            route["default_domain_resolver"]!!.jsonObject["server"]!!.jsonPrimitive.content,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing rule set file fails at compile not engine start`() {
        compiler.build(listOf(node("n1")), "n1", true, RouteMode.BYPASS_RU)
    }

    @Test
    fun `PROXY_BLOCKED proxies only the curated list and defaults direct`() {
        val config = compiler.build(
            listOf(node("n1")), "n1", true, RouteMode.PROXY_BLOCKED,
            ruleSetPaths = rsPaths(RouteMode.PROXY_BLOCKED),
        )
        val root = json.parseToJsonElement(config.configJson).jsonObject

        val route = root["route"]!!.jsonObject
        val ruleSets = route["rule_set"]!!.jsonArray
        val declaredTags = ruleSets.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertEquals(RouteMode.PROXY_BLOCKED.ruleSetTags, declaredTags)
        assertTrue(declaredTags.all { it.startsWith("geosite-") })
        ruleSets.forEach { rs ->
            val obj = rs.jsonObject
            assertEquals("local", obj["type"]!!.jsonPrimitive.content)
            assertEquals("binary", obj["format"]!!.jsonPrimitive.content)
            assertTrue(obj["path"]!!.jsonPrimitive.content.endsWith(".srs"))
        }

        // Order matters: sniff → hijack-dns → private → mode rule.
        val rules = route["rules"]!!.jsonArray
        assertEquals(4, rules.size)
        assertEquals("sniff", rules[0].jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals("hijack-dns", rules[1].jsonObject["action"]!!.jsonPrimitive.content)
        assertTrue(rules[2].jsonObject["ip_is_private"]!!.jsonPrimitive.boolean)
        val blockedRule = rules[3].jsonObject
        assertEquals(
            declaredTags.toSet(),
            blockedRule["rule_set"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals("proxy", blockedRule["outbound"]!!.jsonPrimitive.content)
        assertEquals("direct", route["final"]!!.jsonPrimitive.content)

        // Blocked domains resolve via proxied DoH (ISP answers are spoofed);
        // the rest uses the local resolver.
        val dns = root["dns"]!!.jsonObject
        val dnsRule = dns["rules"]!!.jsonArray.single().jsonObject
        assertEquals(
            declaredTags.toSet(),
            dnsRule["rule_set"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals("remote", dnsRule["server"]!!.jsonPrimitive.content)
        assertEquals("local", dns["final"]!!.jsonPrimitive.content)

        val remote = dns["servers"]!!.jsonArray.first {
            it.jsonObject["tag"]!!.jsonPrimitive.content == "remote"
        }.jsonObject
        assertEquals("proxy", remote["detour"]!!.jsonPrimitive.content)

        // Bootstrap invariant: proxy server names resolve locally.
        assertEquals(
            "local",
            route["default_domain_resolver"]!!.jsonObject["server"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `v6-less underlay routes all v6 through proxy in BYPASS_RU`() {
        val config = compiler.build(
            listOf(node("n1")), "n1", true, RouteMode.BYPASS_RU, underlayIpv6 = false,
            ruleSetPaths = rsPaths(RouteMode.BYPASS_RU),
        )
        val rules = json.parseToJsonElement(config.configJson)
            .jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray

        // sniff → hijack-dns → private → v6→proxy → RU→direct.
        assertEquals(5, rules.size)
        val v6Rule = rules[3].jsonObject
        assertEquals(6, v6Rule["ip_version"]!!.jsonPrimitive.int)
        assertEquals("proxy", v6Rule["outbound"]!!.jsonPrimitive.content)
        val ruRule = rules[4].jsonObject
        assertEquals("direct", ruRule["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun `v6-less underlay routes all v6 through proxy in PROXY_BLOCKED`() {
        val config = compiler.build(
            listOf(node("n1")), "n1", true, RouteMode.PROXY_BLOCKED, underlayIpv6 = false,
            ruleSetPaths = rsPaths(RouteMode.PROXY_BLOCKED),
        )
        val rules = json.parseToJsonElement(config.configJson)
            .jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray

        assertEquals(5, rules.size)
        val v6Rule = rules[3].jsonObject
        assertEquals(6, v6Rule["ip_version"]!!.jsonPrimitive.int)
        assertEquals("proxy", v6Rule["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun `v6-less underlay does not change ALL mode`() {
        val config = compiler.build(
            listOf(node("n1")), "n1", true, RouteMode.ALL, underlayIpv6 = false,
        )
        val rules = json.parseToJsonElement(config.configJson)
            .jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals(3, rules.size)
        assertTrue(rules.none { "ip_version" in it.jsonObject })
    }

    @Test
    fun `v6-capable underlay keeps direct v6 for RU`() {
        val config = compiler.build(
            listOf(node("n1")), "n1", true, RouteMode.BYPASS_RU, underlayIpv6 = true,
            ruleSetPaths = rsPaths(RouteMode.BYPASS_RU),
        )
        val rules = json.parseToJsonElement(config.configJson)
            .jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals(4, rules.size)
        assertTrue(rules.none { "ip_version" in it.jsonObject })
    }

    @Test
    fun `RU dns rule returns ipv4 only so direct dials reach v4`() {
        val config = compiler.build(
            listOf(node("n1")), "n1", true, RouteMode.BYPASS_RU,
            ruleSetPaths = rsPaths(RouteMode.BYPASS_RU),
        )
        val dnsRule = json.parseToJsonElement(config.configJson)
            .jsonObject["dns"]!!.jsonObject["rules"]!!.jsonArray.single().jsonObject
        assertEquals("ipv4_only", dnsRule["strategy"]!!.jsonPrimitive.content)
    }
}
