package dev.typenil.vpnclient.ui.diagnostics

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DNS evidence text must stay inside what the app-side probe checked. */
class DnsHealthTextTest {
    private fun strings(locale: String): Map<String, String> {
        val res = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val nodes = factory.newDocumentBuilder().parse(File(res, "$locale/strings.xml"))
            .getElementsByTagName("string")
        return (0 until nodes.length).associate {
            nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent
        }
    }

    @Test
    fun `dns texts exist in both locales and make no stronger claim than the probe`() {
        for ((locale, notProved, notVerified) in listOf(
            Triple("values", "does not prove", "not verified"),
            Triple("values-ru", "не значит", "не проверены"),
        )) {
            val s = strings(locale)
            val keys = listOf("health_dns_answered", "health_dns_timeout", "health_dns_failed",
                "health_source_dns_query", "health_scope_app_dns")
            keys.forEach { assertTrue("$locale missing $it", s[it].orEmpty().isNotBlank()) }
            // Success disclaims egress; failures disclaim "server down / site blocked".
            assertTrue(locale, s.getValue("health_dns_answered").contains(notVerified))
            assertTrue(locale, s.getValue("health_scope_app_dns").contains(notVerified))
            assertTrue(locale, s.getValue("health_dns_timeout").contains(notProved))
            assertTrue(locale, s.getValue("health_dns_failed").contains(notProved))
            keys.forEach {
                val text = s.getValue(it)
                assertFalse("$locale $it", text.contains("leak", true) || text.contains("утечк", true))
            }
        }
    }

    @Test
    fun `every dns health value has its own label instead of the Unverified fallback`() {
        val dir = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        val source = File(dir, "dev/typenil/vpnclient/ui/diagnostics/DiagnosticsScreen.kt").readText()
        mapOf(
            "HealthReason.DnsAnswered" to "health_dns_answered",
            "HealthReason.DnsTimeout" to "health_dns_timeout",
            "HealthReason.DnsFailed" to "health_dns_failed",
            "HealthSource.DnsQuery" to "health_source_dns_query",
            "HealthScope.AppDnsQuery" to "health_scope_app_dns",
        ).forEach { (value, label) ->
            assertTrue("$value is not labelled $label", source.contains("$value -> R.string.$label"))
        }
    }
}
