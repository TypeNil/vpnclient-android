package dev.typenil.vpnclient.ui.home

import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.vpn.VpnError
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android gives onRevoke no reason, so the text must not claim the user revoked consent. */
class RevokedTextTest {
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
    fun `revoked texts are neutral and tell how to reconnect`() {
        for ((locale, terms) in listOf(
            "values" to listOf("system", "another VPN", "Connect"),
            "values-ru" to listOf("системой", "другим VPN", "Подключить"),
        )) {
            val s = strings(locale)
            for (key in listOf("home_error_permission_revoked", "health_revoked")) {
                val text = s[key].orEmpty()
                assertFalse("$locale $key", text.contains("revoked", true) || text.contains("отозван", true))
            }
            val home = s["home_error_permission_revoked"].orEmpty()
            terms.forEach { assertTrue("$locale missing $it", home.contains(it)) }
        }
    }

    @Test
    fun `missing consent maps to PermissionDenied not revoked`() {
        assertEquals(VpnError.PermissionDenied, VpnError.fromEngine(EngineError.MissingVpnPermission))
        assertFalse(VpnError.PermissionRevoked.message.contains("revoked", true))
    }
}
